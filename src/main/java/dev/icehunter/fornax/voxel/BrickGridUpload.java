package dev.icehunter.fornax.voxel;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.pack.graph.BufferInstance;
import dev.icehunter.fornax.pack.graph.TargetRegistry;
import dev.icehunter.fornax.pass.compute.VulkanComputeBackend;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkSubmitInfo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Uploads harvested section data into six engine-injected brick-grid buffers: a brick-index grid
 * (one {@code int} per toroidal slot -- 0 = empty, else the slot's own index, since payload lives at
 * a fixed offset per slot and needs no indirection), an occupancy mask (512 bytes/slot, one bit per
 * voxel), a palette-compressed payload (one byte per voxel indexing a small per-slot palette table),
 * the palette table itself (real per-face colors and packed partial-shape box bounds, {@link
 * #PALETTE_BYTES_PER_SLOT} per slot) for the raymarch shader's color and shape resolution, the light
 * volume ({@link #lightVolumeBytesPerSlot()} per slot, see the layout comment on {@link
 * #lightCellsPerSectionAxis()}) that the light_inject/light_propagate compute passes populate and
 * the deferred resolve shader samples, and a per-slot coarse SUMMARY ({@link #BRICK_SUMMARY_TARGET},
 * one {@code uint} per slot: bit 0 set iff the slot's section has any solid voxel, bit 1 ({@link
 * #SUMMARY_HAS_EMITTER}) set iff it has any emitter). A two-level DDA (a pack's {@code
 * celestial_shadow.fsh}) can texelFetch the summary once to prove a whole 16^3 brick is empty and
 * skip its per-voxel descent, instead of scanning the fine occupancy bitmask voxel-by-voxel. A
 * pack's {@code light_inject.comp}/{@code light_propagate.comp} texelFetch the same word to skip
 * emitter flood-fill work for regions provably too far from any emitter to ever be lit.
 */
public final class BrickGridUpload {
    public static final String INDEX_GRID_TARGET = "voxelBrickIndex";
    public static final String OCCUPANCY_TARGET = "voxelOccupancy";
    public static final String PAYLOAD_TARGET = "voxelPayload";
    /** One byte per voxel: bits 0-5 are completely sealed boundary faces in Direction data-value
     * order; bit 6 marks a light-transmissive full voxel. */
    public static final String FACE_SEAL_TARGET = "voxelFaceSeal";
    public static final String PALETTE_TARGET = "voxelPalette";
    public static final String LIGHT_VOLUME_TARGET = "voxelLightVolume";
    /** Per-slot coarse occupancy summary -- one little-endian {@code uint} per toroidal slot (4
     * bytes, matching {@link #BRICK_SUMMARY_BYTES_PER_SLOT}), nonzero iff {@link #anySolidVoxel}
     * found at least one FULL/PARTIAL voxel in that slot's harvested section, zero otherwise
     * (including a never-harvested or freshly-cleared slot -- see every write path below). A whole
     * uint rather than a packed bit, matching {@code FullscreenPassRunner}'s "every buffer-kind
     * fullscreen input is R32_UINT" convention: one {@code texelFetch(summary, slot).r != 0u} per
     * brick, no bit-unpacking needed. */
    public static final String BRICK_SUMMARY_TARGET = "voxelBrickSummary";

    /** Bit 1 of {@link #BRICK_SUMMARY_TARGET}'s per-slot word: set iff {@link #anyEmitter} found at
     * least one palette entry with nonzero {@code emissiveStrength} in that slot's harvested section,
     * zero otherwise. Shares the word with bit 0 ({@link #summaryWord}'s "any solid voxel" bit) --
     * bits 1-30 were unused before this, so it needs no new buffer or upload path: it rides the same
     * {@code ensureAllocated}/{@code uploadSlot}/{@code uploadBatchLocked}/{@code
     * clearOccupancySlots} write paths that already produce bit 0 in the same GPU transaction.
     * Consuming shaders ({@code light_inject.comp}/{@code light_propagate.comp}) texelFetch this bit
     * to skip the per-voxel emission scan and neighbor-propagation gather for sections provably too
     * far from any emitter to ever be lit. Like bit 0, a slot with {@link #SUMMARY_PENDING} set must
     * be treated as "unknown, assume an emitter could be there", never as confirmed-empty. */
    public static final int SUMMARY_HAS_EMITTER = 0x2;

    /** Sentinel bit written into a slot's {@link #BRICK_SUMMARY_TARGET} word by {@link
     * #clearOccupancySlots} instead of plain {@code 0}. {@link #summaryWord} only ever sets bits 0
     * and 1 (occupancy and {@link #SUMMARY_HAS_EMITTER}), so bit 31 never collides with a real
     * harvested value -- a consuming shader's {@code brickHasContent} test checks it BEFORE testing
     * "summary != 0" to tell three states apart, not two:
     * <ul>
     *   <li>{@code 0} -- {@link #anySolidVoxel} proved this harvested section has no solid voxel
     *       (confirmed empty);</li>
     *   <li>{@code 1} -- {@link #anySolidVoxel} proved at least one solid voxel (descend into the
     *       fine occupancy bitmask);</li>
     *   <li>{@code SUMMARY_PENDING} -- a window-recenter just zeroed this slot's occupancy/summary
     *       because the shell newly exposed it, but the real harvest+upload (sync or async, see
     *       {@link VoxelWindow#recenterAndResync}) hasn't landed yet: this slot's true content is
     *       unknown, not confirmed-empty.</li>
     * </ul>
     * Without this sentinel, a freshly-cleared slot's summary word was byte-identical to a real,
     * harvest-confirmed empty brick, so a DDA reading it could not tell "genuinely no rock here" apart
     * from "rock may well be here, not harvested yet" and let rays pass through unoccluded. For a slot
     * straddling real solid terrain -- a cave wall just past the streamed shell's leading edge, or any
     * slot re-exposed by a section-boundary cross before the background {@link
     * VoxelWindow#RESYNC_EXECUTOR} tail-drain reaches it -- that read as a transient false "lit":
     * distant terrain shadows fading in/out as the camera moves, and sparkling bright dots along an
     * otherwise dark cave boundary where temporal reprojection cannot damp the flicker. Consumers
     * split on direction: {@code voxel_occlusion_march.glsl} treats a pending brick as TRANSPARENT (a
     * shadow ray must not manufacture occlusion from absent data), while {@code light_list_build.comp}
     * SKIPS a pending brick's emitters (its payload/palette under a pending slot are the previous
     * toroidal owner's stale bytes -- scanning them would fabricate phantom lights). Both directions
     * are the same rule: never synthesize light or shadow from data that has not been harvested yet.
     * Not applied to {@link #ensureAllocated}'s own fresh-buffer zero-clear (a never-yet-harvested slot
     * at first window activation or pack load) -- that cold-start case is a separate, accepted
     * behavior outside this fix's scope. */
    public static final int SUMMARY_PENDING = 0x8000_0000;

    // --- Per-slot light-volume layout ---------------------------------------------------------------
    // One cell per 2x2x2 blocks at the Standard (default) tier: 8^3 = 512 cells/section-slot, indexed
    // (cy*8 + cz)*8 + cx -- the same y-major/z/x order the occupancy/payload voxel index
    // (y<<8)|(z<<4)|x uses, at cell scale. One cell per block at the High tier: 16^3 = 4096
    // cells/section-slot, same indexing. Each cell is one little-endian uint: bits 0-9 R, 10-19 G,
    // 20-29 B (unorm, /1023.0), bits 30-31 reserved. Four consecutive lightCellsPerSlot()-word fields
    // per slot:
    //   words [slot*lightWordsPerSlot(), .. + lightCellsPerSlot())      : DIRECT SOURCE -- light_inject
    //                                               overwrites every frame (zero where no emitter), so
    //                                               emitter removal is reflected immediately;
    //   words [slot*lightWordsPerSlot() + lightCellsPerSlot(), .. + 2*lightCellsPerSlot()) : DIRECT
    //                                               PROPAGATED field -- light_propagate iterates it in
    //                                               place (newCell = max(source, maxNeighbor - attenuation));
    //   words [slot*lightWordsPerSlot() + 2*lightCellsPerSlot(), .. + 3*lightCellsPerSlot()) : ONE-BOUNCE
    //                                               INDIRECT PROPAGATED field -- light_propagate relaxes
    //                                               it in place, persistent across frames like the direct
    //                                               field. The combined analytic+GI resolve samples this
    //                                               field, never the direct flood field;
    //   words [slot*lightWordsPerSlot() + 3*lightCellsPerSlot(), .. + lightWordsPerSlot()) : ONE-BOUNCE
    //                                               INDIRECT SOURCE -- light_inject overwrites every
    //                                               frame with the air-side surface seeds. Kept separate
    //                                               from the indirect field itself: seeding that field
    //                                               directly would rebuild it from scratch every frame
    //                                               with only PROPAGATE_ITERATIONS of relaxation, which
    //                                               cannot converge and leaves an unstable boundary. The
    //                                               separate source segment gives the indirect field the
    //                                               same persistence the direct field already has.
    // The three consuming shaders (light_inject.comp / light_propagate.comp / gbuffer_resolve.fsh)
    // hand-mirror these constants, and the tier selection itself, via each file's own LIGHT_CELL_DETAIL
    // compile option (#if branch to 8 or 16). {@link dev.icehunter.fornax.pack.LightCellStrideContract}
    // validates both branches of all three shaders at load time, the same way {@link
    // dev.icehunter.fornax.pack.PaletteStrideContract} validates the palette stride.
    //
    // The High tier raises resolution from one cell per 2x2x2 blocks to one cell per block, fixing
    // light leaking through walls thinner than 2 blocks and shapeless small-opening bounce shapes that
    // no tuning of the coarser field can fix. Cost is 8x memory per slot (6144 B -> 49152 B), so High
    // is opt-in; the default stays byte-identical to pre-tier behavior. Selected via the pack's
    // LIGHT_CELL_DETAIL compile option, pushed once per {@code GraphRunner.rebuild()} via {@link
    // #setLightCellDetailTier} (mirrors the rebuild-scoped-state pattern {@code
    // GraphRunner.compileValues}/{@code optionsBuffer} already use, rather than this class reaching
    // into {@code pack.graph.GraphRunner} and creating a package cycle). {@code
    // lightCellsPerSectionAxis()} and everything derived from it read this pushed value, so a rebuild
    // that never runs, or a pack that never declares the option, falls back to {@link
    // #LIGHT_CELLS_PER_SECTION_AXIS_STANDARD}. See {@code VoxelDebugRaymarchPass#currentRadius()} for
    // the companion clamp that caps the light volume's reach at High detail, so the two knobs can
    // never combine into an unlimited allocation.
    public static final int LIGHT_CELLS_PER_SECTION_AXIS_STANDARD = 8;
    public static final int LIGHT_CELLS_PER_SECTION_AXIS_HIGH = 16;

    /** Rebuild-scoped tier selector: 0 = Standard, 1 = High. Defaults to 0 (Standard) so any code path
     * that reads {@link #lightCellsPerSectionAxis()} before the first {@code GraphRunner.rebuild()} --
     * or with a pack that never declares {@code LIGHT_CELL_DETAIL} -- reproduces today's exact
     * behavior. NOT {@code final}: intentionally mutable, pushed fresh each rebuild. */
    private static int lightCellDetailTier = 0;

    /** Pushes this rebuild's {@code LIGHT_CELL_DETAIL} compile-option value (0 or 1; any other value
     * -- a malformed pack -- falls back to Standard rather than propagating garbage into buffer
     * sizing). Called from {@code GraphRunner.rebuild()} the same place {@code compileValues} itself
     * is finalized, so every reader of {@link #lightCellsPerSectionAxis()} during a given frame sees
     * the SAME tier {@code GraphRunner.computeDispatchOverride}'s dispatch-group math used that frame
     * -- the same allocated-vs-live consistency discipline {@code VoxelDebugRaymarchPass
     * .allocatedDiameter()} already established for diameter (see that class's I-1 doc comment). */
    public static void setLightCellDetailTier(int compileValue) {
        lightCellDetailTier = compileValue == 1 ? 1 : 0;
    }

    /** The live cells-per-section-axis for the current tier (8 Standard / 16 High) -- the single
     * source every addressing/allocation computation below reads instead of a bare constant. */
    public static int lightCellsPerSectionAxis() {
        return lightCellDetailTier == 1 ? LIGHT_CELLS_PER_SECTION_AXIS_HIGH : LIGHT_CELLS_PER_SECTION_AXIS_STANDARD;
    }

    public static int lightCellsPerSlot() {
        int axis = lightCellsPerSectionAxis();
        return axis * axis * axis;
    }

    public static int lightWordsPerSlot() {
        // direct source + direct field + indirect field + indirect source (see the layout doc above
        // for why the indirect pair is split into separate segments).
        return lightCellsPerSlot() * 4;
    }

    public static long lightVolumeBytesPerSlot() {
        return (long) lightWordsPerSlot() * Integer.BYTES; // 8192 Standard, 65536 High
    }

    private static final int VOXELS_PER_SECTION = 16 * 16 * 16;
    private static final long OCCUPANCY_BYTES_PER_SLOT = VOXELS_PER_SECTION / 8; // 512
    public static final long FACE_SEAL_BYTES_PER_SLOT = VOXELS_PER_SECTION;
    public static final long PAYLOAD_BYTES_PER_SLOT = VOXELS_PER_SECTION + OCCUPANCY_BYTES_PER_SLOT;
    /** One little-endian uint per slot -- see {@link #BRICK_SUMMARY_TARGET}'s own doc for why a whole
     * word rather than a packed bit. */
    public static final long BRICK_SUMMARY_BYTES_PER_SLOT = Integer.BYTES; // 4
    private static final long FENCE_WAIT_TIMEOUT = 0xFFFF_FFFF_FFFF_FFFFL; // UINT64_MAX -> block only until signaled

    // --- Per-slot palette table byte layout -----------------------------------------------------
    // The occupancy/payload buffers only encode "occupied?" + "which palette index"; this fourth
    // buffer holds the palette ENTRIES themselves so the shader can resolve real per-face color and
    // real partial-shape box bounds. It is a fixed-stride table -- MAX_PALETTE_ENTRIES entries per
    // slot -- indexed directly by (slot, paletteIndex), mirroring the fixed-stride occupancy/payload
    // layout rather than introducing a sparse per-slot offset table.
    //
    // Each entry is exactly 16 little-endian uint words = 64 bytes, matching the shader's std430
    // `uint palette[]` view (a scalar uint runtime array has stride 4, so word N of entry E in slot S
    // lives at word  S*PALETTE_ENTRY_WORDS*MAX_PALETTE_ENTRIES + E*PALETTE_ENTRY_WORDS + N):
    //   word 0       : boxCount (bits 0-3, 0 => FULL/solid shape; 1..MAX_BOXES => PARTIAL/CROSS, needs
    //                  box test) | EXTINCTION (bits 4-11, 8-bit unorm scaled by EXTINCTION_SCALE = 2.0
    //                  -- the measured foliage extinction coefficient per block of path length, see
    //                  FoliageDensityResolver) | CUTOUT flag (bit 30) | CROSS flag (bit 31). Bits
    //                  12-29 remain free; bits 30/31 were chosen for maximum distance from boxCount's
    //                  real range. (Bit 12 briefly held a SHAPE_TRUNCATED flag, removed once
    //                  VoxelShapeClassifier started merging excess boxes past MAX_BOXES into one union
    //                  box instead of dropping them, so every entry's box list is always complete and
    //                  no "distrust this" signal is needed -- see
    //                  VoxelShapeClassifier.ClassifiedShape's own doc.)
    //   words 1..6   : the six per-face colors, 0xAARRGGBB, indexed by Direction.get3DDataValue() --
    //                  all-zero for a CROSS entry (no per-cardinal-face concept; see VoxelShapeKind's
    //                  own doc and SectionPalette.Entry's cutout/uvRect doc).
    //   words 7..14  : up to MAX_BOXES (8) packed boxes, one uint each (see packBox) for a PARTIAL
    //                  entry (unchanged); for a CROSS entry, word 7 alone holds the real harvested
    //                  cross-quad bounding box (boxCount == 1, see FaceColorResolver.CrossGeometry).
    //                  CUTOUT REUSE: when the entry is cutout-flagged (word 0 bit 30 set -- always a
    //                  FULL or CROSS entry, see SectionHarvester.buildEntry, so boxCount is always 0 or
    //                  1, never near MAX_BOXES), words 13/14 (box indices 6/7, the last two of the
    //                  eight box slots) carry the packed atlas UV rect instead of box[6]/box[7] -- see
    //                  packPaletteEntries. Reused rather than growing PALETTE_ENTRY_WORDS: consuming
    //                  shaders hand-mirror `const int PALETTE_ENTRY_WORDS = 16`, so a stride change
    //                  would silently corrupt their addressing math. Reusing already-guaranteed-unused
    //                  box words for a non-PARTIAL entry has zero stride impact; a normal PARTIAL entry
    //                  (fences, stairs, walls -- never cutout-tagged) keeps writing real box[6]/box[7]
    //                  data unaffected.
    //                    word 13 (box slot 6, cutout only): u0 (bits 16-31, 16-bit unorm) | v0 (bits 0-15)
    //                    word 14 (box slot 7, cutout only): u1 (bits 16-31, 16-bit unorm) | v1 (bits 0-15)
    //                  Atlas-space UV, not sprite-local -- round(clamp(u,0,1) * 65535) per component;
    //                  the block atlas is large but one sprite's rect is still comfortably inside
    //                  16-bit-unorm's ~1/65536 precision. See FaceColorResolver.resolveCutoutRect /
    //                  resolveCrossGeometry for how the rect is captured, and celestial_shadow.fsh for
    //                  the consuming alpha test.
    //   word 15      : emission -- bits 0-7 = round(clamp(emissiveStrength, 0, 1) * 255) (the effective
    //                  emission SectionHarvester.buildEntry resolves: blocks.toml category strength x
    //                  vanilla getLightEmission()/15, with the vanilla level alone as the
    //                  untagged-emitter floor); bits 8-31 = the authored cast-light color, 0x00RRGGBB
    //                  packed R at bits 24-31 / G at 16-23 / B at 8-15, i.e. (emissionColor &
    //                  0xFFFFFF) << 8 -- SectionHarvester.buildEntry's emissionColor, itself
    //                  MaterialScalars.emissiveColor(categoryId) (blocks.toml emissive.color), or 0
    //                  when the category authored none. Zero here means "no authored hue":
    //                  light_inject.comp falls back to deriving a tint GPU-side from this entry's six
    //                  face-color words. Bit 8 is not a lightTransmissive flag here: no shader
    //                  consumes one, and the 24-bit color needs the full bits 8-31.
    //                  SectionPalette.Entry.lightTransmissive() is still computed for a future
    //                  consumer to place wherever it lands.
    //
    // A packed box uses 5 bits per coordinate, not 4: PackedBox coordinates are 1/16-block units
    // spanning 0..16 inclusive (17 distinct values -- a full-cell extent is literally 16), and 4 bits
    // max out at 15, which would silently truncate every
    // full-extent face (e.g. a slab's maxX/maxZ = 16 -> 0) and collapse the box. 5 bits (0..31) holds
    // 0..16 losslessly; six coordinates * 5 bits = 30 bits fit in one 32-bit word, so a box is 4 bytes
    // (8 boxes = 32 bytes) rather than the brief's 3. Total entry = 4 + 24 + 32 + 4 = 64 bytes.
    /**
     * Identifies the palette word layout, for the load-time skew check in
     * {@code dev.icehunter.fornax.pack.PaletteStrideContract} to name in its error. Bumped whenever the
     * layout changes in any way a shader can observe.
     *
     * <p>Value 1 = the 16-word layout with a cutout entry's single UV rect reusing box slots 6/7.
     */
    public static final int PALETTE_LAYOUT_VERSION = 1;

    /** Public (not package-private) so {@code pack.PaletteStrideContract} can validate pack shaders'
     * hand-mirrored copies of it against the value this class actually packs with. */
    public static final int PALETTE_ENTRY_WORDS = 16;
    static final int PALETTE_ENTRY_BYTES = PALETTE_ENTRY_WORDS * Integer.BYTES; // 64
    public static final long PALETTE_BYTES_PER_SLOT =
            (long) SectionHarvester.MAX_PALETTE_ENTRIES * PALETTE_ENTRY_BYTES; // 96 * 64 = 6144

    private BrickGridUpload() {
    }

    /**
     * The registry key for {@code baseTarget} at cascade {@code tier}. Tier 0 returns {@code
     * baseTarget} UNCHANGED -- a pack's graph.toml, every voxel-reading shader, and
     * GraphInputResolver all bind those exact strings, so the cascade must not rename them; coarse
     * tiers, which no pack-authored graph binds, take a {@code _t<N>} suffix. See
     * BrickGridTargetNamesTest for the pin on this.
     *
     * <p>Validates {@code tier} via {@link VoxelCascade#validateTier} BEFORE building the key --
     * without this, an out-of-range tier (e.g. {@code -1} from a caller that forgot to check {@link
     * VoxelCascade#tierFor}'s sentinel) would still produce a syntactically valid string like {@code
     * "voxelOccupancy_t-1"}, and {@code TargetRegistry.ensureBufferSize} keys on arbitrary strings, so
     * that string would silently allocate a phantom ~108 MiB buffer set that nothing reads and nothing
     * ever frees, instead of crashing at the point the bad tier was actually produced.
     */
    public static String targetName(String baseTarget, int tier) {
        VoxelCascade.validateTier(tier);
        return tier == 0 ? baseTarget : baseTarget + "_t" + tier;
    }

    /**
     * Release-side memory barrier for every brick-grid write path below: {@code vkCmdUpdateBuffer}
     * (a TRANSFER operation) writes occupancy/payload/palette/light-volume bytes that {@code
     * ComputePassRunner}'s compute dispatches (voxel_water_refl, light_inject/light_propagate)
     * subsequently read as {@code STORAGE_BUFFER}s -- on the SAME {@code VkQueue} (both this class
     * and {@code ComputePassRunner} submit via the same cached {@code
     * VulkanComputeBackend#computeQueue()} handle) but as SEPARATE {@code vkQueueSubmit} calls. This
     * barrier's dst stage is scoped to {@code COMPUTE_SHADER_BIT} because it only matters for
     * same-queue readers like those -- it has no effect on a pack's {@code celestial_shadow}
     * fullscreen pass, a different-queue (graphics-family) reader of {@code voxelOccupancy}: a
     * pipeline barrier's second synchronization scope only ever covers later-submitted work on the
     * SAME queue it was recorded on. That cross-family read is left uncovered by this barrier on
     * purpose -- it instead relies on {@code TargetRegistry.ensureBufferSize}'s {@code
     * VK_SHARING_MODE_CONCURRENT} declaration (removes the ownership-transfer requirement) plus this
     * method's own per-call {@code vkWaitForFences} (host-confirmed GPU completion of the upload,
     * well before any consuming frame runs), the same pattern {@code
     * ComputePassRunner.recordComputeWriteReleaseBarrier} already uses for its own compute-write ->
     * graphics-fullscreen-read case.
     *
     * <p>Per-call {@code vkWaitForFences} (every method below waits its own submission's fence
     * before returning) guarantees host-visible completion and same-queue submission ordering, but
     * the Vulkan spec does not extend that guarantee to GPU-side cache visibility for a different
     * access type on a later, separately-submitted command buffer -- a transfer write's availability
     * still needs an explicit visibility operation for the compute shader's read, exactly the
     * {@code VK_ACCESS_TRANSFER_WRITE_BIT -> VK_ACCESS_SHADER_READ_BIT} dependency this barrier
     * declares. Recorded as the last command before {@code vkEndCommandBuffer}: a pipeline barrier's
     * second synchronization scope covers every later-submitted command on the same queue, not just
     * commands later in this same command buffer, so this is a valid release even with nothing
     * recorded after it. A global {@link VkMemoryBarrier} (not a per-buffer {@code
     * VkBufferMemoryBarrier}) matches the granularity {@code VoxelDebugRaymarchPass}'s own
     * compute-to-host barrier already uses; the four call sites below write varying buffer subsets so
     * a global barrier is also the simplest correct choice.
     *
     * <p>Without this, live telemetry showed the predicted symptom: a full-screen occupancy read
     * intermittently saw a stale/empty page of a fully populated, zero-churn grid (rays exiting on a
     * phantom miss, spurious "lit"), flickering on an otherwise static scene -- missing-barrier GPU
     * cache staleness, not a streaming/upload-completion bug.
     */
    private static void recordUploadToComputeReadBarrier(VkCommandBuffer cmd, MemoryStack stack) {
        VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack).sType$Default()
                .srcAccessMask(VK13.VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK13.VK_ACCESS_SHADER_READ_BIT);
        VK13.vkCmdPipelineBarrier(cmd, VK13.VK_PIPELINE_STAGE_TRANSFER_BIT, VK13.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                0, barrier, null, null);
    }

    public static long indexGridSizeBytes(int diameter) {
        return (long) diameter * diameter * diameter * Integer.BYTES;
    }

    /** Pre-cascade entry point -- allocates tier 0, byte-identical to the pre-cascade behaviour. */
    public static void ensureAllocated(TargetRegistry registry, int diameter) {
        ensureAllocated(registry, diameter, 0);
    }

    /**
     * Allocates one cascade tier's buffer set. Every tier is the same shape at the same slot count
     * (see {@link VoxelCascade}); only which registry keys are written differs, via {@link
     * #targetName}. Buffer BYTE sizes are tier-independent -- a coarse tier's brick covers more world
     * per slot but holds the identical 16^3 voxels and light cells.
     *
     * <p><b>{@code tier > 0} requires {@code diameter == }{@link VoxelCascade#TIER_DIAMETER}.</b>
     * Every memory figure in the cascade design spec (including {@link VoxelCascade#reachBlocks}'s own
     * guaranteed-coverage argument) assumes a coarse tier is exactly {@link VoxelCascade#TIER_DIAMETER}
     * slots across -- nothing else derives or checks that at allocation time, so an unvalidated coarse
     * caller passing e.g. a render-distance-derived diameter could silently allocate 4x (or more) the
     * intended VRAM with nothing failing. Tier 0 is exempt: it is the pre-cascade, already-shipped
     * behaviour -- {@code VoxelDebugRaymarchPass} legitimately passes a render-distance-derived
     * diameter (currently ranging 3..33) to the 2-arg overload above, and changing that contract is
     * out of scope here.
     */
    public static void ensureAllocated(TargetRegistry registry, int diameter, int tier) {
        if (tier > 0 && diameter != VoxelCascade.TIER_DIAMETER) {
            throw new IllegalArgumentException(
                    "Coarse tier " + tier + " must allocate at VoxelCascade.TIER_DIAMETER ("
                            + VoxelCascade.TIER_DIAMETER + "), got " + diameter
                            + " -- every coarse-tier memory figure assumes exactly this diameter; tier 0 "
                            + "alone may pass an arbitrary render-distance-derived diameter.");
        }
        registry.ensureBufferSize(targetName(INDEX_GRID_TARGET, tier), indexGridSizeBytes(diameter));
        long slotCount = (long) diameter * diameter * diameter;
        registry.ensureBufferSize(targetName(OCCUPANCY_TARGET, tier), slotCount * OCCUPANCY_BYTES_PER_SLOT);
        registry.ensureBufferSize(targetName(PAYLOAD_TARGET, tier), slotCount * VOXELS_PER_SECTION); // 1 byte/voxel palette index
        registry.ensureBufferSize(targetName(FACE_SEAL_TARGET, tier), slotCount * FACE_SEAL_BYTES_PER_SLOT);
        // Fixed-stride palette table (see PALETTE_BYTES_PER_SLOT). Routed through the same
        // ensureBufferSize path as every other brick-grid buffer, so it inherits that method's
        // mandatory zero-clear at (re)allocation -- MoltenVK does not zero-fill fresh VRAM, and a
        // slot the shader reads before its section is ever harvested must read cleared zeros
        // (boxCount 0, colors 0), not garbage.
        registry.ensureBufferSize(targetName(PALETTE_TARGET, tier), slotCount * PALETTE_BYTES_PER_SLOT);
        // Emitter light volume -- windowed/toroidal identically to the four buffers above (same
        // slotCount, same slot indexing). Routed through ensureBufferSize so it inherits the
        // mandatory vkCmdFillBuffer zero-clear at (re)allocation (TargetRegistry.clearBuffer --
        // the MoltenVK garbage-VRAM law): a never-lit slot must sample as darkness, not garbage.
        registry.ensureBufferSize(targetName(LIGHT_VOLUME_TARGET, tier), slotCount * lightVolumeBytesPerSlot());
        // Coarse per-slot occupancy summary, routed through the same ensureBufferSize path (and
        // therefore the same mandatory zero-clear at (re)allocation) as every buffer above -- a slot
        // whose section is never harvested must summarize as "empty" (0), not garbage, exactly like
        // voxelOccupancy's own zero-clear default already guarantees for the fine bitmask.
        registry.ensureBufferSize(targetName(BRICK_SUMMARY_TARGET, tier), slotCount * BRICK_SUMMARY_BYTES_PER_SLOT);
    }

    /** True for any {@link VoxelShapeKind} that occupies its voxel cell for occlusion purposes --
     * {@code FULL}/{@code PARTIAL} plus {@code CROSS}: a cross-tagged plant's diagonal-plane geometry
     * still needs to set the occupancy bit and the brick-summary word, or the DDA
     * (celestial_shadow.fsh) would never attempt the fine occupancy test that gates its per-texel
     * alpha test, and grass would stop casting any shadow at all. Single source of truth for every
     * "is this voxel solid" test in this class -- see {@link #anySolidVoxel}, {@link #uploadSlot},
     * {@link #uploadBatchLocked}. */
    static boolean isOccupyingShape(VoxelShapeKind kind) {
        return kind == VoxelShapeKind.FULL || kind == VoxelShapeKind.PARTIAL || kind == VoxelShapeKind.CROSS;
    }

    /** Pure "does this harvested section contain at least one solid ({@link #isOccupyingShape}) voxel"
     * test -- the single source of truth for {@link #BRICK_SUMMARY_TARGET}'s per-slot value, factored
     * out so it is unit-tested directly (mirrors {@link #packEmissionWord}/{@link #packPaletteEntries}'
     * own pure-function precedent) without a live GPU handle or {@link SectionHarvester.Result}. Reused
     * verbatim by {@link #uploadSlot} and {@link #uploadBatchLocked} -- both already have a harvested
     * section's {@code paletteIndices} and its palette entries in hand at upload time, so this scan
     * costs a worker-thread CPU pass over 4096 bytes (trivial next to the harvest itself), zero GPU
     * cost, and never needs its own separate storage beyond the 4-byte summary word it produces. */
    static boolean anySolidVoxel(byte[] paletteIndices, List<SectionPalette.Entry> entries) {
        for (byte paletteIndexByte : paletteIndices) {
            SectionPalette.Entry entry = entries.get(paletteIndexByte & 0xFF);
            if (isOccupyingShape(entry.shapeKind())) {
                return true;
            }
        }
        return false;
    }

    /** Pure "does this harvested section contain at least one emitter" test -- the source of truth for
     * {@link #BRICK_SUMMARY_TARGET}'s {@link #SUMMARY_HAS_EMITTER} bit, factored out like {@link
     * #anySolidVoxel} for direct unit testing. Cheaper than {@link #anySolidVoxel} by construction: {@code
     * entries} is already deduplicated to the (at most {@link SectionHarvester#MAX_PALETTE_ENTRIES},
     * typically far fewer) distinct block states {@link SectionHarvester#harvest} actually found
     * present in the section, and each entry's {@link SectionPalette.Entry#emissiveStrength()} is
     * already resolved by harvest time -- so this only needs to OR across {@code entries}, never a
     * second 4096-voxel scan of {@code paletteIndices} the way {@code anySolidVoxel} needs (occupancy
     * genuinely needs per-voxel resolution to catch a single occupied voxel among mostly-air; presence
     * of a distinct block STATE in the palette is exactly "is it present in this section", no per-voxel
     * scan required). */
    static boolean anyEmitter(List<SectionPalette.Entry> entries) {
        for (SectionPalette.Entry entry : entries) {
            if (entry.emissiveStrength() > 0.0) {
                return true;
            }
        }
        return false;
    }

    /** Packs {@link #anySolidVoxel}'s and {@link #anyEmitter}'s booleans into the 4-byte little-endian
     * uint word {@link #BRICK_SUMMARY_TARGET} stores per slot (bit 0 = has solid voxels, bit 1 = {@link
     * #SUMMARY_HAS_EMITTER}, both zero = section confirmed empty of both) -- trivial, but pulled out
     * alongside {@link #anySolidVoxel}/{@link #anyEmitter} so both write paths ({@link #uploadSlot}/
     * {@link #uploadBatchLocked}) share the exact same value for the exact same input, never risking one
     * path packing a flag as some other nonzero bit than the other. */
    static int summaryWord(boolean anySolid, boolean anyEmitter) {
        int word = anySolid ? 1 : 0;
        if (anyEmitter) {
            word |= SUMMARY_HAS_EMITTER;
        }
        return word;
    }

    static byte packFaceSeal(SectionPalette.Entry entry) {
        int packed = entry.faceSealMask() & FaceSealResolver.ALL;
        if (entry.lightTransmissive()) {
            packed |= 0x40;
        }
        return (byte) packed;
    }

    // --- Out-of-bounds write guard ------------------------------------------------------------------
    // A slot index is computed by VoxelWindow.slotFor against whatever WindowState is live AT THAT
    // INSTANT, but the actual vkCmdUpdateBuffer for that slot can land many frames later:
    // VoxelWindow.harvestAndUploadBatch's RESYNC_EXECUTOR backlog (documented running thousands of
    // slots behind the initial-fill queue, see uploadSlots' own doc below) and
    // VoxelWindow.onSectionHarvested's Sodium-worker-thread path both do real, non-trivial work
    // between computing `slot` and taking SHARED_QUEUE_LOCK to write it. If "Colored Light Reach" (the
    // window radius option) shrinks in that gap, TargetRegistry.ensureBufferSize reallocates every
    // brick-grid buffer SMALLER before the stale write ever lands, and the slot's offset -- computed
    // against the OLD, larger diameter -- now points past the new buffer's end: Vulkan UB, and on
    // MoltenVK specifically a silent adjacent-VRAM scribble rather than a fault (a heap suballocation
    // gets no reliable driver-side bounds check), which is why this surfaced live as smeared,
    // texture-like garbage rather than a crash.
    //
    // This is the SAME class of bug GraphRunner's I-1 note (see computeDispatchOverride /
    // computeExtraPushConstants, and VoxelDebugRaymarchPass.allocatedDiameter's own doc) closed for
    // the light-volume DISPATCH side -- by routing every index computation through
    // VoxelDebugRaymarchPass.allocatedDiameter() instead of VoxelWindow.currentState().diameter(). I-1
    // never touched this WRITE path, and re-deriving "the diameter at the moment the slot index was
    // computed" cannot help here anyway -- that computation already happened, possibly frames ago, on
    // a different thread, and cannot be undone. The only point that can never be stale is the byte
    // range checked against the LIVE BufferInstance.sizeBytes() read inside the SAME SHARED_QUEUE_LOCK
    // critical section as the write itself (nothing can reallocate a buffer while that lock is held --
    // see uploadSlot's own "atomic critical section" doc) -- so every call site below checks there,
    // immediately before recording its vkCmdUpdateBuffer, rather than trusting the slot index's own
    // history.

    /** Distinct (target name, buffer size) pairs already warned about by {@link #logOobDrop} -- the
     * {@code SectionHarvester.CUTOUT_DROP_LOGGED} precedent (log once per distinct condition, not once
     * per occurrence). A single window shrink can leave THOUSANDS of already-queued slot writes
     * (RESYNC_EXECUTOR's harvest backlog, or Sodium worker threads racing {@code onSectionHarvested})
     * all rejected by the SAME now-current, smaller buffer size -- logging every one of them would
     * spam the log and burn CPU formatting strings on whichever thread (render or resync) hits this
     * path. Keyed by (target, bufferSize) rather than target alone so a genuinely NEW shrink (a
     * different bufferSize) still gets its own one-shot warning instead of going permanently silent
     * after the very first resize this session ever observed. */
    private static final Set<String> OOB_DROP_LOGGED = ConcurrentHashMap.newKeySet();

    /** One-shot-per-resize diagnostic for a slot write {@link #fitsInBuffer} rejected -- see {@link
     * #OOB_DROP_LOGGED}'s own doc for the dedup rationale. Not an error: a slot whose absolute byte
     * range no longer exists in the current (shrunk) buffer is genuinely obsolete data, not corruption
     * -- the section it belonged to either left the window entirely, or will be re-harvested under a
     * new, in-range slot index the next time {@link VoxelWindow#recenterAndResync} exposes it (see
     * that method's own "stale task cannot corrupt a valid slot" doc). */
    private static void logOobDrop(String targetName, int slot, long offset, long dataSize, long bufferSize) {
        if (OOB_DROP_LOGGED.add(targetName + '@' + bufferSize)) {
            FornaxMod.LOGGER.warn(
                    "[Fornax] BrickGridUpload: dropping slot {} write to {} (offset={}, size={}) -- past "
                            + "the current buffer's capacity ({} bytes). Computed against a since-shrunk "
                            + "voxel window (e.g. Colored Light Reach dragged down); the section will be "
                            + "re-harvested under an in-range slot if it re-enters the window.",
                    slot, targetName, offset, dataSize, bufferSize);
        }
    }

    /** Pure "does this byte range fit entirely inside the destination buffer" test -- the correctness
     * guard every {@code vkCmdUpdateBuffer} call site in this class runs immediately before recording
     * a write. Takes plain {@code long}s rather than a {@link BufferInstance}/handle pair
     * so it is headlessly unit-testable with no GPU, registry, or slot-geometry machinery -- see the
     * out-of-bounds-write-guard section comment above this method for the full hazard this closes.
     * {@code dataSize} of {@code 0} always fits (nothing is actually written); a negative {@code
     * offset}, negative {@code dataSize}, or negative {@code bufferSize} never fits (defensive --
     * none of this class's real callers ever construct one, but a pure function should not need to
     * trust its caller). Written as {@code offset <= bufferSize - dataSize} rather than {@code offset +
     * dataSize <= bufferSize} to avoid a signed-overflow false-positive if a caller ever passes a
     * pathologically huge stale offset (e.g. a slot index computed against a diameter far larger than
     * any diameter this engine actually allocates) -- both {@code bufferSize} and {@code dataSize} are
     * bounded by real allocations here, so their difference can never itself overflow. */
    static boolean fitsInBuffer(long offset, long dataSize, long bufferSize) {
        if (offset < 0 || dataSize < 0 || bufferSize < 0) {
            return false;
        }
        return offset <= bufferSize - dataSize;
    }

    /**
     * Packs one {@link VoxelShapeClassifier.PackedBox} into a single 32-bit word, 5 bits per
     * coordinate (0..16 inclusive -> 0..31 range, lossless). Order low-to-high:
     * minX, minY, minZ, maxX, maxY, maxZ. The shader unpacks with the mirror-image shifts.
     */
    static int packBox(VoxelShapeClassifier.PackedBox b) {
        return (b.minX() & 0x1F)
                | ((b.minY() & 0x1F) << 5)
                | ((b.minZ() & 0x1F) << 10)
                | ((b.maxX() & 0x1F) << 15)
                | ((b.maxY() & 0x1F) << 20)
                | ((b.maxZ() & 0x1F) << 25);
    }

    /** Packs {@link SectionPalette.Entry}'s emission scalars into palette word 15 -- see the
     * layout comment on {@link #PALETTE_ENTRY_WORDS}. {@code emissionColorRgb} is a packed
     * {@code 0x00RRGGBB} (or {@code 0} for "no authored color, fall back to face-color derivation").
     * Pure function, unit-tested directly. */
    static int packEmissionWord(double emissiveStrength, int emissionColorRgb) {
        int quantized = (int) Math.round(Math.clamp(emissiveStrength, 0.0, 1.0) * 255.0);
        int colorBits = (emissionColorRgb & 0xFFFFFF) << 8;
        return quantized | colorBits;
    }

    /** Divisor mapping the measured extinction coefficient (sigma) into the 8-bit unorm field of
     * palette word 0 bits 4-11: {@code round(clamp(sigma / EXTINCTION_SCALE, 0, 1) * 255)}. Measured
     * foliage sigma spans ~0.190-0.623 per block of path length (see the authoritative sigma table in
     * the volumetric-foliage-shadows plan), so 2.0 leaves ~3.2x headroom above the measured max at
     * 0.0078 (1/255) resolution -- room for a denser modded block without silently clipping.
     * {@code celestial_shadow.fsh} HAND-MIRRORS this exact constant -- change both together, or the
     * Java write side and the GLSL read side decode different physical densities from the same byte. */
    static final float EXTINCTION_SCALE = 2.0f;

    /** Packs word 0: boxCount in bits 0-3, extinction in bits 4-11 (8-bit unorm, see {@link
     * #EXTINCTION_SCALE}), cutout flag in bit 30, cross flag in bit 31 -- see the {@link
     * #PALETTE_ENTRY_WORDS} layout comment above (bits 12-29 are free; a SHAPE_TRUNCATED flag briefly
     * lived at bit 12, see that comment's own note). Pure, unit-tested directly. */
    static int packPaletteFlagsWord(int boxCount, boolean cutout, boolean cross, float extinction) {
        int word = boxCount & 0xF;
        int quantized = (int) Math.round(Math.clamp(extinction / EXTINCTION_SCALE, 0.0f, 1.0f) * 255.0);
        word |= (quantized & 0xFF) << 4;
        if (cutout) {
            word |= 1 << 30;
        }
        if (cross) {
            word |= 1 << 31;
        }
        return word;
    }

    /** Packs two [0,1] floats into one word as 16-bit unorm halves (high 16 bits = {@code a}, low 16
     * bits = {@code b}) -- the atlas-UV-rect packing {@link #PALETTE_ENTRY_WORDS}'s layout comment
     * documents for words 13/14 of a cutout entry. Pure, unit-tested directly. */
    static int packUvWord(float a, float b) {
        return (quantizeUnorm16(a) << 16) | quantizeUnorm16(b);
    }

    private static int quantizeUnorm16(float v) {
        return (int) Math.round(Math.clamp(v, 0.0f, 1.0f) * 65535.0);
    }

    /**
     * Packs a slot's palette entries into the exact little-endian byte layout documented above
     * ({@link #PALETTE_ENTRY_BYTES} per entry). Pure function of the harvested palette -- no GPU
     * handle touched -- so it is unit-tested directly and reused by {@link #uploadSlot}. Returns a
     * {@code entries.size() * PALETTE_ENTRY_BYTES}-length array; only the entries actually present are
     * written (a slot's payload never indexes past its own palette size, so the unused tail of the
     * slot's fixed stride is never read regardless of what stale data a reused toroidal slot left
     * there).
     */
    static byte[] packPaletteEntries(List<SectionPalette.Entry> entries) {
        byte[] out = new byte[entries.size() * PALETTE_ENTRY_BYTES];
        ByteBuffer buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        for (SectionPalette.Entry entry : entries) {
            List<VoxelShapeClassifier.PackedBox> boxes = entry.boxes();
            int boxCount = Math.min(boxes.size(), VoxelShapeClassifier.MAX_BOXES);
            boolean cutout = entry.cutout();
            boolean cross = entry.shapeKind() == VoxelShapeKind.CROSS;
            buf.putInt(packPaletteFlagsWord(boxCount, cutout, cross, entry.extinction()));
            int[] faceColors = entry.faceColors();
            for (int f = 0; f < 6; f++) {
                buf.putInt(faceColors[f]);
            }
            // Reuse of box slots 6/7 (words 13/14) as the packed UV rect for a cutout entry -- safe
            // because SectionHarvester.buildEntry only ever sets cutout=true for a FULL entry
            // (boxCount 0) or a CROSS entry (boxCount 1, its own harvested bbox in box[0]), so a
            // cutout-flagged entry's real boxCount never approaches 6 -- box[6]/box[7] are always
            // genuinely unused, exactly like every OTHER unused box slot past a PARTIAL entry's real
            // boxCount already is. See the PALETTE_ENTRY_WORDS layout comment for the full rationale.
            for (int k = 0; k < VoxelShapeClassifier.MAX_BOXES; k++) {
                if (cutout && k == 6) {
                    buf.putInt(packUvWord(entry.uvRect()[0], entry.uvRect()[1]));
                } else if (cutout && k == 7) {
                    buf.putInt(packUvWord(entry.uvRect()[2], entry.uvRect()[3]));
                } else {
                    buf.putInt(k < boxCount ? packBox(boxes.get(k)) : 0);
                }
            }
            buf.putInt(packEmissionWord(entry.emissiveStrength(), entry.emissionColor()));
        }
        return out;
    }

    /** Writes one section's harvested data into slot {@code slot}'s byte range of the four brick-grid
     * data buffers: the occupancy mask (one bit/voxel), the payload (one palette-index byte/voxel),
     * the palette table (real per-face colors + packed partial-shape box bounds, {@link
     * #PALETTE_BYTES_PER_SLOT} per slot, laid out to match the raymarch shader's std430 {@code
     * palette[]} view byte-for-byte; see the layout comment on {@link #PALETTE_ENTRY_WORDS}), and the
     * coarse per-slot summary word ({@link #BRICK_SUMMARY_TARGET}, see {@link #anySolidVoxel}). */
    public static void uploadSlot(TargetRegistry registry, int slot, SectionHarvester.Result result) {
        // Pack occupancy + payload bytes on the calling (Sodium worker) thread without touching any GPU
        // handle -- pure CPU work, kept outside the shared lock so parallel workers can pack
        // concurrently and only serialize for the brief submit.
        ByteBuffer occupancyBytes = MemoryUtil.memAlloc((int) OCCUPANCY_BYTES_PER_SLOT);
        ByteBuffer payloadBytes = MemoryUtil.memAlloc(VOXELS_PER_SECTION);
        ByteBuffer faceSealBytes = MemoryUtil.memAlloc(VOXELS_PER_SECTION);
        // Palette table for this slot. Packed on the worker thread (pure CPU); may be empty only if
        // the section somehow harvested zero palette entries, in which case there is nothing to upload
        // (vkCmdUpdateBuffer forbids a zero size) and no occupied voxel could reference it anyway.
        byte[] paletteData = packPaletteEntries(result.palette().entries());
        ByteBuffer paletteBytes = paletteData.length > 0 ? MemoryUtil.memAlloc(paletteData.length) : null;
        if (paletteBytes != null) {
            paletteBytes.put(paletteData).flip();
        }
        // 4-byte summary word -- see BRICK_SUMMARY_TARGET's own doc for why a whole uint rather than a
        // packed bit. Native order: this is a direct off-heap MemoryUtil buffer (native byte order,
        // little-endian on every supported platform), matching the GPU's own expectation, unlike
        // packPaletteEntries' heap-backed ByteBuffer.wrap(...) which needs an explicit .order() call.
        ByteBuffer summaryBytes = MemoryUtil.memAlloc((int) BRICK_SUMMARY_BYTES_PER_SLOT);
        try {
            byte[] paletteIndices = result.paletteIndices();
            for (int i = 0; i < OCCUPANCY_BYTES_PER_SLOT; i++) {
                occupancyBytes.put(i, (byte) 0);
            }
            for (int voxel = 0; voxel < VOXELS_PER_SECTION; voxel++) {
                int paletteIndex = paletteIndices[voxel] & 0xFF;
                SectionPalette.Entry entry = result.palette().entries().get(paletteIndex);
                boolean occupied = isOccupyingShape(entry.shapeKind());
                if (occupied) {
                    int byteIndex = voxel / 8;
                    int bitIndex = voxel % 8;
                    occupancyBytes.put(byteIndex, (byte) (occupancyBytes.get(byteIndex) | (1 << bitIndex)));
                }
                payloadBytes.put(voxel, paletteIndices[voxel]);
                faceSealBytes.put(voxel, packFaceSeal(entry));
            }
            summaryBytes.putInt(0, summaryWord(anySolidVoxel(paletteIndices, result.palette().entries()),
                    anyEmitter(result.palette().entries())));

            long occupancyOffset = (long) slot * OCCUPANCY_BYTES_PER_SLOT;
            long payloadOffset = (long) slot * VOXELS_PER_SECTION;
            long paletteOffset = (long) slot * PALETTE_BYTES_PER_SLOT;
            long faceSealOffset = (long) slot * FACE_SEAL_BYTES_PER_SLOT;
            long summaryOffset = (long) slot * BRICK_SUMMARY_BYTES_PER_SLOT;

            // The buffer-handle reads and the submit against them are one atomic critical section
            // under the process-wide compute lock: TargetRegistry.close/ensureBufferSize free or
            // reassign these exact buffers from the render thread, so reading the handle outside the
            // lock and submitting inside it would reopen a use-after-free. getBuffer() dereferences
            // TargetRegistry's plain-HashMap `buffers`, mutated only under this same lock -- so the
            // map read is race-free here too. See VulkanComputeBackend.SHARED_QUEUE_LOCK.
            synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
                BufferInstance occupancy = registry.getBuffer(OCCUPANCY_TARGET);
                BufferInstance payload = registry.getBuffer(PAYLOAD_TARGET);
                BufferInstance palette = registry.getBuffer(PALETTE_TARGET);
                BufferInstance faceSeal = registry.getBuffer(FACE_SEAL_TARGET);
                BufferInstance summary = registry.getBuffer(BRICK_SUMMARY_TARGET);
                if (occupancy == null || payload == null || palette == null || faceSeal == null || summary == null) {
                    return; // not allocated yet (or torn down) -- caller must ensureAllocated() first
                }
                // Correctness guard -- see fitsInBuffer's own doc (out-of-bounds-write fix). `slot` was
                // computed by the CALLER (VoxelWindow.onSectionHarvested), possibly against a window
                // state that no longer matches these just-read, LIVE buffer sizes. Checked before
                // recording anything and skips the WHOLE slot rather than partially writing some of the
                // four ranges but not others -- partial per-slot corruption would look plausible instead
                // of obviously wrong; a dropped slot is simply re-harvested if it re-enters the window.
                if (!fitsInBuffer(occupancyOffset, OCCUPANCY_BYTES_PER_SLOT, occupancy.sizeBytes())
                        || !fitsInBuffer(payloadOffset, VOXELS_PER_SECTION, payload.sizeBytes())
                        || !fitsInBuffer(faceSealOffset, FACE_SEAL_BYTES_PER_SLOT, faceSeal.sizeBytes())
                        || (paletteBytes != null && !fitsInBuffer(paletteOffset, paletteData.length, palette.sizeBytes()))
                        || !fitsInBuffer(summaryOffset, BRICK_SUMMARY_BYTES_PER_SLOT, summary.sizeBytes())) {
                    logOobDrop(OCCUPANCY_TARGET, slot, occupancyOffset, OCCUPANCY_BYTES_PER_SLOT, occupancy.sizeBytes());
                    return;
                }
                VulkanComputeBackend backend = VulkanComputeBackend.tryCreate();
                if (backend == null) {
                    return;
                }
                try {
                    // ALL FOUR ranges go into ONE command buffer, ONE submit, ONE fence wait (was two full
                    // staging round trips, each ending in a whole-queue waitIdle -- the upload-stall bug).
                    uploadLocked(backend,
                            occupancy.vkBuffer(), occupancyOffset, occupancyBytes,
                            payload.vkBuffer(), payloadOffset, payloadBytes,
                            faceSeal.vkBuffer(), faceSealOffset, faceSealBytes,
                            palette.vkBuffer(), paletteOffset, paletteBytes,
                            summary.vkBuffer(), summaryOffset, summaryBytes);
                } finally {
                    backend.close();
                }
            }
        } finally {
            MemoryUtil.memFree(occupancyBytes);
            MemoryUtil.memFree(payloadBytes);
            MemoryUtil.memFree(faceSealBytes);
            if (paletteBytes != null) {
                MemoryUtil.memFree(paletteBytes);
            }
            MemoryUtil.memFree(summaryBytes);
        }
    }

    /** One slot's harvested payload plus whether its toroidal index is being reclaimed from a
     * DIFFERENT section (needs its light volume zeroed first -- see {@link #clearLightSlot}'s own
     * doc for why). Input to {@link #uploadSlots}, the batched sibling of {@link #uploadSlot}/{@link
     * #clearLightSlot}: {@code slot} and {@code result} are the same pair {@link #uploadSlot} takes
     * directly; {@code clearLight} is the same condition {@code VoxelWindow#onSectionHarvested}
     * already tests per-slot (a non-null previous owner that differs from the newly harvested
     * position), computed by the caller since only it has both the previous and new owner. */
    public record SlotUpload(int slot, SectionHarvester.Result result, boolean clearLight) {
    }

    /**
     * Batched sibling of {@link #uploadSlot}/{@link #clearLightSlot}: packs and records every entry
     * in {@code uploads} into ONE command buffer, then does exactly ONE fenced {@code
     * vkQueueSubmit}/{@code vkWaitForFences} round trip for the WHOLE batch, instead of one fenced
     * round trip per slot. Exists because the batch-upload-throughput fix's own telemetry showed the
     * async resync tail draining at only ~8 slots/frame against an 11,927-slot initial-fill queue --
     * not because the per-slot GPU work is expensive (each slot copies at most ~21KB of already-
     * packed bytes, trivial for a discrete or integrated GPU), but because the FIXED overhead of one
     * fenced round trip (vkCreateFence + vkQueueSubmit + vkWaitForFences + vkDestroyFence, plus
     * {@link VulkanComputeBackend#tryCreate()}/{@code close()}) is paid once PER SLOT -- roughly
     * ~0.2ms each, the same fence-overhead figure {@link
     * dev.icehunter.fornax.voxel.VoxelWindow}'s {@code SYNC_BUDGET} doc already derives. Folding N
     * slots into one submit pays that fixed cost once for the whole batch; the marginal cost per
     * additional slot is just a few more {@code vkCmdUpdateBuffer} calls recorded into the same
     * command buffer (each still governed by its own 65536-byte inline-data limit and 4-byte
     * alignment requirement -- see {@link #uploadLocked}'s own doc -- both satisfied per-slot exactly
     * as before; there is no aggregate limit on how many such calls one command buffer may contain).
     *
     * <p>Reuses five scratch buffers across every entry in the batch (occupancy, payload, palette,
     * summary, light-zero) rather than allocating fresh native memory per slot: {@code
     * vkCmdUpdateBuffer} copies its source bytes into the command buffer's own storage at RECORD time
     * (proven by this class's own pre-existing {@link #clearOccupancySlotsLocked}, which already
     * reuses one 512-byte {@code zeros} buffer across every slot in its own batched clear), so a
     * scratch buffer is safe to overwrite for the next slot the instant its {@code
     * vkCmdUpdateBuffer} call returns -- no per-slot native allocation churn even for a batch of
     * thousands.
     *
     * <p>No-op (returns immediately) for an empty {@code uploads}, or when any of the four mandatory
     * brick-grid buffers (occupancy/payload/palette/summary) is not yet allocated -- callers must
     * {@link #ensureAllocated} first, same contract as {@link #uploadSlot}. The light-volume buffer
     * may be absent (emitter lighting never activated for the current pack); entries with {@code
     * clearLight() == true} are simply skipped for the light write in that case, matching {@link
     * #clearLightSlot}'s own no-op-when-unallocated behavior. */
    public static void uploadSlots(TargetRegistry registry, List<SlotUpload> uploads) {
        if (uploads.isEmpty()) {
            return;
        }
        ByteBuffer occupancyScratch = MemoryUtil.memAlloc((int) OCCUPANCY_BYTES_PER_SLOT);
        ByteBuffer payloadScratch = MemoryUtil.memAlloc(VOXELS_PER_SECTION);
        ByteBuffer faceSealScratch = MemoryUtil.memAlloc(VOXELS_PER_SECTION);
        ByteBuffer paletteScratch = MemoryUtil.memAlloc((int) PALETTE_BYTES_PER_SLOT);
        ByteBuffer summaryScratch = MemoryUtil.memAlloc((int) BRICK_SUMMARY_BYTES_PER_SLOT);
        ByteBuffer lightZeroScratch = MemoryUtil.memCalloc((int) lightVolumeBytesPerSlot());
        try {
            synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
                BufferInstance occupancy = registry.getBuffer(OCCUPANCY_TARGET);
                BufferInstance payload = registry.getBuffer(PAYLOAD_TARGET);
                BufferInstance palette = registry.getBuffer(PALETTE_TARGET);
                BufferInstance faceSeal = registry.getBuffer(FACE_SEAL_TARGET);
                BufferInstance summary = registry.getBuffer(BRICK_SUMMARY_TARGET);
                if (occupancy == null || payload == null || palette == null || faceSeal == null || summary == null) {
                    return; // not allocated yet (or torn down) -- caller must ensureAllocated() first
                }
                BufferInstance lightVolume = registry.getBuffer(LIGHT_VOLUME_TARGET); // may legitimately be null
                VulkanComputeBackend backend = VulkanComputeBackend.tryCreate();
                if (backend == null) {
                    return;
                }
                try {
                    // Buffer sizes are threaded through alongside the raw handles so uploadBatchLocked
                    // can re-validate EACH item against the LIVE capacity (fitsInBuffer) right before
                    // its own vkCmdUpdateBuffer calls -- see that method's own doc for why this is
                    // per-item rather than the whole-batch skip uploadSlot uses above.
                    uploadBatchLocked(backend,
                            occupancy.vkBuffer(), occupancy.sizeBytes(),
                            payload.vkBuffer(), payload.sizeBytes(),
                            faceSeal.vkBuffer(), faceSeal.sizeBytes(),
                            palette.vkBuffer(), palette.sizeBytes(),
                            summary.vkBuffer(), summary.sizeBytes(),
                            lightVolume != null ? lightVolume.vkBuffer() : -1L,
                            lightVolume != null ? lightVolume.sizeBytes() : 0L,
                            uploads, occupancyScratch, payloadScratch, faceSealScratch,
                            paletteScratch, summaryScratch, lightZeroScratch);
                } finally {
                    backend.close();
                }
            }
        } finally {
            MemoryUtil.memFree(occupancyScratch);
            MemoryUtil.memFree(payloadScratch);
            MemoryUtil.memFree(faceSealScratch);
            MemoryUtil.memFree(paletteScratch);
            MemoryUtil.memFree(summaryScratch);
            MemoryUtil.memFree(lightZeroScratch);
        }
    }

    /** One command buffer covering every entry in {@code uploads}: for each slot, the same
     * occupancy/payload/palette/summary packing {@link #uploadLocked} does for a single slot (reusing
     * the shared scratch buffers instead of allocating fresh ones), plus an optional light-volume
     * zero-fill ({@link #clearLightSlot}'s single range) when {@link SlotUpload#clearLight()} is set
     * -- then ONE fence create/submit/wait/destroy for the whole batch. Caller holds {@code
     * SHARED_QUEUE_LOCK}. {@code lightVolumeBuffer} of {@code -1L} means the light-volume buffer
     * isn't allocated; light-clear entries are skipped rather than attempted against an invalid
     * handle, matching {@link #clearLightSlot}'s own no-op behavior.
     *
     * <p><b>Per-item bounds guard.</b> Every entry in {@code
     * uploads} is checked with {@link #fitsInBuffer} against the {@code *BufferSize} parameters --
     * the LIVE {@link BufferInstance#sizeBytes()} the caller read inside this same {@code
     * SHARED_QUEUE_LOCK} critical section -- immediately before that entry's own {@code
     * vkCmdUpdateBuffer} calls are recorded, and a failing entry is skipped (see {@link
     * #fitsInBuffer}'s own doc for the full hazard). Unlike {@link #uploadSlot}'s whole-slot skip, this
     * is checked PER ITEM rather than once for the whole batch: {@code uploads} is a batch drained from
     * {@link VoxelWindow}'s {@code RESYNC_EXECUTOR} backlog, which can span a real window shrink
     * mid-batch -- some entries were computed against the OLD, larger diameter (now stale) while others
     * (harvested after the shrink) already reflect the new, smaller one, so a single all-or-nothing
     * decision for the whole batch would either wrongly write the stale entries or wrongly drop the
     * still-valid ones. */
    private static void uploadBatchLocked(VulkanComputeBackend backend,
                                          long occupancyBuffer, long occupancyBufferSize,
                                          long payloadBuffer, long payloadBufferSize,
                                          long faceSealBuffer, long faceSealBufferSize,
                                          long paletteBuffer, long paletteBufferSize,
                                          long summaryBuffer, long summaryBufferSize,
                                          long lightVolumeBuffer, long lightVolumeBufferSize, List<SlotUpload> uploads,
                                          ByteBuffer occupancyScratch, ByteBuffer payloadScratch,
                                          ByteBuffer faceSealScratch,
                                          ByteBuffer paletteScratch, ByteBuffer summaryScratch,
                                          ByteBuffer lightZeroScratch) {
        VulkanDevice device = backend.device();
        VkCommandBuffer cmd = backend.commandPool().allocateBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack).sType$Default();
            VK13.vkBeginCommandBuffer(cmd, beginInfo);

            for (SlotUpload item : uploads) {
                int slot = item.slot();
                SectionHarvester.Result result = item.result();
                byte[] paletteIndices = result.paletteIndices();

                long occupancyOffset = (long) slot * OCCUPANCY_BYTES_PER_SLOT;
                long payloadOffset = (long) slot * VOXELS_PER_SECTION;
                long faceSealOffset = (long) slot * FACE_SEAL_BYTES_PER_SLOT;
                long summaryOffset = (long) slot * BRICK_SUMMARY_BYTES_PER_SLOT;
                // Correctness guard, per item -- see this method's own "Per-item bounds guard" doc and
                // fitsInBuffer's doc (out-of-bounds-write fix). Checked BEFORE packing any bytes for
                // this item, so a stale/rejected item costs nothing beyond the check itself.
                if (!fitsInBuffer(occupancyOffset, OCCUPANCY_BYTES_PER_SLOT, occupancyBufferSize)
                        || !fitsInBuffer(payloadOffset, VOXELS_PER_SECTION, payloadBufferSize)
                        || !fitsInBuffer(faceSealOffset, FACE_SEAL_BYTES_PER_SLOT, faceSealBufferSize)
                        || !fitsInBuffer(summaryOffset, BRICK_SUMMARY_BYTES_PER_SLOT, summaryBufferSize)) {
                    logOobDrop(OCCUPANCY_TARGET, slot, occupancyOffset, OCCUPANCY_BYTES_PER_SLOT, occupancyBufferSize);
                    continue;
                }

                occupancyScratch.clear();
                for (int i = 0; i < OCCUPANCY_BYTES_PER_SLOT; i++) {
                    occupancyScratch.put(i, (byte) 0);
                }
                payloadScratch.clear();
                faceSealScratch.clear();
                for (int voxel = 0; voxel < VOXELS_PER_SECTION; voxel++) {
                    int paletteIndex = paletteIndices[voxel] & 0xFF;
                    SectionPalette.Entry entry = result.palette().entries().get(paletteIndex);
                    boolean occupied = isOccupyingShape(entry.shapeKind());
                    if (occupied) {
                        int byteIndex = voxel / 8;
                        int bitIndex = voxel % 8;
                        occupancyScratch.put(byteIndex, (byte) (occupancyScratch.get(byteIndex) | (1 << bitIndex)));
                    }
                    payloadScratch.put(voxel, paletteIndices[voxel]);
                    faceSealScratch.put(voxel, packFaceSeal(entry));
                }
                VK13.vkCmdUpdateBuffer(cmd, occupancyBuffer, occupancyOffset, occupancyScratch);
                VK13.vkCmdUpdateBuffer(cmd, payloadBuffer, payloadOffset, payloadScratch);
                VK13.vkCmdUpdateBuffer(cmd, faceSealBuffer, faceSealOffset, faceSealScratch);

                byte[] paletteData = packPaletteEntries(result.palette().entries());
                if (paletteData.length > 0) {
                    long paletteOffset = (long) slot * PALETTE_BYTES_PER_SLOT;
                    if (fitsInBuffer(paletteOffset, paletteData.length, paletteBufferSize)) {
                        paletteScratch.clear();
                        paletteScratch.put(paletteData).flip();
                        VK13.vkCmdUpdateBuffer(cmd, paletteBuffer, paletteOffset, paletteScratch);
                    } else {
                        logOobDrop(PALETTE_TARGET, slot, paletteOffset, paletteData.length, paletteBufferSize);
                    }
                }

                summaryScratch.clear();
                summaryScratch.putInt(0, summaryWord(anySolidVoxel(paletteIndices, result.palette().entries()),
                        anyEmitter(result.palette().entries())));
                VK13.vkCmdUpdateBuffer(cmd, summaryBuffer, summaryOffset, summaryScratch);

                if (item.clearLight() && lightVolumeBuffer != -1L) {
                    long lightOffset = (long) slot * lightVolumeBytesPerSlot();
                    if (fitsInBuffer(lightOffset, lightVolumeBytesPerSlot(), lightVolumeBufferSize)) {
                        lightZeroScratch.clear();
                        VK13.vkCmdUpdateBuffer(cmd, lightVolumeBuffer, lightOffset, lightZeroScratch);
                    } else {
                        logOobDrop(LIGHT_VOLUME_TARGET, slot, lightOffset, lightVolumeBytesPerSlot(), lightVolumeBufferSize);
                    }
                }
            }

            recordUploadToComputeReadBarrier(cmd, stack);
            VK13.vkEndCommandBuffer(cmd);

            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack).sType$Default();
            LongBuffer fenceOut = stack.mallocLong(1);
            if (VK13.vkCreateFence(device.vkDevice(), fenceInfo, null, fenceOut) != VK13.VK_SUCCESS) {
                FornaxMod.LOGGER.error("[Fornax] BrickGridUpload: vkCreateFence failed for batched slot upload");
                return;
            }
            long fence = fenceOut.get(0);
            try {
                VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack).sType$Default()
                        .pCommandBuffers(stack.pointers(cmd));
                int submitResult = VK13.vkQueueSubmit(backend.computeQueue().vkQueue(), submitInfo, fence);
                if (submitResult != VK13.VK_SUCCESS) {
                    // Must not wait on a fence nothing was ever submitted against -- that would block this
                    // (SHARED_QUEUE_LOCK-held) thread on FENCE_WAIT_TIMEOUT forever.
                    FornaxMod.LOGGER.error("[Fornax] BrickGridUpload: vkQueueSubmit failed with VkResult {} for batched slot upload", submitResult);
                    return;
                }
                int waitResult = VK13.vkWaitForFences(device.vkDevice(), fence, true, FENCE_WAIT_TIMEOUT);
                if (waitResult != VK13.VK_SUCCESS) {
                    FornaxMod.LOGGER.error("[Fornax] BrickGridUpload: vkWaitForFences returned VkResult {} for batched slot upload", waitResult);
                }
            } finally {
                VK13.vkDestroyFence(device.vkDevice(), fence, null);
            }
        }
    }

    /** Zero-fills one slot's three light ranges. Called when a toroidal slot is CLAIMED by a
     * new section (recenter shell resync, or a section entering the window via Sodium's meshing
     * path): the slot index is center-independent (VoxelWindow.slotFor's floorMod), so recenter
     * never remaps light data -- but a newly-claimed slot inherits the PREVIOUS owner's propagated
     * light, which would glow through the new section's geometry for the ~15 iterations the
     * propagation automaton needs to decay it (visible boundary ghosting). The tier-dependent stride
     * is 4-aligned and remains below vkCmdUpdateBuffer's 65536-byte inline-update ceiling
     * offset, embedded inline via vkCmdUpdateBuffer exactly like uploadSlot's ranges (same fenced
     * single-submit path, same SHARED_QUEUE_LOCK atomicity contract). No-op when the buffer isn't
     * allocated (no pack / emitter lighting never activated). */
    public static void clearLightSlot(TargetRegistry registry, int slot) {
        ByteBuffer zeros = MemoryUtil.memCalloc((int) lightVolumeBytesPerSlot());
        try {
            synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
                BufferInstance volume = registry.getBuffer(LIGHT_VOLUME_TARGET);
                if (volume == null) {
                    return;
                }
                long offset = (long) slot * lightVolumeBytesPerSlot();
                // Correctness guard -- see fitsInBuffer's own doc (out-of-bounds-write fix).
                if (!fitsInBuffer(offset, lightVolumeBytesPerSlot(), volume.sizeBytes())) {
                    logOobDrop(LIGHT_VOLUME_TARGET, slot, offset, lightVolumeBytesPerSlot(), volume.sizeBytes());
                    return;
                }
                VulkanComputeBackend backend = VulkanComputeBackend.tryCreate();
                if (backend == null) {
                    return;
                }
                try {
                    uploadSingleRangeLocked(backend, volume.vkBuffer(), offset, zeros);
                } finally {
                    backend.close();
                }
            }
        } finally {
            MemoryUtil.memFree(zeros);
        }
    }

    /** Zero-fills the OCCUPANCY range only (never payload/palette) for every slot in {@code slots}, in
     * one command buffer / one submit / one fence wait. Called synchronously from {@link
     * VoxelWindow#recenterAndResync}, on the render thread, for every slot the move newly exposes:
     * {@code recenterAndResync} publishes the new window geometry synchronously (one volatile write),
     * but the real harvest+upload of a newly-exposed slot happens on {@link VoxelWindow}'s background
     * resync executor, frames later. In between, the slot's GPU occupancy/payload/palette bytes still
     * belong to whatever section previously owned that toroidal index -- without this clear, the DDA
     * in {@code voxel_water_refl.comp} would read that stale geometry as if it belonged to the new
     * slot's world-space position (a "teleported" displaced/copied-looking patch). This method blocks
     * on its fence before returning (same as {@link #uploadSlot}/{@link #clearLightSlot}), so by the
     * time {@code recenterAndResync} returns the clear is already GPU-visible, before this frame's own
     * compute dispatch is submitted on the same queue.
     *
     * <p>Occupancy is the DDA's real correctness gate: {@code voxelOccupied} in {@code
     * voxel_water_refl.comp} is consulted before payload/palette are ever touched, so a slot with
     * occupancy=0 reads as an empty brick (DDA miss -> sky fallback) regardless of what stale bytes
     * its payload/palette ranges still hold; clearing those two as well would just be two more GPU
     * submits per slot for no visible effect (see also {@link #clearLightSlot}'s narrower light-only
     * precedent). The coarse SUMMARY word ({@link #BRICK_SUMMARY_TARGET}) is overwritten here too --
     * unlike payload/palette it is not merely dead weight if left stale: a brick-skip DDA (a pack's
     * {@code celestial_shadow.fsh}) trusts a nonzero summary to mean "descend and test", so leaving a
     * previous owner's real nonzero summary on a freshly-exposed, occupancy-cleared slot would not
     * corrupt the hit/miss verdict (the per-voxel occupancy test underneath is already all-zero and
     * correctly reports no occlusion) but would silently defeat the skip.
     *
     * <p><b>{@link #SUMMARY_PENDING}, not plain {@code 0}.</b> This method writes the {@link
     * #SUMMARY_PENDING} sentinel into the summary word rather than {@code 0}: a plain zero here would
     * be byte-identical to {@link #anySolidVoxel} having proved this brick is really empty, so a
     * consumer could never distinguish "confirmed no rock" from "rock may well be here, harvest just
     * hasn't landed yet" -- the gap that let a DDA ray pass clean through a slot straddling real solid
     * terrain during the window between this clear and the real re-harvest, reporting a false "lit"
     * result. See {@link #SUMMARY_PENDING}'s own doc for the full mechanism. Consumers that only test
     * "summary != 0" ({@code voxel_water_refl.comp}/{@code light_inject.comp}, which do not consume
     * this buffer at all) still correctly treat a pending slot as "has content, worth a closer look" --
     * {@link #SUMMARY_PENDING} sets no low bits {@link #summaryWord} ever produces, so it can never be
     * misread as the boolean {@code 1}; only a consumer that explicitly tests the sentinel bit ({@code
     * marchOcclusion}) gets the more conservative behavior. Keeps the "cleared until harvested"
     * invariant true for both buffers, not just the correctness-critical one.
     *
     * <p>Batched into one submit rather than one call per slot (mirroring {@link #uploadLocked}'s "one
     * command buffer, one submit" reasoning) because a single recenter can expose dozens of slots on
     * every section-boundary cross, and -- unlike {@link #uploadSlot}, which runs on a background
     * harvester thread -- this runs on the render thread, so a separate fenced submit per slot would
     * stall a frame proportional to shell size. No-op for an empty {@code slots} (the never-centered
     * sentinel's first recenter, or a move with a still-attached but not-yet-allocated registry) or
     * when the occupancy buffer isn't allocated yet. */
    public static void clearOccupancySlots(TargetRegistry registry, Collection<Integer> slots) {
        if (slots.isEmpty()) {
            return;
        }
        ByteBuffer zeros = MemoryUtil.memCalloc((int) OCCUPANCY_BYTES_PER_SLOT);
        ByteBuffer faceSealZeros = MemoryUtil.memCalloc((int) FACE_SEAL_BYTES_PER_SLOT);
        // NOT memCalloc: this word must hold SUMMARY_PENDING (a nonzero sentinel), not all-zero bytes --
        // see this method's own "SUMMARY_PENDING, not plain 0" doc paragraph above.
        ByteBuffer summaryPending = MemoryUtil.memAlloc((int) BRICK_SUMMARY_BYTES_PER_SLOT);
        summaryPending.putInt(0, SUMMARY_PENDING);
        try {
            synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
                BufferInstance occupancy = registry.getBuffer(OCCUPANCY_TARGET);
                if (occupancy == null) {
                    return; // not allocated yet (or torn down) -- caller must ensureAllocated() first
                }
                // Summary is allocated in lockstep with occupancy by ensureAllocated -- if occupancy
                // exists, summary does too, in every real code path. A null here means a genuinely
                // torn-down/mid-teardown registry state; the summary write is then simply skipped
                // (matching clearLightSlot's own no-op-when-unallocated precedent) rather than blocking
                // the correctness-critical occupancy clear on it.
                BufferInstance summary = registry.getBuffer(BRICK_SUMMARY_TARGET);
                BufferInstance faceSeal = registry.getBuffer(FACE_SEAL_TARGET);
                VulkanComputeBackend backend = VulkanComputeBackend.tryCreate();
                if (backend == null) {
                    return;
                }
                try {
                    clearOccupancySlotsLocked(backend, occupancy.vkBuffer(), occupancy.sizeBytes(),
                            faceSeal != null ? faceSeal.vkBuffer() : -1L,
                            faceSeal != null ? faceSeal.sizeBytes() : 0L,
                            summary != null ? summary.vkBuffer() : -1L, summary != null ? summary.sizeBytes() : 0L,
                            zeros, faceSealZeros, summaryPending, slots);
                } finally {
                    backend.close();
                }
            }
        } finally {
            MemoryUtil.memFree(zeros);
            MemoryUtil.memFree(faceSealZeros);
            MemoryUtil.memFree(summaryPending);
        }
    }

    /** One {@code vkCmdUpdateBuffer} per slot per buffer (occupancy, plus summary when allocated), all
     * in a single command buffer/submit/fence -- see {@link #clearOccupancySlots}. {@code zeros}/
     * {@code summaryPending} are single reused scratch buffers (each call only reads its
     * address+remaining(), never mutates the buffer's position), so no per-slot allocation is needed;
     * {@code summaryPending} holds the {@link #SUMMARY_PENDING} sentinel, not zero -- see {@link
     * #clearOccupancySlots}'s own "SUMMARY_PENDING, not plain 0" doc paragraph. {@code summaryBuffer}
     * of {@code -1L} means the summary buffer isn't allocated; its per-slot write is then skipped,
     * matching {@link #uploadBatchLocked}'s own light-volume {@code -1L} convention. Caller holds
     * {@code SHARED_QUEUE_LOCK}.
     *
     * <p>Per-slot bounds guard: {@code slots} is the exposed
     * shell {@link VoxelWindow#recenterAndResync} computed against the window state it JUST published,
     * so in practice this call site's race window is far narrower than {@link #uploadSlot}'s/{@link
     * #uploadBatchLocked}'s (no background-thread delay between computing a slot and writing it here).
     * The guard is still applied -- see {@link #fitsInBuffer}'s own doc -- for the same reason every
     * other write path in this class carries it: this is the only check that is not itself racy, and
     * a synchronous caller today is not a guarantee against a future caller shape that isn't. */
    private static void clearOccupancySlotsLocked(VulkanComputeBackend backend, long occupancyBuffer,
                                                   long occupancyBufferSize,
                                                   long faceSealBuffer, long faceSealBufferSize,
                                                   long summaryBuffer, long summaryBufferSize,
                                                   ByteBuffer zeros, ByteBuffer faceSealZeros,
                                                   ByteBuffer summaryPending,
                                                   Collection<Integer> slots) {
        VulkanDevice device = backend.device();
        VkCommandBuffer cmd = backend.commandPool().allocateBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack).sType$Default();
            VK13.vkBeginCommandBuffer(cmd, beginInfo);
            for (int slot : slots) {
                long occupancyOffset = (long) slot * OCCUPANCY_BYTES_PER_SLOT;
                if (!fitsInBuffer(occupancyOffset, OCCUPANCY_BYTES_PER_SLOT, occupancyBufferSize)) {
                    logOobDrop(OCCUPANCY_TARGET, slot, occupancyOffset, OCCUPANCY_BYTES_PER_SLOT, occupancyBufferSize);
                    continue;
                }
                VK13.vkCmdUpdateBuffer(cmd, occupancyBuffer, occupancyOffset, zeros);
                if (faceSealBuffer != -1L) {
                    long faceSealOffset = (long) slot * FACE_SEAL_BYTES_PER_SLOT;
                    if (fitsInBuffer(faceSealOffset, FACE_SEAL_BYTES_PER_SLOT, faceSealBufferSize)) {
                        VK13.vkCmdUpdateBuffer(cmd, faceSealBuffer, faceSealOffset, faceSealZeros);
                    } else {
                        logOobDrop(FACE_SEAL_TARGET, slot, faceSealOffset,
                                FACE_SEAL_BYTES_PER_SLOT, faceSealBufferSize);
                    }
                }
                if (summaryBuffer != -1L) {
                    long summaryOffset = (long) slot * BRICK_SUMMARY_BYTES_PER_SLOT;
                    if (fitsInBuffer(summaryOffset, BRICK_SUMMARY_BYTES_PER_SLOT, summaryBufferSize)) {
                        VK13.vkCmdUpdateBuffer(cmd, summaryBuffer, summaryOffset, summaryPending);
                    } else {
                        logOobDrop(BRICK_SUMMARY_TARGET, slot, summaryOffset, BRICK_SUMMARY_BYTES_PER_SLOT, summaryBufferSize);
                    }
                }
            }
            recordUploadToComputeReadBarrier(cmd, stack);
            VK13.vkEndCommandBuffer(cmd);

            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack).sType$Default();
            LongBuffer fenceOut = stack.mallocLong(1);
            if (VK13.vkCreateFence(device.vkDevice(), fenceInfo, null, fenceOut) != VK13.VK_SUCCESS) {
                FornaxMod.LOGGER.error("[Fornax] BrickGridUpload: vkCreateFence failed for occupancy-slot clear");
                return;
            }
            long fence = fenceOut.get(0);
            try {
                VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack).sType$Default()
                        .pCommandBuffers(stack.pointers(cmd));
                int submitResult = VK13.vkQueueSubmit(backend.computeQueue().vkQueue(), submitInfo, fence);
                if (submitResult != VK13.VK_SUCCESS) {
                    // Must not wait on a fence nothing was ever submitted against -- that would block this
                    // (SHARED_QUEUE_LOCK-held) thread on FENCE_WAIT_TIMEOUT forever.
                    FornaxMod.LOGGER.error("[Fornax] BrickGridUpload: vkQueueSubmit failed with VkResult {} for occupancy-slot clear", submitResult);
                    return;
                }
                int waitResult = VK13.vkWaitForFences(device.vkDevice(), fence, true, FENCE_WAIT_TIMEOUT);
                if (waitResult != VK13.VK_SUCCESS) {
                    FornaxMod.LOGGER.error("[Fornax] BrickGridUpload: vkWaitForFences returned VkResult {} for occupancy-slot clear", waitResult);
                }
            } finally {
                VK13.vkDestroyFence(device.vkDevice(), fence, null);
            }
        }
    }

    /** Uploads the occupancy, payload, palette, and summary byte ranges into their destination
     * buffers in a single fenced submission. No host-visible
     * staging buffer is allocated: {@code vkCmdUpdateBuffer} embeds each range's bytes directly into
     * the command buffer, which is valid here because all four pieces are tiny -- {@code
     * OCCUPANCY_BYTES_PER_SLOT} (512), {@code VOXELS_PER_SECTION} (4096), a slot's actual palette
     * byte count (at most {@code PALETTE_BYTES_PER_SLOT}, 16384), and {@code
     * BRICK_SUMMARY_BYTES_PER_SLOT} (4) -- each a multiple of 4 and far under {@code
     * vkCmdUpdateBuffer}'s 65536-byte inline limit -- and every destination offset is 4-aligned
     * (slot * 512, slot * 4096, slot * PALETTE_BYTES_PER_SLOT, slot * 4). The destination buffers were
     * created with {@code VK_BUFFER_USAGE_TRANSFER_DST_BIT} (they were {@code vkCmdCopyBuffer} targets
     * before this change), which {@code vkCmdUpdateBuffer} also requires.
     *
     * <p>Completion is signalled by a per-call {@link org.lwjgl.vulkan.VkFence} rather than {@code
     * VulkanQueue.waitIdle()}: {@code vkWaitForFences} on this fence drains ONLY this transfer, not
     * every other in-flight submission on the shared queue (the whole-queue drain twice per upload was
     * the upload-stall root cause). Blaze3D's {@code VulkanQueue.Submission.close()} hardcodes a null
     * fence (verified via {@code javap}), so -- exactly as {@code VoxelDebugRaymarchPass.submitDispatch}
     * does -- we bypass it and call {@code VK13.vkQueueSubmit} directly on the raw queue handle to
     * attach the fence. The fence and the {@code backend}'s command pool are ephemeral per call
     * (matching the per-call {@code tryCreate()}/{@code close()} lifecycle), so nothing is shared across
     * the multiple worker threads that call {@code uploadSlot} concurrently. Caller holds {@code
     * SHARED_QUEUE_LOCK}. */
    private static void uploadLocked(VulkanComputeBackend backend,
                                     long occupancyBuffer, long occupancyOffset, ByteBuffer occupancyBytes,
                                     long payloadBuffer, long payloadOffset, ByteBuffer payloadBytes,
                                     long faceSealBuffer, long faceSealOffset, ByteBuffer faceSealBytes,
                                     long paletteBuffer, long paletteOffset, ByteBuffer paletteBytes,
                                     long summaryBuffer, long summaryOffset, ByteBuffer summaryBytes) {
        VulkanDevice device = backend.device();
        VkCommandBuffer cmd = backend.commandPool().allocateBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack).sType$Default();
            VK13.vkBeginCommandBuffer(cmd, beginInfo);
            VK13.vkCmdUpdateBuffer(cmd, occupancyBuffer, occupancyOffset, occupancyBytes);
            VK13.vkCmdUpdateBuffer(cmd, payloadBuffer, payloadOffset, payloadBytes);
            VK13.vkCmdUpdateBuffer(cmd, faceSealBuffer, faceSealOffset, faceSealBytes);
            // Palette range: at most MAX_PALETTE_ENTRIES * PALETTE_ENTRY_BYTES = 16384 bytes, well under
            // vkCmdUpdateBuffer's 65536-byte inline limit; a multiple of 4 (64-byte entries); and its
            // offset (slot * 16384) is 4-aligned -- so it embeds inline exactly like the other two ranges.
            // Skipped only for the degenerate empty-palette section (paletteBytes == null).
            if (paletteBytes != null) {
                VK13.vkCmdUpdateBuffer(cmd, paletteBuffer, paletteOffset, paletteBytes);
            }
            VK13.vkCmdUpdateBuffer(cmd, summaryBuffer, summaryOffset, summaryBytes);
            recordUploadToComputeReadBarrier(cmd, stack);
            VK13.vkEndCommandBuffer(cmd);

            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack).sType$Default();
            LongBuffer fenceOut = stack.mallocLong(1);
            if (VK13.vkCreateFence(device.vkDevice(), fenceInfo, null, fenceOut) != VK13.VK_SUCCESS) {
                FornaxMod.LOGGER.error("[Fornax] BrickGridUpload: vkCreateFence failed for slot upload");
                return;
            }
            long fence = fenceOut.get(0);
            try {
                // Direct submit with the explicit fence -- see method javadoc for why this bypasses
                // VulkanQueue.Submission (its close() always submits a null fence).
                VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack).sType$Default()
                        .pCommandBuffers(stack.pointers(cmd));
                int submitResult = VK13.vkQueueSubmit(backend.computeQueue().vkQueue(), submitInfo, fence);
                if (submitResult != VK13.VK_SUCCESS) {
                    // Must not wait on a fence nothing was ever submitted against -- that would block this
                    // (SHARED_QUEUE_LOCK-held) thread on FENCE_WAIT_TIMEOUT forever.
                    FornaxMod.LOGGER.error("[Fornax] BrickGridUpload: vkQueueSubmit failed with VkResult {}", submitResult);
                    return;
                }
                int waitResult = VK13.vkWaitForFences(device.vkDevice(), fence, true, FENCE_WAIT_TIMEOUT);
                if (waitResult != VK13.VK_SUCCESS) {
                    FornaxMod.LOGGER.error("[Fornax] BrickGridUpload: vkWaitForFences returned VkResult {}", waitResult);
                }
            } finally {
                VK13.vkDestroyFence(device.vkDevice(), fence, null);
            }
        }
    }

    /** Small private sibling of {@link #uploadLocked} for a SINGLE range -- {@link #clearLightSlot}'s
     * zero-fill only ever touches one buffer, so it does not need {@code uploadLocked}'s three-range
     * plumbing. Same begin/{@code vkCmdUpdateBuffer}/end/fence-create/submit/wait/destroy sequence as
     * {@code uploadLocked}, deliberately duplicated rather than folded into it: reshaping the hot
     * three-range upload path to accommodate a one-range caller is not the smallest change here.
     * Caller holds {@code SHARED_QUEUE_LOCK}. */
    private static void uploadSingleRangeLocked(VulkanComputeBackend backend,
                                                 long buffer, long offset, ByteBuffer bytes) {
        VulkanDevice device = backend.device();
        VkCommandBuffer cmd = backend.commandPool().allocateBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack).sType$Default();
            VK13.vkBeginCommandBuffer(cmd, beginInfo);
            VK13.vkCmdUpdateBuffer(cmd, buffer, offset, bytes);
            recordUploadToComputeReadBarrier(cmd, stack);
            VK13.vkEndCommandBuffer(cmd);

            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack).sType$Default();
            LongBuffer fenceOut = stack.mallocLong(1);
            if (VK13.vkCreateFence(device.vkDevice(), fenceInfo, null, fenceOut) != VK13.VK_SUCCESS) {
                FornaxMod.LOGGER.error("[Fornax] BrickGridUpload: vkCreateFence failed for light-slot clear");
                return;
            }
            long fence = fenceOut.get(0);
            try {
                VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack).sType$Default()
                        .pCommandBuffers(stack.pointers(cmd));
                int submitResult = VK13.vkQueueSubmit(backend.computeQueue().vkQueue(), submitInfo, fence);
                if (submitResult != VK13.VK_SUCCESS) {
                    // Must not wait on a fence nothing was ever submitted against -- that would block this
                    // (SHARED_QUEUE_LOCK-held) thread on FENCE_WAIT_TIMEOUT forever.
                    FornaxMod.LOGGER.error("[Fornax] BrickGridUpload: vkQueueSubmit failed with VkResult {} for light-slot clear", submitResult);
                    return;
                }
                int waitResult = VK13.vkWaitForFences(device.vkDevice(), fence, true, FENCE_WAIT_TIMEOUT);
                if (waitResult != VK13.VK_SUCCESS) {
                    FornaxMod.LOGGER.error("[Fornax] BrickGridUpload: vkWaitForFences returned VkResult {} for light-slot clear", waitResult);
                }
            } finally {
                VK13.vkDestroyFence(device.vkDevice(), fence, null);
            }
        }
    }
}

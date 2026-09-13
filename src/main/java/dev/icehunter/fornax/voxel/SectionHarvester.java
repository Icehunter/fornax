package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.pack.material.BlockMaterials;
import dev.icehunter.fornax.pack.material.MaterialScalars;
import dev.icehunter.fornax.pack.material.MaterialScalarsHolder;
import net.minecraft.client.Minecraft;
import dev.icehunter.fornax.atlas.MaterialSourceIndex;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds one section's {@link SectionPalette} plus a per-voxel palette-index array from its real
 * block data. The costly per-block-state work (shape classification, per-face color pick) runs
 * once per DISTINCT state present in the section (via {@code forEachInPalette}), not once per each
 * of the 4096 cells. Partial opaque geometry is resolved at the real cell position: model emission
 * can differ for two cells with the same state, once position is taken into account. Equal proven
 * shapes share one palette entry, within the fixed cap.
 *
 * <p>Takes a {@code PalettedContainerRO<BlockState>} instead of a Sodium- or vanilla-specific type
 * on purpose: both {@code ClonedChunkSection.getBlockData()} (the edit/load path) and vanilla's own
 * {@code LevelChunkSection.getStates()} (the bootstrap/resync path) satisfy this interface, so both
 * paths share this harvest logic with no duplication.
 */
public final class SectionHarvester {
    /**
     * A {@code byte} can address at most 256 distinct palette entries (0-255, read back unsigned via
     * {@code & 0xFF}). Vanilla's own {@code PalettedContainerRO} can exceed this -- once a section
     * accumulates more than 256 distinct block states, its backing storage falls back to the "global
     * palette" -- so 256 is a hard ceiling of OUR fixed-width index, not a vanilla one. This constant
     * itself is set well under that ceiling, from real measured data rather than a guess: {@code
     * PaletteSizeHistogram}'s 2026-07-20 live-world census (16000 sections, {@code
     * FornaxDebugKeys}' palette-histogram-dump keybind) found p99 = 32 and a true observed max of 54
     * across the whole session, zero cap-hits at the old 256 cap. 96 gives ~1.8x headroom over that
     * observed max (54) while reclaiming ~152 MiB of the ~244 MiB the palette table cost at window
     * diameter 25 (256 -> 96 entries/slot: 244.1 -> 91.6 MiB). 64 was considered and rejected: only
     * 1.19x over the observed max, and the census is one world in one session -- a denser modded
     * build could plausibly exceed it, and exceeding the cap silently drops block states (see {@link
     * #harvest} below) rather than erroring. Mirrors {@link VoxelShapeClassifier#MAX_BOXES}'s
     * precedent: cap and log rather than let an index counter silently overflow/alias two different
     * states onto the same byte value. {@code PaletteSizeHistogram} stays on unconditionally so a
     * future session that finally hits this cap's cap-hits counter is the early warning to revisit it.
     */
    public static final int MAX_PALETTE_ENTRIES = 96;

    /** Distinct states already reported by the cutout-drop diagnostic below, so a chunk-load storm logs each once. */
    private static final java.util.Set<String> CUTOUT_DROP_LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** States the rebuild log below has already named, so loading many chunks at once logs each
     * state once. */
    private static final java.util.Set<String> PARTIAL_REBUILD_FAILED_LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * The most boxes a PARTIAL cell's real shape may occupy and still carry a cutout rect. A
     * cutout entry stores its atlas UV rect in palette words 13/14, the words {@code
     * BrickGridUpload.packPaletteEntries} otherwise uses for box slots 6/7; see {@code
     * BrickGridUpload.PALETTE_ENTRY_WORDS}'s layout comment. Six real boxes leave those two slots
     * free; seven or eight would need them for real box data, so a PARTIAL cell needing that many
     * boxes keeps the solid-occluder fallback instead.
     */
    static final int CUTOUT_MAX_BOXES = 6;

    public record Result(byte[] paletteIndices, SectionPalette palette, byte[] lightmap,
                         VoxelSourceSummary sourceSummary, long harvestGeneration, VoxelSourceEvidence sourceEvidence,
                         VoxelSourcePolicy sourcePolicy, RtSectionGeometry rtGeometry) {
        public Result {
            java.util.Objects.requireNonNull(sourceSummary, "sourceSummary");
            java.util.Objects.requireNonNull(sourceEvidence, "sourceEvidence");
            java.util.Objects.requireNonNull(sourcePolicy, "sourcePolicy");
            java.util.Objects.requireNonNull(rtGeometry, "rtGeometry");
            if (lightmap.length != VoxelLightmap.BYTES_PER_SLOT)
                throw new IllegalArgumentException("voxel lightmap must contain one byte per section cell");
        }
        public Result(byte[] paletteIndices, SectionPalette palette, byte[] lightmap,
                      VoxelSourceSummary sourceSummary, long harvestGeneration, VoxelSourceEvidence sourceEvidence,
                      VoxelSourcePolicy sourcePolicy) {
            this(paletteIndices, palette, lightmap, sourceSummary, harvestGeneration, sourceEvidence,
                    sourcePolicy, RtSectionGeometry.legacy(palette));
        }
        public Result(byte[] paletteIndices, SectionPalette palette, byte[] lightmap,
                      VoxelSourceSummary sourceSummary, long harvestGeneration, VoxelSourceEvidence sourceEvidence) {
            this(paletteIndices, palette, lightmap, sourceSummary, harvestGeneration, sourceEvidence, VoxelSourcePolicy.ALL);
        }
        public Result(byte[] paletteIndices, SectionPalette palette, byte[] lightmap,
                      VoxelSourceSummary sourceSummary, long harvestGeneration) {
            this(paletteIndices, palette, lightmap, sourceSummary, harvestGeneration, VoxelSourceEvidence.UNAVAILABLE);
        }
        public Result withLightmap(byte[] updated) {
            return new Result(paletteIndices, palette, updated, sourceSummary, harvestGeneration, sourceEvidence, sourcePolicy, rtGeometry);
        }
        public Result(byte[] paletteIndices, SectionPalette palette, byte[] lightmap,
                      VoxelSourceSummary sourceSummary) {
            this(paletteIndices, palette, lightmap, sourceSummary, VoxelHarvestLifecycle.generation());
        }
        public Result(byte[] paletteIndices, SectionPalette palette, byte[] lightmap) {
            this(paletteIndices, palette, lightmap, VoxelSourceSummary.EMPTY);
        }
        public Result(byte[] paletteIndices, SectionPalette palette) {
            this(paletteIndices, palette, new byte[VoxelLightmap.BYTES_PER_SLOT], VoxelSourceSummary.EMPTY);
        }
    }

    private SectionHarvester() {
    }

    /** The production entry point reads the active material policy only after it gets its lease.
     * Reading it in the caller instead would let old scalars pick up a new generation after a tag
     * reload. */
    public static @Nullable Result harvestCurrent(PalettedContainerRO<BlockState> blockData,
                                                  @Nullable BlockAndTintGetter tintSource,
                                                  int originX, int originY, int originZ) {
        try (var lease = VoxelHarvestLifecycle.tryAcquire()) {
            if (lease == null) return null;
            return harvestLeased(blockData, MaterialScalarsHolder.current(), tintSource,
                    originX, originY, originZ, lease.generation());
        }
    }

    /** No tint: grass, leaves, vines and water come back atlas-grey. Pass a tint if you have one. */
    public static @Nullable Result harvest(PalettedContainerRO<BlockState> blockData, MaterialScalars materialScalars) {
        return harvest(blockData, materialScalars, null, 0, 0, 0);
    }

    /**
     * @param tintSource a thread-safe world view, or null for no tint. On Sodium's build thread this
     *                   is its own {@code LevelSlice}, the view it tints its terrain through.
     * @param originX    section corner in world blocks. The tint is read once at the middle: the
     *                   palette is per-section and biome colour moves slower than 16 blocks.
     */
    public static @Nullable Result harvest(PalettedContainerRO<BlockState> blockData, MaterialScalars materialScalars,
                                 @Nullable BlockAndTintGetter tintSource,
                                 int originX, int originY, int originZ) {
        try (var lease = VoxelHarvestLifecycle.tryAcquire()) {
            if (lease == null) return null;
            return harvestLeased(blockData, materialScalars, tintSource, originX, originY, originZ,
                    lease.generation());
        }
    }

    private static Result harvestLeased(PalettedContainerRO<BlockState> blockData, MaterialScalars materialScalars,
                                       @Nullable BlockAndTintGetter tintSource,
                                       int originX, int originY, int originZ, long harvestGeneration) {
        BlockPos tintAt = tintSource == null
                ? null : new BlockPos(originX + 8, originY + 8, originZ + 8);
        Map<BlockState, Integer> indexByState = new IdentityHashMap<>();
        List<SectionPalette.Entry> entries = new ArrayList<>();
        VoxelSourcePolicy.Builder sourcePolicy = new VoxelSourcePolicy.Builder();
        boolean[] overflowLogged = {false};
        // One immutable material generation per harvest. Upload readers reject it after a reload.
        boolean sourceDiagnostics = VoxelSourceSummary.isEnabled();
        MaterialSourceIndex sourceIndex = sourceDiagnostics ? MaterialSourceIndex.current() : MaterialSourceIndex.EMPTY;
        List<VoxelSourceSummary.Entry> sourceEntries = sourceDiagnostics ? new ArrayList<>() : null;
        VoxelSourceEvidence.Builder sourceEvidence = sourceDiagnostics ? new VoxelSourceEvidence.Builder() : null;
        VoxelSourceSummary.Accumulator sourceInventory = sourceDiagnostics
                ? new VoxelSourceSummary.Accumulator(sourceIndex.generation()) : null;

        blockData.forEachInPalette(state -> {
            if (indexByState.containsKey(state)) {
                return;
            }
            if (entries.size() >= MAX_PALETTE_ENTRIES) {
                if (!overflowLogged[0]) {
                    overflowLogged[0] = true;
                    FornaxMod.LOGGER.warn(
                            "[Fornax] Section has more than {} distinct block states (real palette "
                                    + "overflowed to the global palette); extra states beyond the cap "
                                    + "will render as palette entry 0 instead of their real appearance",
                            MAX_PALETTE_ENTRIES);
                }
                return;
            }
            indexByState.put(state, entries.size());
            sourcePolicy.add(materialScalars.voxelLighting(BlockMaterials.idForState(state)));
            entries.add(buildEntry(state, materialScalars, biomeTint(state, tintSource, tintAt),
                    sourceIndex, sourceEntries, sourceEvidence));
        });

        // buildEntry, called once per distinct state in the forEachInPalette loop above, already
        // rebuilds a PARTIAL state's exact geometry from that state's baked model parts, with no
        // world and no position. That is why it is safe on this background harvest thread: it never
        // calls the live Fabric model-emission hook, the one a connected-texture mod uses with a
        // real world and position. A mod that only changes its geometry inside that hook is not
        // seen here and keeps the selection-shape fallback; that is a documented limit, not a bug.
        // The rebuild does not use shapeVariants below. That path keys on each voxel rather than on
        // each distinct state.
        var shapeVariants = new VoxelPaletteShapes(entries, baseIndex -> {
            sourcePolicy.copy(baseIndex);
            if (sourceEntries != null) {
                sourceEntries.add(sourceEntries.get(baseIndex));
                sourceEvidence.copy(baseIndex);
            }
        });
        var rtGeometry = new RtSectionGeometry.Builder();
        byte[] paletteIndices = new byte[16 * 16 * 16];
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState state = blockData.get(x, y, z);
                    Integer index = indexByState.get(state);
                    if (index == null) { sourcePolicy.markIncomplete(); rtGeometry.unknown(); }
                    else if (entries.get(index).shapeKind() == VoxelShapeKind.CROSS)
                        rtGeometry.harvest(state, entries.get(index), (y << 8) | (z << 4) | x, index,
                                new BlockPos(originX + x, originY + y, originZ + z));
                    else if (entries.get(index).shapeKind() == VoxelShapeKind.PARTIAL) rtGeometry.unknown();
                    // A state that was skipped above (palette overflow past MAX_PALETTE_ENTRIES) has no
                    // entry here -- fall back to index 0 deterministically rather than unboxing null.
                    paletteIndices[(y << 8) | (z << 4) | x] = (byte) (index != null ? index : 0);
                    if (sourceInventory != null) {
                        // Intrinsic emission is raw world data; category styling must not hide it.
                        sourceInventory.add(!state.isAir(), state.getLightEmission(),
                                index == null ? null : sourceEntries.get(index));
                        sourceEvidence.addCell(!state.isAir(), index == null ? -1 : index);
                    }
                }
            }
        }

        // Palette-size diagnostic (2026-07-20): feeds PaletteSizeHistogram's always-on, lock-free
        // counters so MAX_PALETTE_ENTRIES keeps tracking real distribution data instead of a guess --
        // it already shrank the constant once (256 -> 96, see MAX_PALETTE_ENTRIES's own doc), and stays
        // on so a future session that finally hits this cap shows up as a cap-hits > 0 early warning.
        // overflowLogged[0] is the harvester's real "more than MAX_PALETTE_ENTRIES distinct states
        // were present" signal, not just entries.size() == MAX_PALETTE_ENTRIES (a section that
        // happens to have EXACTLY MAX_PALETTE_ENTRIES distinct states with no overflow would also report).
        if (shapeVariants.overflowed()) {
            FornaxMod.LOGGER.warn("[Fornax] Section ({}, {}, {}) exhausted its {} voxel palette entries; "
                    + "additional contextual shapes retain their selection-shape fallback",
                    originX, originY, originZ, MAX_PALETTE_ENTRIES);
        }
        PaletteSizeHistogram.record(entries.size(), overflowLogged[0] || shapeVariants.overflowed());

        return new Result(paletteIndices, new SectionPalette(entries),
                VoxelLightmap.capture(tintSource, originX, originY, originZ),
                sourceInventory == null ? VoxelSourceSummary.EMPTY : sourceInventory.finish(overflowLogged[0]),
                harvestGeneration, sourceEvidence == null ? VoxelSourceEvidence.UNAVAILABLE
                        : sourceEvidence.finish(overflowLogged[0]), sourcePolicy.finish(overflowLogged[0]), rtGeometry.finish());
    }

    /**
     * The biome colour as 0x00RRGGBB, or -1 (white, which multiplies to nothing) when the state has
     * no tint. Vanilla answers -1 for every untinted block, so no list of tinted blocks is needed.
     *
     * <p>Never throws: a tint is worth less than the mesh build this hook sits in.
     */
    private static int biomeTint(BlockState state, @Nullable BlockAndTintGetter tintSource,
                                 @Nullable BlockPos tintAt) {
        if (tintSource == null || tintAt == null) {
            return -1;
        }
        try {
            // MC 26.2 answers with a per-layer tint source, not one getColor call. Layer 0 is the
            // one grass, leaves, vines and water carry; no source means no tint.
            BlockTintSource source = Minecraft.getInstance().getBlockColors().getTintSource(state, 0);
            if (source == null) {
                return -1;
            }
            return source.colorInWorld(state, tintSource, tintAt) | 0xFF000000;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static SectionPalette.Entry buildEntry(BlockState state, MaterialScalars materialScalars,
                                                   int tint, MaterialSourceIndex sourceIndex,
                                                   @Nullable List<VoxelSourceSummary.Entry> sourceEntries,
                                                   VoxelSourceEvidence.@Nullable Builder sourceEvidence) {
        VoxelShapeClassifier.ClassifiedShape shape = VoxelShapeClassifier.classify(state);
        int categoryId = BlockMaterials.idForState(state);

        // The surface check below and the geometry rebuild further down share this one
        // collectParts read of the state's baked model parts, instead of each asking for its own.
        List<BlockStateModelPart> parts = shape.kind() == VoxelShapeKind.EMPTY
                ? List.of() : FaceColorResolver.parts(state);
        // The material layer and quad shape come from the model; a pack tag also counts.
        FaceColorResolver.Surface surface = shape.kind() == VoxelShapeKind.EMPTY
                ? new FaceColorResolver.Surface(false, false) : FaceColorResolver.surface(parts);
        boolean cutoutTag = surface.cutout() || materialScalars.isCutout(categoryId);
        boolean crossTag = surface.cross() || materialScalars.isCross(categoryId);
        VoxelShapeKind effectiveKind = shape.kind();
        List<VoxelShapeClassifier.PackedBox> effectiveBoxes = shape.boxes();
        float[] uvRect = SectionPalette.NO_UV_RECT;
        boolean cutout = false;
        float extinction = 0f;
        if (crossTag) {
            FaceColorResolver.CrossGeometry cross = FaceColorResolver.resolveCrossGeometry(state);
            if (cross != null) {
                effectiveKind = VoxelShapeKind.CROSS;
                effectiveBoxes = List.of(cross.bbox());
                uvRect = cross.uvRect();
                cutout = cutoutTag;
            }
            // else: tagged cross but the real model has no unculled quads (misconfigured blocks.toml
            // entry) -- fall through with the vanilla-classified shape/boxes untouched, cutout false,
            // exactly as if the tag had never been applied. Never guesses at geometry that doesn't exist.
            // A state that is still PARTIAL after this falls through to the rebuild below like any
            // other PARTIAL cell: no cross geometry replaced it, so the cross tag alone must not
            // hold the rebuild back.
        }
        if (effectiveKind == VoxelShapeKind.PARTIAL) {
            // The selection shape (vanilla's own getShape()) can be wider than what the block
            // renders: a fence arm's selection is one solid slab, but its rendered rails are two
            // thinner bars with a real gap between them. Sending the selection box then makes a
            // shadow ray toward the rail's real face start inside the sent box, so the ray reads as
            // blocked and the gap fills in. Rebuilding from the same baked parts the surface read
            // above already collected recovers the real rendered boxes; a state whose geometry is
            // open, off-grid, rotated or alpha-uncertain cannot be proven closed and keeps the
            // selection shape instead, the same fallback used for any shape the rebuild cannot
            // prove.
            List<VoxelShapeClassifier.PackedBox> rendered = VoxelModelShape.reconstruct(parts);
            if (rendered == null && PARTIAL_REBUILD_FAILED_LOGGED.add(state.toString())) {
                FornaxMod.LOGGER.debug(
                        "[Fornax] {} kept its selection shape: its baked model parts do not prove a "
                                + "closed solid shape. Many PARTIAL blocks are open, off-grid, "
                                + "rotated or alpha-uncertain; this is not an error",
                        state);
            }
            effectiveBoxes = renderedOrSelection(effectiveKind, effectiveBoxes, rendered);
            if (partialCutoutAllowed(effectiveKind, effectiveBoxes.size(), cutoutTag)) {
                float[] rect = FaceColorResolver.resolveCutoutRect(state);
                if (rect != null) {
                    uvRect = rect;
                    cutout = true;
                    // extinction stays 0: it stands for a cloud of leaves spread through a block. A
                    // door, trapdoor, glass pane or iron bars is a flat sheet, not a cloud. For a
                    // sheet, the alpha test the shader already runs on each texel is the right
                    // answer.
                }
            }
        } else if (!crossTag && cutoutTag && effectiveKind == VoxelShapeKind.FULL) {
            float[] rect = FaceColorResolver.resolveCutoutRect(state);
            if (rect != null) {
                uvRect = rect;
                cutout = true;
                // Volumetric foliage (2026-07-20): measure how much light this block's real geometry
                // actually blocks, INSTEAD OF the per-texel alpha test cutout normally gets -- see
                // SectionPalette.Entry#extinction for why. Zero here means the model bakes no usable
                // quads, and the shader falls back to the pre-existing alpha test rather than rendering
                // the block fully transparent.
                extinction = FoliageDensityResolver.resolveExtinction(state);
            }
        }
        // Derived from partialCutoutAllowed rather than re-checking the box count directly, so the
        // two can never drift apart on where the limit sits. crossTag needs no separate check: a
        // state whose cross shape resolved is CROSS, not PARTIAL, so partialCutoutAllowed already
        // answers false for it.
        boolean partialBoxLimitDropped = cutoutTag
                && effectiveKind == VoxelShapeKind.PARTIAL && !effectiveBoxes.isEmpty()
                && !partialCutoutAllowed(effectiveKind, effectiveBoxes.size(), cutoutTag);
        // DIAGNOSTIC: a block classified as cutout that nonetheless harvests as a solid
        // occluder is invisible from the outside -- the shadow shader just treats it as an opaque cube, so
        // every foliage-transmission setting looks identical and the canopy self-shadows with no clue why.
        // Logged once per distinct state (this runs per palette entry, i.e. once per state per section, so
        // the set keeps a busy chunk-load from spamming). Remove once the leaf path is confirmed.
        if ((cutoutTag || crossTag) && !cutout && CUTOUT_DROP_LOGGED.add(state.toString())) {
            if (partialBoxLimitDropped) {
                FornaxMod.LOGGER.warn(
                        "[Fornax] {} has a cutout material tag but its PARTIAL shape needs {} boxes, "
                                + "over the {}-box limit that keeps the UV-rect words free (see "
                                + "BrickGridUpload's palette layout comment); it harvests as a SOLID "
                                + "occluder instead",
                        state, effectiveBoxes.size(), CUTOUT_MAX_BOXES);
            } else {
                FornaxMod.LOGGER.warn(
                        "[Fornax] {} has cutout/cross geometry or tags but harvested as a SOLID occluder "
                                + "(shape={}, crossTag={}, cutoutTag={}): no UV rect could be resolved from its "
                                + "baked model, so foliage light transmission will not apply to it",
                        state, effectiveKind, crossTag, cutoutTag);
            }
        }
        // The branch above gives the rect to a PARTIAL cell with a cutout material and at most
        // CUTOUT_MAX_BOXES boxes. That box count leaves box slots 6/7 empty, so they can hold the
        // packed UV rect the way a FULL or CROSS cutout entry does (see
        // BrickGridUpload.PALETTE_ENTRY_WORDS). A PARTIAL cell needing more boxes than that keeps
        // the solid fallback instead of overwriting live box data; the warn above logs it once per
        // state.
        // Effective emission = blocks.toml category strength x the vanilla light level (0..15 -> 0..1),
        // so a redstone torch (tagged, level 7) emits dimmer than glowstone (tagged, level 15) and an
        // OFF redstone lamp (tagged, level 0) emits nothing. Any UNTAGGED block with a nonzero vanilla
        // light level still emits at the vanilla level alone (the untagged-emitter floor -- lava has no
        // blocks.toml category but getLightEmission() == 15; fluids ARE block states on this harvest
        // path, which is exactly why lava becomes a first-class area emitter here when the
        // vertex-material path never could). getLightEmission() javap-confirmed real, zero prior call
        // sites; same per-state access shape as getLightDampening() below.
        double emissiveStrength = effectiveEmission(
                materialScalars.emissiveStrength(categoryId), state.getLightEmission());
        // Authored cast-light hue (hdr-livefix-1: face-color-derived tint can't carry hue for
        // categories whose face texels are pale/handle-colored, or that lump several differently-hued
        // block variants under one category -- e.g. all six torch variants). 0 when the category
        // authored no emissive.color; BrickGridUpload/light_inject fall back to face-color derivation.
        int emissionColor = materialScalars.emissiveColor(categoryId);

        int[] faceColors = new int[6];
        if (effectiveKind == VoxelShapeKind.FULL || effectiveKind == VoxelShapeKind.PARTIAL) {
            for (Direction dir : Direction.values()) {
                // Exposure is genuinely resolved per-VOXEL (it depends on the real neighbor at that
                // specific position), not per-palette-entry -- this task resolves color unconditionally
                // here for every full/partial state's faces; Task 8/9's per-section orchestration is
                // where a real neighbor-aware skip would apply if profiling ever shows this matters.
                // For now this keeps SectionHarvester a pure function of one PalettedContainerRO, with
                // no neighbor-section dependency -- a deliberate simplicity/cost tradeoff, not an
                // oversight: resolving a face that turns out to be buried is wasted work, but resolving
                // it costs nothing MORE than what FaceColorResolver already does once per distinct
                // state, which is already the cheap path (palette-sized, not voxel-sized).
                // One tint per entry; the per-quad tint index picks which quads take it.
                faceColors[dir.get3DDataValue()] = FaceColorResolver.resolve(state, dir, tint);
            }
        }

        // A voxel is light-transmissive if it geometrically fills the cell (FULL shape -- glass is a
        // real full cube, unlike a torch) but vanilla's own light-dampening says it doesn't fully seal
        // light out. STRICTLY less than MAX_LEVEL (15) is required here -- getLightDampening() is
        // already a 0-15 value by definition, so "<= MAX_LEVEL" would always be true and this flag
        // would never be false. Regular glass reports 0 (transmissive); tinted glass is explicitly
        // overridden to report 15 (opaque) despite visually looking like glass -- confirmed vanilla
        // behavior, and exactly why this must read the real per-state value, not assume "glass = see
        // through." There is no separate tint color: the per-face albedo already resolved above IS
        // the transmission tint a later lighting milestone reads.
        boolean lightTransmissive = effectiveKind == VoxelShapeKind.FULL
                && state.getLightDampening() < net.minecraft.world.level.lighting.LightEngine.MAX_LEVEL;

        int[] faceTextures;
        if (sourceEntries == null) {
            faceTextures = VoxelFaceTexture.resolve(state, effectiveKind, tint);
        } else if (state.isAir()) {
            faceTextures = VoxelFaceTexture.resolve(state, effectiveKind, tint);
            sourceEntries.add(new VoxelSourceSummary.Entry(0, false));
            sourceEvidence.add(false, 0, List.of());
        } else {
            var sourceFaces = VoxelFaceTexture.resolveSources(state, effectiveKind, tint, sourceIndex);
            faceTextures = sourceFaces.textureWords();
            sourceEntries.add(VoxelSourceSummary.Entry.from(sourceFaces.summaries()));
            sourceEvidence.add(true, state.getLightEmission(), sourceFaces.summaries(),
                    state.getLightEmission() == 0 && sourceFaces.materialsKnownNonpositive());
        }
        return new SectionPalette.Entry(effectiveKind, effectiveBoxes, faceColors, emissiveStrength,
                lightTransmissive, emissionColor, cutout, uvRect, extinction,
                FaceSealResolver.resolve(effectiveKind, effectiveBoxes),
                faceTextures);
    }

    /** See the call site above -- extracted pure so the tagged/untagged/off-lamp matrix is directly
     * unit-testable without a bootstrapped BlockState. */
    static double effectiveEmission(double categoryStrength, int vanillaLightLevel) {
        double vanilla = vanillaLightLevel / 15.0;
        return categoryStrength > 0.0 ? categoryStrength * vanilla : vanilla;
    }

    /**
     * True when a PARTIAL cell's cutout material tag may carry its sprite rect: it has a cutout
     * material and at least one, but no more than {@link #CUTOUT_MAX_BOXES}, boxes, leaving box
     * slots 6/7 free for the rect. FULL and CROSS always answer false here, whatever the arguments.
     * Their own branches in {@code buildEntry} set their cutout bit. EMPTY always answers false,
     * and so does a PARTIAL cell with no boxes. Sorting a shape as PARTIAL never gives zero boxes,
     * so the range starts at 1. Kept free of other state so a test can call it without a started-up
     * BlockState, which {@code buildEntry} needs.
     */
    static boolean partialCutoutAllowed(VoxelShapeKind kind, int boxCount, boolean cutoutTag) {
        return cutoutTag && kind == VoxelShapeKind.PARTIAL && boxCount >= 1 && boxCount <= CUTOUT_MAX_BOXES;
    }

    /**
     * Which box list a cell sends on: the rebuilt {@code rendered} boxes when {@code kind} is
     * PARTIAL and the rebuild proved a closed shape, {@code selection} (vanilla's own selection
     * shape) otherwise. FULL, CROSS and EMPTY always keep {@code selection}; the rebuild only ever
     * replaces a PARTIAL cell's boxes. Takes no {@code BlockState} and no model manager, so a test
     * can call it without a running Minecraft client, which {@code buildEntry} needs.
     */
    static List<VoxelShapeClassifier.PackedBox> renderedOrSelection(VoxelShapeKind kind,
            List<VoxelShapeClassifier.PackedBox> selection,
            @Nullable List<VoxelShapeClassifier.PackedBox> rendered) {
        return kind == VoxelShapeKind.PARTIAL && rendered != null ? rendered : selection;
    }
}

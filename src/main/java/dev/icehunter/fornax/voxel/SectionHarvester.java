package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.pack.material.BlockMaterials;
import dev.icehunter.fornax.pack.material.MaterialScalars;
import net.minecraft.client.Minecraft;
import dev.icehunter.fornax.atlas.MaterialSourceIndex;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
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
 * Builds one section's {@link SectionPalette} + per-voxel palette-index array from its real block
 * data. The expensive per-block-state work (shape classification, per-face color resolution) runs
 * once per DISTINCT state actually present in the section (via {@code forEachInPalette}), not once
 * per of the 4096 cells -- the cheap final pass just assigns each cell's palette index.
 *
 * <p>Takes a {@code PalettedContainerRO<BlockState>} rather than a Sodium- or vanilla-specific type
 * deliberately: both {@code ClonedChunkSection.getBlockData()} (the edit/load trigger, Task 8) and
 * vanilla's own {@code LevelChunkSection.getStates()} (the bootstrap/resync trigger, Task 9) satisfy
 * this interface, so both triggers share this exact harvest logic with zero duplication.
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

    public record Result(byte[] paletteIndices, SectionPalette palette, byte[] lightmap,
                         VoxelSourceSummary sourceSummary, long harvestGeneration) {
        public Result {
            java.util.Objects.requireNonNull(sourceSummary, "sourceSummary");
            if (lightmap.length != VoxelLightmap.BYTES_PER_SLOT)
                throw new IllegalArgumentException("voxel lightmap must contain one byte per section cell");
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
        boolean[] overflowLogged = {false};
        // One immutable material generation per harvest. Upload readers reject it after a reload.
        boolean sourceDiagnostics = VoxelSourceSummary.isEnabled();
        MaterialSourceIndex sourceIndex = sourceDiagnostics ? MaterialSourceIndex.current() : MaterialSourceIndex.EMPTY;
        List<VoxelSourceSummary.Entry> sourceEntries = sourceDiagnostics ? new ArrayList<>() : null;
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
            entries.add(buildEntry(state, materialScalars, biomeTint(state, tintSource, tintAt),
                    sourceIndex, sourceEntries));
        });

        byte[] paletteIndices = new byte[16 * 16 * 16];
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState state = blockData.get(x, y, z);
                    Integer index = indexByState.get(state);
                    // A state that was skipped above (palette overflow past MAX_PALETTE_ENTRIES) has no
                    // entry here -- fall back to index 0 deterministically rather than unboxing null.
                    paletteIndices[(y << 8) | (z << 4) | x] = (byte) (index != null ? index : 0);
                    if (sourceInventory != null) {
                        // Intrinsic emission is raw world data; category styling must not hide it.
                        sourceInventory.add(!state.isAir(), state.getLightEmission(),
                                index == null ? null : sourceEntries.get(index));
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
        PaletteSizeHistogram.record(entries.size(), overflowLogged[0]);

        return new Result(paletteIndices, new SectionPalette(entries),
                VoxelLightmap.capture(tintSource, originX, originY, originZ),
                sourceInventory == null ? VoxelSourceSummary.EMPTY : sourceInventory.finish(overflowLogged[0]),
                harvestGeneration);
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
                                                   @Nullable List<VoxelSourceSummary.Entry> sourceEntries) {
        VoxelShapeClassifier.ClassifiedShape shape = VoxelShapeClassifier.classify(state);
        int categoryId = BlockMaterials.idForState(state);

        // The material layer and quad shape come from the model; a pack tag also counts.
        FaceColorResolver.Surface surface = shape.kind() == VoxelShapeKind.EMPTY
                ? new FaceColorResolver.Surface(false, false) : FaceColorResolver.surface(state);
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
        } else if (cutoutTag && effectiveKind == VoxelShapeKind.FULL) {
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
        // DIAGNOSTIC: a block classified as cutout that nonetheless harvests as a solid
        // occluder is invisible from the outside -- the shadow shader just treats it as an opaque cube, so
        // every foliage-transmission setting looks identical and the canopy self-shadows with no clue why.
        // Logged once per distinct state (this runs per palette entry, i.e. once per state per section, so
        // the set keeps a busy chunk-load from spamming). Remove once the leaf path is confirmed.
        if ((cutoutTag || crossTag) && !cutout && CUTOUT_DROP_LOGGED.add(state.toString())) {
            FornaxMod.LOGGER.warn(
                    "[Fornax] {} has cutout/cross geometry or tags but harvested as a SOLID occluder "
                            + "(shape={}, crossTag={}, cutoutTag={}) -- no UV rect could be resolved from its "
                            + "baked model, so foliage light transmission will not apply to it",
                    state, effectiveKind, crossTag, cutoutTag);
        }
        // cutoutTag on a PARTIAL/EMPTY shape (neither FULL cube nor real cross geometry) is silently
        // ignored: there is no meaningful UV-rect capture path for it, and forcing one would risk the
        // shader alpha-testing against garbage/zero UV data. No worse than pre-milestone behavior.
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
        } else {
            var sourceFaces = VoxelFaceTexture.resolveSources(state, effectiveKind, tint, sourceIndex);
            faceTextures = sourceFaces.textureWords();
            sourceEntries.add(VoxelSourceSummary.Entry.from(sourceFaces.summaries()));
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
}

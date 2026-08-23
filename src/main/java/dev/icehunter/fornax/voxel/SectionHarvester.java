package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.pack.material.BlockMaterials;
import dev.icehunter.fornax.pack.material.MaterialScalars;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainerRO;

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

    public record Result(byte[] paletteIndices, SectionPalette palette) {
    }

    private SectionHarvester() {
    }

    public static Result harvest(PalettedContainerRO<BlockState> blockData, MaterialScalars materialScalars) {
        Map<BlockState, Integer> indexByState = new IdentityHashMap<>();
        List<SectionPalette.Entry> entries = new ArrayList<>();
        boolean[] overflowLogged = {false};

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
            entries.add(buildEntry(state, materialScalars));
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

        return new Result(paletteIndices, new SectionPalette(entries));
    }

    private static SectionPalette.Entry buildEntry(BlockState state, MaterialScalars materialScalars) {
        VoxelShapeClassifier.ClassifiedShape shape = VoxelShapeClassifier.classify(state);
        int categoryId = BlockMaterials.idForState(state);

        // Cutout/cross milestone: blocks.toml's id-only cutout/cross flags (MaterialScalars.isCutout/
        // isCross, mirroring CategorySpec.cutout()/cross()) reclassify a harvested voxel's occlusion
        // shape and capture the real atlas UV rect celestial_shadow.fsh needs for a per-texel alpha
        // test, INSTEAD OF trusting VoxelShapeClassifier's vanilla getShape()-derived kind for these
        // two categories specifically. This runs BEFORE the shape.kind()-gated face-color loop below
        // so effectiveKind can override which branch that loop takes.
        //
        // CROSS: vanilla's real getShape() for a plant like short_grass returns a small, non-empty
        // selection box (used for the outline/hit-test reticle), which VoxelShapeClassifier classifies
        // PARTIAL -- that box is what the pre-cutout-milestone code marched a ray against, giving grass
        // a tiny, per-frame-jitter-unstable box occluder (the exact "warping shadow blocks" bug this
        // milestone fixes). A cross-tagged category overrides shapeKind to CROSS unconditionally when
        // FaceColorResolver finds real unculled cross-quad geometry, discarding VoxelShapeClassifier's
        // small-box classification for these blocks entirely (the CROSS kind exists in VoxelShapeKind
        // specifically for this -- see that enum's own doc comment, "assigned later, in per-face color
        // resolution, which already has to walk the block's real model quads").
        //
        // CUTOUT-only (leaves): shapeKind stays whatever VoxelShapeClassifier resolved (real leaves are
        // FULL, a genuine solid cube) -- only the UV rect + cutout flag are added, so a leaf voxel keeps
        // its normal six real face colors AND gains a per-texel alpha test on top.
        boolean cutoutTag = materialScalars.isCutout(categoryId);
        boolean crossTag = materialScalars.isCross(categoryId);
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
        // DIAGNOSTIC (2026-07-20): a block blocks.toml TAGGED cutout that nonetheless harvests as a solid
        // occluder is invisible from the outside -- the shadow shader just treats it as an opaque cube, so
        // every foliage-transmission setting looks identical and the canopy self-shadows with no clue why.
        // Logged once per distinct state (this runs per palette entry, i.e. once per state per section, so
        // the set keeps a busy chunk-load from spamming). Remove once the leaf path is confirmed.
        if ((cutoutTag || crossTag) && !cutout && CUTOUT_DROP_LOGGED.add(state.toString())) {
            FornaxMod.LOGGER.warn(
                    "[Fornax] {} is tagged cutout/cross in blocks.toml but harvested as a SOLID occluder "
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
                faceColors[dir.get3DDataValue()] = FaceColorResolver.resolve(state, dir);
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

        return new SectionPalette.Entry(effectiveKind, effectiveBoxes, faceColors, emissiveStrength,
                lightTransmissive, emissionColor, cutout, uvRect, extinction);
    }

    /** See the call site above -- extracted pure so the tagged/untagged/off-lamp matrix is directly
     * unit-testable without a bootstrapped BlockState. */
    static double effectiveEmission(double categoryStrength, int vanillaLightLevel) {
        double vanilla = vanillaLightLevel / 15.0;
        return categoryStrength > 0.0 ? categoryStrength * vanilla : vanilla;
    }
}

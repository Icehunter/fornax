package dev.icehunter.fornax.pack.graph;

/**
 * Fixed-capacity SSBO for the analytic direct-light candidate list (analytic-lights milestone, M1).
 * Rebuilt from scratch every frame by {@code light_list_reset.comp} + {@code light_list_build.comp}
 * (own scan, deliberately NOT shared with {@code light_inject.comp}'s emitter scan -- see that pass's
 * own header comment for why a duplicate, smaller-radius scan is the deliberate choice) -- no
 * incremental add/remove, so there is nothing to invalidate on chunk streaming or block edits.
 *
 * <p>Fixed size, not scale-dependent (unlike {@link VoxelWaterReflBuffer}, which is render-resolution
 * sized) -- mirrors {@link VoxelWaterReflBuffer}'s own allocate/free shape otherwise.
 *
 * <p>Layout: word 0 = atomic light count (clamped to {@link #MAX_LIGHTS} by the build shader's own
 * over-cap check); then {@link #WORDS_PER_LIGHT} words per light: posX/posY/posZ (float bits,
 * window-grid-local blocks, the SAME coordinate space celestial_shadow.fsh's own gridOrigin uses),
 * packedColor (10:10:10 unorm, same packCell() shape light_inject.comp already establishes),
 * packedRadiusCount (low 8 bits = radiusBlocks as uint, bits 8-15 = the aggregated cell's effective
 * source count sumE/maxE in 4.4 fixed point -- the consumer widens its near-field saturation by this
 * count so clustered emitters peak at surface radiance, not summed flux), summedEmission (float bits,
 * total flux of every emitting sub-voxel merged into this per-scan-cell aggregate). Word semantics
 * are owned/hand-mirrored by light_list_build.comp (producer) and direct_light_analytic.fsh
 * (consumer); this class only sizes the allocation.
 */
public final class AnalyticLightListBuffer {
    public static final String TARGET = "analyticLightList";
    public static final int MAX_LIGHTS = 256;
    public static final int WORDS_PER_LIGHT = 6;
    private static final int BYTES_PER_WORD = 4;
    public static final long BYTE_SIZE =
            (long) (1 + MAX_LIGHTS * WORDS_PER_LIGHT) * BYTES_PER_WORD; // 6148 bytes

    private AnalyticLightListBuffer() {}

    /** Fixed size, not scale-dependent -- allocate once. {@code TargetRegistry.ensureBufferSize}
     * zero-clears at allocation (MoltenVK garbage-VRAM law), same as every sibling buffer target. */
    public static void ensureAllocated(TargetRegistry registry) {
        registry.ensureBufferSize(TARGET, BYTE_SIZE);
    }

    /** Free the SSBO when ANALYTIC_LIGHTS is compile-disabled. */
    public static void free(TargetRegistry registry) {
        registry.releaseBuffer(TARGET);
    }
}

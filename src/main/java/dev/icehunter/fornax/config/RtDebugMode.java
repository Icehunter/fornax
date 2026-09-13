package dev.icehunter.fornax.config;

/**
 * Which coloring mode {@code metalfx.rt.MetalRtShadowPass}'s {@code rt_debug} dispatch uses when it
 * traces a primary ray per pixel against the loaded voxel window, shown through the {@code
 * METAL_RT_SCENE_DEBUG} entry in {@link GBufferDebugView}. {@link #OFF} skips the dispatch entirely
 * rather than running it in some default mode, so the feature costs nothing unless a mode is
 * picked. The pass still runs (keeping the acceleration structure current) whenever
 * either this is non-{@code OFF} or the scene debug view is selected, but the dispatch itself only
 * fires when a real mode is chosen.
 */
public enum RtDebugMode {
    /** The {@code rt_debug} dispatch never runs. */
    OFF,

    /** Green where the primary ray hits geometry, red where it misses. */
    HIT_MISS,

    /** Hit distance as a near-to-far heatmap; black on a miss. */
    DISTANCE,

    /** Placeholder: needs a per-instance primitive-data lookup this milestone does not wire up
     * yet (see {@code rt_debug.metal}'s own header comment). Kept in the enum so the setting and
     * UI are complete even though the shader currently paints a flat placeholder colour for it. */
    NORMAL,

    /** The hit instance id, hashed to a stable colour; black on a miss. */
    INSTANCE_ID,

    /** The hit primitive id, hashed to a stable colour; black on a miss. */
    PRIMITIVE_ID,

    /** The primary ray's own direction, remapped to a display colour; black on a miss. */
    RAY_DIRECTION;

    /**
     * The numeric mode {@code rt_debug.metal}'s {@code RtDebugConstants.mode} field expects.
     * Never called for {@link #OFF}: the caller skips the dispatch for that value rather than
     * asking it for a shader mode.
     */
    public int shaderMode() {
        return switch (this) {
            case OFF -> throw new IllegalStateException("RtDebugMode.OFF has no shader mode");
            case HIT_MISS -> 0;
            case DISTANCE -> 1;
            case NORMAL -> 2;
            case INSTANCE_ID -> 3;
            case PRIMITIVE_ID -> 4;
            case RAY_DIRECTION -> 5;
        };
    }
}

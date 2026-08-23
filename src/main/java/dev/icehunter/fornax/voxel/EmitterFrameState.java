package dev.icehunter.fornax.voxel;

/**
 * Per-frame holder for the player camera's ABSOLUTE world position -- the value
 * {@code UniformBufferManagerMixin}'s append delivers to {@code u_Globals} as {@code u_CameraAbs},
 * which resolve adds to its camera-relative reconstructed worldPos to map a pixel into the voxel
 * light volume's absolute-world cell space. The {@code ShadowFrameState} pattern exactly: committed
 * once per frame by {@code SodiumWorldRendererOrchestrationMixin.fornax$prepareOpaque} (which runs
 * before the frame's first {@code uniformBufferManager.update(...)} -- the same "Ordering
 * guarantee" that mixin already documents for ShadowFrameState.commit), read by the append for the
 * rest of the frame. Floats: sub-cell precision degrades only beyond ~1e6 blocks (documented v1
 * limitation). Zero before the first commit -- harmless, nothing samples the volume before a frame
 * has run.
 */
public final class EmitterFrameState {
    private static volatile float camX;
    private static volatile float camY;
    private static volatile float camZ;

    private EmitterFrameState() {
    }

    public static void commit(double x, double y, double z) {
        camX = (float) x;
        camY = (float) y;
        camZ = (float) z;
    }

    public static float camX() { return camX; }
    public static float camY() { return camY; }
    public static float camZ() { return camZ; }
}

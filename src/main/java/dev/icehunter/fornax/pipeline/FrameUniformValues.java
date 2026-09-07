package dev.icehunter.fornax.pipeline;

/** CPU mirror of supported scalar globals, published alongside their uniform writes. */
public final class FrameUniformValues {
    public static final FrameUniformValues CURRENT = new FrameUniformValues();
    // Two existing vec4 ABI fields; no additional GPU storage or uniform layout.
    private final int[] values = new int[8];
    private boolean skyReady;
    private boolean frameReady;

    public void beginFrame() { skyReady = frameReady = false; }

    public void skyState(float x, float y, float z, float w) {
        put(0, x, y, z, w);
        skyReady = true;
    }

    public void frameState(float x, float y, float z, float w) {
        put(4, x, y, z, w);
        frameReady = true;
    }

    private void put(int offset, float x, float y, float z, float w) {
        values[offset] = Float.floatToRawIntBits(x);
        values[offset + 1] = Float.floatToRawIntBits(y);
        values[offset + 2] = Float.floatToRawIntBits(z);
        values[offset + 3] = Float.floatToRawIntBits(w);
    }

    public boolean ready() { return skyReady && frameReady; }

    public int bits(String lane) {
        int index = index(lane);
        if (index < 0 || !ready()) throw new IllegalStateException("unpublished global lane: " + lane);
        return values[index];
    }

    public static boolean supports(String lane) { return index(lane) >= 0; }

    private static int index(String lane) {
        return switch (lane) {
            case "u_SkyState.x" -> 0;
            case "u_SkyState.y" -> 1;
            case "u_SkyState.z" -> 2;
            case "u_SkyState.w" -> 3;
            case "u_FrameState.x" -> 4;
            case "u_FrameState.y" -> 5;
            case "u_FrameState.z" -> 6;
            case "u_FrameState.w" -> 7;
            default -> -1;
        };
    }
}

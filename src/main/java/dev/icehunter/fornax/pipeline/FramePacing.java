package dev.icehunter.fornax.pipeline;

/**
 * Engine-wide frame-ring depth.
 *
 * <p>Every CPU-owned ring whose slots can remain referenced by submitted GPU work uses this
 * value. Keeping the compute command/fence ring and timestamp-query ring aligned avoids silently
 * baking two different assumptions about Blaze3D's maximum frame latency into unrelated systems.
 */
public final class FramePacing {
    public static final int FRAMES_IN_FLIGHT = 3;

    private FramePacing() {
    }
}

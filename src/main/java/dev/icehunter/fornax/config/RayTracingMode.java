package dev.icehunter.fornax.config;

/**
 * Whether the Metal ray tracing sun-shadow pass may run, read by {@code
 * dev.icehunter.fornax.metalfx.rt.MetalRtSupport#probe()} alongside the device's own reported
 * capability. macOS/Apple Silicon only; every other platform stays fully unaffected by this
 * setting regardless of its value, since the pass itself never runs outside the Metal bridge.
 */
public enum RayTracingMode {
    /** The Metal ray tracing pass never runs, even on hardware that supports it. */
    OFF,

    /**
     * The pass runs when the device reports ray tracing support on GPU family apple9 or later.
     * The safe default: apple9 is where Apple's own ray tracing guidance targets steady
     * performance, so older families that merely report the capability are left alone.
     */
    AUTO,

    /**
     * The pass runs whenever the device reports ray tracing support at all, regardless of GPU
     * family. An escape hatch for testing or for hardware the {@link #AUTO} family check excludes.
     */
    FORCE
}

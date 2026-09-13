package dev.icehunter.fornax.config;

import java.util.List;

/**
 * Engine ray tracing backend selection. The enum names and order preserve existing {@code
 * rayTracing} configuration files: OFF means None, AUTO means Automatic, and FORCE means Metal RT.
 * This selects an API, not a shadow feature; the active pack must also subscribe to RT work.
 */
public enum RayTracingMode {
    /** No ray tracing backend. Pack rendering keeps its normal fallback. */
    OFF,

    /**
     * Pick an implemented backend supported by the current device. Currently that is Metal RT;
     * other platforms have no RT backend and keep the pack's normal rendering.
     */
    AUTO,

    /**
     * Select Metal RT explicitly. It still requires device support and a pack subscription;
     * this legacy name never overrides a missing capability.
     */
    FORCE;

    public String backendLabel() {
        return switch (this) {
            case OFF -> "None";
            case AUTO -> "Automatic";
            case FORCE -> "Metal RT";
        };
    }

    /** Only implemented APIs are offered, and explicit Metal selection requires support. */
    public static List<RayTracingMode> availableBackends(boolean metalSupported) {
        return metalSupported ? List.of(AUTO, OFF, FORCE) : List.of(AUTO, OFF);
    }
}

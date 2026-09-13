package dev.icehunter.fornax.metalfx.rt;

import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.config.FornaxConfig;
import dev.icehunter.fornax.config.RayTracingMode;
import dev.icehunter.fornax.metalfx.MetalFxSupport;
import dev.icehunter.fornax.metalfx.objc.Objc;

/**
 * One-time Metal ray tracing hardware probe (Metal RT milestone 1). Answers whether the sun-shadow
 * pass may run: the device must report {@code supportsRaytracing}, and unless the setting is
 * {@link RayTracingMode#FORCE} it must also report GPU family Apple9 or later. Apple's own
 * {@code MTLGPUFamily.apple9} documentation lists A17, M3 and M4 as that family (M5 is the same
 * generation); Apple Tech Talk 111375 describes hardware ray tracing starting there.
 *
 * <p>The device query runs once and is cached forever, same as {@link MetalFxSupport} (a machine
 * fact that never changes within a run). The {@link RayTracingMode} setting is read fresh on every
 * {@link #isAvailable()} call through {@link #allowedBy}, so flipping the setting takes effect
 * immediately without re-probing the device.
 *
 * <p>The device queried here is deliberately not tied to {@link MetalFxSupport}'s own: this probe
 * reuses {@link MetalFxSupport#metalDevice()} when it is available, but falls back to its own
 * {@code MTLCreateSystemDefaultDevice()} call when MetalFX has none (a different Fornax-owned
 * feature failing its own probe must not also disable ray tracing). RT and MetalFX may therefore
 * hold two different {@code MTLDevice} objects for the same physical GPU; that is fine, since
 * neither shares GPU-side state with the other and both are compute/copy work against one queue
 * each.
 */
public final class MetalRtSupport {
    /** MTLGPUFamilyApple9 (Metal's own enum value): hardware ray tracing tier, A17/M3/M4/M5. */
    private static final long MTL_GPU_FAMILY_APPLE9 = 1009;

    // null until probe() has run once; the probed fields below are only meaningful once this is
    // non-null. Set last in runProbe() so a racing reader never observes a half-written probe.
    private static volatile Boolean probedRaytracing;
    private static volatile boolean probedApple9;

    // Set when the device-level query itself could not be answered (bridge not loaded, no device,
    // or an unexpected native failure); null otherwise. Distinct from a setting-driven refusal.
    private static volatile String deviceFailureReason;

    // This class's own retained MTLDevice, created at most once, used only when MetalFxSupport
    // reports none. 0 until created (or if creation was never needed, or itself failed).
    private static volatile long fallbackDevice;

    // Test-only: counts how many times runProbe() ran the device query (as opposed to
    // probe() returning early because it already ran). A passing idempotence test needs this,
    // since probedRaytracing alone can't distinguish "probed once" from "probed twice".
    private static volatile int probeRunCount;

    private MetalRtSupport() {}

    /** Runs the device query once; safe to call every frame, cheap after the first call. */
    public static void probe() {
        if (probedRaytracing != null) {
            return;
        }
        synchronized (MetalRtSupport.class) {
            if (probedRaytracing == null) {
                runProbe();
            }
        }
    }

    private static void runProbe() {
        probeRunCount++;
        boolean raytracing = false;
        boolean apple9 = false;
        String failure = null;
        try {
            // PLATFORM-GUARD ORDER MATTERS, same reasoning as MetalFxSupport.probe(): check the
            // plain platform flag before asking whether the bridge loaded, so an off-platform
            // probe never depends on Objc having anything more than that one field to report.
            if (!Objc.PLATFORM_SUPPORTED) {
                failure = "platform not macOS/aarch64";
            } else if (!Objc.isLoaded()) {
                failure = "Objc bridge not loaded: " + Objc.loadFailure();
            } else {
                long device = resolveDevice();
                if (device == 0) {
                    failure = "no Metal device";
                } else {
                    raytracing = Objc.msgSendBool(device, Objc.selector("supportsRaytracing"));
                    apple9 = Objc.msgSendBool(device,
                            Objc.selector("supportsFamily:"), MTL_GPU_FAMILY_APPLE9);
                }
            }
        } catch (RuntimeException e) {
            // A bridge-level failure degrades to "unavailable", never a crash: this probe runs at
            // most once per process and must never take down mod init on unexpected hardware.
            failure = "probe failed: " + e;
        }
        probedApple9 = apple9;
        deviceFailureReason = failure;
        probedRaytracing = raytracing;

        RayTracingMode mode = FornaxConfig.get().rayTracing;
        boolean available = allowedBy(raytracing, apple9, mode);
        FornaxMod.LOGGER.info(
                "[Fornax] Metal RT: supportsRaytracing={}, apple9={}, mode={}, available={}",
                raytracing, apple9, mode, available);
    }

    /**
     * {@link MetalFxSupport#metalDevice()} when it has one, else this class's own retained device
     * (created once). See the class doc for why RT does not require MetalFX to be available.
     */
    private static long resolveDevice() {
        long device = MetalFxSupport.metalDevice();
        if (device != 0) {
            return device;
        }
        if (fallbackDevice == 0) {
            fallbackDevice = Objc.createSystemDefaultMetalDevice();
        }
        return fallbackDevice;
    }

    /** Whether the pass may run: the probed device facts plus the live setting. */
    public static boolean isAvailable() {
        probe();
        return allowedBy(probedRaytracing, probedApple9, FornaxConfig.get().rayTracing);
    }

    /** The device's own Apple9-or-later answer, independent of the current setting. */
    public static boolean hardwareRayTracing() {
        probe();
        return probedApple9;
    }

    /** Why {@link #isAvailable()} is false, or null when it is true. */
    public static String unavailableReason() {
        probe();
        RayTracingMode mode = FornaxConfig.get().rayTracing;
        if (allowedBy(probedRaytracing, probedApple9, mode)) {
            return null;
        }
        // The setting is checked before any device fact: an explicit Off is the reason on its own
        // terms, even on hardware whose device probe also failed.
        if (mode == RayTracingMode.OFF) {
            return "ray tracing mode is Off";
        }
        if (deviceFailureReason != null) {
            return deviceFailureReason;
        }
        if (!probedRaytracing) {
            return "device does not report supportsRaytracing";
        }
        return "device is below GPU family Apple9; Force overrides this check";
    }

    /** The device's raw {@code supportsRaytracing} answer, independent of the current setting. */
    static boolean deviceSupportsRaytracing() {
        probe();
        return probedRaytracing;
    }

    /** Test-only: how many times the device query ran (see {@link #probeRunCount}). */
    static int probeRunCount() {
        return probeRunCount;
    }

    /**
     * Pure decision the setting makes from the two device facts: {@link RayTracingMode#OFF} never
     * runs, {@link RayTracingMode#AUTO} needs both {@code supportsRt} and {@code apple9},
     * {@link RayTracingMode#FORCE} needs only {@code supportsRt}.
     */
    public static boolean allowedBy(boolean supportsRt, boolean apple9, RayTracingMode mode) {
        return switch (mode) {
            case OFF -> false;
            case AUTO -> supportsRt && apple9;
            case FORCE -> supportsRt;
        };
    }
}

package dev.icehunter.fornax.metalfx;

import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.metalfx.objc.Objc;

import java.util.Locale;

/**
 * One-time MetalFX hardware/OS availability probe (MetalFX spike M0). Answers "can this machine
 * run an {@code MTLFXTemporalScaler} at all" — macOS + Apple silicon + a Metal device that
 * {@code MTLFXTemporalScalerDescriptor.supportsDevice:} accepts. Deliberately does NOT check the
 * Vulkan-vs-GL backend here: that is a per-session render-path fact the upscale seam checks
 * separately at use time (M2), while this probe is a machine fact that never changes within a run
 * — same split {@code VulkanComputeBackend.tryCreate()} vs {@code FX_COMPUTE} already draws
 * between "backend exists" and "feature usable".
 *
 * <p>PLATFORM-GUARD ORDER MATTERS: the {@code os.name}/{@code os.arch} check here runs BEFORE any
 * reference to {@link Objc}, so that class never even initializes on Windows/Linux — a stray
 * static-init link attempt off-platform is the exact cross-platform hazard the plan's risk list
 * names. {@link Objc} carries its own identical guard as defense in depth.
 *
 * <p>The probe result is cached forever (a machine fact); {@link #logProbe()} emits the one-time
 * verdict line — the M0 acceptance signal — mirroring {@code VulkanComputeBackend}'s own log-once
 * topology-line pattern.
 */
public final class MetalFxSupport {
    private static volatile Boolean available;
    private static String detail = "not probed";
    private static volatile Boolean frameInterpolationAvailable;

    // The retained MTLDevice from the successful probe (0 when unavailable). Later milestones
    // (M1/M2) reuse this device rather than creating a second one — one fornax-owned MTLDevice
    // per process, alongside (not extracted from) MoltenVK's own.
    private static long metalDevice;

    private MetalFxSupport() {}

    /** Cached machine-level availability: macOS + aarch64 + Metal device + MetalFX support. */
    public static boolean isAvailable() {
        Boolean cached = available;
        if (cached != null) {
            return cached;
        }
        synchronized (MetalFxSupport.class) {
            if (available == null) {
                available = probe();
            }
            return available;
        }
    }

    /** The probed fornax-owned MTLDevice (retained, process-lifetime); 0 when unavailable. */
    public static long metalDevice() {
        isAvailable();
        return metalDevice;
    }

    /** Emits the one-time probe verdict at INFO — call once at client start (M0 acceptance). */
    public static void logProbe() {
        boolean ok = isAvailable();
        FornaxMod.LOGGER.info("[Fornax] MetalFX probe: {} ({})", ok ? "AVAILABLE" : "unavailable",
                detail);
        // Frame-interpolation capability probe + selector-enumeration diagnostic (see
        // isFrameInterpolationAvailable()) piggybacks on this same one-time CLIENT_STARTED call so
        // its log lines emit exactly once at startup, right alongside the base MetalFX verdict.
        FornaxMod.LOGGER.info("[Fornax] frame interpolation available: {}",
                isFrameInterpolationAvailable());
    }

    /**
     * Cached: true iff the base {@link #isAvailable()} probe passed AND
     * {@code MTLFXFrameInterpolatorDescriptor} exists AND {@code supportsDevice:} returns YES.
     */
    public static boolean isFrameInterpolationAvailable() {
        Boolean cached = frameInterpolationAvailable;
        if (cached != null) {
            return cached;
        }
        synchronized (MetalFxSupport.class) {
            if (frameInterpolationAvailable == null) {
                frameInterpolationAvailable = probeFrameInterpolation();
            }
            return frameInterpolationAvailable;
        }
    }

    private static boolean probeFrameInterpolation() {
        try {
            if (!isAvailable()) {
                return false;
            }
            long descriptorClass = Objc.getClass("MTLFXFrameInterpolatorDescriptor");
            if (descriptorClass == 0) {
                FornaxMod.LOGGER.info(
                        "[Fornax] MTLFXFrameInterpolatorDescriptor absent -- frame generation unavailable (macOS < 26?)");
                return false;
            }
            if (!Objc.msgSendBool(descriptorClass, Objc.selector("supportsDevice:"), metalDevice())) {
                FornaxMod.LOGGER.info("[Fornax] MTLFXFrameInterpolator unsupported on this GPU");
                return false;
            }
            logFrameInterpolatorSelectors(descriptorClass);
            return true;
        } catch (RuntimeException e) {
            FornaxMod.LOGGER.warn("[Fornax] frame interpolation probe failed: {}", e.toString());
            return false;
        }
    }

    /** Dev diagnostic: enumerate which candidate selectors the descriptor + a probe instance answer. */
    private static void logFrameInterpolatorSelectors(long descriptorClass) {
        String[] descriptorSelectors = {
                "setColorTextureFormat:", "setOutputTextureFormat:", "setDepthTextureFormat:",
                "setMotionTextureFormat:", "setInputWidth:", "setInputHeight:",
                "setOutputWidth:", "setOutputHeight:", "setUITextureFormat:",
        };
        String[] instanceSelectors = {
                "setColorTexture:", "setPrevColorTexture:", "setDepthTexture:", "setMotionTexture:",
                "setOutputTexture:", "setUITexture:", "setDeltaTime:", "setMotionVectorScaleX:",
                "setMotionVectorScaleY:", "setDepthReversed:", "setReset:", "setNearPlane:",
                "setFarPlane:", "setFieldOfView:", "setAspectRatio:", "setJitterOffsetX:",
                "setJitterOffsetY:", "encodeToCommandBuffer:",
                // Diagnostic-only probe (not wired to any input this round): the engine already
                // maintains a 1x1 R32_FLOAT auto-exposure target with history (pack graph
                // 'exposure', see GBufferDebugView's own exposure-view doc). If
                // MTLFXFrameInterpolator responds to either of these, it may be able to consume that
                // value directly (some MetalFX APIs take a texture, others a scalar) -- exporting it
                // through the same Vulkan<->Metal interop this class already uses for color/depth/
                // motion is a separate, larger change gated on THIS probe's verdict, not attempted
                // here.
                "setExposureTexture:", "setExposure:",
        };
        long pool = Objc.autoreleasePoolPush();
        try {
            long descriptor = Objc.msgSendId(
                    Objc.msgSendId(descriptorClass, Objc.selector("alloc")), Objc.selector("init"));
            StringBuilder sb = new StringBuilder("[Fornax] framegen descriptor selectors:");
            long responds = Objc.selector("respondsToSelector:");
            for (String s : descriptorSelectors) {
                sb.append(' ').append(s).append('=')
                  .append(descriptor != 0 && Objc.msgSendBool(descriptor, responds, Objc.selector(s)));
            }
            FornaxMod.LOGGER.info(sb.toString());
            // Instance selectors: MTLFXFrameInterpolator is an ObjC PROTOCOL, not a class --
            // objc_getClass("MTLFXFrameInterpolator") returns nil and instancesRespondToSelector:
            // can never work here. Construct a minimally-configured descriptor, ask the factory for
            // a LIVE instance, and probe respondsToSelector: on that instance instead.
            StringBuilder sb2 = new StringBuilder("[Fornax] framegen instance selectors:");
            if (descriptor != 0) {
                Objc.msgSendVoidLong(descriptor, Objc.selector("setColorTextureFormat:"),
                        MetalFxScaler.PIXEL_FORMAT_RGBA8_UNORM);
                Objc.msgSendVoidLong(descriptor, Objc.selector("setOutputTextureFormat:"),
                        MetalFxScaler.PIXEL_FORMAT_RGBA8_UNORM);
                Objc.msgSendVoidLong(descriptor, Objc.selector("setDepthTextureFormat:"),
                        MetalFxScaler.PIXEL_FORMAT_DEPTH32_FLOAT);
                Objc.msgSendVoidLong(descriptor, Objc.selector("setMotionTextureFormat:"),
                        MetalFxScaler.PIXEL_FORMAT_RG16_FLOAT);
                Objc.msgSendVoidLong(descriptor, Objc.selector("setInputWidth:"), 256);
                Objc.msgSendVoidLong(descriptor, Objc.selector("setInputHeight:"), 256);
                Objc.msgSendVoidLong(descriptor, Objc.selector("setOutputWidth:"), 256);
                Objc.msgSendVoidLong(descriptor, Objc.selector("setOutputHeight:"), 256);
            }
            long interpolatorInstance = descriptor == 0 ? 0 : Objc.msgSendId(descriptor,
                    Objc.selector("newFrameInterpolatorWithDevice:"), metalDevice());
            if (interpolatorInstance == 0) {
                sb2.append(" newFrameInterpolatorWithDevice: returned nil -- unable to probe");
            } else {
                try {
                    long instResponds = Objc.selector("respondsToSelector:");
                    for (String s : instanceSelectors) {
                        sb2.append(' ').append(s).append('=').append(
                                Objc.msgSendBool(interpolatorInstance, instResponds, Objc.selector(s)));
                    }
                } finally {
                    Objc.msgSendVoid(interpolatorInstance, Objc.selector("release"));
                }
            }
            FornaxMod.LOGGER.info(sb2.toString());
            if (descriptor != 0) {
                Objc.msgSendVoid(descriptor, Objc.selector("release"));
            }
        } finally {
            Objc.autoreleasePoolPop(pool);
        }
    }

    private static boolean probe() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!os.contains("mac") || !arch.equals("aarch64")) {
            detail = "platform " + os + "/" + arch + " -- macOS aarch64 only";
            return false;
        }
        if (!Objc.isLoaded()) {
            detail = "FFM bridge failed to link: " + Objc.loadFailure();
            return false;
        }
        try {
            long device = Objc.createSystemDefaultMetalDevice();
            if (device == 0) {
                detail = "MTLCreateSystemDefaultDevice returned nil";
                return false;
            }
            String deviceName = Objc.nsStringToJava(
                    Objc.msgSendId(device, Objc.selector("name")));
            long descriptorClass = Objc.getClass("MTLFXTemporalScalerDescriptor");
            if (descriptorClass == 0) {
                detail = "MTLFXTemporalScalerDescriptor class absent (macOS too old) on "
                        + deviceName;
                return false;
            }
            boolean supported = Objc.msgSendBool(descriptorClass,
                    Objc.selector("supportsDevice:"), device);
            if (!supported) {
                detail = "MTLFXTemporalScalerDescriptor.supportsDevice: NO on " + deviceName;
                return false;
            }
            metalDevice = device;
            detail = "temporal scaler supported on " + deviceName;
            return true;
        } catch (RuntimeException e) {
            // A bridge-level failure is a clean "unavailable", never a crash: this probe runs on
            // every macOS launch and must degrade to TAAU silently on anything unexpected.
            detail = "probe failed: " + e;
            return false;
        }
    }
}

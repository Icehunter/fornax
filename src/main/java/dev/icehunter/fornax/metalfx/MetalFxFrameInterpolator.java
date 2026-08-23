package dev.icehunter.fornax.metalfx;

import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.metalfx.objc.Objc;

/**
 * Thin wrapper around one MTLFXFrameInterpolator. Input dims describe the RENDER-resolution
 * depth/motion textures (mirroring {@code MTLFXTemporalScaler}'s own input/output split); output
 * dims describe the presented (native) color/output textures -- the two can differ, and normally
 * do under any AA method that renders below native. All capability checks are
 * respondsToSelector-based; {@link #create} returns null on any missing capability and the caller
 * falls back to no-framegen.
 */
final class MetalFxFrameInterpolator {

    private final long interpolator;
    private final boolean hasPrevColor;
    private final boolean hasNearPlane;
    private final boolean hasFarPlane;
    private final boolean hasFieldOfView;
    private final boolean hasAspectRatio;
    private final boolean hasJitterOffset;

    private MetalFxFrameInterpolator(long interpolator, boolean hasPrevColor, boolean hasNearPlane,
            boolean hasFarPlane, boolean hasFieldOfView, boolean hasAspectRatio,
            boolean hasJitterOffset) {
        this.interpolator = interpolator;
        this.hasPrevColor = hasPrevColor;
        this.hasNearPlane = hasNearPlane;
        this.hasFarPlane = hasFarPlane;
        this.hasFieldOfView = hasFieldOfView;
        this.hasAspectRatio = hasAspectRatio;
        this.hasJitterOffset = hasJitterOffset;
    }

    static MetalFxFrameInterpolator create(long mtlDevice, int inputWidth, int inputHeight,
            int outputWidth, int outputHeight,
            long colorFormat, long depthFormat, long motionFormat, long outputFormat) {
        long pool = Objc.autoreleasePoolPush();
        try {
            long descriptorClass = Objc.getClass("MTLFXFrameInterpolatorDescriptor");
            if (descriptorClass == 0) {
                return null;
            }
            long descriptor = Objc.msgSendId(
                    Objc.msgSendId(descriptorClass, Objc.selector("alloc")), Objc.selector("init"));
            if (descriptor == 0) {
                return null;
            }
            try {
                String[] required = {
                        "setColorTextureFormat:", "setDepthTextureFormat:",
                        "setMotionTextureFormat:", "setOutputTextureFormat:",
                        "setInputWidth:", "setInputHeight:", "setOutputWidth:", "setOutputHeight:",
                        "newFrameInterpolatorWithDevice:",
                };
                long responds = Objc.selector("respondsToSelector:");
                for (String sel : required) {
                    if (!Objc.msgSendBool(descriptor, responds, Objc.selector(sel))) {
                        FornaxMod.LOGGER.warn("[Fornax] framegen descriptor missing {} -- unavailable", sel);
                        return null;
                    }
                }
                Objc.msgSendVoidLong(descriptor, Objc.selector("setColorTextureFormat:"), colorFormat);
                Objc.msgSendVoidLong(descriptor, Objc.selector("setDepthTextureFormat:"), depthFormat);
                Objc.msgSendVoidLong(descriptor, Objc.selector("setMotionTextureFormat:"), motionFormat);
                Objc.msgSendVoidLong(descriptor, Objc.selector("setOutputTextureFormat:"), outputFormat);
                Objc.msgSendVoidLong(descriptor, Objc.selector("setInputWidth:"), inputWidth);
                Objc.msgSendVoidLong(descriptor, Objc.selector("setInputHeight:"), inputHeight);
                Objc.msgSendVoidLong(descriptor, Objc.selector("setOutputWidth:"), outputWidth);
                Objc.msgSendVoidLong(descriptor, Objc.selector("setOutputHeight:"), outputHeight);

                long interpolator = Objc.msgSendId(descriptor,
                        Objc.selector("newFrameInterpolatorWithDevice:"), mtlDevice);
                if (interpolator == 0) {
                    FornaxMod.LOGGER.warn("[Fornax] newFrameInterpolatorWithDevice: returned nil");
                    return null;
                }
                long instResponds = Objc.selector("respondsToSelector:");
                String[] requiredInstance = {
                        "setColorTexture:", "setDepthTexture:", "setMotionTexture:",
                        "setOutputTexture:", "setDeltaTime:", "encodeToCommandBuffer:",
                };
                for (String sel : requiredInstance) {
                    if (!Objc.msgSendBool(interpolator, instResponds, Objc.selector(sel))) {
                        FornaxMod.LOGGER.warn("[Fornax] framegen interpolator missing {} -- unavailable", sel);
                        Objc.msgSendVoid(interpolator, Objc.selector("release"));
                        return null;
                    }
                }
                boolean hasPrevColor = Objc.msgSendBool(interpolator, instResponds,
                        Objc.selector("setPrevColorTexture:"));
                // Camera-linearization setters: checked once here (like hasPrevColor above) rather
                // than every encode() call, since capability never changes for a live instance.
                boolean hasNearPlane = Objc.msgSendBool(interpolator, instResponds,
                        Objc.selector("setNearPlane:"));
                boolean hasFarPlane = Objc.msgSendBool(interpolator, instResponds,
                        Objc.selector("setFarPlane:"));
                boolean hasFieldOfView = Objc.msgSendBool(interpolator, instResponds,
                        Objc.selector("setFieldOfView:"));
                boolean hasAspectRatio = Objc.msgSendBool(interpolator, instResponds,
                        Objc.selector("setAspectRatio:"));
                // Jitter inputs: the depth (and to a lesser extent color) the interpolator receives
                // is the RAW jittered G-buffer input -- without telling it the per-frame jitter
                // offset (same convention MetalFxScaler already feeds MTLFXTemporalScaler), it
                // reprojects/aligns as if every frame's samples landed at pixel centers, producing a
                // uniform diagonal dither/stipple on generated frames. Gated like hasPrevColor above:
                // both X/Y setters must respond, checked once per live instance rather than every
                // encode() call.
                boolean hasJitterOffsetX = Objc.msgSendBool(interpolator, instResponds,
                        Objc.selector("setJitterOffsetX:"));
                boolean hasJitterOffsetY = Objc.msgSendBool(interpolator, instResponds,
                        Objc.selector("setJitterOffsetY:"));
                boolean hasJitterOffset = hasJitterOffsetX && hasJitterOffsetY;
                if (!hasJitterOffset) {
                    FornaxMod.LOGGER.info("[Fornax] interpolator has no jitter inputs");
                }
                return new MetalFxFrameInterpolator(interpolator, hasPrevColor,
                        hasNearPlane, hasFarPlane, hasFieldOfView, hasAspectRatio, hasJitterOffset);
            } finally {
                Objc.msgSendVoid(descriptor, Objc.selector("release"));
            }
        } finally {
            Objc.autoreleasePoolPop(pool);
        }
    }

    boolean wantsPrevColor() {
        return hasPrevColor;
    }

    /**
     * {@code nearPlane}/{@code farPlane}/{@code fovDegrees}/{@code aspectRatio} feed
     * {@code MTLFXFrameInterpolator}'s own camera-linearization setters ({@code setNearPlane:} /
     * {@code setFarPlane:} / {@code setFieldOfView:} / {@code setAspectRatio:}), all confirmed present
     * on this build's interpolator by a live respondsToSelector probe and guarded here exactly like
     * {@code hasPrevColor} above. Without them the interpolator has no way to turn our reversed-Z
     * depth back into linear view-space depth, degrading reprojection on generated frames.
     *
     * <p>{@code fovDegrees} is the VERTICAL field of view in DEGREES -- confirmed against Apple's own
     * {@code MTLFXFrameInterpolatorBase.fieldOfView} doc comment ("The vertical field of view angle,
     * in degrees, of the camera that renders the scene into the color buffer"), not the radians most
     * of the rest of this Metal-facing codebase otherwise uses. This is also exactly the unit
     * {@code net.minecraft.client.Camera#getFov()} already returns (vanilla stores/lerps fov in
     * degrees and only converts to radians at {@code Projection.getMatrix}'s JOML call), so the caller
     * passes it straight through with no conversion. {@code nearPlane}/{@code farPlane} are the same
     * world-space (block) units the engine's own reversed-Z projection uses -- {@code
     * Camera.PROJECTION_Z_NEAR} (0.05f) and {@code Camera}'s per-frame {@code depthFar}
     * (render-distance/cloud-range derived, not infinite), respectively.
     *
     * <p>{@code jitterX}/{@code jitterY} are the SAME per-frame jitter (in input-texture pixels,
     * MetalFX's own convention) {@link MetalFxScaler#encode} already feeds {@code
     * MTLFXTemporalScaler} for this frame's upscale -- the depth/color this interpolator receives are
     * that same jittered render-res G-buffer, so without telling it the offset it treats every sample
     * as landing at its pixel center, producing a uniform diagonal dither/stipple on generated
     * frames. Accepted-and-ignored when {@code hasJitterOffset} is false (older MetalFX runtime).
     */
    void encode(long commandBuffer, long prevColorTexture, long colorTexture, long depthTexture,
            long motionTexture, long outputTexture, float jitterX, float jitterY,
            float deltaTimeSeconds, float motionScaleX, float motionScaleY, boolean reset,
            boolean depthReversed, float nearPlane, float farPlane, float fovDegrees,
            float aspectRatio) {
        if (hasPrevColor) {
            Objc.msgSendVoid(interpolator, Objc.selector("setPrevColorTexture:"), prevColorTexture);
        }
        Objc.msgSendVoid(interpolator, Objc.selector("setColorTexture:"), colorTexture);
        Objc.msgSendVoid(interpolator, Objc.selector("setDepthTexture:"), depthTexture);
        Objc.msgSendVoid(interpolator, Objc.selector("setMotionTexture:"), motionTexture);
        Objc.msgSendVoid(interpolator, Objc.selector("setOutputTexture:"), outputTexture);
        if (hasJitterOffset) {
            Objc.msgSendVoidFloat(interpolator, Objc.selector("setJitterOffsetX:"), jitterX);
            Objc.msgSendVoidFloat(interpolator, Objc.selector("setJitterOffsetY:"), jitterY);
        }
        Objc.msgSendVoidFloat(interpolator, Objc.selector("setDeltaTime:"), deltaTimeSeconds);
        long responds = Objc.selector("respondsToSelector:");
        if (Objc.msgSendBool(interpolator, responds, Objc.selector("setMotionVectorScaleX:"))) {
            Objc.msgSendVoidFloat(interpolator, Objc.selector("setMotionVectorScaleX:"), motionScaleX);
            Objc.msgSendVoidFloat(interpolator, Objc.selector("setMotionVectorScaleY:"), motionScaleY);
        }
        if (Objc.msgSendBool(interpolator, responds, Objc.selector("setReset:"))) {
            Objc.msgSendVoidBool(interpolator, Objc.selector("setReset:"), reset);
        }
        if (Objc.msgSendBool(interpolator, responds, Objc.selector("setDepthReversed:"))) {
            Objc.msgSendVoidBool(interpolator, Objc.selector("setDepthReversed:"), depthReversed);
        }
        if (hasNearPlane) {
            Objc.msgSendVoidFloat(interpolator, Objc.selector("setNearPlane:"), nearPlane);
        }
        if (hasFarPlane) {
            Objc.msgSendVoidFloat(interpolator, Objc.selector("setFarPlane:"), farPlane);
        }
        if (hasFieldOfView) {
            Objc.msgSendVoidFloat(interpolator, Objc.selector("setFieldOfView:"), fovDegrees);
        }
        if (hasAspectRatio) {
            Objc.msgSendVoidFloat(interpolator, Objc.selector("setAspectRatio:"), aspectRatio);
        }
        Objc.msgSendVoid(interpolator, Objc.selector("encodeToCommandBuffer:"), commandBuffer);
    }

    void release() {
        Objc.msgSendVoid(interpolator, Objc.selector("release"));
    }
}

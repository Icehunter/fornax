package dev.icehunter.fornax.metalfx;

import dev.icehunter.fornax.metalfx.objc.Objc;

/**
 * Thin ObjC wrapper around one {@code MTLFXTemporalScaler} instance (MetalFX spike M2). Created
 * from an {@code MTLFXTemporalScalerDescriptor} configured with fixed input/output sizes and pixel
 * formats -- MetalFX validates those at creation, so a size or format change means dropping this
 * instance and creating a new one (the caller tracks that and sets {@link #encode}'s {@code reset}
 * flag for the first frame after recreation, which also clears the scaler's INTERNAL temporal
 * history -- fornax keeps no history of its own for this path).
 *
 * <p>MTLPixelFormat constants used by the callers (hand-mirrored from Metal headers; values are
 * ABI-stable): r8Unorm=10, rgba8Unorm=70, rg16Float=65, depth32Float=252.
 */
final class MetalFxScaler {
    static final long PIXEL_FORMAT_R8_UNORM = 10;
    static final long PIXEL_FORMAT_RGBA8_UNORM = 70;
    static final long PIXEL_FORMAT_RG16_FLOAT = 65;
    static final long PIXEL_FORMAT_DEPTH32_FLOAT = 252;

    private final long scaler; // retained MTLFXTemporalScaler

    private MetalFxScaler(long scaler) {
        this.scaler = scaler;
    }

    /**
     * Builds the descriptor, creates the scaler, releases the descriptor. Returns null if MetalFX
     * refuses the configuration (caller treats that as "fall back to TAAU").
     */
    static MetalFxScaler create(long mtlDevice, int inputWidth, int inputHeight,
            int outputWidth, int outputHeight,
            long colorFormat, long depthFormat, long motionFormat, long outputFormat) {
        long pool = Objc.autoreleasePoolPush();
        try {
            long descriptorClass = Objc.getClass("MTLFXTemporalScalerDescriptor");
            if (descriptorClass == 0) {
                return null;
            }
            long descriptor = Objc.msgSendId(
                    Objc.msgSendId(descriptorClass, Objc.selector("alloc")), Objc.selector("init"));
            if (descriptor == 0) {
                return null;
            }
            try {
                Objc.msgSendVoidLong(descriptor, Objc.selector("setColorTextureFormat:"), colorFormat);
                Objc.msgSendVoidLong(descriptor, Objc.selector("setDepthTextureFormat:"), depthFormat);
                Objc.msgSendVoidLong(descriptor, Objc.selector("setMotionTextureFormat:"), motionFormat);
                Objc.msgSendVoidLong(descriptor, Objc.selector("setOutputTextureFormat:"), outputFormat);
                Objc.msgSendVoidLong(descriptor, Objc.selector("setInputWidth:"), inputWidth);
                Objc.msgSendVoidLong(descriptor, Objc.selector("setInputHeight:"), inputHeight);
                Objc.msgSendVoidLong(descriptor, Objc.selector("setOutputWidth:"), outputWidth);
                Objc.msgSendVoidLong(descriptor, Objc.selector("setOutputHeight:"), outputHeight);
                long respondsToSelector = Objc.selector("respondsToSelector:");
                long reactiveEnabledSetter = Objc.selector("setReactiveMaskTextureEnabled:");
                long reactiveFormatSetter = Objc.selector("setReactiveMaskTextureFormat:");
                if (!Objc.msgSendBool(descriptor, respondsToSelector, reactiveEnabledSetter)
                        || !Objc.msgSendBool(descriptor, respondsToSelector, reactiveFormatSetter)) {
                    return null;
                }
                Objc.msgSendVoidBool(descriptor, reactiveEnabledSetter, true);
                Objc.msgSendVoidLong(descriptor, reactiveFormatSetter, PIXEL_FORMAT_R8_UNORM);
                long scaler = Objc.msgSendId(descriptor,
                        Objc.selector("newTemporalScalerWithDevice:"), mtlDevice);
                return scaler == 0 ? null : new MetalFxScaler(scaler);
            } finally {
                Objc.msgSendVoid(descriptor, Objc.selector("release"));
            }
        } finally {
            Objc.autoreleasePoolPop(pool);
        }
    }

    /**
     * Configures this frame's textures/parameters and encodes the upscale into {@code
     * commandBuffer} (an MTLCommandBuffer the caller commits and waits). Jitter is in PIXELS of the
     * INPUT texture; motionVectorScale converts sampled motion-texture values into input-pixel
     * units (fornax's gMotion stores currentUV - previousUV, so the base scale is the input size,
     * with signs pinned empirically via the upscale pass's system-property knobs).
     */
    void encode(long commandBuffer, long colorTexture, long depthTexture, long motionTexture,
            long reactiveMaskTexture,
            long outputTexture, float jitterX, float jitterY, float motionScaleX,
            float motionScaleY, boolean reset, boolean depthReversed) {
        Objc.msgSendVoid(scaler, Objc.selector("setColorTexture:"), colorTexture);
        Objc.msgSendVoid(scaler, Objc.selector("setDepthTexture:"), depthTexture);
        Objc.msgSendVoid(scaler, Objc.selector("setMotionTexture:"), motionTexture);
        Objc.msgSendVoid(scaler, Objc.selector("setReactiveMaskTexture:"), reactiveMaskTexture);
        Objc.msgSendVoid(scaler, Objc.selector("setOutputTexture:"), outputTexture);
        Objc.msgSendVoidFloat(scaler, Objc.selector("setJitterOffsetX:"), jitterX);
        Objc.msgSendVoidFloat(scaler, Objc.selector("setJitterOffsetY:"), jitterY);
        Objc.msgSendVoidFloat(scaler, Objc.selector("setMotionVectorScaleX:"), motionScaleX);
        Objc.msgSendVoidFloat(scaler, Objc.selector("setMotionVectorScaleY:"), motionScaleY);
        Objc.msgSendVoidBool(scaler, Objc.selector("setReset:"), reset);
        Objc.msgSendVoidBool(scaler, Objc.selector("setDepthReversed:"), depthReversed);
        Objc.msgSendVoid(scaler, Objc.selector("encodeToCommandBuffer:"), commandBuffer);
    }

    /** Releases the retained scaler. */
    void release() {
        Objc.msgSendVoid(scaler, Objc.selector("release"));
    }
}

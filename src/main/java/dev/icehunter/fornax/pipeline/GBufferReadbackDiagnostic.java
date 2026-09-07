package dev.icehunter.fornax.pipeline;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import dev.icehunter.fornax.FornaxMod;

import java.nio.ByteOrder;

/**
 * Reads the middle 2x2 pixels of the AO, albedo and normal G-buffer attachments straight from
 * VRAM to CPU and logs their raw values. Periodic reads need
 * {@code -Dfornax.debug.gbufferReadback=true}; they run on the first frame after that flag is set
 * and every {@link #INTERVAL_FRAMES} frames after. Turning on timing stats does not turn on these
 * reads, so measuring performance never causes this diagnostic's render-thread stalls.
 *
 * <p>{@link #requestDump()} is a separate on-demand path, wired to F10 in
 * {@code dev.icehunter.fornax.debug.FornaxDebugKeys}. It dumps all five G-buffer attachments once,
 * on the next {@link #maybeLog} call, even when periodic reads are off.
 *
 * <p>The read itself is a {@code USAGE_MAP_READ}{@code |}{@code USAGE_COPY_DST} buffer, a region
 * copy through {@link CommandEncoder#copyTextureToBuffer}, and a callback that maps, reads and
 * closes the buffer. That matches how {@code net.minecraft.client.Screenshot#takeScreenshot} reads
 * a texture back (checked against the real MC 26.2 client jar with {@code javap} and {@code cfr}).
 * That match shows vanilla runs this exact sequence start to finish on one thread on this
 * Blaze3D/MoltenVK stack: the callback's {@code buffer.close()} is the last line inside the very
 * {@link Runnable} passed to {@code copyTextureToBuffer}, and vanilla never submits or fences
 * around it on its own. So no extra {@code submit()}/{@code GpuFence} step is needed here either:
 * this call is free to stall the render thread until the copied bytes can be read, which is fine
 * only because someone has to turn this diagnostic on by hand.
 */
public final class GBufferReadbackDiagnostic {
    // Keep the old first-frame/every-30-frames cadence once this is turned on by hand.
    private static final int INTERVAL_FRAMES = 30;
    private static final boolean PERIODIC_READBACK = Boolean.getBoolean("fornax.debug.gbufferReadback");

    private static long frameCounter;

    /** Set by {@link #requestDump()}, consumed by the very next {@link #maybeLog} call -- render-
     * thread only (set from the client tick's keybind handler, read from the render thread's own
     * {@code GraphRunner.finish}, both the same thread in practice), so a plain field is enough. */
    private static boolean dumpRequested;

    private GBufferReadbackDiagnostic() {
    }

    /** Requests a one-shot full-attachment dump on the next {@link #maybeLog} call, on its own path
     * even when periodic reads are off. This is the path F10 drives. */
    public static void requestDump() {
        dumpRequested = true;
        dev.icehunter.fornax.debug.FullscreenCapture.request();
    }

    /**
     * Call once per frame from {@code GraphRunner.finish} after terrain has drawn into {@code
     * gbuffer}. Always services a pending {@link #requestDump()} first, then checks the JVM flag
     * and a frame count before the periodic log. With periodic reads off, this just checks two
     * booleans and returns.
     */
    public static void maybeLog(GBuffer gbuffer) {
        if (dumpRequested) {
            dumpRequested = false;
            dumpAll(gbuffer);
        }

        // Periodic reads only turn on by hand; F10 was serviced above this check.
        if (!PERIODIC_READBACK) {
            return;
        }
        frameCounter++;
        // Fire on the very first frame too (diagnosis rounds are short) and every 30 thereafter.
        if (frameCounter != 1 && frameCounter % INTERVAL_FRAMES != 0) {
            return;
        }

        GpuTexture aoTexture = gbuffer.getAoTexture();
        GpuTexture albedoTexture = gbuffer.getAlbedoTexture();
        GpuTexture normalTexture = gbuffer.getNormalTexture();
        if (aoTexture == null || albedoTexture == null || normalTexture == null) {
            return; // test-constructed GBuffer, or a texture failed to allocate -- nothing to read
        }

        int width = gbuffer.getWidth();
        int height = gbuffer.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        // Center 2x2 (clamped to the texture bounds, so this degrades gracefully on a 1x1 target).
        int x = Math.max(0, Math.min(width - 1, width / 2 - 1));
        int y = Math.max(0, Math.min(height - 1, height / 2 - 1));
        int w = Math.min(2, width - x);
        int h = Math.min(2, height - y);

        FornaxMod.LOGGER.info("[Fornax][readback] frame {} -- reading center {}x{} region at ({},{}) of a {}x{} G-buffer",
                frameCounter, w, h, x, y, width, height);

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        // AO attachment is RGBA8_UNORM: .r = baked AO (the proven-good channel), .gba = intrinsic
        // raw albedo (the channel under investigation -- see terrain.fsh's gAoOut write).
        readRgba8(encoder, aoTexture, "AO(r=bakedAO,gba=rawAlbedo)", x, y, w, h);
        readRgba8(encoder, albedoTexture, "Albedo(lit atlas sample,a=skyLight)", x, y, w, h);
        readRgba16Snorm(encoder, normalTexture, "Normal", x, y, w, h);
    }

    /**
     * On-demand full-attachment dump serviced by {@link #maybeLog} for a pending {@link
     * #requestDump()} -- reads the center 2x2 of all FIVE G-buffer attachments (normal, albedo,
     * material, ao, motion; see {@code GBufferManager}'s allocation for each one's {@code
     * GpuFormat}) plus the current G-buffer's size and instance. Runs whether or not the periodic
     * log's JVM flag is set.
     */
    private static void dumpAll(GBuffer gbuffer) {
        GpuTexture normalTexture = gbuffer.getNormalTexture();
        GpuTexture albedoTexture = gbuffer.getAlbedoTexture();
        GpuTexture materialTexture = gbuffer.getMaterialTexture();
        GpuTexture aoTexture = gbuffer.getAoTexture();
        GpuTexture motionTexture = gbuffer.getMotionTexture();

        int width = gbuffer.getWidth();
        int height = gbuffer.getHeight();
        FornaxMod.LOGGER.info("[Fornax][readback] on-demand dump -- GBuffer instance {}, size {}x{}",
                System.identityHashCode(gbuffer), width, height);

        if (normalTexture == null || albedoTexture == null || materialTexture == null
                || aoTexture == null || motionTexture == null) {
            FornaxMod.LOGGER.warn("[Fornax][readback] on-demand dump requested but the GBuffer has no "
                    + "allocated textures (shaders disabled, no pack loaded, or a texture failed to allocate)");
            return;
        }
        if (width <= 0 || height <= 0) {
            return;
        }
        // Center 2x2 (clamped to the texture bounds, so this degrades gracefully on a 1x1 target).
        int x = Math.max(0, Math.min(width - 1, width / 2 - 1));
        int y = Math.max(0, Math.min(height - 1, height / 2 - 1));
        int w = Math.min(2, width - x);
        int h = Math.min(2, height - y);

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        readRgba16Snorm(encoder, normalTexture, "Normal", x, y, w, h);
        readRgba8(encoder, albedoTexture, "Albedo(lit atlas sample,a=skyLight)", x, y, w, h);
        readRgba8(encoder, materialTexture, "Material", x, y, w, h);
        readRgba8(encoder, aoTexture, "AO(r=bakedAO,gba=rawAlbedo)", x, y, w, h);
        readRg16Float(encoder, motionTexture, "Motion", x, y, w, h);
    }

    /** Reads an RGBA8_UNORM texture's {@code w}x{@code h} region at ({@code x},{@code y}) and logs
     * each pixel's four raw unsigned bytes (0-255 each, matching the GLSL 0.0-1.0 UNORM decode). */
    private static void readRgba8(CommandEncoder encoder, GpuTexture texture, String label,
                                   int x, int y, int w, int h) {
        int blockSize = texture.getFormat().blockSize();
        long size = (long) w * h * blockSize;
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "[Fornax] readback " + label,
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_HINT_CLIENT_STORAGE | GpuBuffer.USAGE_COPY_DST, size);
        encoder.copyTextureToBuffer(texture, buffer, 0L, () -> {
            try (GpuBufferSlice.MappedView read = buffer.map(true, false)) {
                read.data().order(ByteOrder.nativeOrder());
                for (int py = 0; py < h; py++) {
                    for (int px = 0; px < w; px++) {
                        int offset = (px + py * w) * blockSize;
                        int r = read.data().get(offset) & 0xFF;
                        int g = read.data().get(offset + 1) & 0xFF;
                        int b = read.data().get(offset + 2) & 0xFF;
                        int a = read.data().get(offset + 3) & 0xFF;
                        FornaxMod.LOGGER.info(
                                "[Fornax][readback] {} pixel ({},{}): RGBA8 raw = ({}, {}, {}, {}) of 255",
                                label, x + px, y + py, r, g, b, a);
                    }
                }
            }
            buffer.close();
        }, 0, x, y, w, h);
    }

    /** Reads an RGBA16_SNORM texture's {@code w}x{@code h} region at ({@code x},{@code y}) and logs
     * each pixel's four raw signed 16-bit values (-32768..32767, matching the GLSL -1.0..1.0 SNORM
     * decode) -- used for the normal attachment cross-check only, never AO/albedo. */
    private static void readRgba16Snorm(CommandEncoder encoder, GpuTexture texture, String label,
                                         int x, int y, int w, int h) {
        int blockSize = texture.getFormat().blockSize();
        long size = (long) w * h * blockSize;
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "[Fornax] readback " + label,
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_HINT_CLIENT_STORAGE | GpuBuffer.USAGE_COPY_DST, size);
        encoder.copyTextureToBuffer(texture, buffer, 0L, () -> {
            try (GpuBufferSlice.MappedView read = buffer.map(true, false)) {
                read.data().order(ByteOrder.nativeOrder());
                for (int py = 0; py < h; py++) {
                    for (int px = 0; px < w; px++) {
                        int offset = (px + py * w) * blockSize;
                        short r = read.data().getShort(offset);
                        short g = read.data().getShort(offset + 2);
                        short b = read.data().getShort(offset + 4);
                        short a = read.data().getShort(offset + 6);
                        FornaxMod.LOGGER.info(
                                "[Fornax][readback] {} pixel ({},{}): RGBA16_SNORM raw = ({}, {}, {}, {})",
                                label, x + px, y + py, r, g, b, a);
                    }
                }
            }
            buffer.close();
        }, 0, x, y, w, h);
    }

    /** Reads an RG16_FLOAT texture's {@code w}x{@code h} region at ({@code x},{@code y}) and logs
     * each pixel's two raw half-float components decoded to {@code float} via {@link
     * Float#float16ToFloat(short)} -- used for the motion attachment only (the dump-all path;
     * {@code gMotion} is currentUV - previousUV, so values are typically tiny fractions of a
     * texel). */
    private static void readRg16Float(CommandEncoder encoder, GpuTexture texture, String label,
                                       int x, int y, int w, int h) {
        int blockSize = texture.getFormat().blockSize();
        long size = (long) w * h * blockSize;
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "[Fornax] readback " + label,
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_HINT_CLIENT_STORAGE | GpuBuffer.USAGE_COPY_DST, size);
        encoder.copyTextureToBuffer(texture, buffer, 0L, () -> {
            try (GpuBufferSlice.MappedView read = buffer.map(true, false)) {
                read.data().order(ByteOrder.nativeOrder());
                for (int py = 0; py < h; py++) {
                    for (int px = 0; px < w; px++) {
                        int offset = (px + py * w) * blockSize;
                        float r = Float.float16ToFloat(read.data().getShort(offset));
                        float g = Float.float16ToFloat(read.data().getShort(offset + 2));
                        FornaxMod.LOGGER.info(
                                "[Fornax][readback] {} pixel ({},{}): RG16_FLOAT raw = ({}, {})",
                                label, x + px, y + py, r, g);
                    }
                }
            }
            buffer.close();
        }, 0, x, y, w, h);
    }
}

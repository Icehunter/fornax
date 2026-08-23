package dev.icehunter.fornax.pipeline;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.config.FornaxConfig;

import java.nio.ByteOrder;

/**
 * ecv2 diagnostic instrument: once every {@link #INTERVAL_FRAMES} frames, while the profiler
 * overlay is enabled, reads the center 2x2 pixels of the AO/albedo/normal G-buffer attachments
 * straight from VRAM to CPU (bypassing every display/debug-view path) and logs the raw component
 * values -- ground truth for "does the TEXTURE actually contain nonzero bytes" questions that a
 * days-long "channel reads black on screen" investigation otherwise can't answer on its own (see
 * the ecv2 saga in {@code .superpowers/sdd/progress.md}: the AO attachment now packs baked AO in
 * {@code .r} and raw unlit albedo in {@code .gba} -- {@code .r} is proven live; {@code .gba} is the
 * one this instrument exists to verify). Costs nothing while the overlay is off; the {@link
 * FornaxConfig#get()}{@code .profilerOverlay} gate is the same one {@code ProfilerOverlay} itself
 * reads, so this instrument is only ever live alongside a HUD the player deliberately turned on.
 *
 * <p>{@link #requestDump()} is the on-demand counterpart (wired to the F10 debug keybind, see
 * {@code dev.icehunter.fornax.debug.FornaxDebugKeys}): a one-shot dump of ALL FIVE G-buffer
 * attachments, serviced by the very next {@link #maybeLog} call regardless of the profiler-overlay
 * gate -- the live-iteration path that replaced the diagnosis round's unconditional logging (see
 * that gate's own comment below).
 *
 * <p>The readback sequence -- a {@code USAGE_MAP_READ}{@code |}{@code USAGE_COPY_DST} buffer, a
 * region {@link CommandEncoder#copyTextureToBuffer}, and a callback that maps/reads/closes -- is a
 * direct mirror of {@code net.minecraft.client.Screenshot#takeScreenshot}'s own texture-readback
 * shape (bytecode- and decompile-verified against the real MC 26.2 client jar via {@code javap}/
 * {@code cfr}): vanilla's own proof that this exact sequence runs synchronously start-to-finish
 * on this Blaze3D/MoltenVK stack (the callback's {@code buffer.close()} is the last statement
 * inside the very {@link Runnable} passed to {@code copyTextureToBuffer}, and vanilla never
 * separately submits or fences around it), so no extra {@code submit()}/{@code GpuFence} dance is
 * needed here either -- this call is allowed to (and does) stall the render thread until the
 * copied bytes are readable, which is fine for a profiler-gated diagnostic.
 */
public final class GBufferReadbackDiagnostic {
    private static final int INTERVAL_FRAMES = 120;

    private static long frameCounter;

    /** Set by {@link #requestDump()}, consumed by the very next {@link #maybeLog} call -- render-
     * thread only (set from the client tick's keybind handler, read from the render thread's own
     * {@code GraphRunner.finish}, both the same thread in practice), so a plain field is enough. */
    private static boolean dumpRequested;

    private GBufferReadbackDiagnostic() {
    }

    /** Requests a one-shot full-attachment dump on the next {@link #maybeLog} call, independent of
     * {@link FornaxConfig#get()}{@code .profilerOverlay} -- the on-demand path F10 drives. */
    public static void requestDump() {
        dumpRequested = true;
    }

    /**
     * Call once per frame from {@code GraphRunner.finish} after terrain has drawn into {@code
     * gbuffer} -- services a pending {@link #requestDump()} unconditionally, then internally gates
     * the automatic periodic log on {@link FornaxConfig#get()}{@code .profilerOverlay} and a
     * frame-counter cadence, so most calls are a single cheap field read and return.
     */
    public static void maybeLog(GBuffer gbuffer) {
        if (dumpRequested) {
            dumpRequested = false;
            dumpAll(gbuffer);
        }

        // Re-gated behind profilerOverlay (was unconditionally logging for a live-diagnosis round;
        // that round is over -- requestDump()/F10 is now the on-demand path instead).
        if (!FornaxConfig.get().profilerOverlay) {
            return;
        }
        frameCounter++;
        // Fire on the very first frame too (diagnosis rounds are short) and every 30 thereafter.
        if (frameCounter != 1 && frameCounter % 30 != 0) {
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
     * GpuFormat}) plus the current G-buffer's size/instance, independent of the periodic log's
     * {@code profilerOverlay} gate.
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

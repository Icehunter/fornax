package dev.icehunter.fornax.atlas;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.mixin.sodium.ShaderChunkRendererAccessor;
import dev.icehunter.fornax.mixin.vanilla.SpriteContentsAccessor;
import dev.icehunter.fornax.pipeline.SpriteBoundsTexture;
import dev.icehunter.fornax.util.GpuFatalErrors;
import dev.icehunter.fornax.util.GpuFatalException;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.jspecify.annotations.Nullable;

/**
 * The paged block atlas's ALBEDO overflow layers: one array texture, one layer per overflow page,
 * carrying every spilled static sprite's FULL-RESOLUTION content at its page-local placement. The
 * terrain sampling include ({@code fornax:block_atlas.glsl}) recovers a layer coordinate from a
 * ghost UV by pure arithmetic (see {@link BlockAtlasGhostLayout}) and samples this texture; every
 * other consumer keeps sampling the ghost in page 0 and never sees this object.
 *
 * <p><b>Content is vanilla's own mip chain, copied, never re-derived:</b> the compositor uploads
 * {@code SpriteContents.byMipLevel} (via {@link SpriteContentsAccessor}) per mip per layer with
 * {@code writeToTexture} -- the identical images vanilla's own staging blit reads from -- at the
 * content origin ({@code paddedOrigin + padding}). Animated ghosts have no overflow copy at all
 * ({@link BlockAtlasGhostSprite#hasOverflowCopy()}): their cell is never remapped by the include,
 * so their layer region simply stays unwritten and unsampled. The padding GUTTER around each
 * static sprite is likewise unwritten -- page 0's own gutters are only ever filled by the blit
 * shader's edge extrusion, and with the stitcher's mip-aligned placement no in-rect sample ever
 * reads a gutter texel at any composited level.
 *
 * <p><b>Lifecycle</b> is close-before-build: the generation resident when a reload starts is freed
 * (via {@link #releaseCurrent}, called from {@code TextureAtlasReleaseGenerationMixin} at
 * {@code upload} HEAD, and again defensively by {@link #rebuild} itself) before the next
 * generation's array texture is ever requested from the driver, so old and new are never resident
 * together: double-residency here is a native out-of-memory risk during a resource-pack switch.
 * {@link #rebuild} normally runs from
 * {@link AtlasGenerationSchedule}'s terminal tick after the same generation's sidecar work, with
 * the upload-RETURN hook retained as the no-pending fallback. When the overflow page count changes
 * between generations, Sodium's terrain program cache is cleared (the same generation-guarded
 * clear {@code GraphRunner}'s republish path performs, and safe here for the same reason:
 * {@code upload} runs on the render thread between frames during a reload apply) so the next
 * terrain draw recompiles with the new {@code FORNAX_ATLAS_OVERFLOW_PAGES} constant.
 */
public final class BlockAtlasOverflow {
    private record Published(ArrayTextures.Allocation albedo, int pageCount, long residentBytes) {
    }

    @Nullable
    private static volatile Published current;

    /** The page count the terrain programs were last (implicitly) compiled against -- render
     * thread only, updated by {@link #rebuild}. */
    private static int lastPublishedPageCount;

    /** Diagnostic sample-provenance tint (see {@link #toggleDebugTint}) -- render thread only. */
    private static boolean debugTint;

    private BlockAtlasOverflow() {
    }

    /** Whether terrain compiles with {@code FORNAX_ATLAS_DEBUG_TINT} -- the include then colors
     * every overflow-layer sample red and every ghost sample yellow, page 0 untouched, so a visual
     * artifact's PROVENANCE is readable straight off the screen. */
    public static boolean debugTint() {
        return debugTint;
    }

    /**
     * Live-toggles the provenance tint from its debug keybind: flips the flag and clears Sodium's
     * terrain program cache (same between-frames clear {@link #rebuild} performs), so the next
     * terrain draw recompiles with or without the tint -- no reload, no restart.
     */
    public static void toggleDebugTint() {
        debugTint = !debugTint;
        ShaderChunkRendererAccessor.fornax$getPrograms().clear();
        FornaxMod.LOGGER.info("[Fornax] Paged block atlas: sample-provenance tint {}",
                debugTint ? "ON (red = overflow layer, yellow = ghost, untinted = page 0)" : "OFF");
    }

    /** The albedo overflow array view, or {@code null} when the current atlas is unpaged (bind the
     * neutral array fallback instead). */
    @Nullable
    public static GpuTextureView albedoView() {
        Published published = current;
        return published == null ? null : published.albedo.view();
    }

    /** Overflow pages in the published build -- the value the terrain shader constant must carry. */
    public static int overflowPageCount() {
        Published published = current;
        return published == null ? 0 : published.pageCount;
    }

    /**
     * Frees the currently published overflow allocation, if any, ahead of a new generation's
     * build. Normally called from {@code TextureAtlasReleaseGenerationMixin} at {@code upload}
     * HEAD, three render-loop-separated polls before {@link #rebuild} runs; {@link #rebuild} also
     * closes-before-building on its own as a second line of defense in case it is ever reached
     * without that hook.
     */
    public static void releaseCurrent() {
        Published previous = current;
        if (previous == null) {
            return;
        }
        current = null;
        previous.albedo.close();
        // Every allocation of this array was already logged (see build()'s own INFO line); nothing
        // logged its release before this, which is exactly what made three back-to-back
        // resource-pack switches accumulating past available VRAM invisible in the log.
        FornaxMod.LOGGER.info(
                "[Fornax] Paged block atlas: released {} overflow layer(s) ({} MB freed)",
                previous.pageCount(), previous.residentBytes() / (1024L * 1024L));

        // Matches rebuild()'s own invalidation for the same reason: overflowPageCount() now
        // returns 0 (current is null above), so the terrain shader's compiled
        // FORNAX_ATLAS_OVERFLOW_PAGES constant must drop to 0 too, right here -- not only when
        // rebuild() itself eventually runs. If upload() throws (or a fatal rethrow from this same
        // class's own allocation failure propagates) between this release and rebuild() ever
        // running, leaving that step out would leave cached terrain programs compiled for the old
        // nonzero page count while the actual binding has already dropped to the neutral fallback.
        if (previous.pageCount() != 0 && lastPublishedPageCount != 0) {
            lastPublishedPageCount = 0;
            ShaderChunkRendererAccessor.fornax$getPrograms().clear();
            FornaxMod.LOGGER.info(
                    "[Fornax] Paged block atlas: overflow page count now 0, terrain programs cleared");
        }
    }

    /**
     * Rebuilds (or clears) the overflow layers for the atlas generation whose {@code upload} just
     * completed. An ordinary compositor failure logs, publishes UNPAGED (terrain then renders
     * every ghost at quarter resolution -- correct, just soft), and leaves vanilla state alone. A
     * FATAL failure (the device is lost, or this multi-gigabyte array allocation itself ran the
     * device out of memory) rethrows instead: this allocation is a native out-of-memory risk during
     * a resource-pack switch, and a Vulkan OOM logged as a soft warning here would mean rendering
     * continues for several more frames on a device that already failed, surfacing as an
     * unattributed native crash deeper in, rather than at the line that actually failed.
     *
     * <p>Also applies this generation's sprite-bounds grid size ({@link
     * SpriteBoundsTexture#useGridSize}), on {@code layout}'s behalf: {@code
     * BlockAtlasPagedStitch#takeover} decides the grid size on the stitch's background executor
     * (cheap, no GPU call) but cannot apply it there -- {@code useGridSize} closes a live GPU
     * texture, and this is the render-thread call that generation's grid decision was waiting for.
     *
     * <p>Publishes nothing (soft-degrades to unpaged, same as any other failure here) when this
     * generation's LabPBR sidecar pair isn't up yet: a paged overflow array and its sidecar
     * counterparts must describe the same layer count, and {@link AtlasGenerationSchedule} can
     * reach this before {@code LabPbrAtlasPair.rebuild} has run if the sidecar rebuild itself
     * failed.
     */
    public static void rebuild(@Nullable BlockAtlasPagedLayout layout) {
        SpriteBoundsTexture.useGridSize(
                layout == null ? SpriteBoundsTexture.DEFAULT_SIZE : layout.gridSize());
        releaseCurrent();
        Published next = null;
        try {
            boolean matchingSidecarsReady = layout == null
                    || LabPbrAtlasPair.get(TextureAtlas.LOCATION_BLOCKS) != null;
            if (!matchingSidecarsReady) {
                FornaxMod.LOGGER.warn("[Fornax] Paged block atlas: not publishing overflow layers"
                        + " because this generation's LabPBR sidecar pair is unavailable");
            }
            next = layout == null || !matchingSidecarsReady ? null : build(layout);
        } catch (RuntimeException e) {
            GpuFatalErrors.rethrowIfFatal(e);
            FornaxMod.LOGGER.warn("[Fornax] Paged block atlas: overflow compositor failed;"
                    + " spilled sprites stay at ghost resolution", e);
        }
        current = next;

        int pageCount = next == null ? 0 : next.pageCount;
        if (pageCount != lastPublishedPageCount) {
            lastPublishedPageCount = pageCount;
            // See class doc -- forces the next terrain draw to recompile against the new
            // FORNAX_ATLAS_OVERFLOW_PAGES value instead of serving a program compiled for the
            // previous atlas generation's layout.
            ShaderChunkRendererAccessor.fornax$getPrograms().clear();
            FornaxMod.LOGGER.info(
                    "[Fornax] Paged block atlas: overflow page count now {}, terrain programs cleared",
                    pageCount);
        }
    }

    private static final java.util.Map<Integer, ArrayTextures.Allocation> neutrals = new java.util.HashMap<>();
    @Nullable
    private static GpuDevice neutralDevice;

    /** Opaque black -- the albedo lane's unpaged fallback for {@code u_BlockPagesTex}. */
    public static final int NEUTRAL_BLACK_RGBA = 0xFF_00_00_00;
    /** LabPBR neutral normal (matches {@code LabPbrNeutralTextures.NORMAL_ARGB}'s byte meaning in
     * this path's RGBA byte order) -- the {@code u_NormalPagesTex} fallback. */
    public static final int NEUTRAL_NORMAL_RGBA = 0xFF_80_80_80;
    /** LabPBR missing-material sentinel -- the {@code u_MaterialPagesTex} fallback. */
    public static final int NEUTRAL_MATERIAL_RGBA = 0xFF_00_00_00;

    /**
     * A 1x1x1 single-color array texture for binding one of the paged samplers when the atlas is
     * unpaged (or that lane's layers failed to build) -- the bind-group layout declares every
     * paged sampler unconditionally (it is baked at class-init, before any pack loads), and with
     * {@code FORNAX_ATLAS_OVERFLOW_PAGES} at 0 no shader ever samples them. Same
     * device-generation idiom as {@code LabPbrNeutralTextures}: rebuilt when the device changes,
     * {@code null} before any device exists or on a non-Vulkan backend (callers skip the bind
     * then, like the geometry slots do).
     */
    @Nullable
    public static synchronized GpuTextureView neutralArrayView(int rgba) {
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            return null;
        }
        if (neutralDevice != device) {
            neutrals.values().forEach(ArrayTextures.Allocation::close);
            neutrals.clear();
            neutralDevice = device;
        }
        ArrayTextures.Allocation cached = neutrals.get(rgba);
        if (cached != null) {
            return cached.view();
        }
        ArrayTextures.Allocation created = ArrayTextures.create(
                "Fornax Paged Block Atlas Neutral " + Integer.toHexString(rgba),
                GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);
        if (created == null) {
            return null;
        }
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            java.nio.ByteBuffer pixel = stack.malloc(4)
                    .put(new byte[]{(byte) (rgba & 0xFF), (byte) ((rgba >>> 8) & 0xFF),
                            (byte) ((rgba >>> 16) & 0xFF), (byte) ((rgba >>> 24) & 0xFF)})
                    .flip();
            device.createCommandEncoder().writeToTexture(created.texture(), pixel, 0, 0, 0, 0, 1, 1);
        }
        neutrals.put(rgba, created);
        return created.view();
    }

    @Nullable
    private static Published build(BlockAtlasPagedLayout layout) {
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            return null;
        }
        ArrayTextures.Allocation albedo;
        try {
            albedo = ArrayTextures.create("Fornax Paged Block Atlas Albedo",
                    GpuFormat.RGBA8_UNORM, layout.canvasSize(), layout.canvasSize(),
                    layout.overflowPageCount(), layout.mipLevel() + 1);
        } catch (RuntimeException e) {
            // Any exception from this specific call -- OOM, device loss, anything
            // VulkanUtils.crashIfFailure maps to a bare IllegalStateException -- means the device
            // could not fulfill this multi-gigabyte allocation, never a soft, retry-safe condition.
            // rebuild()'s own catch only rethrows GpuDeviceLossException/GpuFatalException; without
            // this, a real Vulkan OOM here (VK_ERROR_OUT_OF_DEVICE_MEMORY, mapped to a plain
            // IllegalStateException, not GpuDeviceLossException) was logged as a soft warning while
            // rendering continued on a device that had already failed.
            throw new GpuFatalException(
                    "Paged block atlas: overflow array allocation failed: " + e.getMessage());
        }
        if (albedo == null) {
            FornaxMod.LOGGER.warn("[Fornax] Paged block atlas: array textures unavailable on this"
                    + " backend; spilled sprites stay at ghost resolution");
            return null;
        }

        long start = System.nanoTime();
        CommandEncoder encoder = device.createCommandEncoder();
        int composited = 0;
        try {
            for (BlockAtlasGhostSprite ghost : layout.ghosts()) {
                if (!ghost.hasOverflowCopy()) {
                    continue;
                }
                NativeImage[] mips = ((SpriteContentsAccessor) (Object) ghost.contents()).fornax$byMipLevel();
                int levels = Math.min(layout.mipLevel() + 1, mips.length);
                for (int mip = 0; mip < levels; mip++) {
                    writeSpriteMip(encoder, albedo, ghost, mips[mip], mip);
                }
                composited++;
            }
        } catch (RuntimeException e) {
            // albedo already succeeded above; rebuild()'s own catch has no reference to it and can
            // only fall back to the previous atlas, so it must be freed here or a malformed sprite
            // partway through this loop leaks the whole array-texture allocation.
            albedo.close();
            throw e;
        }
        long residentBytes = (long) layout.canvasSize() * layout.canvasSize() * 4L
                * layout.overflowPageCount() * 4L / 3L;
        FornaxMod.LOGGER.info(
                "[Fornax] Paged block atlas: composited {} spilled sprite(s) onto {} overflow layer(s)"
                        + " ({} MB resident) in {} ms",
                composited, layout.overflowPageCount(), residentBytes / (1024L * 1024L),
                (System.nanoTime() - start) / 1_000_000L);
        return new Published(albedo, layout.overflowPageCount(), residentBytes);
    }

    /**
     * One sprite, one mip, onto its layer -- as the full PADDED box, reproducing vanilla's page-0
     * blit CONTINUOUSLY, not by integer offset. Two failure modes force that:
     *
     * <ul>
     * <li>Content-only writes leave the anisotropy gutters as uninitialized VRAM, and anisotropic
     *     filtering's wide edge footprints read them (dotted seams).</li>
     * <li>Integer-offset padded writes misregister the MIPS: the stitcher aligns padded-box
     *     ORIGINS to {@code 1 << mipLevel} (decompile: {@code Holder} dims are
     *     {@code smallestFittingMinTexel(w + 2p, mipLevel)}), but the CONTENT sits at
     *     {@code origin + padding}, and an odd padding (anisotropy bit 3 on the live profile) is
     *     not divisible by {@code 1 << mip} -- so {@code (pageX + p) >> mip} lands each level a
     *     different sub-texel distance from where the UVs sample it. Vanilla never floors: its
     *     blit draws the box (whose {@code x >> mip} IS exact, by the alignment above) and places
     *     content by interpolation. The result of flooring was a band of doubled/shifted texels at
     *     sprite edges, widening with mip depth under anisotropic filtering.</li>
     * </ul>
     *
     * So each destination texel here samples the content mip bilinearly at exactly the coordinate
     * vanilla's blit shader would ({@code u = t * (w + 2p) / w - p / w}, clamped -- the clamp IS
     * the gutter extrusion), with an integer fast path when the mapping is texel-exact (always at
     * mip 0, and at every mip when padding is 0 or mip-aligned).
     */
    private static void writeSpriteMip(CommandEncoder encoder, ArrayTextures.Allocation albedo,
                                       BlockAtlasGhostSprite ghost, NativeImage content, int mip) {
        int layer = ghost.overflowPage() - 1;
        int padding = ghost.padding();
        int boxX = ghost.pageX() >> mip;
        int boxY = ghost.pageY() >> mip;
        if (padding == 0) {
            encoder.writeToTexture(albedo.texture(), content, mip, layer, boxX, boxY);
            return;
        }
        int spriteW = ghost.contents().width();
        int spriteH = ghost.contents().height();
        int contentW = content.getWidth();
        int contentH = content.getHeight();
        int boxW = Math.max(1, (spriteW + 2 * padding) >> mip);
        int boxH = Math.max(1, (spriteH + 2 * padding) >> mip);
        boolean exact = (padding & ((1 << mip) - 1)) == 0
                && boxW << mip == spriteW + 2 * padding
                && boxH << mip == spriteH + 2 * padding;
        try (NativeImage padded = new NativeImage(boxW, boxH, false)) {
            if (exact) {
                int offX = padding >> mip;
                int offY = padding >> mip;
                for (int y = 0; y < boxH; y++) {
                    int srcY = Math.clamp(y - offY, 0, contentH - 1);
                    for (int x = 0; x < boxW; x++) {
                        padded.setPixel(x, y, content.getPixel(Math.clamp(x - offX, 0, contentW - 1), srcY));
                    }
                }
            } else {
                // Vanilla's blit math per destination texel: quad t = (j + 0.5) / box, content
                // u = t * (w + 2p) / w - p / w, sampled bilinearly with clamp (the extrusion).
                float scaleX = (float) (spriteW + 2 * padding) / spriteW / boxW;
                float biasX = (float) padding / spriteW;
                float scaleY = (float) (spriteH + 2 * padding) / spriteH / boxH;
                float biasY = (float) padding / spriteH;
                for (int y = 0; y < boxH; y++) {
                    float srcYf = ((y + 0.5f) * scaleY - biasY) * contentH - 0.5f;
                    for (int x = 0; x < boxW; x++) {
                        float srcXf = ((x + 0.5f) * scaleX - biasX) * contentW - 0.5f;
                        padded.setPixel(x, y, bilinearClamped(content, srcXf, srcYf, contentW, contentH));
                    }
                }
            }
            encoder.writeToTexture(albedo.texture(), padded, mip, layer, boxX, boxY);
        }
    }

    /** Clamped bilinear fetch on a packed-RGBA {@link NativeImage}, channel-wise -- the CPU twin of
     * the clamp-sampler {@code textureLod} vanilla's blit performs. */
    private static int bilinearClamped(NativeImage image, float xf, float yf, int w, int h) {
        int x0 = Math.clamp((int) Math.floor(xf), 0, w - 1);
        int y0 = Math.clamp((int) Math.floor(yf), 0, h - 1);
        int x1 = Math.min(x0 + 1, w - 1);
        int y1 = Math.min(y0 + 1, h - 1);
        float fx = Math.clamp(xf - x0, 0.0f, 1.0f);
        float fy = Math.clamp(yf - y0, 0.0f, 1.0f);
        int p00 = image.getPixel(x0, y0);
        int p10 = image.getPixel(x1, y0);
        int p01 = image.getPixel(x0, y1);
        int p11 = image.getPixel(x1, y1);
        int out = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            float top = ((p00 >>> shift) & 0xFF) * (1.0f - fx) + ((p10 >>> shift) & 0xFF) * fx;
            float bottom = ((p01 >>> shift) & 0xFF) * (1.0f - fx) + ((p11 >>> shift) & 0xFF) * fx;
            int channel = Math.clamp(Math.round(top * (1.0f - fy) + bottom * fy), 0, 255);
            out |= channel << shift;
        }
        return out;
    }
}

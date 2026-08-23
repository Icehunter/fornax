package dev.icehunter.fornax.pipeline;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.mixin.vanilla.TextureAtlasSpritesAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Answers "which sprite occupies this point on the block atlas, and what rectangle does it span?"
 *
 * <p>A coarse grid laid over the atlas: each cell stores the {@code (u0, v0, u1, v1)} of the sprite
 * covering it, so a shader holding an atlas coordinate reads its sprite's true extents with a single
 * {@code texelFetch} and no per-vertex data at all.
 *
 * <p><b>Why the atlas is asked rather than the geometry.</b> The obvious cheaper route is to take each
 * quad's own min/max UV as the sprite rectangle, which costs nothing and needs no atlas access. It is
 * also wrong, and wrong in a way that looks plausible: a block model may map a face onto part of its
 * texture rather than all of it, so those quads report a sub-rectangle. Anything scaled by it -- a
 * parallax step expressed as a fraction of a block, a tiling wrap -- then works in the wrong units for
 * some faces and not others. Measured live, the failure showed up as texture coordinates that ramped
 * cleanly across some faces and across several blocks on others. The atlas is the only authority on
 * where a sprite actually is.
 */
public final class SpriteBoundsTexture {
    /**
     * Grid resolution. Each cell is one atlas texel at 512px, or 8 texels on a 4096px atlas -- fine
     * enough for any block sprite, since the smallest is 16px and still spans two cells there.
     */
    public static final int SIZE = 512;
    private static final int BYTES = SIZE * SIZE * 4 * Float.BYTES;

    private static @Nullable GpuTexture texture;
    private static @Nullable GpuTextureView view;
    private static @Nullable GpuTexture rangeTexture;
    private static @Nullable GpuTextureView rangeView;
    private static boolean built;
    private static boolean reportedFailure;

    private SpriteBoundsTexture() {}

    /**
     * The view to bind, building it on first use. Returns {@code null} before a device or atlas
     * exists, which callers treat like any other unresolvable input.
     */
    @Nullable
    public static synchronized GpuTextureView view() {
        if (!built) {
            build();
        }
        return view;
    }

    /**
     * The companion grid holding each sprite's labPBR height range, normalised to 0..1:
     * {@code (trueMin, trueMax, robustMin, robustMax)}.
     *
     * <p>The first two channels are unchanged and still mean exactly what they always did -- the
     * single deepest and single shallowest texel in the sprite. The trimmed 1st/99th-percentile pair
     * was ADDED in the two channels this RGBA32F grid was already paying for and never used, so a
     * shader reading only {@code .xy} sees no change at all and one reading {@code .zw} gets a range
     * a stray texel cannot move. See {@link dev.icehunter.fornax.atlas.SpriteHeightRanges} for the
     * measurements behind the second pair, and
     * {@link dev.icehunter.fornax.atlas.NormalMapAtlasReloadListener#ROBUST_TRIM_PERCENT} for why the
     * trim is 1% and not something else.
     *
     * <p>Built alongside the bounds so the two can never describe different atlases.
     */
    @Nullable
    public static synchronized GpuTextureView rangeViewOrNull() {
        if (!built) {
            build();
        }
        return rangeView;
    }

    /** Forces a rebuild, for when the atlas is restitched and every rectangle may have moved. */
    public static synchronized void invalidate() {
        built = false;
    }

    private static void build() {
        GpuDevice device = RenderSystem.tryGetDevice();
        Minecraft minecraft = Minecraft.getInstance();
        if (device == null || minecraft == null || minecraft.getAtlasManager() == null) {
            return; // try again next frame
        }

        // Found by iteration rather than by asking for TextureAtlas.LOCATION_BLOCKS directly:
        // AtlasManager keys its atlases by its own identifiers, which are not the texture paths the
        // LOCATION_* constants name, so the direct lookup throws and -- caught -- looked exactly like
        // "the atlas is not stitched yet". It never recovered, and every frame silently produced no
        // grid at all.
        java.util.List<TextureAtlasSprite> sprites = null;
        var found = new java.util.concurrent.atomic.AtomicReference<java.util.List<TextureAtlasSprite>>();
        minecraft.getAtlasManager().forEach((id, candidate) -> {
            if (id != null && id.getPath().contains("block")) {
                found.set(((TextureAtlasSpritesAccessor) candidate).fornax$sprites());
            }
        });
        sprites = found.get();
        if (sprites == null || sprites.isEmpty()) {
            // Nothing to build from yet. Deliberately silent: this runs every frame until the atlas
            // exists, and one line per frame would bury the log.
            return;
        }

        ByteBuffer pixels = MemoryUtil.memAlloc(BYTES);
        ByteBuffer ranges = MemoryUtil.memAlloc(BYTES);
        try {
            // Zeroed, so a cell no sprite claims reads a degenerate rectangle. Shaders treat that as
            // "no bounds" and skip; uninitialized memory would instead give them a plausible-looking
            // wrong rectangle, which is the failure mode that hides rather than announces itself.
            MemoryUtil.memSet(pixels, 0);
            // Zeroed means "no range recorded", in BOTH pairs. A shader reading that must fall back
            // to labPBR's nominal 0..1 rather than dividing by a zero span -- and because zero is
            // degenerate in the trimmed pair too, the same test that catches an unrecorded sprite
            // also catches a sprite whose trimmed range collapsed. One rule, not two.
            MemoryUtil.memSet(ranges, 0);

            // Height ranges first, keyed by the same cells the rectangles use, so a fragment resolves
            // both from one coordinate.
            writeRanges(ranges, dev.icehunter.fornax.atlas.SpriteHeightRanges.all());

            int written = 0;
            for (TextureAtlasSprite sprite : sprites) {
                if (sprite == null) {
                    continue;
                }
                float u0 = sprite.getU0(), v0 = sprite.getV0();
                float u1 = sprite.getU1(), v1 = sprite.getV1();
                if (!(u1 > u0) || !(v1 > v0)) {
                    continue;
                }
                // Animated sprites deliberately store a degenerate rectangle. Their atlas rectangle
                // spans the whole vertical strip of frames rather than the one frame on display, so
                // anything tiling within it walks through the animation instead of across the
                // texture -- which on water rendered as a hard repeating grid.
                boolean animated = sprite.contents() != null && sprite.contents().isAnimated();

                int x0 = firstCell(u0);
                int x1 = lastCell(u1);
                int y0 = firstCell(v0);
                int y1 = lastCell(v1);
                for (int y = y0; y <= y1; y++) {
                    for (int x = x0; x <= x1; x++) {
                        int offset = (y * SIZE + x) * 4 * Float.BYTES;
                        pixels.putFloat(offset, animated ? 0.0f : u0);
                        pixels.putFloat(offset + 4, animated ? 0.0f : v0);
                        pixels.putFloat(offset + 8, animated ? 0.0f : u1);
                        pixels.putFloat(offset + 12, animated ? 0.0f : v1);
                    }
                }
                written++;
            }

            if (texture == null) {
                texture = device.createTexture("Fornax Sprite Bounds",
                        GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                        GpuFormat.RGBA32_FLOAT, SIZE, SIZE, 1, 1);
                view = device.createTextureView(texture);
            }
            if (rangeTexture == null) {
                rangeTexture = device.createTexture("Fornax Sprite Height Ranges",
                        GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                        GpuFormat.RGBA32_FLOAT, SIZE, SIZE, 1, 1);
                rangeView = device.createTextureView(rangeTexture);
            }
            CommandEncoder encoder = device.createCommandEncoder();
            encoder.writeToTexture(texture, pixels, 0, 0, 0, 0, SIZE, SIZE);
            encoder.writeToTexture(rangeTexture, ranges, 0, 0, 0, 0, SIZE, SIZE);
            built = true;
            // Coverage, not just success. A grid that built from a handful of sprites is a different
            // failure from one that never built, and both look identical in the world -- the first
            // leaves most surfaces without bounds, the second leaves the shader reading noise.
            int covered = 0;
            int rangeCovered = 0;
            for (int cell = 0; cell < SIZE * SIZE; cell++) {
                if (pixels.getFloat(cell * 4 * Float.BYTES + 8) > 0.0f) {
                    covered++;
                }
                if (ranges.getFloat(cell * 4 * Float.BYTES + 4) > 0.0f) {
                    rangeCovered++;
                }
            }
            // Height-range count included deliberately: the ranges are recorded by the normal atlas
            // build and consumed here, so a zero means the two ran out of order and every height
            // remap silently became a no-op -- indistinguishable in-game from the option doing nothing.
            //
            // The TRIMMED count is reported beside it for the same reason one step further in. A
            // sprite whose 1st and 99th percentiles collide publishes a zero-width trimmed range and
            // a shader falls back to the true one, which is correct and also invisible -- so "1200
            // ranges, 0 of them usable" and "1200 ranges, 900 usable" render identically at a glance
            // while meaning completely different things about whether this is doing any work.
            //
            // And the range grid's own COVERAGE is reported beside the bounds grid's, because a count
            // of ranges says only that they were measured, never that they were filed anywhere a
            // fragment can find them. That distinction is exactly what hid the halved-coordinate bug:
            // the count read a healthy 1139 while nine cells in ten held nothing, the shader fell back
            // to the raw height precisely as designed, and every POM setting became inert with no
            // fault reported anywhere. Two percentages that should track each other are a check a
            // glance at the log can make; a count is not.
            var ranges_ = dev.icehunter.fornax.atlas.SpriteHeightRanges.all();
            long trimmed = ranges_.stream()
                    .filter(r -> r.robustMaxAlpha() > r.robustMinAlpha()).count();
            FornaxMod.LOGGER.info("[Fornax] Sprite bounds grid: {} sprites, {}% of the atlas covered,"
                    + " {} height ranges ({} with a usable trimmed range) covering {}% of the grid",
                    written,
                    Math.round(100.0 * covered / (SIZE * (double) SIZE)),
                    ranges_.size(), trimmed,
                    Math.round(100.0 * rangeCovered / (SIZE * (double) SIZE)));
        } catch (RuntimeException e) {
            if (!reportedFailure) {
                reportedFailure = true;
                FornaxMod.LOGGER.error("[Fornax] Could not build the sprite bounds grid -- terrain"
                        + " renders without parallax this session.", e);
            }
        } finally {
            MemoryUtil.memFree(pixels);
            MemoryUtil.memFree(ranges);
        }
    }

    /**
     * The first grid cell a normalised edge falls in, and the last -- the ONE mapping from atlas UV
     * to cell, shared by both grids.
     *
     * <p>Shared deliberately rather than written twice. The two grids are read by a shader with a
     * single {@code texelFetch} coordinate and are only meaningful together: a fragment takes its
     * sprite rectangle from one and that same sprite's height range from the other. When the two
     * loops each did their own conversion they were free to disagree, and they did -- the range loop
     * converted from the SIDECAR atlas's texels while dividing by the BLOCK atlas's width, putting
     * every range at half its true position. Bounds resolved, ranges did not, and the shader's
     * fallback to the raw height made it look like a pack with no height data.
     *
     * <p>There is no atlas size in either signature any more, which is the point: the only unit that
     * survives a half-resolution sidecar is the normalised one, so the mismatch has nowhere to live.
     */
    static int firstCell(float uv) {
        // Clamped at BOTH ends, so this is the same total function the shader's
        // `texelFetch(grid, ivec2(v_TexCoord * SIZE))` is. An unclamped floor returns SIZE at uv 1.0
        // -- harmless in the write loops, which simply never run, but it means the first cell a
        // sprite claims and the cell a fragment on that same edge fetches can disagree, and that
        // disagreement is precisely the class of defect this pair of methods was extracted to make
        // impossible to have twice.
        return Math.min(SIZE - 1, Math.max(0, (int) Math.floor(uv * SIZE)));
    }

    static int lastCell(float uv) {
        return Math.min(SIZE - 1, (int) Math.ceil(uv * SIZE) - 1);
    }

    /**
     * Lays every recorded height range into {@code ranges}, one RGBA32F cell per grid cell, as
     * {@code (trueMin, trueMax, robustMin, robustMax)} scaled to 0..1.
     *
     * <p>Package-private and taking its buffer rather than reaching for the field, so
     * {@code SpriteBoundsRangeGridTest} can run the real placement headlessly. The grid is the part
     * that was wrong, and it was wrong in a way no arithmetic test upstream could see: the
     * percentiles it carries were exact, and they were written to the wrong cells.
     */
    static void writeRanges(ByteBuffer ranges, java.util.List<dev.icehunter.fornax.atlas.SpriteHeightRanges.Range> all) {
        for (var range : all) {
            int rx0 = firstCell(range.u0());
            int rx1 = lastCell(range.u1());
            int ry0 = firstCell(range.v0());
            int ry1 = lastCell(range.v1());
            for (int y = ry0; y <= ry1; y++) {
                for (int x = rx0; x <= rx1; x++) {
                    int offset = (y * SIZE + x) * 4 * Float.BYTES;
                    ranges.putFloat(offset, range.minAlpha() / 255.0f);
                    ranges.putFloat(offset + 4, range.maxAlpha() / 255.0f);
                    ranges.putFloat(offset + 8, range.robustMinAlpha() / 255.0f);
                    ranges.putFloat(offset + 12, range.robustMaxAlpha() / 255.0f);
                }
            }
        }
    }

    /** Drops the GPU resources, for pack teardown and device loss. */
    public static synchronized void destroy() {
        if (texture != null) {
            texture.close();
        }
        if (rangeTexture != null) {
            rangeTexture.close();
        }
        texture = null;
        view = null;
        rangeTexture = null;
        rangeView = null;
        built = false;
    }
}

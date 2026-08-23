package dev.icehunter.fornax.pipeline;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.jspecify.annotations.Nullable;

/**
 * Per-session holder for the vanilla celestials atlas ({@code Sheets.CELESTIAL_SHEET} --
 * {@code minecraft:textures/atlas/celestials.png}) GPU texture/view plus the nine sprite UV rects
 * the graph's resolve pass needs: the sun sprite and all eight {@code MoonPhase} sprites (vanilla
 * index order 0-7 = {@code full_moon, waning_gibbous, third_quarter, waning_crescent, new_moon,
 * waxing_crescent, first_quarter, waxing_gibbous} -- see {@code MoonPhase.index()}/{@code
 * getSerializedName()}). 26.2 has no single moon sheet sliced by cell math -- each phase is its own
 * atlas sprite (`minecraft:moon/<serializedName>`), so this holder stores nine independent rects,
 * not one rect plus an index formula.
 *
 * <p>Committed by {@code TextureAtlasCelestialHookMixin} at the {@code TextureAtlas#upload} RETURN
 * hook, gated on the celestials atlas's own {@code location} field -- every resource reload that
 * re-uploads the atlas fires the hook again and this holder is simply overwritten in place
 * (idempotent capture), mirroring how {@code NormalMapAtlas}/the material-map atlas consumers
 * handle re-upload: no separate invalidation step, the next upload's capture is authoritative. This
 * class does not own the GPU texture/view it stores (vanilla's {@code TextureAtlas} does, via
 * {@code AbstractTexture}), so unlike {@code NormalMapAtlas.setInstance} there is nothing to close
 * on replacement -- only the reference is swapped.
 *
 * <p>Garbage-VRAM law: before the first capture (no pack active, or a resource reload has not yet
 * completed), every accessor returns a defined zero value -- {@code {0,0,0,0}} rects and {@code
 * null} views -- never uninitialized data. Render-thread only, like the sibling frame-state holders
 * ({@link SkyFrameState} et al.) -- no cross-thread publication to order.
 */
public final class CelestialSprites {
    private static final float[] ZERO_RECT = {0f, 0f, 0f, 0f};

    @Nullable
    private static GpuTexture texture;
    @Nullable
    private static GpuTextureView textureView;
    private static float[] sunRect = ZERO_RECT;
    private static float[][] moonRects = new float[8][];

    static {
        clear();
    }

    private CelestialSprites() {
    }

    /**
     * Installs {@code texture}/{@code view} plus the sun rect and all eight moon-phase rects,
     * captured by {@code TextureAtlasCelestialHookMixin} at the celestials atlas's upload hook.
     * Overwrites any previous capture in place (see class doc on re-upload handling).
     *
     * @param moonRects indexed by {@code MoonPhase.index()} (0-7); must have exactly 8 entries, each
     *                  {@code {u0, v0, u1, v1}}
     */
    public static void capture(@Nullable GpuTexture texture, @Nullable GpuTextureView view,
            float[] sunRect, float[][] moonRects) {
        CelestialSprites.texture = texture;
        CelestialSprites.textureView = view;
        CelestialSprites.sunRect = sunRect;
        CelestialSprites.moonRects = moonRects;
    }

    /** Test-only seam: installs sun/moon rects without a real GPU texture/view (stays null). */
    public static void captureForTest(float[] sunRect, float[][] moonRects) {
        capture(null, null, sunRect, moonRects);
    }

    /** Zero-fills every rect and clears the GPU references -- see the class doc's garbage-VRAM law. */
    public static void clear() {
        texture = null;
        textureView = null;
        sunRect = ZERO_RECT;
        moonRects = new float[8][];
        for (int i = 0; i < 8; i++) {
            moonRects[i] = ZERO_RECT;
        }
    }

    /** The celestials atlas GPU texture, or {@code null} if never captured this session. */
    @Nullable
    public static GpuTexture atlasTexture() {
        return texture;
    }

    /** The celestials atlas GPU texture view, or {@code null} if never captured this session. */
    @Nullable
    public static GpuTextureView atlasView() {
        return textureView;
    }

    /** The sun sprite's {@code {u0, v0, u1, v1}} rect, or all-zero if never captured this session. */
    public static float[] sunRect() {
        return sunRect;
    }

    /**
     * The rect for moon phase {@code phase} (vanilla {@code MoonPhase.index()} order, 0 =
     * {@code full_moon} .. 7 = {@code waxing_gibbous}). Clamped to {@code [0, 7]} rather than
     * throwing -- the render thread must never crash on odd attribute data (e.g. a stray value from
     * a pack's own uniform math).
     */
    public static float[] moonPhaseRect(int phase) {
        int clamped = Math.max(0, Math.min(7, phase));
        return moonRects[clamped];
    }
}

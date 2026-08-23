package dev.icehunter.fornax.pass.taa;

import dev.icehunter.fornax.config.AaMethod;
import dev.icehunter.fornax.config.FornaxConfig;
import dev.icehunter.fornax.config.FornaxSettings;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector2f;

/**
 * The camera jitter sequence, applied to the shared projection matrix once per frame (see {@code
 * GameRendererMixin.fornax$setProjection}) to accumulate extra spatial samples over time for a
 * temporal pass to resolve. Which sequence (if any) is active is keyed off {@link
 * FornaxConfig#get()}'s {@code aaMethod}, not a pack compile option:
 * <ul>
 *   <li>{@code OFF}/{@code SSAA} -- no jitter; every sample already lands at native (or
 *       supersampled) resolution every frame, with nothing to temporally resolve.</li>
 *   <li>{@code TAA} -- the original fixed, deterministic 4-entry rotated-grid sequence.</li>
 *   <li>{@code TAAU} -- a Halton(2,3) low-discrepancy sequence, longer than the 4-tap grid since
 *       upscaling needs more temporal samples to fill in a larger per-frame resolution gap; length
 *       is driven by {@link dev.icehunter.fornax.config.TaauRatio#haltonSequenceLength()}.</li>
 * </ul>
 * Every sequence is cyclic rather than random, so "last frame's jitter offset" is always directly
 * computable from the current frame index, with no separate snapshot/history mechanism needed.
 */
public final class CameraJitter {
    // Quarter-pixel offsets in a 2x2 rotated-grid pattern -- a small, standard TAA jitter magnitude.
    private static final float[][] PIXEL_OFFSETS = {
            {-0.25f, -0.25f},
            {0.25f, -0.25f},
            {-0.25f, 0.25f},
            {0.25f, 0.25f},
    };

    private static volatile int frameIndex = 0;

    /** This frame's projection matrix as captured BEFORE {@code GameRendererMixin.fornax$setProjection}
     * applies any TAA/TAAU jitter -- see {@link #captureUnjitteredProjection}/{@link
     * #currentUnjitteredProjection}. Guarded by the same single-render-thread access every other
     * per-frame camera state here assumes (no volatile needed: written and read from the render
     * thread only, unlike {@link #frameIndex} which {@link #currentOffsetNdc} also reads from wherever
     * {@code Minecraft.getInstance()} is valid). */
    private static final Matrix4f unjitteredProjection = new Matrix4f();

    private CameraJitter() {
    }

    /** Captures {@code projection} BY VALUE before jitter is applied to it -- called unconditionally
     * from {@code GameRendererMixin.fornax$setProjection}, every frame, regardless of whether jitter
     * ends up applied this frame (AA method off, or the reconstruct off-screen swap didn't happen
     * yet). Any consumer that needs an un-jittered inverse-view-projection (e.g. the voxel
     * water-reflection compute kernel's world-space DDA ray -- a sub-pixel jitter cyclically flips
     * which voxel a near-silhouette ray hits, reading as a flash) should read {@link
     * #currentUnjitteredProjection} instead of the shared (possibly jittered) {@code
     * matrices.projection()} every other consumer correctly keeps using (screen-space passes must
     * stay jitter-consistent with the rasterized G-buffer). Copied by value so the caller's later
     * in-place {@code translateLocal} jitter mutation of its own matrix instance never retroactively
     * changes what this returns. */
    public static void captureUnjitteredProjection(Matrix4f projection) {
        unjitteredProjection.set(projection);
    }

    /** This frame's projection matrix with no TAA/TAAU jitter applied -- see {@link
     * #captureUnjitteredProjection}. Returns a fresh copy so callers can freely mutate it (e.g.
     * {@code .mul(modelView).invert()}) without corrupting the cached value for any other reader this
     * same frame. */
    public static Matrix4f currentUnjitteredProjection() {
        return new Matrix4f(unjitteredProjection);
    }

    /**
     * Pure function: the NDC-space jitter offset for an arbitrary frame index, given the physical
     * framebuffer resolution (1 pixel = 2.0 / resolution in NDC units, since NDC spans [-1, 1] across
     * the full resolution). No GPU/device dependency, independently testable. Unchanged by the
     * {@code aaMethod} rework below -- this is specifically the 4-tap {@code TAA} sequence.
     */
    public static Vector2f offsetNdcForFrame(int frameIndex, int framebufferWidth, int framebufferHeight) {
        float[] pixels = PIXEL_OFFSETS[Math.floorMod(frameIndex, PIXEL_OFFSETS.length)];
        return new Vector2f(2.0f * pixels[0] / framebufferWidth, 2.0f * pixels[1] / framebufferHeight);
    }

    /**
     * Pure function: frame {@code frameIndex}'s Halton(2,3) low-discrepancy sample, centered to
     * {@code [-0.5, 0.5]} pixels and scaled to NDC the same way {@link #offsetNdcForFrame} is (2.0 /
     * resolution per pixel). {@code seqLength} sets the cycle length -- the sequence repeats every
     * {@code seqLength} frames, using 1-based Halton indices ({@code 1..seqLength}) so index 0 (which
     * radical-inverts to exactly 0 in both bases, degenerating to no offset at all) is never sampled.
     */
    public static Vector2f haltonNdc(int frameIndex, int seqLength, int framebufferWidth, int framebufferHeight) {
        int haltonIndex = Math.floorMod(frameIndex, seqLength) + 1;
        float x = haltonRadicalInverse(haltonIndex, 2) - 0.5f;
        float y = haltonRadicalInverse(haltonIndex, 3) - 0.5f;
        return new Vector2f(2.0f * x / framebufferWidth, 2.0f * y / framebufferHeight);
    }

    /** Radical inverse of {@code index} in {@code base} -- the low-discrepancy digit-reversal Halton sequences are built from. */
    private static float haltonRadicalInverse(int index, int base) {
        float result = 0.0f;
        float denominator = 1.0f;
        int i = index;
        while (i > 0) {
            denominator *= base;
            result += (i % base) / denominator;
            i /= base;
        }
        return result;
    }

    /**
     * This frame's jitter offset, using the real physical framebuffer size -- getWidth()/getHeight()
     * (NOT getScreenWidth()/getScreenHeight(), which return a separate HiDPI logical/points size).
     */
    public static Vector2f currentOffsetNdc() {
        var window = Minecraft.getInstance().getWindow();
        return offsetForMethod(FornaxConfig.get(), frameIndex, window.getWidth(), window.getHeight());
    }

    /** Last frame's jitter offset -- computed directly from the deterministic sequence, no snapshot needed. */
    public static Vector2f previousOffsetNdc() {
        var window = Minecraft.getInstance().getWindow();
        return offsetForMethod(FornaxConfig.get(), frameIndex - 1, window.getWidth(), window.getHeight());
    }

    /**
     * Dispatches to the sequence {@code settings.aaMethod} selects -- the pure, device-free core
     * {@link #currentOffsetNdc}/{@link #previousOffsetNdc} delegate to, and what {@code
     * CameraJitterTest} exercises directly (no {@code Minecraft.getInstance()} needed).
     */
    static Vector2f offsetForMethod(FornaxSettings settings, int frameIndex, int framebufferWidth, int framebufferHeight) {
        AaMethod method = settings.aaMethod;
        return switch (method) {
            case OFF, SSAA -> new Vector2f(0.0f, 0.0f);
            case TAA -> offsetNdcForFrame(frameIndex, framebufferWidth, framebufferHeight);
            // METALFX reuses TAAU's Halton sequence unchanged: MetalFX wants exactly a jittered
            // low-res input, and the sequence length tied to the ratio's sample density transfers.
            case TAAU, METALFX -> haltonNdc(frameIndex, settings.taauRatio.haltonSequenceLength(), framebufferWidth, framebufferHeight);
        };
    }

    /** Advances to the next frame's jitter offset. Call exactly once per frame. */
    public static void advanceFrame() {
        frameIndex++;
    }

    /**
     * A monotonic per-frame counter for packs (Iris and OptiFine packs call this {@code
     * frameCounter}): animated noise, temporal dither rotation, anything that must vary frame to
     * frame. This class already owns the only exactly-once-per-frame tick in the engine, so the
     * counter rides it rather than introducing a second one that could drift out of step.
     *
     * <p>Wrapped at 720720 following Iris's convention. The number is highly composite -- divisible
     * by every integer up to 16 bar 11 and 13 -- so {@code frameCounter % N} cycles evenly for the
     * small N a shader is likely to pick, with no discontinuity at the wrap. It is also small enough
     * to stay exactly representable as a float, which matters because it reaches shaders through a
     * std140 float lane.
     */
    public static int frameCounter() {
        return Math.floorMod(frameIndex, 720720);
    }
}

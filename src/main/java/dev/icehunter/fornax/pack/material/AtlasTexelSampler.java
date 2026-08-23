package dev.icehunter.fornax.pack.material;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.util.ARGB;

/** Averages a fractional (0..1) sub-rectangle of a real, already-decoded atlas sprite image into a
 * single packed ARGB color -- the CPU-side "what does this face really look like" step for voxel
 * harvesting, reading genuine texel data rather than a single representative pixel. */
public final class AtlasTexelSampler {
    private AtlasTexelSampler() {
    }

    /** {@code u0/v0/u1/v1} are fractional [0,1] bounds within {@code image} (sprite-local, already
     * stripped of atlas padding/position by the caller). Returns a packed {@code 0xAARRGGBB} average,
     * alpha-weighted so texels sampled from atlas padding (typically transparent) don't skew the
     * result -- if every sampled texel is fully transparent, returns transparent black rather than
     * dividing by zero. */
    public static int averageColor(NativeImage image, float u0, float v0, float u1, float v1) {
        int width = image.getWidth();
        int height = image.getHeight();
        int minX = clamp((int) Math.floor(u0 * width), 0, width - 1);
        int maxX = clamp((int) Math.ceil(u1 * width) - 1, 0, width - 1);
        int minY = clamp((int) Math.floor(v0 * height), 0, height - 1);
        int maxY = clamp((int) Math.ceil(v1 * height) - 1, 0, height - 1);

        long sumA = 0;
        long weightedSumR = 0, weightedSumG = 0, weightedSumB = 0;
        int count = 0;
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                int pixel = image.getPixel(x, y);
                int a = ARGB.alpha(pixel);
                weightedSumR += (long) ARGB.red(pixel) * a;
                weightedSumG += (long) ARGB.green(pixel) * a;
                weightedSumB += (long) ARGB.blue(pixel) * a;
                sumA += a;
                count++;
            }
        }
        if (count == 0 || sumA == 0) {
            return 0; // fully transparent (or degenerate) region -- nothing meaningful to report
        }
        int avgA = (int) (sumA / count);
        int avgR = (int) (weightedSumR / sumA);
        int avgG = (int) (weightedSumG / sumA);
        int avgB = (int) (weightedSumB / sumA);
        return ARGB.color(avgA, avgR, avgG, avgB);
    }

    /** Fraction of texels in the given sprite-local UV rect whose alpha is at or above {@code
     * alphaThreshold}, in [0, 1]. This is COVERAGE, not average alpha: for an alpha-cutout texture only
     * texels that survive the same threshold the cutout discard uses actually block light, so an
     * antialiased edge must not contribute partial occlusion. Used by {@link
     * dev.icehunter.fornax.voxel.FoliageDensityResolver} to weight a quad's geometric area by how much
     * of it is really opaque. */
    public static float opaqueFraction(NativeImage image, float u0, float v0, float u1, float v1,
                                        float alphaThreshold) {
        int width = image.getWidth();
        int height = image.getHeight();
        int minX = clamp((int) Math.floor(u0 * width), 0, width - 1);
        int maxX = clamp((int) Math.ceil(u1 * width) - 1, 0, width - 1);
        int minY = clamp((int) Math.floor(v0 * height), 0, height - 1);
        int maxY = clamp((int) Math.ceil(v1 * height) - 1, 0, height - 1);
        // An inverted rect (u0 > u1 or v0 > v1) must not silently produce an empty loop -- collapse it
        // to its start edge instead of returning a false "nothing here" 0.0.
        if (maxX < minX) {
            maxX = minX;
        }
        if (maxY < minY) {
            maxY = minY;
        }

        int threshold = clamp(Math.round(alphaThreshold * 255.0f), 0, 255);
        int opaque = 0, total = 0;
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                int pixel = image.getPixel(x, y);
                if (ARGB.alpha(pixel) >= threshold) {
                    opaque++;
                }
                total++;
            }
        }
        return total == 0 ? 0.0f : (float) opaque / total;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}

package dev.icehunter.fornax.pack.material;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.util.ARGB;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/** Averages a fractional (0..1) sub-rectangle of a real, already-decoded atlas sprite image into a
 * single packed ARGB color -- the CPU-side "what does this face really look like" step for voxel
 * harvesting, reading genuine texel data rather than a single representative pixel.
 *
 * <p>The read is budgeted to {@link #SAMPLES_PER_AXIS} texels an axis, so a 512x sprite costs what
 * a 32x sprite does: the voxel harvest runs this on the render thread for every face of every
 * palette entry of a section, and a full walk of a high-resolution sprite there stalls the frame
 * at every window recenter. Each answer is cached per image and rectangle: a decoded image is
 * immutable, and the map is weak on the image so a re-stitched atlas drops its entries. */
public final class AtlasTexelSampler {
    /**
     * Texels read along each axis of the sampled rectangle, at most. A rectangle of this many texels
     * or fewer is read exactly (every 16x and 32x pack face); a larger one is read at every
     * {@code ceil(extent / 32)}th texel. The mean of 1024 samples of a face is within a level or two
     * of the true mean for the textures a block face carries, and one level of 255 is below what a
     * voxel face colour can show. Each sample is offset within its cell ({@link #cellOffset}) so a
     * texture whose period matches the stride, such as a checkerboard, still averages both phases.
     */
    static final int SAMPLES_PER_AXIS = 32;

    /** Per image, the answers so far: rectangle key to packed colour (averageColor) or to the
     * float bits of a fraction (opaqueFraction, keyed with its threshold). Weak on the image: every
     * atlas stitch decodes a new image object, and the old one's entries go with it. Read from the
     * render thread and the harvest workers. */
    private static final Map<NativeImage, ConcurrentHashMap<Long, Integer>> CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

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
        long key = rectKey(minX, minY, maxX, maxY, 0);
        ConcurrentHashMap<Long, Integer> answers = answersFor(image);
        Integer cached = answers.get(key);
        if (cached != null) {
            return cached;
        }
        int strideX = sampleStride(maxX - minX + 1);
        int strideY = sampleStride(maxY - minY + 1);
        long sumA = 0;
        long weightedSumR = 0, weightedSumG = 0, weightedSumB = 0;
        int count = 0;
        for (int y = minY, row = 0; y <= maxY; y += strideY, row++) {
            for (int x = minX, column = 0; x <= maxX; x += strideX, column++) {
                int pixel = image.getPixel(Math.min(x + cellOffset(row, column, strideX, false), maxX),
                        Math.min(y + cellOffset(row, column, strideY, true), maxY));
                int a = ARGB.alpha(pixel);
                weightedSumR += (long) ARGB.red(pixel) * a;
                weightedSumG += (long) ARGB.green(pixel) * a;
                weightedSumB += (long) ARGB.blue(pixel) * a;
                sumA += a;
                count++;
            }
        }
        int result;
        if (count == 0 || sumA == 0) {
            result = 0; // fully transparent (or degenerate) region: nothing to report
        } else {
            int avgA = (int) (sumA / count);
            int avgR = (int) (weightedSumR / sumA);
            int avgG = (int) (weightedSumG / sumA);
            int avgB = (int) (weightedSumB / sumA);
            result = ARGB.color(avgA, avgR, avgG, avgB);
        }
        answers.put(key, result);
        return result;
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
        long key = rectKey(minX, minY, maxX, maxY, 1 + threshold);
        ConcurrentHashMap<Long, Integer> answers = answersFor(image);
        Integer cached = answers.get(key);
        if (cached != null) {
            return Float.intBitsToFloat(cached);
        }
        int strideX = sampleStride(maxX - minX + 1);
        int strideY = sampleStride(maxY - minY + 1);
        int opaque = 0, total = 0;
        for (int y = minY, row = 0; y <= maxY; y += strideY, row++) {
            for (int x = minX, column = 0; x <= maxX; x += strideX, column++) {
                int pixel = image.getPixel(Math.min(x + cellOffset(row, column, strideX, false), maxX),
                        Math.min(y + cellOffset(row, column, strideY, true), maxY));
                if (ARGB.alpha(pixel) >= threshold) {
                    opaque++;
                }
                total++;
            }
        }
        float result = total == 0 ? 0.0f : (float) opaque / total;
        answers.put(key, Float.floatToRawIntBits(result));
        return result;
    }

    /** The step between sampled texels along an axis of {@code extent} texels: 1 up to
     * {@link #SAMPLES_PER_AXIS}, then the smallest step that keeps the sample count at or under it. */
    static int sampleStride(int extent) {
        return Math.max(1, (extent + SAMPLES_PER_AXIS - 1) / SAMPLES_PER_AXIS);
    }

    /** Where inside its {@code stride}-wide cell the sample at ({@code row}, {@code column}) is
     * taken, along one axis. The two axes take different bits of one integer hash of the cell: a
     * pair of linear offsets has a fixed parity and reads one phase of a checkerboard only. The
     * multipliers are the 32-bit golden-ratio constant and MurmurHash3's second mixing constant. */
    static int cellOffset(int row, int column, int stride, boolean vertical) {
        if (stride == 1) return 0;
        int h = row * 0x9E3779B1 + column * 0x85EBCA77;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        return Math.floorMod(vertical ? h >>> 8 : h, stride);
    }

    private static ConcurrentHashMap<Long, Integer> answersFor(NativeImage image) {
        synchronized (CACHE) {
            return CACHE.computeIfAbsent(image, k -> new ConcurrentHashMap<>());
        }
    }

    /** Texel bounds fit 14 bits each (an atlas sprite is at most 16384 texels a side); the kind
     * byte separates an average from an opaque fraction at each threshold. */
    private static long rectKey(int minX, int minY, int maxX, int maxY, int kind) {
        return ((long) kind << 56) | ((long) (minX & 0x3FFF) << 42) | ((long) (minY & 0x3FFF) << 28)
                | ((long) (maxX & 0x3FFF) << 14) | (maxY & 0x3FFF);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}

package dev.icehunter.fornax.atlas;

import com.mojang.blaze3d.platform.NativeImage;
import dev.icehunter.fornax.FornaxMod;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Places one measured labPBR sidecar into its rectangle in a sidecar atlas.
 *
 * <p>Shared by {@link NormalMapAtlasReloadListener} and {@link MaterialMapAtlasReloadListener},
 * which had it copied verbatim; the two differ only in which suffix they survey for and what they
 * pre-fill unclaimed space with.
 */
public final class LabPbrSidecarBlitter {
    /** Semantic channel layout used when transport cannot be an exact copy. */
    public enum Filter {
        NORMAL,
        MATERIAL,
        GENERIC
    }

    private LabPbrSidecarBlitter() {
    }

    /**
     * A transform applied to a sidecar's FIRST FRAME in its own resolution, before the resample.
     *
     * <p>It exists because one labPBR channel is CATEGORICAL: the {@code _s} alpha reserves 255 to
     * mean "this sprite authored no emission", and a resampler cannot know that. Averaging a 255
     * sentinel with a real 0..254 magnitude produces a number that is neither -- and the resolution
     * step this atlas takes ({@link PbrSidecarAtlasScale#PBR_ATLAS_LOG2_DIVISOR}) makes that
     * average on almost every texel of a dithered ore. See
     * {@link LabPbrEmissionSentinel#resolve} for what it costs and how it was measured.
     */
    @FunctionalInterface
    public interface SourceTransform {
        /**
         * @param source      the sidecar as the pack shipped it, mutated in place
         * @param frameHeight rows of {@code source} the blit will actually read: one animation
         *                    frame, or the whole image
         */
        void apply(NativeImage source, int frameHeight);
    }

    /**
     * Blits {@code entry}'s sidecar into {@code atlasImage} at ({@code x}, {@code y}), resampling it
     * to exactly {@code width} x {@code height} -- the sprite's normalised slot scaled to this
     * atlas, which is what keeps the layout identical to the block atlas's.
     *
     * <p>Three cases, in the order they are tested:
     *
     * <ol>
     * <li><b>The sidecar is already the slot's size.</b> Exact per-pixel copy. This is the path a
     *     pack whose maps match its colour resolution takes for every sprite, and it is a copy
     *     rather than a 1:1 resample deliberately: the result must be bit-identical to a direct pixel
     *     copy, since a resampler is not obliged to be the identity even at scale 1.</li>
     * <li><b>The owning albedo is animated, and this call was routed here as one.</b> Vanilla's own
     *     animated-texture convention, and NOT a resolution mismatch -- {@code sprite.contents()}
     *     reports one FRAME's size, so a sidecar whose height is a whole number of frame-heights at
     *     its own resolution is an animation. Only the caller-selected frame is placed here; no
     *     animation timing is applied in this call. Which frame that is, and whether this case is even
     *     reached, is decided entirely by the owning albedo's declared animation state (the
     *     {@code animated} parameter) -- never inferred from the sidecar PNG's own shape. A STATIC
     *     owner whose sidecar happens to be strip-shaped does not fall into this case; see the next
     *     one.</li>
     * <li><b>Anything else, including a static owner whose sidecar is strip-shaped.</b> A genuinely
     *     different resolution -- the case this whole round exists for, a 512px map over a 64px
     *     albedo -- resampled to fill the slot, PROVIDED its aspect ratio matches the slot's. When it
     *     does not (the ambiguous case: a static albedo paired with a sidecar shaped like an animation
     *     strip, with no {@code .mcmeta} on the albedo to say so), the sidecar is rejected rather than
     *     guessed at, and the caller's pre-fill stands -- see the {@code @return} below.</li>
     * </ol>
     *
     * <p>No overload of this method defaults {@code filter} to {@link Filter#GENERIC}. GENERIC is a
     * raw 4-channel weighted mean that averages straight across every LabPBR categorical boundary
     * (metal index, the porosity/SSS split, the emission sentinel) -- safe for ordinary colour data,
     * never safe for an {@code _s}/{@code _n} sidecar. A future {@code _s}/{@code _n} caller must
     * name {@link Filter#NORMAL} or {@link Filter#MATERIAL} explicitly rather than inheriting a
     * channel-blind average.
     *
     * @return {@code true} if a sidecar was placed; {@code false} if the entry has none, or reading
     * it failed, leaving the caller's pre-fill in place.
     */
    public static boolean blit(NativeImage atlasImage, LabPbrSidecarSurvey.Entry entry,
                               ResourceManager resourceManager, int x, int y, int width, int height,
                               SourceTransform sourceTransform, Filter filter) {
        return blit(atlasImage, entry, resourceManager, x, y, width, height,
                sourceTransform, filter, 0, 1, false);
    }

    /** Places a row-major animation frame selected by the owning albedo timeline. */
    public static boolean blit(NativeImage atlasImage, LabPbrSidecarSurvey.Entry entry,
                               ResourceManager resourceManager, int x, int y, int width, int height,
                               SourceTransform sourceTransform, Filter filter,
                               int initialFrameIndex, int frameColumns) {
        return blit(atlasImage, entry, resourceManager, x, y, width, height,
                sourceTransform, filter, initialFrameIndex, frameColumns, true);
    }

    /**
     * Places a sidecar using animation layout only when the owning albedo declares animation.
     * Static owners must supply one image with the same aspect ratio as their atlas slot.
     */
    public static boolean blit(NativeImage atlasImage, LabPbrSidecarSurvey.Entry entry,
                               ResourceManager resourceManager, int x, int y, int width, int height,
                               SourceTransform sourceTransform, Filter filter,
                               int initialFrameIndex, int frameColumns, boolean animated) {
        if (entry.id() == null) {
            return false;
        }

        Optional<Resource> resource = resourceManager.getResource(entry.id());
        if (resource.isEmpty()) {
            // Surveyed present, gone by now: a reload raced this build. Neutral is the safe answer.
            return false;
        }

        try (InputStream in = resource.get().open();
             NativeImage source = NativeImage.read(NativeImage.Format.RGBA, in)) {

            int columns = animated ? Math.max(1, frameColumns) : 1;
            if (source.getWidth() % columns != 0) {
                return false;
            }
            int frameWidth = source.getWidth() / columns;
            int frameHeight = animated
                    ? (columns == 1
                            ? firstFrameHeight(frameWidth, source.getHeight(), width, height)
                            : frameHeightForAspect(frameWidth, width, height))
                    : source.getHeight();
            if (frameHeight <= 0 || source.getHeight() % frameHeight != 0) {
                return false;
            }
            if (!animated && frameHeightForAspect(frameWidth, width, height) != frameHeight) {
                return false;
            }

            if (sourceTransform != null) {
                // BEFORE the branch, not inside the resampling arm: the copy arm has to agree with
                // the resample arm about what the atlas holds, or a pack whose maps match its slot
                // size would carry a different meaning from one whose maps do not.
                //
                // frameHeight, not source.getHeight(): the interface contract is "rows the blit will
                // actually read: one animation frame, or the whole image". For an animated multi-frame
                // strip those differ -- passing the whole sheet's height would make a transform (e.g.
                // the emission-sentinel scan below) inspect every frame instead of only the one about
                // to be placed, so a sprite could be reported as carrying authored emission based on a
                // frame this call never selects.
                sourceTransform.apply(source, frameHeight);
            }

            int frameCount = columns * (source.getHeight() / frameHeight);
            if (initialFrameIndex < 0 || initialFrameIndex >= frameCount) {
                return false;
            }
            int selected = initialFrameIndex;
            try (NativeImage frame = new NativeImage(
                         NativeImage.Format.RGBA, frameWidth, frameHeight, false);
                 NativeImage transported = new NativeImage(
                         NativeImage.Format.RGBA, width, height, false)) {
                blitFrame(frame, source, selected, frameWidth, frameHeight, columns,
                        0, 0, frameWidth, frameHeight);
                transport(frame, frameHeight, transported, filter);
                copyInto(atlasImage, transported, x, y, width, height);
            }
            return true;
        } catch (IOException e) {
            FornaxMod.LOGGER.warn("[LabPBR] Failed to read sidecar {}; leaving the neutral value",
                    entry.id(), e);
            return false;
        }
    }

    static int frameHeightForAspect(int frameWidth, int slotWidth, int slotHeight) {
        if (frameWidth <= 0 || slotWidth <= 0 || slotHeight <= 0) {
            return -1;
        }
        long scaled = (long) frameWidth * slotHeight;
        return scaled % slotWidth == 0 && scaled / slotWidth <= Integer.MAX_VALUE
                ? (int) (scaled / slotWidth) : -1;
    }

    /** Copies one row-major frame without filtering or channel conversion. */
    static void blitFrame(NativeImage destination, NativeImage source, int frameIndex,
                          int frameWidth, int frameHeight, int frameColumns,
                          int destinationX, int destinationY, int width, int height) {
        int sourceX = (frameIndex % frameColumns) * frameWidth;
        int sourceY = (frameIndex / frameColumns) * frameHeight;
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                destination.setPixel(destinationX + col, destinationY + row,
                        source.getPixel(sourceX + col, sourceY + row));
            }
        }
    }

    /** Transports one source frame into a destination without treating LabPBR data as colour. */
    static void transport(NativeImage source, int frameHeight, NativeImage destination,
                          Filter filter) {
        int sourceWidth = source.getWidth();
        int sourceHeight = Math.max(1, Math.min(frameHeight, source.getHeight()));
        int destinationWidth = destination.getWidth();
        int destinationHeight = destination.getHeight();

        if (sourceWidth == destinationWidth && sourceHeight == destinationHeight) {
            copyInto(destination, source, 0, 0, sourceWidth, sourceHeight);
            return;
        }

        // Enlarging a sidecar is placement, not reduction. Replicate the selected authored texel
        // byte-for-byte so categorical material codes and valid special normal values (including
        // LabPBR's all-black flat-normal sentinel) survive a mixed-resolution atlas unchanged.
        if (destinationWidth >= sourceWidth && destinationHeight >= sourceHeight) {
            for (int y = 0; y < destinationHeight; y++) {
                int sourceY = Math.min(sourceHeight - 1,
                        (int) ((y + 0.5) * sourceHeight / destinationHeight));
                for (int x = 0; x < destinationWidth; x++) {
                    int sourceX = Math.min(sourceWidth - 1,
                            (int) ((x + 0.5) * sourceWidth / destinationWidth));
                    destination.setPixel(x, y, source.getPixel(sourceX, sourceY));
                }
            }
            return;
        }

        WeightedSamples samples = new WeightedSamples();
        for (int y = 0; y < destinationHeight; y++) {
            AxisRange yr = axisRange(y, destinationHeight, sourceHeight);
            for (int x = 0; x < destinationWidth; x++) {
                AxisRange xr = axisRange(x, destinationWidth, sourceWidth);
                samples.reset();
                destination.setPixel(x, y, reduce(source, xr, yr, filter, samples));
            }
        }
    }

    private static AxisRange axisRange(int destinationCoordinate, int destinationSize,
                                       int sourceSize) {
        if (destinationSize >= sourceSize) {
            int sourceCoordinate = Math.min(sourceSize - 1,
                    (int) ((destinationCoordinate + 0.5) * sourceSize / destinationSize));
            return new AxisRange(sourceCoordinate, sourceCoordinate + 1,
                    sourceCoordinate, sourceCoordinate + 1);
        }
        double start = destinationCoordinate * (double) sourceSize / destinationSize;
        double end = (destinationCoordinate + 1.0) * sourceSize / destinationSize;
        return new AxisRange((int) Math.floor(start), (int) Math.ceil(end), start, end);
    }

    private static int reduce(NativeImage source, AxisRange xr, AxisRange yr, Filter filter,
                              WeightedSamples samples) {
        for (int sy = yr.first(); sy < yr.last(); sy++) {
            double wy = overlap(sy, sy + 1.0, yr.start(), yr.end());
            for (int sx = xr.first(); sx < xr.last(); sx++) {
                double wx = overlap(sx, sx + 1.0, xr.start(), xr.end());
                samples.add(source.getPixel(sx, sy), wx * wy);
            }
        }
        return switch (filter) {
            case NORMAL -> samples.normal();
            case MATERIAL -> samples.material();
            case GENERIC -> samples.generic();
        };
    }

    private static double overlap(double a0, double a1, double b0, double b1) {
        return Math.max(0.0, Math.min(a1, b1) - Math.max(a0, b0));
    }

    private record AxisRange(int first, int last, double start, double end) {
    }

    private static final class WeightedSamples {
        private final double[] greenWeights = new double[256];
        private final int[] touchedGreens = new int[256];
        private final int[] greenEpochs = new int[256];
        private int epoch;
        private int touchedGreenCount;
        private double totalWeight;
        private double rawAlphaSum;
        private double emissionSum;
        private double redSum;
        private double greenSum;
        private double blueSum;
        private double normalX;
        private double normalY;
        private double normalZ;
        private double metalWeight;
        private double metalRedSum;
        private double metalRedWeight;
        private double dielectricRedSum;
        private double dielectricRedWeight;
        private double dielectricGreenSum;
        private double porousWeight;
        private double porousBlueSum;
        private double subsurfaceWeight;
        private double subsurfaceBlueSum;
        private boolean authoredEmission;
        private boolean onlyFlatNormalSentinels;

        void reset() {
            if (++epoch == 0) {
                java.util.Arrays.fill(greenEpochs, 0);
                epoch = 1;
            }
            touchedGreenCount = 0;
            totalWeight = 0.0;
            rawAlphaSum = 0.0;
            emissionSum = 0.0;
            redSum = 0.0;
            greenSum = 0.0;
            blueSum = 0.0;
            normalX = 0.0;
            normalY = 0.0;
            normalZ = 0.0;
            metalWeight = 0.0;
            metalRedSum = 0.0;
            metalRedWeight = 0.0;
            dielectricRedSum = 0.0;
            dielectricRedWeight = 0.0;
            dielectricGreenSum = 0.0;
            porousWeight = 0.0;
            porousBlueSum = 0.0;
            subsurfaceWeight = 0.0;
            subsurfaceBlueSum = 0.0;
            authoredEmission = false;
            onlyFlatNormalSentinels = true;
        }

        void add(int argb, double weight) {
            if (weight <= 0.0) {
                return;
            }
            int alpha = channel(argb, 24);
            int red = channel(argb, 16);
            int green = channel(argb, 8);
            int blue = channel(argb, 0);
            totalWeight += weight;
            rawAlphaSum += alpha * weight;
            redSum += red * weight;
            greenSum += green * weight;
            blueSum += blue * weight;
            if (greenEpochs[green] != epoch) {
                greenEpochs[green] = epoch;
                greenWeights[green] = 0.0;
                touchedGreens[touchedGreenCount++] = green;
            }
            greenWeights[green] += weight;
            if (alpha != LabPbrEmissionSentinel.UNAUTHORED) {
                authoredEmission = true;
                emissionSum += alpha * weight;
            }
            if (green >= LabPbrMaterialReduction.METAL_MIN) {
                metalWeight += weight;
                metalRedSum += red * weight;
                metalRedWeight += weight;
            } else {
                dielectricRedSum += red * weight;
                dielectricRedWeight += weight;
                dielectricGreenSum += green * weight;
            }
            if (blue <= LabPbrMaterialReduction.POROSITY_MAX) {
                porousWeight += weight;
                porousBlueSum += blue * weight;
            } else {
                subsurfaceWeight += weight;
                subsurfaceBlueSum += blue * weight;
            }

            if (flatNormalSentinel(argb)) {
                normalZ += weight;
            } else {
                onlyFlatNormalSentinels = false;
                double nx = red * (2.0 / 255.0) - 1.0;
                double ny = green * (2.0 / 255.0) - 1.0;
                normalX += nx * weight;
                normalY += ny * weight;
                normalZ += Math.sqrt(Math.max(0.0, 1.0 - nx * nx - ny * ny)) * weight;
            }
        }

        int normal() {
            if (onlyFlatNormalSentinels) {
                return argb(rounded(rawAlphaSum / totalWeight), 0, 0, 0);
            }
            double length = Math.sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ);
            double nx = length > 0.0 ? normalX / length : 0.0;
            double ny = length > 0.0 ? normalY / length : 0.0;
            return argb(rounded(rawAlphaSum / totalWeight), encodeNormal(nx), encodeNormal(ny),
                    rounded(blueSum / totalWeight));
        }

        int material() {
            boolean metalWins = metalWeight * 2.0 >= totalWeight;
            int green = metalWins
                    ? weightedMetalMode()
                    : rounded(dielectricGreenSum / dielectricRedWeight);
            int red = weightedMeanByGreenClass(metalWins);

            boolean porousWins = porousWeight * 2.0 >= totalWeight;
            int blue = porousWins
                    ? rounded(porousBlueSum / porousWeight)
                    : rounded(subsurfaceBlueSum / subsurfaceWeight);
            int alpha = authoredEmission ? rounded(emissionSum / totalWeight)
                    : LabPbrEmissionSentinel.UNAUTHORED;
            return argb(alpha, red, green, blue);
        }

        int generic() {
            return argb(rounded(rawAlphaSum / totalWeight), rounded(redSum / totalWeight),
                    rounded(greenSum / totalWeight), rounded(blueSum / totalWeight));
        }

        private int weightedMeanByGreenClass(boolean metal) {
            double weight = metal ? metalRedWeight : dielectricRedWeight;
            double sum = metal ? metalRedSum : dielectricRedSum;
            return weight > 0.0 ? rounded(sum / weight) : rounded(redSum / totalWeight);
        }

        private int weightedMetalMode() {
            int best = LabPbrMaterialReduction.METAL_MIN;
            double bestWeight = -1.0;
            for (int index = 0; index < touchedGreenCount; index++) {
                int value = touchedGreens[index];
                if (value >= LabPbrMaterialReduction.METAL_MIN
                        && greenWeights[value] >= bestWeight) {
                    best = value;
                    bestWeight = greenWeights[value];
                }
            }
            return best;
        }
    }

    static int reduceNormal(int p0, int p1, int p2, int p3) {
        // POM height (alpha) is continuous with no LabPBR sentinel, but the standard recommends
        // avoiding the byte value 0 -- it breaks some shaders' POM implementations, with 1 as the
        // suggested floor. A MIXED footprint's average can round down to 0 even when no single input
        // was itself that low (0.5 or the mean rounds to 0), inventing a value nothing here
        // authored -- so the floor applies there. It does NOT apply when all four inputs already
        // agree on exactly 0: that is not an invented value, it is a faithfully reproduced one (e.g.
        // a genuinely all-zero footprint from cleared/uninitialised image memory), and forcing it to 1
        // would rewrite authored-uniform data the same way this fix exists to prevent elsewhere.
        int rawAlphaSum = channel(p0, 24) + channel(p1, 24) + channel(p2, 24) + channel(p3, 24);
        int alpha = rawAlphaSum == 0 ? 0 : Math.max(1, rounded(rawAlphaSum / 4.0));
        if (flatNormalSentinel(p0) && flatNormalSentinel(p1)
                && flatNormalSentinel(p2) && flatNormalSentinel(p3)) {
            return argb(alpha, 0, 0, 0);
        }
        double x0 = normalComponent(p0, 16);
        double y0 = normalComponent(p0, 8);
        double x1 = normalComponent(p1, 16);
        double y1 = normalComponent(p1, 8);
        double x2 = normalComponent(p2, 16);
        double y2 = normalComponent(p2, 8);
        double x3 = normalComponent(p3, 16);
        double y3 = normalComponent(p3, 8);
        double nx = x0 + x1 + x2 + x3;
        double ny = y0 + y1 + y2 + y3;
        double nz = reconstructedNormalZ(p0) + reconstructedNormalZ(p1)
                + reconstructedNormalZ(p2) + reconstructedNormalZ(p3);
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length > 0.0) {
            nx /= length;
            ny /= length;
        }
        return argb(alpha,
                encodeNormal(nx), encodeNormal(ny),
                rounded((channel(p0, 0) + channel(p1, 0)
                        + channel(p2, 0) + channel(p3, 0)) / 4.0));
    }

    private static double normalComponent(int argb, int shift) {
        if (flatNormalSentinel(argb)) {
            return 0.0;
        }
        return channel(argb, shift) * (2.0 / 255.0) - 1.0;
    }

    private static double reconstructedNormalZ(int argb) {
        if (flatNormalSentinel(argb)) {
            return 1.0;
        }
        double x = normalComponent(argb, 16);
        double y = normalComponent(argb, 8);
        return Math.sqrt(Math.max(0.0, 1.0 - x * x - y * y));
    }

    // Package-private: LabPbrAnimatedSidecar.interpolateNormal needs the same sentinel check so
    // interpolation between two flat-fill frames doesn't decode RGB(0,0,0) through the literal
    // (value/255)*2-1 formula, which reads it as x=y=-1 instead of the flat-normal placeholder it is.
    static boolean flatNormalSentinel(int argb) {
        return (argb & 0x00FF_FFFF) == 0;
    }

    private static int encodeNormal(double component) {
        return rounded((Math.max(-1.0, Math.min(1.0, component)) * 0.5 + 0.5) * 255.0);
    }

    private static int rounded(double value) {
        return Math.max(0, Math.min(255, (int) Math.round(value)));
    }

    private static int channel(int argb, int shift) {
        return (argb >>> shift) & 0xFF;
    }

    private static int argb(int alpha, int red, int green, int blue) {
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    /**
     * The height of the source region to take, in the sidecar's own texels: one animation frame if
     * the image is a vertical strip of them, otherwise the whole image.
     *
     * <p>A frame is the slot's aspect ratio at the sidecar's own width, so this works whatever the
     * sidecar's resolution is -- a plain {@code sourceWidth == slotWidth} test would not, since a
     * 512px sidecar over a 64px albedo never equals its slot's width.
     */
    static int firstFrameHeight(int sourceWidth, int sourceHeight, int slotWidth, int slotHeight) {
        if (slotWidth <= 0 || slotHeight <= 0) {
            return sourceHeight;
        }
        long frame = (long) sourceWidth * slotHeight / slotWidth;
        if (frame <= 0 || frame >= sourceHeight) {
            return sourceHeight;
        }
        return sourceHeight % frame == 0 ? (int) frame : sourceHeight;
    }

    /**
     * Copies a source image into {@code atlasImage} at ({@code dstX}, {@code dstY}). Uses per-pixel
     * ARGB copy so it is independent of either image's internal channel ordering.
     */
    private static void copyInto(NativeImage atlasImage, NativeImage source, int dstX, int dstY,
                                 int width, int height) {
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                atlasImage.setPixel(dstX + col, dstY + row, source.getPixel(col, row));
            }
        }
    }
}

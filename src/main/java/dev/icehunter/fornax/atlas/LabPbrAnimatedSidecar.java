package dev.icehunter.fornax.atlas;

import com.mojang.blaze3d.platform.NativeImage;
import dev.icehunter.fornax.FornaxMod;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Retains semantically transported frames for one animated LabPBR sidecar. */
final class LabPbrAnimatedSidecar implements AutoCloseable {
    record Rect(int x, int y, int width, int height) {
        Rect {
            if (x < 0 || y < 0 || width <= 0 || height <= 0) {
                throw new IllegalArgumentException("animation rectangle must be positive");
            }
        }

        Rect atMip(int level) {
            return new Rect(x >> level, y >> level,
                    Math.max(1, width >> level), Math.max(1, height >> level));
        }
    }

    record FrameLayout(int width, int height, int columns) {
        FrameLayout {
            if (width <= 0 || height <= 0 || columns <= 0) {
                throw new IllegalArgumentException("frame layout must be positive");
            }
        }

        static FrameLayout fromSheet(NativeImage source, Rect contentRect, int columns) {
            if (source.getWidth() % columns != 0) {
                throw new IllegalArgumentException("sidecar width does not match albedo frame columns");
            }
            int width = source.getWidth() / columns;
            int height = LabPbrSidecarBlitter.frameHeightForAspect(
                    width, contentRect.width(), contentRect.height());
            if (height <= 0 || source.getHeight() % height != 0) {
                throw new IllegalArgumentException("sidecar frame does not preserve albedo aspect ratio");
            }
            return new FrameLayout(width, height, columns);
        }

        int frameCount(NativeImage source) {
            return columns * (source.getHeight() / height);
        }
    }

    @FunctionalInterface
    interface Uploader {
        void upload(int level, int x, int y, NativeImage pixels);
    }

    private final LabPbrAnimationState state;
    private final Rect contentRect;
    private final Rect uploadRect;
    private final LabPbrSidecarBlitter.Filter filter;
    private final Map<Integer, NativeImage[]> frames;
    private final NativeImage[] interpolationScratch;
    private int uploadMipLevels;
    private boolean closed;

    private LabPbrAnimatedSidecar(LabPbrAnimationState state, Rect contentRect, Rect uploadRect,
                                  LabPbrSidecarBlitter.Filter filter,
                                  Map<Integer, NativeImage[]> frames,
                                  NativeImage[] interpolationScratch) {
        this.state = state;
        this.contentRect = contentRect;
        this.uploadRect = uploadRect;
        this.filter = filter;
        this.frames = frames;
        this.interpolationScratch = interpolationScratch;
        this.uploadMipLevels = interpolationScratch.length;
    }

    @Nullable
    static LabPbrAnimatedSidecar load(LabPbrSidecarSurvey.Entry entry,
                                      ResourceManager resources,
                                      @Nullable LabPbrAnimationMetadata metadata,
                                      Rect contentRect, int padding, int mipLevels,
                                      LabPbrSidecarBlitter.Filter filter) {
        if (entry.id() == null || metadata == null) {
            return null;
        }
        java.util.Optional<Resource> resource = resources.getResource(entry.id());
        if (resource.isEmpty()) {
            return null;
        }
        try (InputStream input = resource.get().open();
             NativeImage source = NativeImage.read(NativeImage.Format.RGBA, input)) {
            FrameLayout layout = FrameLayout.fromSheet(source, contentRect, metadata.frameColumns());
            return prepare(source, layout, metadata.frames(), metadata.interpolate(),
                    contentRect, padding, mipLevels, filter);
        } catch (IOException | IllegalArgumentException failure) {
            // ONE warn line naming the sidecar (whose path already names the sprite it rides
            // along with -- e.g. iron_block_s.png for iron_block) with the reason inline -- not
            // the exception object, which would make SLF4J append the full stack trace and read
            // like an uncaught crash (this is fully caught; a malformed sidecar degrading to
            // neutral is expected, not exceptional). Full detail stays available at debug level
            // for whoever needs to see exactly where it was thrown from.
            FornaxMod.LOGGER.warn("[LabPBR] Sidecar {} failed validation ({}); leaving it neutral",
                    entry.id(), failure.getMessage());
            FornaxMod.LOGGER.debug("[LabPBR] Animated sidecar validation failure detail for {}",
                    entry.id(), failure);
            return null;
        }
    }

    static LabPbrAnimatedSidecar prepare(NativeImage source, FrameLayout layout,
                                         List<LabPbrAnimationState.Frame> timeline,
                                         boolean interpolate, Rect contentRect, int padding,
                                         int mipLevels, LabPbrSidecarBlitter.Filter filter) {
        Rect uploadRect = new Rect(contentRect.x() - padding, contentRect.y() - padding,
                contentRect.width() + padding * 2, contentRect.height() + padding * 2);
        int available = layout.frameCount(source);
        Set<Integer> unique = new LinkedHashSet<>();
        for (LabPbrAnimationState.Frame frame : timeline) {
            if (frame.index() >= available) {
                throw new IllegalArgumentException("sidecar is missing animation frame " + frame.index());
            }
            unique.add(frame.index());
        }

        Map<Integer, NativeImage[]> prepared = new LinkedHashMap<>();
        NativeImage[] scratch = new NativeImage[mipLevels];
        try {
            for (int frame : unique) {
                prepared.put(frame, prepareFrame(source, frame, layout, contentRect,
                        uploadRect, mipLevels, filter));
            }
            for (int level = 0; level < mipLevels; level++) {
                Rect rect = uploadRect.atMip(level);
                scratch[level] = new NativeImage(NativeImage.Format.RGBA,
                        rect.width(), rect.height(), false);
            }
            return new LabPbrAnimatedSidecar(new LabPbrAnimationState(timeline, interpolate),
                    contentRect, uploadRect, filter, prepared, scratch);
        } catch (RuntimeException failure) {
            closeFrames(prepared);
            closeImages(scratch);
            throw failure;
        }
    }

    Rect uploadRectAtMip(int level) {
        return this.uploadRect.atMip(level);
    }

    int preparedMipLevels() {
        return this.interpolationScratch.length;
    }

    /** Adds every unique referenced frame's transported level-zero height values. */
    void accumulateLevelZeroAlphaHistogram(int[] histogram) {
        if (histogram.length < 256) {
            throw new IllegalArgumentException("height histogram requires 256 bins");
        }
        int left = this.contentRect.x() - this.uploadRect.x();
        int top = this.contentRect.y() - this.uploadRect.y();
        for (NativeImage[] levels : this.frames.values()) {
            NativeImage levelZero = levels[0];
            for (int y = 0; y < this.contentRect.height(); y++) {
                for (int x = 0; x < this.contentRect.width(); x++) {
                    histogram[(levelZero.getPixel(left + x, top + y) >>> 24) & 0xFF]++;
                }
            }
        }
    }

    void stopBeforeMip(int level) {
        this.uploadMipLevels = Math.min(this.uploadMipLevels, Math.max(0, level));
    }

    int tick(Uploader uploader) {
        if (this.closed) {
            return 0;
        }
        LabPbrAnimationState.Sample sample = this.state.tick();
        if (!sample.upload()) {
            return 0;
        }
        NativeImage[] current = this.frames.get(sample.currentFrameIndex());
        NativeImage[] next = this.frames.get(sample.nextFrameIndex());
        boolean blend = sample.blend() > 0.0f
                && sample.currentFrameIndex() != sample.nextFrameIndex();
        for (int level = 0; level < this.uploadMipLevels; level++) {
            NativeImage pixels = current[level];
            if (blend) {
                pixels = this.interpolationScratch[level];
                interpolate(current[level], next[level], pixels, sample.blend(), this.filter);
            }
            Rect target = this.uploadRect.atMip(level);
            uploader.upload(level, target.x(), target.y(), pixels);
        }
        return this.uploadMipLevels;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        closeFrames(this.frames);
        closeImages(this.interpolationScratch);
    }

    private static NativeImage[] prepareFrame(NativeImage source, int frameIndex,
                                               FrameLayout layout, Rect contentRect,
                                               Rect uploadRect, int mipLevels,
                                               LabPbrSidecarBlitter.Filter filter) {
        NativeImage[] content = new NativeImage[mipLevels];
        try (NativeImage authored = new NativeImage(NativeImage.Format.RGBA,
                layout.width(), layout.height(), false)) {
            LabPbrSidecarBlitter.blitFrame(authored, source, frameIndex,
                    layout.width(), layout.height(), layout.columns(),
                    0, 0, layout.width(), layout.height());
            content[0] = new NativeImage(NativeImage.Format.RGBA,
                    contentRect.width(), contentRect.height(), false);
            LabPbrSidecarBlitter.transport(authored, authored.getHeight(), content[0], filter);
        }
        try {
            for (int level = 1; level < mipLevels; level++) {
                Rect levelRect = contentRect.atMip(level);
                content[level] = new NativeImage(NativeImage.Format.RGBA,
                        levelRect.width(), levelRect.height(), false);
                reduce(content[level - 1], content[level], filter);
            }
            NativeImage[] padded = padLevels(content, contentRect, uploadRect);
            closeImages(content);
            return padded;
        } catch (RuntimeException failure) {
            closeImages(content);
            throw failure;
        }
    }

    private static NativeImage[] padLevels(NativeImage[] content, Rect contentRect, Rect uploadRect) {
        NativeImage[] padded = new NativeImage[content.length];
        try {
            for (int level = 0; level < content.length; level++) {
                Rect levelContent = contentRect.atMip(level);
                Rect levelUpload = uploadRect.atMip(level);
                NativeImage pixels = new NativeImage(NativeImage.Format.RGBA,
                        levelUpload.width(), levelUpload.height(), false);
                int left = Math.max(0, Math.min(levelContent.x() - levelUpload.x(),
                        levelUpload.width() - content[level].getWidth()));
                int top = Math.max(0, Math.min(levelContent.y() - levelUpload.y(),
                        levelUpload.height() - content[level].getHeight()));
                for (int y = 0; y < pixels.getHeight(); y++) {
                    int sy = Math.max(0, Math.min(content[level].getHeight() - 1, y - top));
                    for (int x = 0; x < pixels.getWidth(); x++) {
                        int sx = Math.max(0, Math.min(content[level].getWidth() - 1, x - left));
                        pixels.setPixel(x, y, content[level].getPixel(sx, sy));
                    }
                }
                padded[level] = pixels;
            }
            return padded;
        } catch (RuntimeException failure) {
            closeImages(padded);
            throw failure;
        }
    }

    private static void reduce(NativeImage source, NativeImage destination,
                               LabPbrSidecarBlitter.Filter filter) {
        int maxX = source.getWidth() - 1;
        int maxY = source.getHeight() - 1;
        for (int y = 0; y < destination.getHeight(); y++) {
            int y0 = Math.min(y * 2, maxY);
            int y1 = Math.min(y * 2 + 1, maxY);
            for (int x = 0; x < destination.getWidth(); x++) {
                int x0 = Math.min(x * 2, maxX);
                int x1 = Math.min(x * 2 + 1, maxX);
                int p0 = source.getPixel(x0, y0);
                int p1 = source.getPixel(x1, y0);
                int p2 = source.getPixel(x0, y1);
                int p3 = source.getPixel(x1, y1);
                int pixel = switch (filter) {
                    case NORMAL -> LabPbrSidecarBlitter.reduceNormal(p0, p1, p2, p3);
                    case MATERIAL -> LabPbrMaterialReduction.reduce(p0, p1, p2, p3);
                    case GENERIC -> average(p0, p1, p2, p3);
                };
                destination.setPixel(x, y, pixel);
            }
        }
    }

    static int interpolatePixel(int a, int b, float amount,
                                LabPbrSidecarBlitter.Filter filter) {
        return switch (filter) {
            case NORMAL -> interpolateNormal(a, b, amount);
            case MATERIAL -> interpolateMaterial(a, b, amount);
            case GENERIC -> interpolateGeneric(a, b, amount);
        };
    }

    private static void interpolate(NativeImage current, NativeImage next, NativeImage destination,
                                    float amount, LabPbrSidecarBlitter.Filter filter) {
        for (int y = 0; y < destination.getHeight(); y++) {
            for (int x = 0; x < destination.getWidth(); x++) {
                destination.setPixel(x, y, interpolatePixel(
                        current.getPixel(x, y), next.getPixel(x, y), amount, filter));
            }
        }
    }

    private static int interpolateNormal(int a, int b, float amount) {
        // Interpolation must be the identity whenever both frames already agree -- not just at
        // amount 0/1, but for any amount, since there is nothing to blend toward. Checking this
        // first also sidesteps flatNormalSentinel(a,a): renormalising two equal decoded vectors can
        // still round-trip to a different byte than the input wherever the decode isn't already its
        // own inverse (see below), so equality on the raw pixel is the only exact guarantee.
        if (a == b) {
            return a;
        }
        double ax = normalComponent(a, 16);
        double ay = normalComponent(a, 8);
        double bx = normalComponent(b, 16);
        double by = normalComponent(b, 8);
        double x = lerp(ax, bx, amount);
        double y = lerp(ay, by, amount);
        double z = lerp(reconstructedZ(a, ax, ay), reconstructedZ(b, bx, by), amount);
        double length = Math.sqrt(x * x + y * y + z * z);
        if (length > 0.0) {
            x /= length;
            y /= length;
        }
        return argb(rounded(lerp(channel(a, 24), channel(b, 24), amount)),
                encodeNormal(x), encodeNormal(y),
                rounded(lerp(channel(a, 0), channel(b, 0), amount)));
    }

    // RGB(0,0,0) is Fornax's flat-normal placeholder (see LabPbrSidecarBlitter.flatNormalSentinel),
    // not an authored direction -- decoding it through the literal (value/255)*2-1 formula below
    // reads it as x=y=-1, a steep near-grazing normal the sentinel never meant. Mirrors
    // LabPbrSidecarBlitter.normalComponent/reconstructedNormalZ so a mip-reduced frame and an
    // interpolated tick treat the same sentinel the same way.
    private static double normalComponent(int pixel, int shift) {
        if (LabPbrSidecarBlitter.flatNormalSentinel(pixel)) {
            return 0.0;
        }
        return channel(pixel, shift) * (2.0 / 255.0) - 1.0;
    }

    private static double reconstructedZ(int pixel, double x, double y) {
        if (LabPbrSidecarBlitter.flatNormalSentinel(pixel)) {
            return 1.0;
        }
        return Math.sqrt(Math.max(0.0, 1.0 - x * x - y * y));
    }

    private static int interpolateMaterial(int a, int b, float amount) {
        int ga = channel(a, 8);
        int gb = channel(b, 8);
        int ba = channel(a, 0);
        int bb = channel(b, 0);
        boolean bothDielectric = ga < LabPbrMaterialReduction.METAL_MIN
                && gb < LabPbrMaterialReduction.METAL_MIN;
        boolean sameGreenClass = (ga < LabPbrMaterialReduction.METAL_MIN)
                == (gb < LabPbrMaterialReduction.METAL_MIN);
        int green = bothDielectric ? rounded(lerp(ga, gb, amount)) : endpoint(ga, gb, amount);
        int red = sameGreenClass
                ? rounded(lerp(channel(a, 16), channel(b, 16), amount))
                : endpoint(channel(a, 16), channel(b, 16), amount);
        boolean sameBlueClass = (ba <= LabPbrMaterialReduction.POROSITY_MAX)
                == (bb <= LabPbrMaterialReduction.POROSITY_MAX);
        int blue = sameBlueClass ? rounded(lerp(ba, bb, amount)) : endpoint(ba, bb, amount);
        int aa = channel(a, 24);
        int ab = channel(b, 24);
        int alpha = aa == LabPbrEmissionSentinel.UNAUTHORED
                && ab == LabPbrEmissionSentinel.UNAUTHORED
                ? LabPbrEmissionSentinel.UNAUTHORED
                : Math.min(254, rounded(lerp(
                        aa == LabPbrEmissionSentinel.UNAUTHORED ? 0 : aa,
                        ab == LabPbrEmissionSentinel.UNAUTHORED ? 0 : ab, amount)));
        return argb(alpha, red, green, blue);
    }

    private static int interpolateGeneric(int a, int b, float amount) {
        return argb(rounded(lerp(channel(a, 24), channel(b, 24), amount)),
                rounded(lerp(channel(a, 16), channel(b, 16), amount)),
                rounded(lerp(channel(a, 8), channel(b, 8), amount)),
                rounded(lerp(channel(a, 0), channel(b, 0), amount)));
    }

    private static int endpoint(int a, int b, float amount) {
        return amount < 0.5f ? a : b;
    }

    private static int average(int a, int b, int c, int d) {
        return argb((channel(a, 24) + channel(b, 24) + channel(c, 24) + channel(d, 24) + 2) / 4,
                (channel(a, 16) + channel(b, 16) + channel(c, 16) + channel(d, 16) + 2) / 4,
                (channel(a, 8) + channel(b, 8) + channel(c, 8) + channel(d, 8) + 2) / 4,
                (channel(a, 0) + channel(b, 0) + channel(c, 0) + channel(d, 0) + 2) / 4);
    }

    private static int encodeNormal(double component) {
        return rounded((Math.max(-1.0, Math.min(1.0, component)) * 0.5 + 0.5) * 255.0);
    }

    private static double lerp(double a, double b, double amount) {
        return a + (b - a) * amount;
    }

    private static int rounded(double value) {
        return Math.max(0, Math.min(255, (int) Math.round(value)));
    }

    private static int channel(int pixel, int shift) {
        return (pixel >>> shift) & 0xFF;
    }

    private static int argb(int a, int r, int g, int b) {
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static void closeFrames(Map<Integer, NativeImage[]> frames) {
        for (NativeImage[] images : frames.values()) {
            closeImages(images);
        }
    }

    private static void closeImages(NativeImage[] images) {
        for (NativeImage image : images) {
            if (image != null && !image.isClosed()) {
                image.close();
            }
        }
    }
}

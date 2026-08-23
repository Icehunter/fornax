package dev.icehunter.fornax.atlas;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class LabPbrAnimatedSidecarTest {
    @TempDir
    Path temp;

    @Test
    void rowMajorFramesFollowTheAlbedoTimelineAndUpdateEverySafeMip() {
        try (NativeImage sheet = new NativeImage(NativeImage.Format.RGBA, 4, 4, false)) {
            sheet.fillRect(0, 0, 2, 2, normal(128, 128, 10, 10));
            sheet.fillRect(2, 0, 2, 2, normal(140, 128, 20, 20));
            sheet.fillRect(0, 2, 2, 2, normal(150, 128, 30, 30));
            sheet.fillRect(2, 2, 2, 2, normal(160, 128, 40, 40));

            try (LabPbrAnimatedSidecar sidecar = LabPbrAnimatedSidecar.prepare(
                    sheet, new LabPbrAnimatedSidecar.FrameLayout(2, 2, 2),
                    List.of(new LabPbrAnimationState.Frame(1, 1),
                            new LabPbrAnimationState.Frame(3, 1)), false,
                    new LabPbrAnimatedSidecar.Rect(4, 4, 2, 2), 1, 2,
                    LabPbrSidecarBlitter.Filter.NORMAL)) {
                List<Upload> uploads = new ArrayList<>();
                assertEquals(2, sidecar.tick((level, x, y, pixels) ->
                        uploads.add(new Upload(level, x, y, pixels.getPixel(0, 0)))));
                assertEquals(List.of(0, 1), uploads.stream().map(Upload::level).toList());
                assertEquals(normal(160, 128, 40, 40), uploads.getFirst().pixel());
            }
        }
    }

    @Test
    void normalInterpolationRenormalizesDirection() {
        int pixel = LabPbrAnimatedSidecar.interpolatePixel(
                normal(255, 128, 20, 40), normal(128, 255, 220, 240), 0.5f,
                LabPbrSidecarBlitter.Filter.NORMAL);
        double x = channel(pixel, 16) * (2.0 / 255.0) - 1.0;
        double y = channel(pixel, 8) * (2.0 / 255.0) - 1.0;
        double z = Math.sqrt(Math.max(0.0, 1.0 - x * x - y * y));
        assertEquals(1.0, Math.sqrt(x * x + y * y + z * z), 0.02);
        assertEquals(120, channel(pixel, 0), 1);
        assertEquals(140, channel(pixel, 24), 1);
    }

    @Test
    void materialInterpolationKeepsCategoriesAndTreatsMissingEmissionAsZeroCoverage() {
        int dielectricPorous = argb(255, 20, 229, 64);
        int metalSss = argb(200, 220, 230, 65);

        int quarter = LabPbrAnimatedSidecar.interpolatePixel(
                dielectricPorous, metalSss, 0.25f, LabPbrSidecarBlitter.Filter.MATERIAL);
        assertEquals(229, channel(quarter, 8));
        assertEquals(20, channel(quarter, 16),
                "smoothness must use the same dielectric endpoint as the categorical F0 lane");
        assertEquals(64, channel(quarter, 0));
        assertEquals(50, channel(quarter, 24),
                "255 is absence and must contribute zero, not near-maximum emission");

        int half = LabPbrAnimatedSidecar.interpolatePixel(
                dielectricPorous, metalSss, 0.5f, LabPbrSidecarBlitter.Filter.MATERIAL);
        assertEquals(230, channel(half, 8));
        assertEquals(220, channel(half, 16),
                "smoothness must use the same metal endpoint as the categorical F0 lane");
        assertEquals(65, channel(half, 0));
        assertEquals(100, channel(half, 24));
    }

    @Test
    void normalInterpolationIsTheIdentityWheneverBothFramesAgree() {
        // Any equal pair, not just the flat sentinel: interpolation has nothing to blend toward.
        int authored = normal(200, 60, 100, 210);
        assertEquals(authored, LabPbrAnimatedSidecar.interpolatePixel(
                authored, authored, 0.5f, LabPbrSidecarBlitter.Filter.NORMAL));
        assertEquals(authored, LabPbrAnimatedSidecar.interpolatePixel(
                authored, authored, 0.0f, LabPbrSidecarBlitter.Filter.NORMAL));
        assertEquals(authored, LabPbrAnimatedSidecar.interpolatePixel(
                authored, authored, 1.0f, LabPbrSidecarBlitter.Filter.NORMAL));
    }

    @Test
    void flatNormalSentinelSurvivesInterpolationAgainstItself() {
        // Regression for the exact corruption the audit measured: RGB(0,0,0) decoded through the
        // literal (value/255)*2-1 formula reads as x=y=-1, renormalises, and re-encodes to (37,37)
        // even when both animation frames are identically flat. Fornax's flat-normal sentinel must
        // round-trip through interpolation unchanged.
        int flat = argb(255, 0, 0, 0);
        int interpolated = LabPbrAnimatedSidecar.interpolatePixel(
                flat, flat, 0.5f, LabPbrSidecarBlitter.Filter.NORMAL);
        assertEquals(0, channel(interpolated, 16), "R must stay 0, not drift to 37");
        assertEquals(0, channel(interpolated, 8), "G must stay 0, not drift to 37");
    }

    @Test
    void twoMissingEmissionEndpointsRemainMissing() {
        int pixel = LabPbrAnimatedSidecar.interpolatePixel(
                argb(255, 10, 20, 30), argb(255, 40, 50, 60), 0.5f,
                LabPbrSidecarBlitter.Filter.MATERIAL);
        assertEquals(255, channel(pixel, 24));
    }

    @Test
    void changedFrameCarriesItsOwnEdgePadding() {
        try (NativeImage strip = new NativeImage(NativeImage.Format.RGBA, 2, 4, false)) {
            strip.fillRect(0, 0, 2, 2, normal(128, 128, 10, 10));
            strip.fillRect(0, 2, 2, 2, normal(180, 128, 30, 30));
            try (LabPbrAnimatedSidecar sidecar = LabPbrAnimatedSidecar.prepare(
                    strip, new LabPbrAnimatedSidecar.FrameLayout(2, 2, 1),
                    List.of(new LabPbrAnimationState.Frame(0, 1),
                            new LabPbrAnimationState.Frame(1, 1)), false,
                    new LabPbrAnimatedSidecar.Rect(2, 2, 2, 2), 1, 1,
                    LabPbrSidecarBlitter.Filter.NORMAL)) {
                NativeImage[] uploaded = new NativeImage[1];
                sidecar.tick((level, x, y, pixels) -> uploaded[0] = pixels);
                assertEquals(uploaded[0].getPixel(1, 1), uploaded[0].getPixel(0, 0));
            }
        }
    }

    @Test
    void heightHistogramIncludesEveryReferencedAnimationFrame() {
        try (NativeImage strip = new NativeImage(NativeImage.Format.RGBA, 2, 4, false)) {
            strip.fillRect(0, 0, 2, 2, normal(128, 128, 255, 10));
            strip.fillRect(0, 2, 2, 2, normal(128, 128, 255, 200));
            try (LabPbrAnimatedSidecar sidecar = LabPbrAnimatedSidecar.prepare(
                    strip, new LabPbrAnimatedSidecar.FrameLayout(2, 2, 1),
                    List.of(new LabPbrAnimationState.Frame(0, 1),
                            new LabPbrAnimationState.Frame(1, 1)), false,
                    new LabPbrAnimatedSidecar.Rect(4, 4, 2, 2), 1, 1,
                    LabPbrSidecarBlitter.Filter.NORMAL)) {
                int[] histogram = new int[256];
                sidecar.accumulateLevelZeroAlphaHistogram(histogram);

                assertEquals(4, histogram[10]);
                assertEquals(4, histogram[200]);
                assertEquals(8, java.util.Arrays.stream(histogram).sum());
            }
        }
    }

    @Test
    void loadDegradesToNeutralWhenTheSidecarIsMissingAReferencedAnimationFrame() throws IOException {
        // Regression for live evidence: a pack's albedo animation timeline can reference a frame
        // index the pack's own _s/_n sidecar strip does not contain -- the sidecar is simply
        // shorter than the albedo it rides along with. LabPbrAnimatedSidecar.prepare() correctly
        // rejects that as IllegalArgumentException; load() must absorb it here (see its own
        // catch) rather than let it escape into the atlas build.
        Identifier id = Identifier.fromNamespaceAndPath("pack", "textures/block/probe_s.png");
        Path png = temp.resolve("probe_s.png");
        try (NativeImage source = new NativeImage(NativeImage.Format.RGBA, 2, 2, false)) {
            source.fillRect(0, 0, 2, 2, normal(128, 128, 10, 10));
            source.writeToFile(png);
        }

        LabPbrAnimationMetadata metadata = new LabPbrAnimationMetadata(
                List.of(new LabPbrAnimationState.Frame(0, 1), new LabPbrAnimationState.Frame(1, 1)),
                1, false);
        LabPbrSidecarSurvey.Entry entry = new LabPbrSidecarSurvey.Entry(null, id, 2, 2, null);

        LabPbrAnimatedSidecar sidecar = LabPbrAnimatedSidecar.load(
                entry, resources(id, png), metadata,
                new LabPbrAnimatedSidecar.Rect(4, 4, 2, 2), 1, 1,
                LabPbrSidecarBlitter.Filter.NORMAL);

        assertNull(sidecar, "a sidecar missing a referenced frame must degrade to neutral, not throw");
    }

    private static ResourceManager resources(Identifier id, Path path) {
        return new ResourceManager() {
            @Override
            public Optional<Resource> getResource(Identifier lookup) {
                return lookup.equals(id) ? Optional.of(new Resource(
                        (PackResources) null, () -> Files.newInputStream(path))) : Optional.empty();
            }

            @Override
            public Set<String> getNamespaces() {
                return Set.of(id.getNamespace());
            }

            @Override
            public List<Resource> getResourceStack(Identifier lookup) {
                return getResource(lookup).stream().toList();
            }

            @Override
            public Map<Identifier, Resource> listResources(String path, Predicate<Identifier> predicate) {
                return Map.of();
            }

            @Override
            public Map<Identifier, List<Resource>> listResourceStacks(
                    String path, Predicate<Identifier> predicate) {
                return Map.of();
            }

            @Override
            public Stream<PackResources> listPacks() {
                return Stream.empty();
            }
        };
    }

    private record Upload(int level, int x, int y, int pixel) {
    }

    private static int normal(int r, int g, int ao, int height) {
        return argb(height, r, g, ao);
    }

    private static int argb(int a, int r, int g, int b) {
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int channel(int pixel, int shift) {
        return (pixel >>> shift) & 0xFF;
    }
}

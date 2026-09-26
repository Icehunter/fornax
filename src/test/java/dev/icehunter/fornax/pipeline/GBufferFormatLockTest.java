package dev.icehunter.fornax.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Locks the G-buffer colour target formats declared in {@link DeferredGeometryPipelines} and created
 * in {@link GBufferManager} to each other and, for the three colour lanes a pack shader actually
 * decodes as integers, to {@code RGBA8_UNORM}.
 *
 * <p>gMaterial carries the LabPBR {@code _s} categorical bytes. Plague's {@code brdf.glsl} recovers
 * them with {@code int(f0Raw * 255.0 + 0.5)} against the raw sampled float, which only round-trips
 * correctly for an unsigned-normalised 8-bit-per-channel linear target -- an sRGB or floating-point
 * target would silently hand back the wrong byte with no validation error. Plague cannot see this
 * engine-side declaration from its own repo, so the two Java declaration sites here are the only place
 * this guarantee can be held, and they must never drift from each other.
 */
class GBufferFormatLockTest {
    private static final Pattern PIPELINE_FORMAT =
            Pattern.compile("GpuFormat\\.(\\w+),\\s*//\\s*g(\\w+):");
    // Matches a bare GpuFormat.X literal, or one of the three named colour-lane constants
    // (NORMAL_FORMAT, ALBEDO_FORMAT, MATERIAL_FORMAT) at the same call site. PlayerMirrorTargets
    // reuses these constants by reference. DEPTH_FORMAT is left out of this list because depth is
    // not one of the five colour lanes this test compares.
    // CONSTANT_DECL maps a named constant to the literal it stands for.
    private static final Pattern MANAGER_FORMAT = Pattern.compile(
            "(GpuFormat\\.\\w+|NORMAL_FORMAT|ALBEDO_FORMAT|MATERIAL_FORMAT), width, height, 1, 1\\);");
    private static final Pattern CONSTANT_DECL =
            Pattern.compile("GpuFormat (\\w+_FORMAT) = GpuFormat\\.(\\w+);");

    @Test
    void colourTargetsAgreeBetweenDeclarationSitesAndMaterialAlbedoAoStayRgba8Unorm() throws IOException {
        String pipelines = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/pipeline/DeferredGeometryPipelines.java"));
        String manager = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/pipeline/GBufferManager.java"));

        List<String> pipelineFormats = new ArrayList<>();
        List<String> pipelineSlots = new ArrayList<>();
        Matcher pipelineMatcher = PIPELINE_FORMAT.matcher(pipelines);
        while (pipelineMatcher.find()) {
            pipelineFormats.add(pipelineMatcher.group(1));
            pipelineSlots.add(pipelineMatcher.group(2));
        }

        Map<String, String> namedFormatConstants = new HashMap<>();
        Matcher constantMatcher = CONSTANT_DECL.matcher(manager);
        while (constantMatcher.find()) {
            namedFormatConstants.put(constantMatcher.group(1), constantMatcher.group(2));
        }
        List<String> managerFormats = new ArrayList<>();
        Matcher managerMatcher = MANAGER_FORMAT.matcher(manager);
        while (managerMatcher.find()) {
            String token = managerMatcher.group(1);
            String literal = token.startsWith("GpuFormat.") ? token.substring("GpuFormat.".length())
                    : namedFormatConstants.get(token);
            managerFormats.add(literal);
        }

        assertEquals(List.of("Normal", "Albedo", "Material", "Ao", "Motion"), pipelineSlots,
                "GBUFFER_FORMATS slot comments must list Normal, Albedo, Material, Ao, Motion in "
                        + "that order for this test's index-based comparison against GBufferManager "
                        + "to mean anything");
        assertEquals(5, pipelineFormats.size(), "expected exactly 5 GBUFFER_FORMATS entries");
        assertEquals(pipelineFormats, managerFormats,
                "DeferredGeometryPipelines.GBUFFER_FORMATS and GBufferManager.ensureSize's texture "
                        + "creation must declare the same format, in the same order, for every "
                        + "G-buffer colour target -- a drift here is silent at compile time and at "
                        + "validation time, and only shows up as a wrong render");

        assertEquals("RGBA8_UNORM", pipelineFormats.get(1), "gAlbedo");
        assertEquals("RGBA8_UNORM", pipelineFormats.get(2),
                "gMaterial must stay RGBA8_UNORM: Plague's brdf.glsl recovers the LabPBR _s "
                        + "categorical metal-index bytes via int(f0Raw * 255.0 + 0.5), which only "
                        + "round-trips for an unsigned-normalised 8-bit linear target");
        assertEquals("RGBA8_UNORM", pipelineFormats.get(3), "gAo");
        assertFalse(pipelineFormats.contains("SRGB8") || pipelineFormats.contains("SRGB8_ALPHA8"),
                "no G-buffer colour target may be sRGB -- LabPBR thresholds are defined on the raw "
                        + "8-bit value, not a gamma-corrected one");
    }
}

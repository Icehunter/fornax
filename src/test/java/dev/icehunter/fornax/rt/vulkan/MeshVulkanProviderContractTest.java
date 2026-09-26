package dev.icehunter.fornax.rt.vulkan;

import dev.icehunter.fornax.rt.CasterCapture;
import dev.icehunter.fornax.rt.RayQueryKind;
import dev.icehunter.fornax.rt.RayTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-level, where a device would be needed. Pins the wiring that fails silently: which platform
 * installs which tier, how casters reach the tier, and that the tier never publishes a frame it did
 * not trace.
 */
class MeshVulkanProviderContractTest {

    private static final Path SOURCE = Path.of("src/main/java/dev/icehunter/fornax");

    private static String read(String path) throws IOException {
        return Files.readString(SOURCE.resolve(path));
    }

    @Test
    void theTierIsExactMeshAndAnswersBothQueryKinds() {
        MeshVulkanProvider provider = new MeshVulkanProvider();
        assertEquals(RayTier.HARDWARE_MESH, provider.tier());
        assertTrue(provider.answers(RayQueryKind.VISIBILITY));
        assertTrue(provider.answers(RayQueryKind.CLOSEST_HIT));
        assertFalse(provider.answers(null));
        assertTrue(provider instanceof CasterCapture, "the shadow pass hands casters over by this type");
    }

    @Test
    void aQueryIsAnsweredInPlaceAndAQueryOnlyFrameBuildsACameraWindow() throws IOException {
        String provider = read("rt/vulkan/MeshVulkanProvider.java");
        int answer = provider.indexOf("public void answer(BufferQuery query)");
        String body = provider.substring(answer, provider.indexOf("private void ensureStructureForQuery(", answer));
        assertTrue(body.contains("tracer.answerQuery(cmd, query, tier,"), "the tier travels by name, not as a literal");
        assertFalse(provider.contains("encoder.submit();"), "a full submit host-waits");
        assertFalse(provider.contains("fornax$flushPending"),
                "a partial flush host-waits on the transient ring; readers follow in the same stream");
        assertTrue(provider.contains("tracer.retire(encoder::createFence);"),
                "retirement rides the frame's own submission through an encoder fence");
        assertFalse(body.contains("vkCmdCopyBuffer(cmd, query.requestBuffer()"),
                "no copy in: the pack's request buffer is bound directly");
        assertFalse(java.util.regex.Pattern.compile("vkCmdCopyBuffer\\(cmd, [^,]+, query\\.hitBuffer\\(\\)").matcher(body).find(),
                "no copy out: nothing is copied into the pack's hit buffer, the kernel writes it in place");
        assertTrue(body.contains("if ((tracer == null || !tracer.hasStructure()) && !queryBuildAttempted)"));
        assertTrue(body.contains("ensureStructureForQuery(device);"));
        String ensure = provider.substring(provider.indexOf("private void ensureStructureForQuery("));
        assertTrue(ensure.contains("TerrainCasterSnapshot.cameraWindow(TerrainCasterSnapshot.QUERY_REACH_BLOCKS)"),
                "a query-only build selects around the camera, since there is no light volume");
        assertTrue(provider.contains("queryBuildAttempted = false;"), "one build attempt per frame");
    }

    @Test
    void theGraphWaitsForARequestWriterAtBothTheTransferAndComputeStages() throws IOException {
        String runner = read("pack/graph/GraphRunner.java");
        int branch = runner.indexOf("} else if (reader.type() == PassType.RAY_QUERY) {");
        assertTrue(branch > 0);
        String arm = runner.substring(branch, runner.indexOf("}", branch + 60));
        assertTrue(arm.contains("VK_PIPELINE_STAGE_2_TRANSFER_BIT | VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT"),
                "the Metal tier copies the requests at transfer; the Vulkan tier reads them from a compute dispatch");
    }

    @Test
    void withoutADeviceVerdictTheTierIsNotReadyAndSaysWhy() {
        VulkanRtSupport.reset();
        var readiness = new MeshVulkanProvider().readiness();
        assertFalse(readiness.ready());
        assertTrue(readiness.reason().contains("Vulkan device") || readiness.reason().contains("None"), readiness.reason());
    }

    @Test
    void theGraphAlwaysInstallsTheVulkanTierOffMacAndLeavesTheDeviceVerdictToReadiness() throws IOException {
        // A pack rebuild can run before Blaze3D has created the Vulkan device, so an install-time
        // read of the verdict answers "no" once and is never asked again. The tier is installed
        // unconditionally; readiness(), evaluated every frame, carries the verdict.
        String runner = read("pack/graph/GraphRunner.java");
        int metal = runner.indexOf("if (dev.icehunter.fornax.metalfx.objc.Objc.PLATFORM_SUPPORTED) {");
        int install = runner.indexOf("rayProviders.add(new dev.icehunter.fornax.rt.vulkan.MeshVulkanProvider());");
        assertTrue(metal > 0 && install > metal, "the Vulkan tier is the else branch of the Metal platform gate");
        String between = runner.substring(metal, install);
        assertFalse(between.contains("VulkanRtSupport.isAvailable()"),
                "no device or setting gate at install time");
        assertTrue(install < runner.indexOf("RayRouter.install(rayProviders);"));
        String provider = read("rt/vulkan/MeshVulkanProvider.java");
        assertTrue(provider.contains("String reason = VulkanRtSupport.unavailableReason();"),
                "readiness asks the verdict every frame");
    }

    @Test
    void theShadowPassHandsCastersToWhicheverMeshTierIsInstalled() throws IOException {
        String mixin = read("mixin/sodium/SodiumWorldRendererOrchestrationMixin.java");
        assertTrue(mixin.contains("RayRouter.provider(dev.icehunter.fornax.rt.CasterCapture.class)"),
                "by the neutral type, not by naming a backend");
        assertFalse(mixin.contains("MeshMetalProvider.class"));
        assertTrue(read("metalfx/rt/MeshMetalProvider.java").contains("implements dev.icehunter.fornax.rt.CasterCapture"),
                "the Metal tier is found by the same type");
    }

    @Test
    void publishOnlyDeliversAFrameThisTierTraced() throws IOException {
        String provider = read("rt/vulkan/MeshVulkanProvider.java");
        int publish = provider.indexOf("public boolean publishCelestialVisibility()");
        String body = provider.substring(publish, provider.indexOf("public void close()", publish));
        assertTrue(body.contains("if (!traced ||"), "a frame that did not trace publishes nothing");
        assertTrue(body.indexOf("copyImage(") < body.indexOf("TerrainShadowResult.published();"),
                "validity is declared after the copy is recorded, never before");
        int begin = provider.indexOf("public void beginFrame()");
        String frame = provider.substring(begin, provider.indexOf("public void fillCelestialVisibility", begin));
        assertTrue(frame.contains("casters = null;") && frame.contains("traced = false;"),
                "each frame starts with no casters and nothing traced");
    }

    @Test
    void aFailedTraceLatchesUntilPackReloadAndInvalidatesTheImage() throws IOException {
        String provider = read("rt/vulkan/MeshVulkanProvider.java");
        int trace = provider.indexOf("private void traceFrame(");
        String body = provider.substring(trace);
        int catchAll = body.indexOf("catch (RuntimeException e)");
        assertTrue(catchAll > 0);
        String handler = body.substring(catchAll, body.indexOf("}", catchAll));
        assertTrue(handler.contains("failed = true;"));
        assertTrue(handler.contains("TerrainShadowResult.invalidate();"));
        assertTrue(body.indexOf("catch (GpuDeviceLossException | GpuFatalException e)") < catchAll,
                "a dead device is rethrown, never latched as a tier failure");
    }

    @Test
    void anEmptySceneClearsRatherThanTracesAndCertifiesNothing() throws IOException {
        String provider = read("rt/vulkan/MeshVulkanProvider.java");
        assertTrue(provider.contains("MeshVulkanTracer.clearEmpty(cmd, outputImage);"));
        String tracer = read("rt/vulkan/MeshVulkanTracer.java");
        assertTrue(tracer.contains("value.float32(0, 1f).float32(1, 0f).float32(2, 0f).float32(3, 0f);"),
                "R = 1 (miss depth), zero tier, zero validity: the same clear the Metal kernel writes");
    }
}

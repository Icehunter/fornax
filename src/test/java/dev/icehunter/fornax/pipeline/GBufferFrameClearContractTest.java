package dev.icehunter.fornax.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Locks the G-buffer's first-actual-writer clear contract across every deferred draw path. */
class GBufferFrameClearContractTest {
    private static final Path MANAGER = Path.of(
            "src/main/java/dev/icehunter/fornax/pipeline/GBufferManager.java");
    private static final Path RUNNER = Path.of(
            "src/main/java/dev/icehunter/fornax/pack/graph/GraphRunner.java");
    private static final List<Path> WRITERS = List.of(
            Path.of("src/main/java/dev/icehunter/fornax/mixin/sodium/DefaultChunkRendererRenderPassMixin.java"),
            Path.of("src/main/java/dev/icehunter/fornax/mixin/vanilla/PreparedRenderTypeDeferredMixin.java"),
            Path.of("src/main/java/dev/icehunter/fornax/mixin/vanilla/QuadParticleDeferredMixin.java"));

    @Test
    void firstClaimClearsAllAttachmentsAndEveryLaterClaimLoads() {
        GBuffer gbuffer = GBuffer.createForTesting(320, 180);
        GBufferManager.beginFrame();

        assertClears(GBufferManager.claimWriterDescriptor(() -> "cutout first", gbuffer));
        assertLoads(GBufferManager.claimWriterDescriptor(() -> "solid later", gbuffer));

        GBufferManager.beginFrame();
        assertClears(GBufferManager.claimWriterDescriptor(() -> "entity first", gbuffer));
        assertLoads(GBufferManager.claimWriterDescriptor(() -> "particle later", gbuffer));
    }

    @Test
    void concurrentWritersStillProduceExactlyOneClearDescriptor() {
        GBuffer gbuffer = GBuffer.createForTesting(320, 180);
        GBufferManager.beginFrame();

        long clearDescriptors = IntStream.range(0, 64).parallel()
                .mapToObj(index -> GBufferManager.claimWriterDescriptor(
                        () -> "writer " + index, gbuffer))
                .filter(descriptor -> descriptor.colorAttachments().getFirst()
                        .clearValue().isPresent())
                .count();

        assertEquals(1L, clearDescriptors);
    }

    @Test
    void firstActualDeferredWriterClaimsLoadOpClearsAndLaterWritersLoad() throws IOException {
        String manager = Files.readString(MANAGER);

        assertTrue(manager.contains("AtomicBoolean frameWriterClaimed"),
                "one central atomic latch must arbitrate terrain, entity, block-entity and particle writers");
        assertTrue(manager.contains("frameWriterClaimed.compareAndSet(false, true)"),
                "exactly one actual writer may win the frame's attachment clears");
        assertTrue(manager.contains("public static RenderPassDescriptor claimWriterDescriptor("),
                "writers need one central descriptor factory that owns clear-vs-load selection");
        assertTrue(manager.contains("Optional.of(FRAME_CLEAR_COLOR) : Optional.empty()"),
                "the winning writer clears every colour lane and all later writers load");
        assertTrue(manager.contains("OptionalDouble.of(RenderSystem.DEFAULT_DEPTH_CLEAR_VALUE)"
                        + " : OptionalDouble.empty()"),
                "the winning writer clears depth to far and all later writers load");

        for (Path writer : WRITERS) {
            String source = Files.readString(writer);
            assertTrue(source.contains("GBufferManager.claimWriterDescriptor("),
                    writer.getFileName() + " must claim only when its real render pass is created");
            assertFalse(source.contains("withColorAttachment(gbuffer.getNormalView(), Optional.empty())"),
                    writer.getFileName() + " must not bypass the central first-writer lifecycle");
        }
    }

    @Test
    void frameStartResetsTheLatchAndFinishFallsBackOnlyWhenNothingDrew() throws IOException {
        String manager = Files.readString(MANAGER);
        String runner = Files.readString(RUNNER);

        int ensure = runner.indexOf("GBufferManager.ensureSize(width, height);");
        int reset = runner.indexOf("GBufferManager.beginFrame();", ensure);
        assertTrue(ensure >= 0 && reset > ensure,
                "the writer latch must reset after the current frame's G-buffer exists");

        int gbufferReady = runner.indexOf("GBuffer gbuffer = GBufferManager.getInstance();", reset);
        int fallback = runner.indexOf("GBufferManager.clearIfNoWriterForResolve();", gbufferReady);
        int readback = runner.indexOf("GBufferReadbackDiagnostic.maybeLog(gbuffer);", gbufferReady);
        assertTrue(gbufferReady >= 0 && fallback > gbufferReady && fallback < readback,
                "zero-writer fallback must define attachments before readback or resolve consumes them");

        int fallbackMethod = manager.indexOf("public static void clearIfNoWriterForResolve()");
        assertTrue(fallbackMethod >= 0, "missing zero-writer fallback");
        String fallbackBody = manager.substring(fallbackMethod);
        assertTrue(fallbackBody.contains("if (!frameWriterClaimed.compareAndSet(false, true))"),
                "fallback must no-op after any terrain/entity/particle writer already claimed the frame");
        assertTrue(fallbackBody.contains("createRenderPass("),
                "a zero-writer frame still requires one unavoidable clear before resolve");

        int claimMethod = manager.indexOf("public static RenderPassDescriptor claimWriterDescriptor(");
        String commonWriterPath = manager.substring(claimMethod, fallbackMethod);
        assertFalse(commonWriterPath.contains("createRenderPass("),
                "the common rendered-frame path must fold clears into the writer's load ops");
        assertFalse(runner.contains("clearDepthTexture(gbuffer.getDepthTexture()"),
                "the old separate depth clear must not survive beside the first-writer policy");
    }

    private static void assertClears(RenderPassDescriptor descriptor) {
        assertEquals(5, descriptor.colorAttachments().size());
        descriptor.colorAttachments().forEach(attachment -> {
            assertTrue(attachment.clearValue().isPresent());
            assertEquals(0.0f, attachment.clearValue().orElseThrow().x());
            assertEquals(0.0f, attachment.clearValue().orElseThrow().y());
            assertEquals(0.0f, attachment.clearValue().orElseThrow().z());
            assertEquals(0.0f, attachment.clearValue().orElseThrow().w());
        });
        assertTrue(descriptor.depthAttachment().clearValue().isPresent());
        assertEquals(RenderSystem.DEFAULT_DEPTH_CLEAR_VALUE,
                descriptor.depthAttachment().clearValue().orElseThrow());
    }

    private static void assertLoads(RenderPassDescriptor descriptor) {
        assertEquals(5, descriptor.colorAttachments().size());
        descriptor.colorAttachments().forEach(
                attachment -> assertTrue(attachment.clearValue().isEmpty()));
        assertTrue(descriptor.depthAttachment().clearValue().isEmpty());
    }
}

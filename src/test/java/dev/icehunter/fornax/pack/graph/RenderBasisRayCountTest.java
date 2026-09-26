package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.metalfx.rt.RayQueryAbi;
import dev.icehunter.fornax.pack.GraphSpec;
import dev.icehunter.fornax.pack.RayQuerySpec;
import dev.icehunter.fornax.pack.TargetSpec;
import dev.icehunter.fornax.rt.AtlasUvEncoding;
import dev.icehunter.fornax.rt.RayQueryKind;
import dev.icehunter.fornax.rt.RayTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The run-time half of {@code rays = "render"}: the plan sizes the buffers from the render size
 * and the runner reads the ray count back off them, so a window larger than any literal traces
 * every pixel and a frame where a buffer has not yet grown traces only what fits.
 */
class RenderBasisRayCountTest {

    private static final RayQuerySpec PER_PIXEL =
            new RayQuerySpec(RayQueryKind.VISIBILITY, 0, RayTier.NONE, AtlasUvEncoding.PACKED_HALF, true);

    @Test
    void thePlanSizesAPerPixelBufferFromTheRenderSizeAndResizesItWithTheWindow() {
        Map<String, TargetSpec> targets = new LinkedHashMap<>();
        targets.put("sunShadowHits", TargetSpec.buffer("sunShadowHits", null, BufferSize.perRenderPixel(36)));
        GraphSpec graph = new GraphSpec(targets, List.of());
        TargetPlan small = TargetPlan.compute(graph, Map.of(), 1920, 1080);
        TargetPlan large = TargetPlan.compute(graph, Map.of(), 2560, 1369);
        assertEquals(List.of(new TargetPlan.BufferEntry("sunShadowHits", 2073600L * 36)), small.bufferEntries());
        assertEquals(List.of(new TargetPlan.BufferEntry("sunShadowHits", 3504640L * 36)), large.bufferEntries());
    }

    @Test
    void thePlanSizesFromTheRenderSizeNotTheOutputSize() {
        // A TAAU frame renders below its output; a ray a render pixel is what the seed writes.
        Map<String, TargetSpec> targets = new LinkedHashMap<>();
        targets.put("rays", TargetSpec.buffer("rays", null, BufferSize.perRenderPixel(32)));
        GraphSpec graph = new GraphSpec(targets, List.of());
        TargetPlan plan = TargetPlan.compute(graph, Map.of(), 1280, 720, 2560, 1440);
        assertEquals(921600L * 32, plan.bufferEntries().get(0).sizeBytes());
    }

    @Test
    void theLiveCountIsWhatBothBuffersHoldCappedAtTheAbiCeiling() {
        long requests = RayQueryAbi.requestByteSize(1) * 3504640L;
        long hits = RayQueryAbi.hitByteSize(1) * 3504640L;
        assertEquals(3504640, GraphRunner.liveRayCount(PER_PIXEL, requests, hits));
        // One buffer a frame behind the other: only what fits in both is traced, never past an end.
        assertEquals(2073600, GraphRunner.liveRayCount(PER_PIXEL, requests, RayQueryAbi.hitByteSize(1) * 2073600L));
        assertEquals(RayQueryAbi.MAX_RAYS, GraphRunner.liveRayCount(PER_PIXEL,
                RayQueryAbi.requestByteSize(1) * (RayQueryAbi.MAX_RAYS + 5L), RayQueryAbi.hitByteSize(1) * (RayQueryAbi.MAX_RAYS + 5L)));
        assertEquals(0, GraphRunner.liveRayCount(PER_PIXEL, 0, 0));
    }

    @Test
    void aFixedCountIsUsedAsDeclaredWhateverTheBuffersHold() {
        RayQuerySpec fixed = new RayQuerySpec(RayQueryKind.VISIBILITY, 1024, RayTier.NONE);
        assertEquals(1024, GraphRunner.liveRayCount(fixed, 1L << 30, 1L << 30));
    }

    @Test
    void theRunnerTracesTheLiveCountNotTheDeclaredOne() throws IOException {
        String runner = Files.readString(Path.of("src/main/java/dev/icehunter/fornax/pack/graph/GraphRunner.java"));
        int branch = runner.indexOf("case RAY_QUERY -> {");
        String body = runner.substring(branch, runner.indexOf("case COPY -> {", branch));
        assertTrue(body.contains("int rays = liveRayCount(raySpec, requestBuffer.sizeBytes(), hitBuffer.sizeBytes());"));
        assertTrue(body.contains("hitBuffer.vkBuffer(), rays);"), "the clear covers the live count");
        assertTrue(body.contains("rays, p.name(), raySpec.atlasUvEncoding()));"), "the query carries the live count");
        assertTrue(!body.contains("raySpec.rayCount()"), "the declared count is never used at run time");
    }

    @Test
    void theCeilingCoversAFourKWindow() {
        // 3840 x 2160 is 8294400 rays; a render-basis query on a 4K still has to fit under the cap.
        assertTrue(RayQueryAbi.MAX_RAYS >= 3840 * 2160);
    }
}

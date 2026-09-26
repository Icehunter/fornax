package dev.icehunter.fornax.pack;

import dev.icehunter.fornax.pack.graph.BufferSize;
import dev.icehunter.fornax.rt.RayQueryKind;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code count = "render"} on a buffer and {@code rays = "render"} on a ray_query: one element or
 * one ray per render pixel, sized from the live render size rather than a literal. The literal
 * form is the failure this exists for: a pack sizing a per-pixel buffer to one window silently
 * loses the tail of the screen on any larger one.
 */
class RenderBasisDeclarationTest {

    private static final String FILE = "graph.toml";

    private static GraphSpec load(String toml) {
        return PackTomlLoader.loadGraph(new StringReader(toml), FILE);
    }

    private static final String RENDER_GRAPH = """
            [targets.rayRequests]
            kind = "buffer"
            stride_bytes = 32
            count = "render"

            [targets.rayHits]
            kind = "buffer"
            stride_bytes = 36
            count = "render"

            [[pass]]
            name = "seed_rays"
            type = "compute"
            shader = "compute/seed_rays.comp"
            outputs = ["rayRequests"]
            dispatch = [16, 1, 1]

            [[pass]]
            name = "trace_sun"
            type = "ray_query"
            inputs = ["rayRequests"]
            outputs = ["rayHits"]

            [pass.ray_query]
            kind = "visibility"
            rays = "render"
            """;

    @Test
    void countRenderParsesToAPerPixelSizeThatFollowsTheRenderSize() {
        GraphSpec graph = load(RENDER_GRAPH);
        BufferSize size = graph.targets().get("rayRequests").bufferSize();
        assertNotNull(size, "a render-basis buffer is still pack-owned");
        assertTrue(size.perRenderPixel());
        assertEquals(32, size.strideBytes());
        assertEquals(0, size.count(), "no fixed count");
        // 1920 x 1080 = 2073600 pixels at 32 bytes each.
        assertEquals(2073600L * 32, size.sizeBytes(1920, 1080));
        // A larger window is a larger buffer.
        assertEquals(3504640L * 32, size.sizeBytes(2560, 1369));
        assertEquals(3504640L, size.countAt(2560, 1369));
        assertThrows(IllegalStateException.class, size::sizeBytes, "no size without a render size");
    }

    @Test
    void thePerPixelSizeIsCappedAtTheBufferCeilingRatherThanRefused() {
        BufferSize size = BufferSize.perRenderPixel(36);
        long capped = size.sizeBytes(16384, 16384);
        assertTrue(capped <= BufferSize.MAX_SIZE_BYTES);
        assertEquals(0, capped % 36, "whole records only");
    }

    @Test
    void raysRenderParsesToAPerPixelQueryWithNoFixedCount() {
        GraphSpec graph = load(RENDER_GRAPH);
        RayQuerySpec spec = graph.passes().get(1).rayQuery();
        assertNotNull(spec);
        assertTrue(spec.perRenderPixel());
        assertEquals(0, spec.rayCount());
        assertEquals(RayQueryKind.VISIBILITY, spec.kind());
    }

    @Test
    void aFixedCountStaysFixedAndIsNotPerPixel() {
        GraphSpec graph = load(RENDER_GRAPH.replace("count = \"render\"", "count = 1024").replace("rays = \"render\"", "rays = 1024"));
        BufferSize size = graph.targets().get("rayRequests").bufferSize();
        assertFalse(size.perRenderPixel());
        assertEquals(1024L * 32, size.sizeBytes(2560, 1369), "a fixed count ignores the render size");
        assertFalse(graph.passes().get(1).rayQuery().perRenderPixel());
    }

    @Test
    void anyOtherStringIsRefusedNamingTheKeyAndTheOneAcceptedWord() {
        FornaxPackError count = assertThrows(FornaxPackError.class,
                () -> load(RENDER_GRAPH.replace("count = \"render\"", "count = \"screen\"")));
        assertTrue(count.getMessage().contains("targets.rayRequests.count"), count.getMessage());
        assertTrue(count.getMessage().contains("\"render\""), count.getMessage());
        FornaxPackError rays = assertThrows(FornaxPackError.class,
                () -> load(RENDER_GRAPH.replace("rays = \"render\"", "rays = \"screen\"")));
        assertTrue(rays.getMessage().contains("rays"), rays.getMessage());
        assertTrue(rays.getMessage().contains("\"render\""), rays.getMessage());
    }

    @Test
    void theRecordsRefuseTheInconsistentShapes() {
        assertThrows(IllegalArgumentException.class, () -> new BufferSize(32, 5, true),
                "a per-pixel buffer has no fixed count");
        assertThrows(IllegalArgumentException.class,
                () -> new RayQuerySpec(RayQueryKind.VISIBILITY, 5, dev.icehunter.fornax.rt.RayTier.NONE,
                        dev.icehunter.fornax.rt.AtlasUvEncoding.PACKED_HALF, true));
        assertThrows(IllegalArgumentException.class,
                () -> new RayQuerySpec(RayQueryKind.VISIBILITY, 0, dev.icehunter.fornax.rt.RayTier.NONE));
    }
}

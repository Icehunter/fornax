package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.FornaxPackError;
import dev.icehunter.fornax.pack.GraphSpec;
import dev.icehunter.fornax.pack.PackTomlLoader;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code rays = "render"} query and its two buffers must agree on the basis: a fixed buffer
 * under a per-pixel query drops the tail of a larger window with no error, and a fixed count over
 * a per-pixel buffer has no size to be checked against. Both are refused at load.
 */
class RenderBasisValidationTest {

    private static String graph(String requestsCount, String hitsCount, String rays, int hitStride) {
        return """
                [targets.rayRequests]
                kind = "buffer"
                stride_bytes = 32
                count = %s

                [targets.rayHits]
                kind = "buffer"
                stride_bytes = %d
                count = %s

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
                rays = %s
                """.formatted(requestsCount, hitStride, hitsCount, rays);
    }

    private static void validate(String toml) {
        GraphSpec g = PackTomlLoader.loadGraph(new StringReader(toml), "graph.toml");
        GraphValidator.validate(g, Map.of(), 1920, 1080);
    }

    @Test
    void perPixelRaysOverPerPixelBuffersValidate() {
        assertDoesNotThrow(() -> validate(graph("\"render\"", "\"render\"", "\"render\"", 36)));
    }

    @Test
    void perPixelRaysOverAFixedBufferAreRefusedNamingTheTailLoss() {
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> validate(graph("\"render\"", "2097152", "\"render\"", 36)));
        assertTrue(e.getMessage().contains("rayHits"), e.getMessage());
        assertTrue(e.getMessage().contains("count = \"render\""), e.getMessage());
    }

    @Test
    void aFixedRayCountOverAPerPixelBufferIsRefusedBecauseNothingCanBeChecked() {
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> validate(graph("\"render\"", "\"render\"", "1024", 36)));
        assertTrue(e.getMessage().contains("rays = \"render\""), e.getMessage());
    }

    @Test
    void aPerPixelBufferWithAStrideShorterThanARecordIsRefused() {
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> validate(graph("\"render\"", "\"render\"", "\"render\"", 32)));
        assertTrue(e.getMessage().contains("stride_bytes 32"), e.getMessage());
        assertTrue(e.getMessage().contains("36 bytes"), e.getMessage());
    }
}

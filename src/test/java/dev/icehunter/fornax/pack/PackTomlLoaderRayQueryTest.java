package dev.icehunter.fornax.pack;

import dev.icehunter.fornax.metalfx.rt.RayQueryAbi;
import dev.icehunter.fornax.pack.graph.GraphValidator;
import dev.icehunter.fornax.rt.RayQueryKind;
import dev.icehunter.fornax.rt.RayTier;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code ray_query} pass type, which a pack uses to cast its own rays without naming hardware.
 *
 * <p>Every rejection here is pinned because the failure it prevents is silent. This pass has no
 * shader, so there is nowhere for a mistake to surface: a buffer one record too small is written
 * past its end, a request buffer no pass writes traces whatever the allocation left behind, and
 * both produce hits rather than an error. A pack author would see wrong lighting, not a message.
 */
class PackTomlLoaderRayQueryTest {

    private static final String FILE = "graph.toml";

    /** 1024 rays: 32 KiB of requests, 32 KiB of hits, both at 32 bytes a record. */
    private static final int RAYS = 1024;

    private static GraphSpec load(String toml) {
        return PackTomlLoader.loadGraph(new StringReader(toml), FILE);
    }

    private static String graph(String rayQueryPass) {
        return """
                [targets.rayRequests]
                kind = "buffer"
                stride_bytes = 32
                count = 1024

                [targets.rayHits]
                kind = "buffer"
                stride_bytes = 32
                count = 1024

                [[pass]]
                name = "seed_rays"
                type = "compute"
                shader = "compute/seed_rays.comp"
                outputs = ["rayRequests"]
                dispatch = [16, 1, 1]

                """ + rayQueryPass;
    }

    private static final String VALID_PASS = """
            [[pass]]
            name = "trace_bounce"
            type = "ray_query"
            inputs = ["rayRequests"]
            outputs = ["rayHits"]

            [pass.ray_query]
            kind = "closest_hit"
            rays = 1024
            """;

    @Test
    void aRayQueryPassParsesItsKindCountAndDefaultFloor() {
        GraphSpec spec = load(graph(VALID_PASS));
        PassSpec pass = spec.passes().get(1);

        assertEquals(PassType.RAY_QUERY, pass.type());
        assertNotNull(pass.rayQuery());
        assertEquals(RayQueryKind.CLOSEST_HIT, pass.rayQuery().kind());
        assertEquals(RAYS, pass.rayQuery().rayCount());
        assertEquals(RayTier.NONE, pass.rayQuery().minTier(),
                "a pack that states no floor takes any tier that can answer");
        assertDoesNotThrow(() -> GraphValidator.validate(spec, Map.of(), 1920, 1080));
    }

    @Test
    void aFloorIsParsedByTierName() {
        GraphSpec spec = load(graph(VALID_PASS.replace("rays = 1024",
                "rays = 1024\nmin_tier = \"hardware_voxel\"")));
        assertEquals(RayTier.HARDWARE_VOXEL, spec.passes().get(1).rayQuery().minTier());
    }

    /** A shader key on this type names something that does not exist, so it is a typo, not a hint. */
    @Test
    void theShaderPassKeysAreRefusedOnAPassThatHasNoShader() {
        for (String key : new String[]{"program = \"x\"", "shader = \"x\"", "target = \"x\""}) {
            FornaxPackError error = assertThrows(FornaxPackError.class,
                    () -> load(graph(VALID_PASS.replace("type = \"ray_query\"",
                            "type = \"ray_query\"\n" + key))),
                    key + " must be refused");
            assertTrue(error.getMessage().contains("no shader of its own"), error.getMessage());
        }
    }

    @Test
    void aRayQueryPassWithoutItsTableIsRefused() {
        FornaxPackError error = assertThrows(FornaxPackError.class,
                () -> load(graph("""
                        [[pass]]
                        name = "trace_bounce"
                        type = "ray_query"
                        inputs = ["rayRequests"]
                        outputs = ["rayHits"]
                        """)));
        assertTrue(error.getMessage().contains("[pass.ray_query]"), error.getMessage());
    }

    @Test
    void anUnknownKindNamesWhatIsAccepted() {
        FornaxPackError error = assertThrows(FornaxPackError.class,
                () -> load(graph(VALID_PASS.replace("closest_hit", "any_hit"))));
        assertTrue(error.getMessage().contains("visibility or closest_hit"), error.getMessage());
    }

    @Test
    void aRayCountPastTheAbiCapIsRefusedRatherThanTruncated() {
        FornaxPackError error = assertThrows(FornaxPackError.class,
                () -> load(graph(VALID_PASS.replace("rays = 1024",
                        "rays = " + (RayQueryAbi.MAX_RAYS + 1)))));
        assertTrue(error.getMessage().contains("between 1 and"), error.getMessage());
    }

    /** The silent one: one record short and the traversal writes past the end of the allocation. */
    @Test
    void aBufferTooSmallForTheDeclaredRayCountIsRefusedAtLoad() {
        GraphSpec spec = load(graph(VALID_PASS).replace("""
                [targets.rayHits]
                kind = "buffer"
                stride_bytes = 32
                count = 1024""", """
                [targets.rayHits]
                kind = "buffer"
                stride_bytes = 32
                count = 1023"""));
        FornaxPackError error = assertThrows(FornaxPackError.class,
                () -> GraphValidator.validate(spec, Map.of(), 1920, 1080));
        assertTrue(error.getMessage().contains("written past its end"), error.getMessage());
    }

    /** Uninitialised requests are finite floats often enough to trace, so they produce answers. */
    @Test
    void aRequestBufferNoEarlierPassWritesIsRefused() {
        GraphSpec spec = load("""
                [targets.rayRequests]
                kind = "buffer"
                stride_bytes = 32
                count = 1024

                [targets.rayHits]
                kind = "buffer"
                stride_bytes = 32
                count = 1024

                """ + VALID_PASS);
        FornaxPackError error = assertThrows(FornaxPackError.class,
                () -> GraphValidator.validate(spec, Map.of(), 1920, 1080));
        assertTrue(error.getMessage().contains("not written by any earlier pass"), error.getMessage());
    }

    /** A traversal reads the whole request set while it writes, so one buffer cannot be both. */
    @Test
    void theSameBufferCannotCarryBothRequestsAndHits() {
        GraphSpec spec = load(graph(VALID_PASS.replace("outputs = [\"rayHits\"]",
                "outputs = [\"rayRequests\"]")));
        FornaxPackError error = assertThrows(FornaxPackError.class,
                () -> GraphValidator.validate(spec, Map.of(), 1920, 1080));
        assertTrue(error.getMessage().contains("cannot be both"), error.getMessage());
    }

    /** A texture target would resolve as a sampler and never carry a record stride. */
    @Test
    void aTextureTargetIsRefusedWhereABufferIsRequired() {
        GraphSpec spec = load("""
                [targets.rayRequests]
                kind = "buffer"
                stride_bytes = 32
                count = 1024

                [targets.rayHits]
                format = "rgba16f"

                [[pass]]
                name = "seed_rays"
                type = "compute"
                shader = "compute/seed_rays.comp"
                outputs = ["rayRequests"]
                dispatch = [16, 1, 1]

                """ + VALID_PASS);
        FornaxPackError error = assertThrows(FornaxPackError.class,
                () -> GraphValidator.validate(spec, Map.of(), 1920, 1080));
        assertTrue(error.getMessage().contains("kind = \"buffer\""), error.getMessage());
    }
}

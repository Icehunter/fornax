package dev.icehunter.fornax.pack;

import dev.icehunter.fornax.pack.graph.GraphRunner;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The pack's end-portal pass resolves to a program the substitution path can use. */
class EndPortalProgramResolvesTest {
    @Test
    void plagueClaimsTheEndPortalSlot() {
        Path root = locatePlague();
        assumeTrue(root != null, "Plague pack not present next to this checkout, skipping");

        PackModel pack = PackDiscovery.loadFrom(root, 1920, 1080);
        String path = GraphRunner.geometryProgramPath(pack, GeometrySlot.END_PORTAL);
        assertEquals("blocks/end_portal", path,
                "The pack declares a geometry pass on slot 'end_portal', so this must resolve to the"
                        + " program the pipeline substitutes. Null means vanilla's own fragment"
                        + " draws into the five-attachment G-buffer, writing colour into the normal"
                        + " target and leaving the other four unset.");
        assertTrue(Files.isRegularFile(root.resolve("shaders/blocks/end_portal.fsh")),
                "the resolved program has no fragment file on disk");
    }

    private static void assertTrue(boolean condition, String message) {
        org.junit.jupiter.api.Assertions.assertTrue(condition, message);
    }

    private static Path locatePlague() {
        Path repo = Path.of("").toAbsolutePath();
        for (Path candidate : new Path[] {
                repo.resolve("run/shaderpacks/Plague"),
                repo.getParent() == null ? null : repo.getParent().resolve("plague"),
        }) {
            if (candidate != null && Files.isRegularFile(candidate.resolve("pack.toml"))) {
                return candidate;
            }
        }
        return null;
    }
}

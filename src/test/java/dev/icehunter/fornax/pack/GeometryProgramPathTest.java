package dev.icehunter.fornax.pack;

import dev.icehunter.fornax.pack.graph.GraphRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins {@link GraphRunner#geometryProgramPath(PackModel, GeometrySlot)}: the conversion from a
 * geometry pass's pack-root-relative {@code program} key into the extension-less path a blaze3d
 * {@code Identifier} wants, resolved per slot.
 *
 * <p>This is the contract every geometry slot compiles against, so the null cases matter as much as
 * the happy path -- a null means "this slot draws vanilla", and a caller that mistook it for an error
 * would break every pack that simply does not claim the slot.
 */
class GeometryProgramPathTest {
    private static final String PACK_TOML = """
            [pack]
            name = "Program Path Pack"
            version = "1.0.0"
            authors = ["Test Author"]
            license = "MIT"
            format = 1
            """;

    private static final String SCREENS_TOML = """
            [main]
            elements = []
            columns = 1
            """;

    private static final String SHADER_SOURCE =
            "#define BLOOM_ENABLED 1 //[0 1] compile \"Bloom Enabled\"\n";

    @Test
    void stripsShadersPrefixAndExtensionPerSlot(@TempDir Path root) throws IOException {
        PackModel pack = load(root, """
                [targets.gAlbedo]
                format = "rgba8"
                scale = 1.0

                [[pass]]
                name = "terrain"
                type = "geometry"
                slot = "terrain"
                program = "shaders/blocks/terrain"
                outputs = ["gAlbedo"]

                [[pass]]
                name = "entities"
                type = "geometry"
                slot = "entities"
                program = "shaders/blocks/entities.vsh"
                outputs = ["gAlbedo"]
                """);

        assertEquals("blocks/terrain", GraphRunner.geometryProgramPath(pack, GeometrySlot.TERRAIN));
        assertEquals("blocks/entities", GraphRunner.geometryProgramPath(pack, GeometrySlot.ENTITIES));
    }

    @Test
    void unclaimedSlotResolvesToNullRatherThanThrowing(@TempDir Path root) throws IOException {
        PackModel pack = load(root, """
                [targets.gAlbedo]
                format = "rgba8"
                scale = 1.0

                [[pass]]
                name = "terrain"
                type = "geometry"
                slot = "terrain"
                program = "shaders/blocks/terrain"
                outputs = ["gAlbedo"]
                """);

        // Not an error: a pack claiming only terrain is the normal case, and every other slot must
        // report "draws vanilla" so the caller keeps whatever pipeline it would have built.
        assertNull(GraphRunner.geometryProgramPath(pack, GeometrySlot.ENTITIES));
        assertNull(GraphRunner.geometryProgramPath(pack, GeometrySlot.HAND));
    }

    @Test
    void slotOmittedInTomlResolvesUnderTerrain(@TempDir Path root) throws IOException {
        PackModel pack = load(root, """
                [targets.gAlbedo]
                format = "rgba8"
                scale = 1.0

                [[pass]]
                name = "terrain"
                type = "geometry"
                program = "shaders/blocks/terrain"
                outputs = ["gAlbedo"]
                """);

        assertEquals("blocks/terrain", GraphRunner.geometryProgramPath(pack, GeometrySlot.TERRAIN));
    }

    @Test
    void noActivePackResolvesToNull() {
        assertNull(GraphRunner.geometryProgramPath(null, GeometrySlot.TERRAIN));
    }

    /**
     * A FORWARD slot resolves through this same function and by the same rules.
     *
     * <p>Worth pinning separately because the forward path reaches it by a different route:
     * {@code DeferredGeometryPipelines.build} resolves a forward program through the EXPLICIT-slot
     * overload of {@code GeometryProgramSource.replacementIdentifierFor}, since the one-argument form
     * looks the slot up in {@code GeometryPipelineMap} -- which by construction never contains a
     * forward pipeline. Get that wrong and every forward draw silently stays vanilla with no error
     * anywhere; this test at least pins the half that is a pure function.
     */
    @Test
    void forwardSlotProgramResolvesLikeAnyOther(@TempDir Path root) throws IOException {
        PackModel pack = load(root, """
                [targets.gAlbedo]
                format = "rgba8"
                scale = 1.0

                [[pass]]
                name = "terrain"
                type = "geometry"
                slot = "terrain"
                program = "shaders/blocks/terrain"
                outputs = ["gAlbedo"]

                [[pass]]
                name = "banner_patterns"
                type = "geometry"
                slot = "banner_patterns"
                program = "shaders/blocks/banner_patterns"
                outputs = ["gAlbedo"]
                """);

        assertEquals("blocks/banner_patterns",
                GraphRunner.geometryProgramPath(pack, GeometrySlot.BANNER_PATTERNS));
        // And a pack that does not claim it still reports "draws vanilla" rather than throwing, which
        // is what keeps the forward hook opt-in.
        assertTrue(GeometrySlot.BANNER_PATTERNS.rendersForward());
    }

    @Test
    void unclaimedForwardSlotResolvesToNull(@TempDir Path root) throws IOException {
        PackModel pack = load(root, """
                [targets.gAlbedo]
                format = "rgba8"
                scale = 1.0

                [[pass]]
                name = "terrain"
                type = "geometry"
                slot = "terrain"
                program = "shaders/blocks/terrain"
                outputs = ["gAlbedo"]
                """);

        assertNull(GraphRunner.geometryProgramPath(pack, GeometrySlot.BANNER_PATTERNS));
    }

    /**
     * The real Plague pack's forward pass, resolved end to end.
     *
     * <p>The engine builds {@code fornax_runtime:blocks/banner_patterns} from exactly this string, so a
     * rename in graph.toml that is not matched in shaders/blocks produces no error at all -- the
     * forward hook simply declines every draw and banners silently go back to being unfogged. Which is
     * indistinguishable from the bug this round exists to fix.
     */
    @Test
    void realPlagueBannerPatternProgramResolves() {
        Path plague = Path.of("../plague").toAbsolutePath().normalize();
        if (!Files.isRegularFile(plague.resolve("pack.toml"))) {
            return; // pack not present next to this checkout -- PlaguePackLoadsTest skips likewise
        }
        PackModel pack = PackDiscovery.loadFrom(plague, 1920, 1080);
        String program = GraphRunner.geometryProgramPath(pack, GeometrySlot.BANNER_PATTERNS);
        assertEquals("blocks/banner_patterns", program);
        assertTrue(Files.isRegularFile(plague.resolve("shaders/" + program + ".fsh")),
                "graph.toml names a forward program with no .fsh beside it -- the hook would decline"
                        + " every draw and banners would silently stay unfogged");
        assertTrue(Files.isRegularFile(plague.resolve("shaders/" + program + ".vsh")),
                "the forward program ships no .vsh, so vanilla's own vertex stage runs -- and it"
                        + " forwards no position, which is the one thing the fragment stage needs");
    }

    @Test
    void installedPackTerrainProgramResolves(@TempDir Path unused) {
        // A pack declares program = "shaders/blocks/terrain"; this is the path the Sodium terrain
        // pipeline compiles against, so a regression here is a black world. Runs against whatever
        // pack is installed beside this checkout, and skips when none is.
        Path installed = Path.of("run/shaderpacks").toAbsolutePath();
        if (!Files.isDirectory(installed)) {
            return; // no packs installed -- shape is covered by SamplePackScreensParseTest
        }
        try (Stream<Path> entries = Files.list(installed)) {
            Path pack = entries.filter(p -> Files.isRegularFile(p.resolve("pack.toml")))
                    .filter(p -> Files.isRegularFile(p.resolve("shaders/blocks/terrain.fsh")))
                    .findFirst().orElse(null);
            if (pack == null) {
                return; // nothing installed declares a terrain program
            }
            PackModel model = PackDiscovery.loadFrom(pack, 1920, 1080);
            assertEquals("blocks/terrain", GraphRunner.geometryProgramPath(model, GeometrySlot.TERRAIN));
        } catch (IOException e) {
            return; // unreadable shaderpacks dir is not this test's subject
        }
    }

    private static PackModel load(Path root, String graphToml) throws IOException {
        Files.createDirectories(root.resolve("shaders"));
        Files.writeString(root.resolve("pack.toml"), PACK_TOML);
        Files.writeString(root.resolve("graph.toml"), graphToml);
        Files.writeString(root.resolve("screens.toml"), SCREENS_TOML);
        Files.writeString(root.resolve("shaders/terrain.fsh"), SHADER_SOURCE);
        return PackDiscovery.loadFrom(root, 1920, 1080);
    }
}

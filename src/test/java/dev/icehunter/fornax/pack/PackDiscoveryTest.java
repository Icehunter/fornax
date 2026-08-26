package dev.icehunter.fornax.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PackDiscoveryTest {
    private static final String PACK_TOML = """
            [pack]
            name = "My Pack"
            version = "1.0.0"
            authors = ["Test Author"]
            license = "MIT"
            format = 1
            """;

    private static final String GRAPH_TOML = """
            [targets.gAlbedo]
            format = "rgba8"
            scale = 1.0

            [[pass]]
            name = "terrain"
            type = "geometry"
            slot = "terrain"
            program = "shaders/terrain"
            outputs = ["gAlbedo"]
            """;

    private static final String SCREENS_TOML = """
            [main]
            elements = []
            columns = 1
            """;

    private static final String SHADER_SOURCE =
            "#define BLOOM_ENABLED 1 //[0 1] compile \"Bloom Enabled\"\n";

    @Test
    void discoversFolderAndZipPacks(@TempDir Path gameDir) throws IOException {
        Path shaderpacks = gameDir.resolve("shaderpacks");
        Files.createDirectories(shaderpacks);
        writeFolderPack(shaderpacks.resolve("FolderPack"));
        zipFolderPack(shaderpacks.resolve("FolderPack"), shaderpacks.resolve("ZipPack.zip"));

        List<DiscoveredPack> found = PackDiscovery.discoverIn(shaderpacks);
        try {
            assertEquals(2, found.size());
            assertEquals(List.of("FolderPack", "ZipPack"),
                    found.stream().map(DiscoveredPack::name).sorted().toList());
            DiscoveredPack zipPack = found.stream().filter(DiscoveredPack::zip).findFirst().orElseThrow();
            assertNotNull(zipPack.fileSystem());
            DiscoveredPack folderPack = found.stream().filter(p -> !p.zip()).findFirst().orElseThrow();
            assertNull(folderPack.fileSystem());
        } finally {
            for (DiscoveredPack p : found) p.close();
        }
    }

    @Test
    void reopeningZipAfterCloseDoesNotThrow(@TempDir Path gameDir) throws IOException {
        Path shaderpacks = gameDir.resolve("shaderpacks");
        Files.createDirectories(shaderpacks);
        writeFolderPack(gameDir.resolve("source"));
        zipFolderPack(gameDir.resolve("source"), shaderpacks.resolve("ZipPack.zip"));

        List<DiscoveredPack> first = PackDiscovery.discoverIn(shaderpacks);
        for (DiscoveredPack p : first) p.close();

        // Re-opening the same zip after closing must not throw FileSystemAlreadyExistsException.
        List<DiscoveredPack> second = assertDoesNotThrow(() -> PackDiscovery.discoverIn(shaderpacks));
        try {
            assertEquals(1, second.size());
        } finally {
            for (DiscoveredPack p : second) p.close();
        }
    }

    @Test
    void discardingAPriorDiscoverySetWithoutClosingLeaksItsZipFileSystem(@TempDir Path gameDir) throws IOException {
        // Pins the hazard a re-init path (e.g. a window resize rebuilding the same screen's widgets)
        // must guard against: reassigning `this.discovered` without tearing down the previous set
        // first. Losing the only reference to a DiscoveredPack whose zip FileSystem was never closed
        // leaks that handle -- repeated enough times (repeated resizes with a zip pack present) this
        // exhausts file handles.
        Path shaderpacks = gameDir.resolve("shaderpacks");
        Files.createDirectories(shaderpacks);
        writeFolderPack(gameDir.resolve("source"));
        zipFolderPack(gameDir.resolve("source"), shaderpacks.resolve("ZipPack.zip"));

        List<DiscoveredPack> first = PackDiscovery.discoverIn(shaderpacks);
        FileSystem leakedFileSystem = first.stream().filter(DiscoveredPack::zip).findFirst().orElseThrow().fileSystem();

        // The buggy path: rediscover without closing `first` first, then only close the new batch
        // (mirroring addOptions() dropping its only reference to the previous `this.discovered`).
        List<DiscoveredPack> second = PackDiscovery.discoverIn(shaderpacks);
        try {
            assertTrue(leakedFileSystem.isOpen(), "prior zip FileSystem was silently leaked open");
        } finally {
            for (DiscoveredPack p : second) p.close();
            leakedFileSystem.close();
        }
    }

    @Test
    void loadFromReturnsPackModelWithExpectedOptionNames(@TempDir Path gameDir) throws IOException {
        Path root = gameDir.resolve("MyPack");
        writeFolderPack(root);

        PackModel model = PackDiscovery.loadFrom(root, 1920, 1080);

        assertEquals("My Pack", model.meta().name());
        assertEquals(List.of("BLOOM_ENABLED"), List.copyOf(model.options().keySet()));
    }

    @Test
    void unsupportedFormatThrows(@TempDir Path gameDir) throws IOException {
        Path root = gameDir.resolve("BadFormatPack");
        writeFolderPack(root);
        Files.writeString(root.resolve("pack.toml"), PACK_TOML.replace("format = 1", "format = 99"));

        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> PackDiscovery.loadFrom(root, 1920, 1080));
        assertEquals("format", e.key());
    }

    // --- [textures.*] pack-shipped texture assets: PackDiscovery.loadFrom eagerly proves the
    // declared file exists and decodes cleanly, at pack-load time -- never a silent black surfacing
    // deep in a frame. -------------------------------------------------------------------------

    @Test
    void loadFromWithValidTextureAssetSucceeds(@TempDir Path gameDir) throws IOException {
        Path root = gameDir.resolve("TexturedPack");
        writeFolderPack(root);
        Files.writeString(root.resolve("graph.toml"), GRAPH_TOML + """

                [textures.waterWaveNormal]
                file = "textures/water_wave_normal.png"
                """);
        Files.createDirectories(root.resolve("textures"));
        writePng(root.resolve("textures/water_wave_normal.png"), 4, 4);

        PackModel model = PackDiscovery.loadFrom(root, 1920, 1080);
        assertEquals(1, model.graph().textures().size());
        assertEquals("textures/water_wave_normal.png",
                model.graph().textures().get("waterWaveNormal").file());
    }

    @Test
    void loadFromWithMissingTextureFileThrows(@TempDir Path gameDir) throws IOException {
        Path root = gameDir.resolve("MissingTexturePack");
        writeFolderPack(root);
        Files.writeString(root.resolve("graph.toml"), GRAPH_TOML + """

                [textures.waterWaveNormal]
                file = "textures/water_wave_normal.png"
                """);
        // Deliberately never write textures/water_wave_normal.png.

        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> PackDiscovery.loadFrom(root, 1920, 1080));
        assertEquals("textures.waterWaveNormal.file", e.key());
        assertTrue(e.reason().contains("not found"), e.reason());
    }

    @Test
    void loadFromWithUndecodableTextureFileThrows(@TempDir Path gameDir) throws IOException {
        Path root = gameDir.resolve("CorruptTexturePack");
        writeFolderPack(root);
        Files.writeString(root.resolve("graph.toml"), GRAPH_TOML + """

                [textures.waterWaveNormal]
                file = "textures/water_wave_normal.png"
                """);
        Files.createDirectories(root.resolve("textures"));
        Files.writeString(root.resolve("textures/water_wave_normal.png"), "not a real png");

        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> PackDiscovery.loadFrom(root, 1920, 1080));
        assertEquals("textures.waterWaveNormal.file", e.key());
        assertTrue(e.reason().contains("failed to decode"), e.reason());
    }

    private static void writePng(Path file, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        ImageIO.write(image, "png", file.toFile());
    }

    private static void writeFolderPack(Path root) throws IOException {
        Files.createDirectories(root.resolve("shaders"));
        Files.writeString(root.resolve("pack.toml"), PACK_TOML);
        Files.writeString(root.resolve("graph.toml"), GRAPH_TOML);
        Files.writeString(root.resolve("screens.toml"), SCREENS_TOML);
        Files.writeString(root.resolve("shaders/terrain.fsh"), SHADER_SOURCE);
    }

    private static void zipFolderPack(Path folder, Path zipFile) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile));
             Stream<Path> walk = Files.walk(folder)) {
            for (Path path : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                String entryName = folder.relativize(path).toString().replace('\\', '/');
                zos.putNextEntry(new ZipEntry(entryName));
                Files.copy(path, zos);
                zos.closeEntry();
            }
        }
    }
}

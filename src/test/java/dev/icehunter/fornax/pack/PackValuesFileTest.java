package dev.icehunter.fornax.pack;

import dev.icehunter.fornax.pack.option.OptionRange;
import dev.icehunter.fornax.pack.option.OptionType;
import dev.icehunter.fornax.pack.option.PackOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PackValuesFileTest {
    private static final Map<String, PackOption> OPTIONS = Map.of(
            "SSAO_RADIUS", new PackOption("SSAO_RADIUS", OptionType.RUNTIME,
                    new OptionRange(0.0, 4.0, 0.1), List.of(), false, false, "0.5", "SSAO Radius", Map.of()),
            "BLOOM_ENABLED", new PackOption("BLOOM_ENABLED", OptionType.COMPILE,
                    null, List.of(), true, false, "0", "Bloom", Map.of()));

    @Test
    void roundTripsKeyValuePairs(@TempDir Path dir) {
        Path file = dir.resolve("MyPack.txt");
        Map<String, String> values = Map.of("SSAO_RADIUS", "1.5", "BLOOM_ENABLED", "1");

        PackValuesFile.save(file, values);
        Map<String, String> loaded = PackValuesFile.load(file, OPTIONS);

        assertEquals(values, loaded);
    }

    @Test
    void unknownKeysAreDroppedOnLoad(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("MyPack.txt");
        Files.writeString(file, "SSAO_RADIUS=1.5\nGHOST_OPTION=42\n");

        Map<String, String> loaded = PackValuesFile.load(file, OPTIONS);

        assertEquals(Map.of("SSAO_RADIUS", "1.5"), loaded);
    }

    @Test
    void retiredOptionKeysAreDroppedNotFatal(@TempDir Path dir) throws Exception {
        // The exact shape a live migration leaves behind: a values file saved when the pack still
        // exposed options it has since retired (TAA_ENABLED/u_TaaBlendFactor moved engine-side).
        // Loading against the post-retirement option table must drop the dead keys and keep the
        // rest -- drift tolerance, never a load failure that would take the whole pack down.
        Path file = dir.resolve("SamplePack.txt");
        Files.writeString(file, "SSAO_RADIUS=1.5\nTAA_ENABLED=1\nu_TaaBlendFactor=0.9\nBLOOM_ENABLED=1\n");

        Map<String, String> loaded = assertDoesNotThrow(() -> PackValuesFile.load(file, OPTIONS));

        assertEquals(Map.of("SSAO_RADIUS", "1.5", "BLOOM_ENABLED", "1"), loaded);
    }

    @Test
    void malformedLinesAreSkipped(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("MyPack.txt");
        Files.writeString(file, "SSAO_RADIUS=1.5\nnot a valid line\n=noKey\n# a comment\n\nBLOOM_ENABLED=1\n");

        Map<String, String> loaded = PackValuesFile.load(file, OPTIONS);

        assertEquals(Map.of("SSAO_RADIUS", "1.5", "BLOOM_ENABLED", "1"), loaded);
    }

    @Test
    void missingFileReturnsEmptyMap(@TempDir Path dir) {
        Path file = dir.resolve("DoesNotExist.txt");

        Map<String, String> loaded = PackValuesFile.load(file, OPTIONS);

        assertTrue(loaded.isEmpty());
    }

    @Test
    void saveWritesCompleteFileAtomicallyWithNoTempResidue(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("MyPack.txt");
        // Seed a stale prior save to prove the atomic move fully replaces it, not just appends/merges.
        Files.writeString(file, "STALE_KEY=999\nSSAO_RADIUS=0.1\n");

        Map<String, String> values = Map.of("SSAO_RADIUS", "1.5", "BLOOM_ENABLED", "1");
        PackValuesFile.save(file, values);

        // The saved file round-trips exactly as written, with the stale content fully replaced.
        Map<String, String> loaded = PackValuesFile.load(file, OPTIONS);
        assertEquals(values, loaded);

        // No temp/partial files left behind in the target directory after the move.
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path entry : entries) {
                assertEquals(file, entry, "unexpected leftover file: " + entry);
            }
        }
    }
}

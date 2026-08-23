package dev.icehunter.fornax.atlas;

import com.mojang.blaze3d.platform.NativeImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The read/write round trip against a {@code @TempDir}, exercised through
 * {@link LabPbrAtlasDiskCache#writeFileSync}/{@link LabPbrAtlasDiskCache#tryReadFile} rather than the
 * {@code Identifier}-keyed public entry points -- those resolve their path through {@code
 * FabricLoader.getInstance().getConfigDir()}, which throws {@code IllegalStateException} outside a
 * real game environment (see e.g. {@code MetaBindingTest}'s own note on the same limit). The
 * production entry points are one-line wrappers around exactly this logic (see
 * {@code LabPbrAtlasDiskCache.tryRead}/{@code writeAsync}), so this covers the real read/write bytes,
 * header validation, and mismatch handling without needing a live Fabric/game environment.
 */
class LabPbrAtlasDiskCacheTest {
    private static final int WIDTH = 4;
    private static final int HEIGHT = 4;

    @Test
    void roundTripsByteIdenticalWithNoOverflowLayers(@TempDir Path dir) {
        byte[] base = pattern(WIDTH * HEIGHT * 4, (byte) 0x11);
        LabPbrAtlasDiskCache.writeFileSync(dir.resolve("entry.bin"), fingerprint("a"), WIDTH, HEIGHT,
                base, new byte[0][]);

        try (LabPbrAtlasDiskCache.Loaded loaded = LabPbrAtlasDiskCache.tryReadFile(
                dir.resolve("entry.bin"), fingerprint("a"), WIDTH, HEIGHT, 0)) {
            assertEquals(0, loaded.layers().length);
            assertArrayEqualsPixels(base, loaded.base());
        }
    }

    @Test
    void roundTripsByteIdenticalWithOverflowLayers(@TempDir Path dir) {
        byte[] base = pattern(WIDTH * HEIGHT * 4, (byte) 0x22);
        byte[] layer0 = pattern(WIDTH * HEIGHT * 4, (byte) 0x33);
        byte[] layer1 = pattern(WIDTH * HEIGHT * 4, (byte) 0x44);
        LabPbrAtlasDiskCache.writeFileSync(dir.resolve("entry.bin"), fingerprint("b"), WIDTH, HEIGHT,
                base, new byte[][] {layer0, layer1});

        try (LabPbrAtlasDiskCache.Loaded loaded = LabPbrAtlasDiskCache.tryReadFile(
                dir.resolve("entry.bin"), fingerprint("b"), WIDTH, HEIGHT, 2)) {
            assertEquals(2, loaded.layers().length);
            assertArrayEqualsPixels(base, loaded.base());
            assertArrayEqualsPixels(layer0, loaded.layers()[0]);
            assertArrayEqualsPixels(layer1, loaded.layers()[1]);
        }
    }

    @Test
    void missesOnFingerprintMismatch(@TempDir Path dir) {
        byte[] base = pattern(WIDTH * HEIGHT * 4, (byte) 0x55);
        LabPbrAtlasDiskCache.writeFileSync(dir.resolve("entry.bin"), fingerprint("original"), WIDTH, HEIGHT,
                base, new byte[0][]);

        assertNull(LabPbrAtlasDiskCache.tryReadFile(
                dir.resolve("entry.bin"), fingerprint("changed"), WIDTH, HEIGHT, 0),
                "a different fingerprint must never return the previous entry's pixels");
    }

    @Test
    void missesOnDimensionMismatch(@TempDir Path dir) {
        byte[] base = pattern(WIDTH * HEIGHT * 4, (byte) 0x66);
        LabPbrAtlasDiskCache.writeFileSync(dir.resolve("entry.bin"), fingerprint("c"), WIDTH, HEIGHT,
                base, new byte[0][]);

        assertNull(LabPbrAtlasDiskCache.tryReadFile(
                dir.resolve("entry.bin"), fingerprint("c"), WIDTH * 2, HEIGHT, 0),
                "a resized atlas must never be served stale pixels at the wrong dimensions");
    }

    @Test
    void missesOnLayerCountMismatch(@TempDir Path dir) {
        byte[] base = pattern(WIDTH * HEIGHT * 4, (byte) 0x77);
        byte[] layer0 = pattern(WIDTH * HEIGHT * 4, (byte) 0x88);
        LabPbrAtlasDiskCache.writeFileSync(dir.resolve("entry.bin"), fingerprint("d"), WIDTH, HEIGHT,
                base, new byte[][] {layer0});

        assertNull(LabPbrAtlasDiskCache.tryReadFile(
                dir.resolve("entry.bin"), fingerprint("d"), WIDTH, HEIGHT, 0),
                "a changed overflow-page count must never be served the old layer set");
    }

    @Test
    void missesOnAbsentFile(@TempDir Path dir) {
        assertNull(LabPbrAtlasDiskCache.tryReadFile(
                dir.resolve("never_written.bin"), fingerprint("e"), WIDTH, HEIGHT, 0));
    }

    @Test
    void missesOnTruncatedFile(@TempDir Path dir) throws Exception {
        byte[] base = pattern(WIDTH * HEIGHT * 4, (byte) 0x99);
        Path file = dir.resolve("entry.bin");
        LabPbrAtlasDiskCache.writeFileSync(file, fingerprint("f"), WIDTH, HEIGHT, base, new byte[0][]);

        // Simulate a crash mid-write: truncate to half the real file size.
        long fullSize = java.nio.file.Files.size(file);
        try (var channel = java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.WRITE)) {
            channel.truncate(fullSize / 2);
        }

        assertNull(LabPbrAtlasDiskCache.tryReadFile(file, fingerprint("f"), WIDTH, HEIGHT, 0),
                "a truncated (crash-mid-write) file must degrade to a miss, never throw");
    }

    // A real fingerprint is always a 64-char hex SHA-256 digest (LabPbrAtlasFingerprint.compute's
    // contract), and the on-disk header's fingerprint field is a fixed 64 bytes to match -- a
    // shorter test literal would leave that field NUL-padded and never round-trip, which is exactly
    // what this helper avoids by hashing the tag down to a real 64-char digest.
    private static String fingerprint(String tag) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(tag.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static byte[] pattern(int length, byte seed) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (seed + i);
        }
        return bytes;
    }

    private static void assertArrayEqualsPixels(byte[] expected, NativeImage actual) {
        byte[] actualBytes = new byte[expected.length];
        actual.getPixelBytes().duplicate().get(actualBytes);
        assertEquals(expected.length, actualBytes.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actualBytes[i], "byte mismatch at index " + i);
        }
    }
}

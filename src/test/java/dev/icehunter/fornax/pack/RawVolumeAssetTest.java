package dev.icehunter.fornax.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RawVolumeAssetTest {

    private static Path writeVolume(Path dir, int width, int height, int depth, int formatTag,
                                     byte[] texels) throws IOException {
        Path file = dir.resolve("volume.bin");
        ByteBuffer buf = ByteBuffer.allocate(16 + texels.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(width).putInt(height).putInt(depth).putInt(formatTag).put(texels);
        Files.write(file, buf.array());
        return file;
    }

    @Test
    void readsAWellFormedR8Volume(@TempDir Path dir) throws IOException {
        byte[] texels = new byte[2 * 2 * 2]; // width*height*depth*1 byte
        Path file = writeVolume(dir, 2, 2, 2, 0, texels);

        RawVolumeAsset asset = RawVolumeAsset.read(file);

        assertEquals(2, asset.width());
        assertEquals(2, asset.height());
        assertEquals(2, asset.depth());
        assertEquals(RawVolumeAsset.Format.R8, asset.format());
        assertEquals(8, asset.texels().remaining());
    }

    @Test
    void rejectsATruncatedFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("truncated.bin");
        // Header declares 2x2x2 R8 (needs 16 + 8 = 24 bytes) but the file is only 20 bytes.
        ByteBuffer buf = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(2).putInt(2).putInt(2).putInt(0);
        Files.write(file, buf.array());

        IOException e = assertThrows(IOException.class, () -> RawVolumeAsset.read(file));
        assertTrue(e.getMessage().contains("truncated"), "message was: " + e.getMessage());
    }

    @Test
    void rejectsAnUnrecognizedFormatTag(@TempDir Path dir) throws IOException {
        Path file = writeVolume(dir, 1, 1, 1, 99, new byte[1]);

        IOException e = assertThrows(IOException.class, () -> RawVolumeAsset.read(file));
        assertTrue(e.getMessage().contains("99"), "message was: " + e.getMessage());
    }

    @Test
    void parseFormatAcceptsKnownTokensCaseInsensitively() {
        assertEquals(RawVolumeAsset.Format.R8, RawVolumeAsset.parseFormat("r8"));
        assertEquals(RawVolumeAsset.Format.RGBA8, RawVolumeAsset.parseFormat("RGBA8"));
    }

    @Test
    void parseFormatRejectsUnknownToken() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> RawVolumeAsset.parseFormat("rg16f"));
        assertTrue(e.getMessage().contains("rg16f"));
    }
}

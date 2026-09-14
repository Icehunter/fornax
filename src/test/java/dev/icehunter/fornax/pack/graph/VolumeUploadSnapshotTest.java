package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.RawVolumeAsset;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Headless proof of the exact owned bytes used for staging; it does not verify a GPU copy. */
class VolumeUploadSnapshotTest {
    @Test
    void retainsOnlyUploadedBytesAndCannotBeChangedThroughTheSourceOrCaptureView() {
        // The surrounding bytes are excluded by the upload buffer's selected range.
        byte[] source = {99, 0, 1, 2, 3, 88};
        ByteBuffer texels = ByteBuffer.wrap(source).position(1).limit(5);
        RawVolumeAsset asset = new RawVolumeAsset(2, 1, 2, RawVolumeAsset.Format.R8, texels);
        Volume3DTexture.UploadSnapshot snapshot = Volume3DTexture.snapshotUpload(asset, 9); // Vulkan R8_UNORM.
        assertEquals(1, texels.position());
        assertEquals(5, texels.limit());
        source[2] = 77;
        texels.clear();

        ByteBuffer first = snapshot.texels();
        byte[] received = new byte[first.remaining()];
        first.get(received);
        assertArrayEquals(new byte[]{0, 1, 2, 3}, received);
        assertThrows(ReadOnlyBufferException.class, () -> first.put(0, (byte) 42));
        ByteBuffer second = snapshot.texels();
        assertEquals(0, second.position());
        assertEquals(4, second.remaining());
        assertEquals(2, snapshot.width());
        assertEquals(1, snapshot.height());
        assertEquals(2, snapshot.depth());
        assertEquals(9, snapshot.vkFormat());
        assertEquals(RawVolumeAsset.Format.R8, snapshot.format());
        assertEquals(1, snapshot.mipLevels());
        // SHA-256 of four literal bytes 00 01 02 03, independent of the production digest helper.
        assertEquals("054edec1d0211f624fed0cbca9d4f9400b0e491c43742af2c5b0abebf0c990d8",
                snapshot.sha256());
    }
}

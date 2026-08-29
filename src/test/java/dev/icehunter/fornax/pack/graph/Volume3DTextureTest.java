package dev.icehunter.fornax.pack.graph;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vulkan.VulkanConst;
import dev.icehunter.fornax.pack.RawVolumeAsset;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Two halves, and it matters which is which.
 *
 * <p>The <b>device-gated</b> tests exercise the real Vulkan path: creating a hand-built
 * {@code VK_IMAGE_TYPE_3D} image, uploading into it, tearing it down. {@link Volume3DTexture#create}
 * reaches the backend through {@code RenderSystem.tryGetDevice()} and returns {@code null} without
 * one, so these SKIP headless, exactly like {@code PlaguePackLoadsTest} skips when its input is
 * absent. Where they do run they are smoke tests for allocation and submission only: nothing here
 * reads a volume back, so they prove nothing about what it samples as, which needs a shader read in
 * a running client.
 *
 * <p>The <b>ungated</b> tests below them cover {@link Volume3DTexture#validateUpload} and Blaze3D's
 * format table. These really run, everywhere. Since the upload path can never execute headless,
 * they are the only part of this suite that is proven rather than derived; keep them that way,
 * and resist moving anything into them that would need a device.
 */
class Volume3DTextureTest {

    /** {@code VK_FORMAT_R8_UNORM}: the single-channel format the cloud noise volumes use. */
    private static final int VK_FORMAT_R8_UNORM = 9;

    @Test
    void createsAndClosesA3DImageWithoutThrowing() {
        assumeTrue(RenderSystem.tryGetDevice() != null, "no GPU device here, skipping");

        Volume3DTexture volume = Volume3DTexture.create("test-volume",
                GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                VK_FORMAT_R8_UNORM, 4, 4, 4);
        assertNotNull(volume, "a live Vulkan device must produce a volume");
        assertEquals(4, volume.width());
        assertEquals(4, volume.height());
        assertEquals(4, volume.depth());
        // The dimensions Blaze3D itself reads must describe the REAL volume, not the 1x1x1
        // placeholder the super-constructor allocates.
        assertEquals(4, volume.getWidth(0));
        assertEquals(4, volume.getHeight(0));
        assertEquals(4, volume.getDepthOrLayers());
        assertNotNull(volume.view());
        assertDoesNotThrow(volume::close);
    }

    /**
     * Pins that the hand-recorded upload path runs end to end: staging buffer, one
     * {@code vkCmdCopyBufferToImage} carrying the REAL depth, and the two layout transitions that
     * leave the volume in {@code VK_IMAGE_LAYOUT_GENERAL}. Device-gated for the same reason as
     * {@link #createsAndClosesA3DImageWithoutThrowing}, so it SKIPS headless.
     *
     * <p>What it cannot prove, stated so the green tick is not read as more than it is: nothing
     * here reads the texels back, so this is a "the submission completed without a fence timeout or
     * a VkResult failure" check, not a check that texel (x,y,z) landed at (x,y,z). Sampling order
     * needs a shader read in a running client.
     */
    @Test
    void uploadsTexelDataAndTransitionsToShaderReadable() {
        assumeTrue(RenderSystem.tryGetDevice() != null, "no GPU device here, skipping");

        byte[] texels = new byte[4 * 4 * 4]; // 4x4x4 R8, one byte per texel
        for (int i = 0; i < texels.length; i++) {
            texels[i] = (byte) i;
        }
        RawVolumeAsset asset = new RawVolumeAsset(4, 4, 4, RawVolumeAsset.Format.R8,
                ByteBuffer.wrap(texels));

        Volume3DTexture volume = Volume3DTexture.create("test-volume-upload",
                GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                VK_FORMAT_R8_UNORM, 4, 4, 4);
        assertNotNull(volume, "a live Vulkan device must produce a volume");
        assertDoesNotThrow(() -> volume.upload(asset));
        // Uploading twice is rejected loudly rather than re-barriering a live image from UNDEFINED.
        assertThrows(IllegalStateException.class, () -> volume.upload(asset));
        assertDoesNotThrow(volume::close);
    }

    /**
     * Needs no device, so this one actually runs headless. {@code Volume3DTexture} inverts
     * {@code VulkanConst.toVk} to name the placeholder's {@code GpuFormat}: pin the two entries
     * that inversion depends on, so a Blaze3D format-table change surfaces here rather than as a
     * placeholder quietly declared in the wrong format.
     */
    @Test
    void blaze3dsFormatTableStillCarriesTheVolumeFormats() {
        assertEquals(VK_FORMAT_R8_UNORM, VulkanConst.toVk(GpuFormat.R8_UNORM));
        assertEquals(VK_FORMAT_R8G8B8A8_UNORM, VulkanConst.toVk(GpuFormat.RGBA8_UNORM));
    }

    // -------------------------------------------------------------------------------------------
    // validateUpload: the device-independent half of upload()'s contract.
    //
    // These run for real, headless, and that is the whole point of them: upload() itself cannot
    // execute without a Vulkan device, so its guards would otherwise be the untested part of an
    // already-untestable path. Each one stands between a malformed asset and an out-of-bounds
    // device read of the staging buffer, a failure with no Java-side symptom whatsoever, since
    // vkCmdCopyBufferToImage sizes its read from the IMAGE's format and extent, never from
    // anything the caller computed.
    // -------------------------------------------------------------------------------------------

    /** {@code VK_FORMAT_R8G8B8A8_UNORM}: 4 bytes per texel, the size foil for {@code R8_UNORM}. */
    private static final int VK_FORMAT_R8G8B8A8_UNORM = 37;

    private static final int COPY_DST_USAGE =
            GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST;

    private static RawVolumeAsset asset(int width, int height, int depth,
                                        RawVolumeAsset.Format format, int texelBytes) {
        return new RawVolumeAsset(width, height, depth, format, ByteBuffer.allocate(texelBytes));
    }

    /** The happy 4x4x4 R8 call every negative case below perturbs exactly one field of. */
    private static long validate4x4x4R8(RawVolumeAsset asset) {
        return Volume3DTexture.validateUpload("t", false, COPY_DST_USAGE, 4, 4, 4,
                VK_FORMAT_R8_UNORM, asset);
    }

    @Test
    void validateUploadReturnsTheExactStagingSize() {
        assertEquals(64L, validate4x4x4R8(asset(4, 4, 4, RawVolumeAsset.Format.R8, 64)));
        assertEquals(256L, Volume3DTexture.validateUpload("t", false, COPY_DST_USAGE, 4, 4, 4,
                VK_FORMAT_R8G8B8A8_UNORM, asset(4, 4, 4, RawVolumeAsset.Format.RGBA8, 256)));
    }

    @Test
    void validateUploadRejectsASecondUpload() {
        // The first barrier's UNDEFINED/TOP_OF_PIPE source scope is correct only for an image
        // nothing has read yet, so a re-upload must be refused rather than under-synchronized.
        assertThrows(IllegalStateException.class,
                () -> Volume3DTexture.validateUpload("t", true, COPY_DST_USAGE, 4, 4, 4,
                        VK_FORMAT_R8_UNORM, asset(4, 4, 4, RawVolumeAsset.Format.R8, 64)));
    }

    @Test
    void validateUploadRejectsAVolumeCreatedWithoutCopyDst() {
        // No USAGE_COPY_DST means no VK_IMAGE_USAGE_TRANSFER_DST_BIT on the image, so the copy is
        // invalid Vulkan that only a validation layer would ever mention.
        assertThrows(IllegalStateException.class,
                () -> Volume3DTexture.validateUpload("t", false, GpuTexture.USAGE_TEXTURE_BINDING,
                        4, 4, 4, VK_FORMAT_R8_UNORM, asset(4, 4, 4, RawVolumeAsset.Format.R8, 64)));
    }

    /**
     * Each asset here carries 64 bytes, the byte count the 4x4x4 IMAGE wants, so the dimension
     * guard is the only check any of them can trip. Sizing them to their own declared dimensions
     * (128) would make them throw on the byte-count guard instead, and the test would still pass
     * with the dimension check deleted.
     */
    @Test
    void validateUploadRejectsEachMismatchedAxis() {
        assertThrows(IllegalArgumentException.class,
                () -> validate4x4x4R8(asset(8, 4, 4, RawVolumeAsset.Format.R8, 64)));
        assertThrows(IllegalArgumentException.class,
                () -> validate4x4x4R8(asset(4, 8, 4, RawVolumeAsset.Format.R8, 64)));
        // Depth especially: it is the axis Blaze3D's own copy paths cannot address at all, so it is
        // both the likeliest to be wrong and the least likely to be noticed.
        assertThrows(IllegalArgumentException.class,
                () -> validate4x4x4R8(asset(4, 4, 8, RawVolumeAsset.Format.R8, 64)));
    }

    @Test
    void validateUploadRejectsATexelSizeThatDisagreesWithTheImageFormat() {
        // A 1 B/texel asset into a 4 B/texel image: the copy would read 4x the staging buffer.
        assertThrows(IllegalArgumentException.class,
                () -> Volume3DTexture.validateUpload("t", false, COPY_DST_USAGE, 4, 4, 4,
                        VK_FORMAT_R8G8B8A8_UNORM, asset(4, 4, 4, RawVolumeAsset.Format.R8, 64)));
        // And the other way round.
        assertThrows(IllegalArgumentException.class,
                () -> validate4x4x4R8(asset(4, 4, 4, RawVolumeAsset.Format.RGBA8, 256)));
    }

    /**
     * The texel-size cross-check is skipped when {@code blaze3dFormatOf} fell back to
     * {@code RGBA8_UNORM} for a {@code VkFormat} Blaze3D's table does not carry: comparing an
     * asset against the fallback would reject perfectly good data. Pins that the fallback branch
     * stays permissive instead of quietly becoming a 4-bytes-per-texel assertion.
     */
    @Test
    void validateUploadSkipsTheFormatCheckForAVkFormatBlaze3dDoesNotKnow() {
        int unmapped = 1; // VK_FORMAT_R4G4_UNORM_PACK8
        for (GpuFormat candidate : GpuFormat.values()) {
            assertNotEquals(unmapped, VulkanConst.toVk(candidate),
                    "this test needs a VkFormat Blaze3D's table does NOT carry, and " + candidate
                            + " now maps to it: pick another, do not delete the assertion");
        }
        assertEquals(64L, Volume3DTexture.validateUpload("t", false, COPY_DST_USAGE, 4, 4, 4,
                unmapped, asset(4, 4, 4, RawVolumeAsset.Format.R8, 64)));
    }

    /**
     * 2048^3 R8 = 8589934592 bytes. {@code MemoryUtil.memByteBuffer} takes an {@code int} length, so
     * without this guard the cast would wrap to a nonsense capacity rather than fail.
     *
     * <p>The message assertion is load-bearing, not decoration. No test buffer can actually hold
     * 8 GiB, so the byte-count guard downstream would reject this asset too, meaning a bare
     * {@code assertThrows} would still pass with the overflow check deleted. Matching on the text
     * only the overflow check produces is what makes this test about the overflow check.
     */
    @Test
    void validateUploadRejectsAVolumeTooLargeToMap() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> Volume3DTexture.validateUpload("t", false, COPY_DST_USAGE,
                        2048, 2048, 2048, VK_FORMAT_R8_UNORM,
                        asset(2048, 2048, 2048, RawVolumeAsset.Format.R8, 0)));
        assertTrue(thrown.getMessage().contains("addressable limit"),
                "expected the overflow guard, got: " + thrown.getMessage());
    }

    @Test
    void validateUploadRejectsATexelBufferOfTheWrongLength() {
        assertThrows(IllegalArgumentException.class,
                () -> validate4x4x4R8(asset(4, 4, 4, RawVolumeAsset.Format.R8, 63)));
        assertThrows(IllegalArgumentException.class,
                () -> validate4x4x4R8(asset(4, 4, 4, RawVolumeAsset.Format.R8, 65)));
    }

    /**
     * A partly-consumed buffer has fewer bytes left than it has capacity, and {@code remaining()}
     * is what the copy would actually get, so that, not capacity, is what the guard measures.
     */
    @Test
    void validateUploadMeasuresTheBufferFromItsPositionNotItsCapacity() {
        ByteBuffer partlyRead = ByteBuffer.allocate(64);
        partlyRead.position(8);
        RawVolumeAsset shortByEight =
                new RawVolumeAsset(4, 4, 4, RawVolumeAsset.Format.R8, partlyRead);
        assertThrows(IllegalArgumentException.class, () -> validate4x4x4R8(shortByEight));
    }
}

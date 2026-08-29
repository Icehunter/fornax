package dev.icehunter.fornax.pack.graph;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * {@code load2D()} needs a live GPU device to exercise a mid-sequence failure directly, the same
 * constraint that makes {@code BuiltinResolutionContractTest} a source-level test. Pins that the
 * texture is freed if anything after its creation (the upload/mip loop, or the view) fails, rather
 * than being registered into {@code textures}/{@code views} only after the whole sequence
 * succeeds with no reference kept to close it on failure.
 *
 * <p>Targets {@code load2D} specifically, not the {@code load} dispatcher above it. {@code load2D}'s
 * own leak window is a create-texture-then-create-VIEW split: {@code device.createTexture} succeeds,
 * then a separate {@code device.createTextureView} (or a mip-chain step) fails. That exact window
 * cannot happen for {@code loadVolume}'s {@link Volume3DTexture}: {@code create()} builds the image
 * and its {@code VK_IMAGE_VIEW_TYPE_3D} view together, atomically, in one constructor, so this
 * grep-based test has no {@code loadVolume} equivalent to add.
 *
 * <p>{@code loadVolume} has a different leak window instead: {@code create()} returns a live
 * volume, then the separate {@code volume.upload(asset)} call fails. That one IS real and IS
 * covered, just not by this test: {@code loadVolume} hoists its own {@code volume} local above its
 * try and frees it in the catch, the same shape this test pins for {@code load2D}'s
 * {@code texture}/{@code view}. Not verified by a grep pin like this one because doing so headless
 * would need a controlled instant device failure, not a nonexistent one; {@link Volume3DTextureTest}
 * takes the same trade-off (it can only test {@code validateUpload} headless; the actual
 * upload path is fully device-gated) and this class follows the same line.
 */
class PackTextureRegistryLoadLeakContractTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/dev/icehunter/fornax/pack/graph/PackTextureRegistry.java");

    @Test
    void loadFreesTheTextureIfAnythingAfterItFails() throws IOException {
        String source = Files.readString(SOURCE);
        int methodStart = source.indexOf("private void load2D(");
        assertTrue(methodStart >= 0, "load2D must still exist");
        String method = source.substring(methodStart, source.indexOf("\n    }\n", methodStart));

        assertTrue(method.contains("catch (IOException | RuntimeException e) {"),
                "a mid-sequence failure (decode, upload, or a transient GPU failure) must be caught");
        assertTrue(method.contains("if (texture != null) texture.close();"),
                "the catch must free the texture if it was created but never registered");
    }
}

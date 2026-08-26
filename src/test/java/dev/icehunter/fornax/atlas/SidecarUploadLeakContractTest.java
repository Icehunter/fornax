package dev.icehunter.fornax.atlas;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * {@code upload()} needs a live GPU device to exercise a mid-sequence failure directly, the same
 * constraint that makes {@code BuiltinResolutionContractTest} a source-level test. Pins that both
 * sidecar listeners' GPU-upload methods hoist their {@code texture}/{@code pages} allocations (and
 * intermediate mip {@code NativeImage}s) so a throw partway through frees whatever already
 * succeeded, matching the shape {@code ArrayTextures.create} and {@code BlockAtlasOverflow.build}
 * already use -- and that their two allocation calls specifically are wrapped so any exception from
 * them becomes a {@code GpuFatalException}.
 */
class SidecarUploadLeakContractTest {
    private static final Path NORMAL_LISTENER =
            Path.of("src/main/java/dev/icehunter/fornax/atlas/NormalMapAtlasReloadListener.java");
    private static final Path MATERIAL_LISTENER =
            Path.of("src/main/java/dev/icehunter/fornax/atlas/MaterialMapAtlasReloadListener.java");

    @Test
    void normalListenerUploadHoistsAndFreesOnFailure() throws IOException {
        assertHoistsAndFrees(NORMAL_LISTENER, "private static NormalMapAtlas upload(",
                "Normal map atlas base texture allocation failed",
                "Normal map atlas overflow pages allocation failed");
    }

    @Test
    void materialListenerUploadHoistsAndFreesOnFailure() throws IOException {
        assertHoistsAndFrees(MATERIAL_LISTENER, "private static MaterialMapAtlas upload(",
                "Material map atlas base texture allocation failed",
                "Material map atlas overflow pages allocation failed");
    }

    private static void assertHoistsAndFrees(Path path, String methodSignature,
                                             String baseTextureFatalMessage,
                                             String pagesFatalMessage) throws IOException {
        String source = Files.readString(path);
        int methodStart = source.indexOf(methodSignature);
        assertTrue(methodStart >= 0, methodSignature + " must still exist in " + path);
        // This method's own closing brace, not an inner block's -- an outer try/catch wraps almost
        // the whole body, so the first "\n    }\n" after methodStart is still the method's own end.
        String method = source.substring(methodStart, source.indexOf("\n    }\n", methodStart));

        assertTrue(method.contains("GpuTexture texture = null;") && method.contains("texture = device.createTexture("),
                "the base texture must be hoisted to a nulled local, assigned only once allocated");
        assertTrue(method.contains("ArrayTextures.Allocation pages = null;"),
                "the overflow-pages allocation must be hoisted to a nulled local");
        assertTrue(method.contains(baseTextureFatalMessage),
                "the base texture allocation call must be wrapped to raise a GpuFatalException");
        assertTrue(method.contains(pagesFatalMessage),
                "the overflow-pages allocation call must be wrapped to raise a GpuFatalException");

        int outerCatchIndex = method.lastIndexOf("} catch (RuntimeException e) {");
        assertTrue(outerCatchIndex >= 0, "an outer catch must free whatever already succeeded");
        String outerCatch = method.substring(outerCatchIndex);
        assertTrue(outerCatch.contains("texture.close();") && outerCatch.contains("pages.close();"),
                "the outer catch must close both the base texture and the overflow pages when they"
                        + " succeeded before a later step threw");
        assertTrue(outerCatch.contains("throw e;"),
                "the outer catch must rethrow the original exception, not swallow it");
    }
}

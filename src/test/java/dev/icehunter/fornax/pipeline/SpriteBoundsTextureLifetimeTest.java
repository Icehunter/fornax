package dev.icehunter.fornax.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.icehunter.fornax.atlas.AtlasGenerationSchedule;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

final class SpriteBoundsTextureLifetimeTest {
    @Test
    void destroyClosesBothViewsBeforeTheirTextures() throws IOException {
        assertTrue(invocations("destroy").contains(new Call(
                        Type.getInternalName(SpriteBoundsTexture.class), "closeGridResources")),
                "destroy must route every live grid through the ownership-aware close helper");
        List<Call> calls = invocations("closeGridResources");
        String viewOwner = Type.getInternalName(GpuTextureView.class);
        String textureOwner = Type.getInternalName(GpuTexture.class);

        List<Integer> viewCloses = indexesOf(calls, viewOwner, "close");
        List<Integer> textureCloses = indexesOf(calls, textureOwner, "close");

        assertEquals(2, viewCloses.size(),
                "both explicit sprite-grid views must be closed; dropping their references keeps"
                        + " the Vulkan textures' live-view counts above zero forever");
        assertEquals(2, textureCloses.size(), "both sprite-grid textures must still be closed");
        assertTrue(viewCloses.getLast() < textureCloses.getFirst(),
                "views must retire before their backing textures, matching Blaze3D ownership");
    }

    @Test
    void lazyAccessDoesNotBuildWhileTheBlockGenerationIsPending() throws IOException {
        assertPendingCheckPrecedesBuild("view");
        assertPendingCheckPrecedesBuild("rangeViewOrNull");

        String source = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/pipeline/SpriteBoundsTexture.java"));
        assertPendingBranchReturnsNeutral(source, "public static synchronized GpuTextureView view()");
        assertPendingBranchReturnsNeutral(source,
                "public static synchronized GpuTextureView rangeViewOrNull()");
    }

    @Test
    void pendingFallbackIsAnExplicitlyZeroedFloatGrid() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/pipeline/SpriteBoundsTexture.java"));
        int methodStart = source.indexOf("private static GpuTextureView neutralViewOrNull()");
        assertTrue(methodStart >= 0, "pending access needs a stable semantic-neutral view");
        String method = source.substring(methodStart, source.indexOf("\n    }\n", methodStart));

        assertTrue(method.contains("GpuFormat.RGBA32_FLOAT")
                        && method.contains(", 1, 1, 1, 1)"),
                "the fallback must match the grids' float format while remaining one texel");
        assertTrue(method.contains("MemoryUtil.memCalloc(4 * Float.BYTES)"),
                "new Vulkan memory is not guaranteed to be zero-filled; the fallback must upload"
                        + " an explicit degenerate rectangle/no-range texel");
        assertTrue(method.contains("writeToTexture("),
                "allocating a zero buffer is not enough; it must be uploaded before publication");
    }

    private static void assertPendingBranchReturnsNeutral(String source, String signature) {
        int methodStart = source.indexOf(signature);
        assertTrue(methodStart >= 0, "missing " + signature);
        String method = source.substring(methodStart, source.indexOf("\n    }\n", methodStart));
        assertTrue(method.contains("if (AtlasGenerationSchedule.hasPending(TextureAtlas.LOCATION_BLOCKS)) {\n"
                        + "            return neutralViewOrNull();\n"
                        + "        }"),
                signature + " must return the semantic-zero fallback from the actual pending"
                        + " branch; merely calling hasPending() is not a gate");
    }

    private static void assertPendingCheckPrecedesBuild(String methodName) throws IOException {
        List<Call> calls = invocations(methodName);
        int pendingCheck = calls.indexOf(new Call(
                Type.getInternalName(AtlasGenerationSchedule.class), "hasPending"));
        int build = calls.indexOf(new Call(
                Type.getInternalName(SpriteBoundsTexture.class), "build"));

        assertTrue(pendingCheck >= 0,
                methodName + " must return the neutral fallback during the retirement window"
                        + " instead of allocating a cross-generation grid");
        assertTrue(build > pendingCheck,
                methodName + " must check the pending block generation before lazy construction");
    }

    private static List<Integer> indexesOf(List<Call> calls, String owner, String name) {
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            Call call = calls.get(i);
            if (call.owner().equals(owner) && call.name().equals(name)) {
                indexes.add(i);
            }
        }
        return indexes;
    }

    private static List<Call> invocations(String methodName) throws IOException {
        List<Call> calls = new ArrayList<>();
        String resource = "/" + SpriteBoundsTexture.class.getName().replace('.', '/') + ".class";
        try (InputStream input = SpriteBoundsTexture.class.getResourceAsStream(resource)) {
            assertTrue(input != null, "missing bytecode for " + SpriteBoundsTexture.class.getName());
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    if (!name.equals(methodName)) {
                        return null;
                    }
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name,
                                                    String descriptor, boolean isInterface) {
                            calls.add(new Call(owner, name));
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return calls;
    }

    private record Call(String owner, String name) {
    }
}

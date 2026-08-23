package dev.icehunter.fornax.mixin.sodium;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.injection.ModifyArg;

final class DefaultChunkRendererFaceCullingMixinContractTest {
    private static final String FILL_COMMAND_BUFFER_DESCRIPTOR =
            "(Lnet/caffeinemc/mods/sodium/client/gpu/device/batch/MultiDrawBatch;"
                    + "Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion;"
                    + "Lnet/caffeinemc/mods/sodium/client/render/chunk/data/SectionRenderDataStorage;"
                    + "Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/ChunkRenderList;"
                    + "Lnet/caffeinemc/mods/sodium/client/render/viewport/CameraTransform;"
                    + "Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;ZZ)V";

    @Test
    void mixinModifiesTheBlockFaceCullingArgumentOfTheFillCommandBufferCall() {
        ModifyArg modifyArg = java.util.Arrays.stream(
                        DefaultChunkRendererFaceCullingMixin.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(ModifyArg.class))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "expected a @ModifyArg-annotated handler on DefaultChunkRendererFaceCullingMixin"));
        assertEquals(List.of("render"), List.of(modifyArg.method()));
        assertTrue(List.of(modifyArg.at()).stream().anyMatch(at ->
                        at.target().contains("fillCommandBuffer") && at.target().endsWith(FILL_COMMAND_BUFFER_DESCRIPTOR)),
                "must redirect the fillCommandBuffer(...) call site, not some other invocation");
        assertEquals(6, modifyArg.index(),
                "index 6 is fillCommandBuffer's useBlockFaceCulling boolean, not useIndexedTessellation (index 7)");
    }

    @Test
    void productionBytecodeStillCallsFillCommandBufferWithTheExpectedShape() throws IOException {
        List<Call> renderCalls = invocations(DefaultChunkRenderer.class, "render");
        assertTrue(renderCalls.stream().anyMatch(call ->
                        call.owner().equals("net/caffeinemc/mods/sodium/client/render/chunk/DefaultChunkRenderer")
                                && call.name().equals("fillCommandBuffer")
                                && call.descriptor().equals(FILL_COMMAND_BUFFER_DESCRIPTOR)),
                "render() must still call fillCommandBuffer with the exact 8-argument shape this mixin targets");

        String mixins = Files.readString(Path.of("src/main/resources/fornax.mixins.json"));
        assertTrue(mixins.contains("sodium.DefaultChunkRendererFaceCullingMixin"),
                "the face-culling mixin must be registered in fornax.mixins.json");
    }

    private static List<Call> invocations(Class<?> type, String methodName) throws IOException {
        List<Call> calls = new ArrayList<>();
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream(resource)) {
            assertTrue(input != null, "missing bytecode for " + type.getName());
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
                            calls.add(new Call(owner, name, descriptor));
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return calls;
    }

    private record Call(String owner, String name, String descriptor) {
    }
}

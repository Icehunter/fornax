package dev.icehunter.fornax.mixin.vanilla;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.texture.atlas.sources.DirectoryLister;
import net.minecraft.client.renderer.texture.atlas.sources.SingleFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

final class LabPbrAtlasProvenanceMixinContractTest {
    @Test
    void mixinDefinitionsMatchTheActualRuntimeTargetKindsAndStableMethods() {
        WrapOperation directoryWrap = wrapOperation(
                DirectoryListerLabPbrProvenanceMixin.class);
        assertEquals(List.of("run"), List.of(directoryWrap.method()));
        assertTrue(List.of(directoryWrap.at()).stream().anyMatch(at ->
                        at.target().contains("FileToIdConverter;listMatchingResources")),
                "directory provenance must wrap the stable run() resource-map seam");
        assertTrue(java.util.Arrays.stream(
                        DirectoryListerLabPbrProvenanceMixin.class.getDeclaredMethods())
                .anyMatch(method -> method.getAnnotation(ModifyVariable.class) != null),
                "directory provenance must wrap the concrete run() output argument");
    }

    @Test
    void productionBytecodeStillContainsEveryWrappedOwnershipSeam() throws IOException {
        assertTrue(invocations(DirectoryLister.class, "lambda$run$0").stream().anyMatch(call ->
                call.owner().equals("net/minecraft/client/renderer/texture/atlas/SpriteSource$Output")
                        && call.name().equals("add")
                        && call.descriptor().contains("Resource;")));
        assertTrue(invocations(SingleFile.class, "run").stream().anyMatch(call ->
                call.owner().equals("net/minecraft/client/renderer/texture/atlas/SpriteSource$Output")
                        && call.name().equals("add")
                        && call.descriptor().contains("Resource;")));
        String mixins = Files.readString(Path.of("src/main/resources/fornax.mixins.json"));
        assertTrue(mixins.contains("vanilla.DirectoryListerLabPbrProvenanceMixin"));
        assertTrue(mixins.contains("vanilla.SingleFileLabPbrProvenanceMixin"));
        assertTrue(!mixins.contains("vanilla.SpriteSourceOutputLabPbrProvenanceMixin"),
                "provenance must not inject into the SpriteSource.Output interface");
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

    private static WrapOperation wrapOperation(Class<?> mixin) {
        return java.util.Arrays.stream(mixin.getDeclaredMethods())
                .map(method -> method.getAnnotation(WrapOperation.class))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElseThrow();
    }

    private record Call(String owner, String name, String descriptor) {
    }
}

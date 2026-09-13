package dev.icehunter.fornax.mixin.sodium;

import com.google.gson.JsonParser;
import dev.icehunter.fornax.pipeline.TerrainMeshRevision;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Executes owned injected callbacks and their real stamp storage. Public signatures and injection
 * annotations pin the seam; this JVM does not transform a live renderer or allocate its GPU data. */
class SectionRenderDataStorageRevisionMixinTest {
    private SectionRenderDataStorageRevisionMixin storage() {
        return new SectionRenderDataStorageRevisionMixin();
    }

    private long revision(Object storage, int section) {
        return ((TerrainMeshRevision) storage).fornax$revision(section);
    }

    private Method handler(Object storage, String method) {
        return Arrays.stream(storage.getClass().getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Inject.class))
                .filter(m -> Arrays.stream(m.getAnnotation(Inject.class).method()).anyMatch(s ->
                        s.equals(method) || s.startsWith(method + "(")))
                .findFirst().orElseThrow(() -> new AssertionError("no mutation hook for " + method));
    }

    private void change(Object storage, String method, int section) {
        Method callback = handler(storage, method);
        assertDoesNotThrow(() -> {
            callback.setAccessible(true);
            var ci = new CallbackInfo(method, false);
            if (method.equals("setVertexData")) callback.invoke(storage, section, null, new int[]{4, 0, 0, 0, 0, 0, 0}, ci);
            else if (method.equals("onBufferResized") || method.equals("delete")) callback.invoke(storage, ci);
            else callback.invoke(storage, section, ci);
        });
    }

    private boolean segmentParameterIsCoerced(Method callback) {
        boolean[] coerced = {false};
        // @Coerce is CLASS-retained; inspect only this owned class's annotation metadata.
        assertDoesNotThrow(() -> new ClassReader(callback.getDeclaringClass().getName()).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (!name.equals(callback.getName())) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public AnnotationVisitor visitParameterAnnotation(int parameter, String annotation, boolean visible) {
                        if (parameter == 1 && annotation.equals(Type.getDescriptor(Coerce.class))) coerced[0] = true;
                        return null;
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES));
        return coerced[0];
    }

    @Test void sameSizedRebuildsInvalidateOnlyTheirSectionBeforeTheMutationReturns() {
        Object storage = storage();
        long previous = revision(storage, 2);
        long neighbor = revision(storage, 3);
        change(storage, "setVertexData", 2);
        long first = revision(storage, 2);
        change(storage, "setVertexData", 2);
        assertTrue(first > previous);
        assertTrue(revision(storage, 2) > first, "equal addresses/counts do not imply equal vertex content");
        assertEquals(neighbor, revision(storage, 3));
    }

    @Test void removalAndReuseCannotRestoreThePreviousGeneration() {
        Object storage = storage();
        change(storage, "setVertexData", 1);
        long populated = revision(storage, 1);
        change(storage, "removeVertexData", 1);
        long removedVertices = revision(storage, 1);
        assertTrue(removedVertices > populated);
        change(storage, "setVertexData", 1);
        long reused = revision(storage, 1);
        assertTrue(reused > removedVertices);
        change(storage, "removeData", 1);
        assertTrue(revision(storage, 1) > reused);
    }

    @Test void individualSegmentRelocationInvalidatesItsSlotAndIsOptionalOnEarlierApis() {
        Object storage = storage();
        long before = revision(storage, 2);
        long neighbor = revision(storage, 3);
        change(storage, "onVertexSegmentChanged", 2);
        assertTrue(revision(storage, 2) > before);
        assertEquals(neighbor, revision(storage, 3));
        var inject = handler(storage, "onVertexSegmentChanged").getAnnotation(Inject.class);
        assertEquals("HEAD", inject.at()[0].value());
        assertEquals(0, inject.require(), "older storage APIs do not publish per-segment relocation callbacks");
    }

    @Test void vertexReplacementDoesNotLinkEitherVersionsConcreteArenaSegmentClass() {
        var callback = handler(storage(), "setVertexData");
        assertEquals("setVertexData", callback.getAnnotation(Inject.class).method()[0]);
        assertEquals(int.class, callback.getParameterTypes()[0]);
        assertEquals(Object.class, callback.getParameterTypes()[1]);
        assertTrue(segmentParameterIsCoerced(callback));
        assertEquals(int[].class, callback.getParameterTypes()[2]);
        // Public signatures verified with javap -public -s: only this reference parameter changed.
        for (String segment : new String[]{"GlBufferSegment", "BufferSegment"}) {
            Type signature = Type.getMethodType("(ILnet/caffeinemc/mods/sodium/client/gpu/arena/" + segment + ";[I)V");
            Type[] arguments = signature.getArgumentTypes();
            assertEquals(arguments.length + 1, callback.getParameterCount());
            assertEquals(arguments[0], Type.getType(callback.getParameterTypes()[0]));
            assertEquals(arguments[2], Type.getType(callback.getParameterTypes()[2]));
            assertEquals(signature.getReturnType(), Type.getType(callback.getReturnType()));
        }
    }

    @Test void bufferResizeAndDeletionInvalidateEveryRegionSlot() {
        Object storage = storage();
        var before = new long[RenderRegion.REGION_SIZE];
        for (int i = 0; i < before.length; i++) before[i] = revision(storage, i);
        change(storage, "onBufferResized", 0);
        for (int i = 0; i < before.length; i++) {
            assertTrue(revision(storage, i) > before[i]);
            before[i] = revision(storage, i);
        }
        change(storage, "delete", 0);
        for (int i = 0; i < before.length; i++) assertTrue(revision(storage, i) > before[i]);
    }

    @Test void distinctSlotsAndReplacementStoragesNeverAliasTheirStamps() {
        Object first = storage();
        Object replacement = storage();
        var seen = new HashSet<Long>();
        for (int i = 0; i < RenderRegion.REGION_SIZE; i++) {
            assertTrue(revision(first, i) > 0, "zero remains available as unknown to consumers");
            assertTrue(seen.add(revision(first, i)));
            assertTrue(seen.add(revision(replacement, i)), "storage/region replacement cannot reuse a slot stamp");
        }
    }

    @Test void everyPublicMutationUsesTheRequiredHeadInjectionAndTheMixinIsRegistered() throws Exception {
        Object storage = storage();
        var api = TerrainMeshRevision.class;
        assertTrue(api.isAssignableFrom(storage.getClass()));
        assertTrue(!api.getPackageName().contains(".mixin"));
        for (String method : new String[]{"setVertexData", "removeVertexData", "removeData", "onBufferResized", "delete"}) {
            Method callback = handler(storage, method);
            var inject = callback.getAnnotation(Inject.class);
            assertEquals("HEAD", inject.at()[0].value(), "failed mutations must retire the old snapshot too");
            assertTrue(Arrays.stream(SectionRenderDataStorage.class.getMethods()).anyMatch(m ->
                            m.getName().equals(method)
                                    && (Arrays.asList(inject.method()).contains(m.getName())
                                        || Arrays.asList(inject.method()).contains(m.getName() + Type.getMethodDescriptor(m)))),
                    "the injection must match the dependency's public API");
        }
        var config = JsonParser.parseString(Files.readString(Path.of("src/main/resources/fornax.mixins.json"))).getAsJsonObject();
        assertTrue(config.getAsJsonArray("client").asList().stream().anyMatch(e ->
                e.getAsString().equals("sodium.SectionRenderDataStorageRevisionMixin")));
    }
}

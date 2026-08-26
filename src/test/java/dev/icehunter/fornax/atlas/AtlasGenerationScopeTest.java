package dev.icehunter.fornax.atlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.icehunter.fornax.mixin.vanilla.TextureAtlasReleaseGenerationMixin;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

final class AtlasGenerationScopeTest {
    private static final String SCOPE_OWNER =
            Type.getInternalName(AtlasGenerationSchedule.class) + "$RebuildScope";

    @Test
    void scopeRetainsUnchangedSidecarsButStillDefersBlockOverflow() {
        assertEquals(AtlasGenerationSchedule.RebuildScope.BLOCK_OVERFLOW_ONLY,
                AtlasGenerationSchedule.scopeFor(TextureAtlas.LOCATION_BLOCKS, true));
        assertEquals(AtlasGenerationSchedule.RebuildScope.BLOCK_FULL,
                AtlasGenerationSchedule.scopeFor(TextureAtlas.LOCATION_BLOCKS, false));
        assertEquals(AtlasGenerationSchedule.RebuildScope.NONE,
                AtlasGenerationSchedule.scopeFor(LabPbrGeometryBindings.BANNER_ATLAS, true));
        assertEquals(AtlasGenerationSchedule.RebuildScope.SIDECARS_ONLY,
                AtlasGenerationSchedule.scopeFor(LabPbrGeometryBindings.BANNER_ATLAS, false));
    }

    @Test
    void terminalTickUsesTheScopeAndKeepsTheGenerationPendingThroughRebuild() throws IOException {
        List<Call> calls = invocations(AtlasGenerationSchedule.class, "tick");
        int sidecarGate = calls.indexOf(new Call(SCOPE_OWNER, "rebuildSidecars"));
        int sidecarRebuild = calls.indexOf(new Call(
                Type.getInternalName(LabPbrAtlasPair.class), "rebuild"));
        int overflowGate = calls.indexOf(new Call(SCOPE_OWNER, "rebuildBlockResources"));
        int overflowRebuild = calls.indexOf(new Call(
                Type.getInternalName(BlockAtlasOverflow.class), "rebuild"));
        int pendingRemoval = calls.lastIndexOf(new Call(
                Type.getInternalName(java.util.Map.class), "remove"));

        assertTrue(sidecarGate >= 0 && sidecarRebuild > sidecarGate,
                "overflow-only retirement must skip the sidecar rebuild");
        assertTrue(overflowGate >= 0 && overflowRebuild > overflowGate,
                "block overflow must rebuild for both full and overflow-only retirement");
        assertTrue(pendingRemoval > overflowRebuild,
                "the sprite-grid gate must remain active until terminal block work succeeds");

        String source = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/atlas/AtlasGenerationSchedule.java"));
        int methodStart = source.indexOf("public static synchronized void tick(");
        String method = source.substring(methodStart, source.indexOf("\n    }\n", methodStart));
        assertTrue(method.contains("if (pending.scope().rebuildSidecars()) {\n"
                        + "            LabPbrAtlasPair.rebuild("),
                "the sidecar predicate must control the rebuild, not merely be evaluated near it");
        assertTrue(method.contains("if (pending.scope().rebuildBlockResources()) {\n"
                        + "            BlockAtlasOverflow.rebuild("),
                "the block-resource predicate must control the overflow rebuild");
    }

    @Test
    void releaseHookPassesItsScopeIntoTheDeferredSchedule() throws IOException {
        List<Call> calls = invocations(
                TextureAtlasReleaseGenerationMixin.class, "fornax$releasePreviousGeneration");
        String scheduleOwner = Type.getInternalName(AtlasGenerationSchedule.class);

        assertTrue(calls.contains(new Call(scheduleOwner, "scopeFor")),
                "the release hook must distinguish unchanged block overflow from a total no-op");
        assertTrue(calls.stream().anyMatch(call -> call.owner().equals(scheduleOwner)
                        && call.name().equals("scheduleRelease")
                        && call.descriptor().contains("AtlasGenerationSchedule$RebuildScope")),
                "the chosen rebuild scope must survive the three-frame retirement window");

        String source = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/mixin/vanilla/TextureAtlasReleaseGenerationMixin.java"));
        int methodStart = source.indexOf("private void fornax$releasePreviousGeneration(");
        String method = source.substring(methodStart, source.indexOf("\n    }\n", methodStart));
        assertTrue(method.contains("if (scope.rebuildSidecars()) {\n"
                        + "            LabPbrAtlasPair.replace("),
                "an overflow-only block reload must retain the published sidecar pair");
        assertTrue(method.contains("if (scope == RebuildScope.BLOCK_FULL) {\n"
                        + "            SpriteHeightRanges.replaceAll(List.of());"),
                "a failed full sidecar rebuild must not let the next grid publish the previous"
                        + " generation's height ranges");
        assertTrue(method.contains("if (scope.rebuildBlockResources()) {\n"
                        + "            BlockAtlasOverflow.releaseCurrent();"),
                "both block scopes must retire overflow and the sprite grid");
    }

    @Test
    void fullPendingWorkCannotBeDowngradedByAnOverflowOnlyReschedule() {
        var full = AtlasGenerationSchedule.RebuildScope.BLOCK_FULL;
        var overflowOnly = AtlasGenerationSchedule.RebuildScope.BLOCK_OVERFLOW_ONLY;

        assertEquals(full, full.merge(overflowOnly));
        assertEquals(full, overflowOnly.merge(full));
    }

    @Test
    void pagedAlbedoIsNotPublishedWithoutItsMatchingSidecarPair() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/atlas/BlockAtlasOverflow.java"));
        int methodStart = source.indexOf("public static void rebuild(");
        String method = source.substring(methodStart, source.indexOf("\n    }\n", methodStart));

        int pairCheck = method.indexOf("LabPbrAtlasPair.get(TextureAtlas.LOCATION_BLOCKS)");
        int build = method.indexOf("build(layout)");
        assertTrue(pairCheck >= 0 && pairCheck < build,
                "a nonzero albedo page count may only be published beside sidecar arrays with the"
                        + " same generation shape");
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
        Call(String owner, String name) {
            this(owner, name, "");
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Call call)) {
                return false;
            }
            return this.owner.equals(call.owner)
                    && this.name.equals(call.name)
                    && (this.descriptor.isEmpty() || call.descriptor.isEmpty()
                    || this.descriptor.equals(call.descriptor));
        }

        @Override
        public int hashCode() {
            return 31 * this.owner.hashCode() + this.name.hashCode();
        }
    }
}

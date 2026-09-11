package dev.icehunter.fornax.mixin.sodium;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks both material-id mixins use {@code MaterialIdContext.setAll}, not five separate setter
 * calls. Each of those calls read thread storage on its own, once per block or fluid surface, in
 * the busiest part of building the world's mesh. A mixin cannot run inside a unit test without a
 * live game (see {@code .claude/rules/mixins.md}), so this test reads the source file instead,
 * the same way {@code ChunkBuilderMeshingTaskMixinContractTest} does.
 */
final class MaterialIdMixinsUseBulkAccessorContractTest {
    @Test
    void blockRendererMixinUsesTheBulkSetter() throws IOException {
        assertUsesBulkSetter("src/main/java/dev/icehunter/fornax/mixin/sodium/BlockRendererMaterialIdMixin.java");
    }

    @Test
    void fluidRendererMixinUsesTheBulkSetter() throws IOException {
        assertUsesBulkSetter("src/main/java/dev/icehunter/fornax/mixin/sodium/FluidRendererMaterialIdMixin.java");
    }

    private static void assertUsesBulkSetter(String path) throws IOException {
        String source = Files.readString(Path.of(path));
        assertTrue(source.contains("MaterialIdContext.setAll("),
                path + " must set every per-block fact through MaterialIdContext.setAll, one "
                        + "ThreadLocal probe instead of five");
        assertFalse(source.contains("MaterialIdContext.set(") && source.contains("MaterialIdContext.setPrecipitation("),
                path + " must not mix the bulk setter with the individual setters it replaces");
    }
}

package dev.icehunter.fornax.mixin.sodium;

import dev.icehunter.fornax.pipeline.FornaxVertexFacts;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.Inject;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the vertex-facts mixin to the copy it hooks and to the registry. Unregistered, it never
 * applies and re-encoded translucent quads silently lose every lane.
 */
final class ChunkVertexFactsMixinContractTest {

    @Test
    void mixinIsRegisteredAndHooksCopyVertexTo() throws IOException {
        String mixins = Files.readString(Path.of("src/main/resources/fornax.mixins.json"));
        assertTrue(mixins.contains("sodium.ChunkVertexFactsMixin"),
                "ChunkVertexFactsMixin must be listed in fornax.mixins.json or it never applies");

        Inject inject = Arrays.stream(ChunkVertexFactsMixin.class.getDeclaredMethods())
                .map(m -> m.getAnnotation(Inject.class))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected an @Inject handler on the mixin"));
        assertEquals(List.of("copyVertexTo"), List.of(inject.method()));
        assertTrue(FornaxVertexFacts.class.isAssignableFrom(ChunkVertexFactsMixin.class),
                "the mixin must implement FornaxVertexFacts; the encoder reads the stamp through it");
    }

    @Test
    void rendererStillCopiesVerticesThroughTheStaticMethodTheMixinHooks() throws NoSuchMethodException {
        Method copy = ChunkVertexEncoder.Vertex.class.getMethod("copyVertexTo",
                ChunkVertexEncoder.Vertex.class, ChunkVertexEncoder.Vertex.class);
        assertTrue(Modifier.isStatic(copy.getModifiers()), "the handler signature assumes copyVertexTo is static");
        assertEquals(void.class, copy.getReturnType());
    }
}

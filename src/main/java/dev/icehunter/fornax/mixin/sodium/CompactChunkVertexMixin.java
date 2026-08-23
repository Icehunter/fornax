package dev.icehunter.fornax.mixin.sodium;
import dev.icehunter.fornax.pipeline.FornaxChunkVertex;

import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.impl.CompactChunkVertex;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Substitutes {@link FornaxChunkVertex} (a widened, 24-byte parallel vertex format) for {@code
 * CompactChunkVertex} at the one place the official Sodium jar constructs it -- {@code
 * ChunkMeshFormats}'s static initializer.
 *
 * <p>A parallel format registered here is safe because {@code ChunkMeshFormats.COMPACT} is
 * declared as the {@code ChunkVertexType} interface type, and every real consumer of vertex layout
 * goes through that interface dynamically rather than the concrete {@code CompactChunkVertex} class
 * or its {@code STRIDE} constant (see {@link FornaxChunkVertex}'s javadoc). No {@code @Overwrite}
 * of {@code CompactChunkVertex} itself is needed.
 *
 * <p>{@code ChunkMeshFormats.<clinit>} is exactly {@code NEW CompactChunkVertex; DUP;
 * INVOKESPECIAL <init>; PUTSTATIC COMPACT} -- one constructor call, redirected here to return a
 * {@code FornaxChunkVertex} instance instead (declared to return the exact {@code
 * CompactChunkVertex} type the redirected instruction produces, since {@code FornaxChunkVertex
 * extends CompactChunkVertex}).
 */
@Mixin(ChunkMeshFormats.class)
public class CompactChunkVertexMixin {
    @Redirect(
            method = "<clinit>",
            at = @At(value = "NEW", target = "net/caffeinemc/mods/sodium/client/render/chunk/vertex/format/impl/CompactChunkVertex")
    )
    private static CompactChunkVertex fornax$substituteWidenedVertexFormat() {
        return new FornaxChunkVertex();
    }
}

package dev.icehunter.fornax.mixin.sodium;

import dev.icehunter.fornax.pipeline.FornaxVertexFacts;
import dev.icehunter.fornax.pipeline.VertexFacts;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stamps a chunk vertex with its block's facts so a quad re-encoded after meshing keeps them.
 *
 * <p>Translucent sorting copies each quad's vertices while the owning block is meshed, and copies
 * them again when it splits an intersecting quad, outside any block's scope. Both copies go through
 * {@code copyVertexTo}: the first stamps from the live context, later ones from the source's stamp.
 * {@code FornaxChunkVertex} prefers a stamp and otherwise reads the context. No-op with no pack:
 * only Fornax's encoder reads the stamp, and it holds what the context would have said.
 */
@Mixin(ChunkVertexEncoder.Vertex.class)
public class ChunkVertexFactsMixin implements FornaxVertexFacts {
    @Unique
    private int fornax$facts;

    @Override
    public int fornax$facts() {
        return this.fornax$facts;
    }

    @Override
    public void fornax$facts(int facts) {
        this.fornax$facts = facts;
    }

    @Inject(method = "copyVertexTo", at = @At("RETURN"))
    private static void fornax$stampCopy(ChunkVertexEncoder.Vertex src, ChunkVertexEncoder.Vertex dst, CallbackInfo ci) {
        int sourceFacts = ((FornaxVertexFacts) src).fornax$facts();
        ((FornaxVertexFacts) dst).fornax$facts(VertexFacts.isStamped(sourceFacts) ? sourceFacts : VertexFacts.snapshot());
    }
}

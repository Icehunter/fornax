package dev.icehunter.fornax.pipeline;

/**
 * A chunk vertex carrying its block's packed facts (see {@link VertexFacts}). Implemented onto
 * Sodium's vertex class by {@code ChunkVertexFactsMixin}; the encoder reads the stamp through this
 * interface and never names the mixin.
 */
public interface FornaxVertexFacts {
    int fornax$facts();

    void fornax$facts(int facts);
}

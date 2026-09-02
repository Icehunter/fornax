package dev.icehunter.fornax.pipeline;

import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;

/**
 * A chunk vertex's per-block facts, packed into one int so they ride on the vertex object and
 * survive encoding after the producing block is gone.
 *
 * <p>The encoder reads facts from {@link MaterialIdContext}, live only while the owning block is
 * meshed. Translucent sorting keeps quads as objects, splits intersecting ones along a plane, and
 * re-encodes the pieces after the per-block loop, with the context cleared. Unstamped, those pieces
 * encode every lane as "nothing".
 *
 * <p>A stamped vertex captured its facts while its block's context was live. The encoder prefers
 * the stamp and otherwise reads the context; the push path's scratch vertices are never stamped.
 *
 * <p>Layout: bits 0..15 material id, 16..17 precipitation, 18..21 light emission, 22..28 block
 * class flags, bit 30 the stamped marker. Bit 31 stays clear so a stamp is never negative.
 */
public final class VertexFacts {
    private static final int ID_MASK = 0xFFFF;
    private static final int PRECIPITATION_SHIFT = 16;
    private static final int PRECIPITATION_MASK = 0x3;
    private static final int EMISSION_SHIFT = 18;
    private static final int EMISSION_MASK = 0xF;
    private static final int CLASS_SHIFT = 22;
    /** Set by {@link #pack}; a zero int is unstamped. */
    public static final int STAMPED = 1 << 30;

    private VertexFacts() {
    }

    public static int pack(int materialId, int precipitation, int lightEmission, int blockClassFlags) {
        return STAMPED
                | (materialId & ID_MASK)
                | ((precipitation & PRECIPITATION_MASK) << PRECIPITATION_SHIFT)
                | ((lightEmission & EMISSION_MASK) << EMISSION_SHIFT)
                | ((blockClassFlags & BlockClasses.MASK) << CLASS_SHIFT);
    }

    /** The current block's facts from the context. */
    public static int snapshot() {
        return pack(MaterialIdContext.get(), MaterialIdContext.getPrecipitation(),
                MaterialIdContext.getLightEmission(), MaterialIdContext.getBlockClass());
    }

    public static boolean isStamped(int facts) {
        return (facts & STAMPED) != 0;
    }

    public static int materialId(int facts) {
        return facts & ID_MASK;
    }

    public static int precipitation(int facts) {
        return (facts >>> PRECIPITATION_SHIFT) & PRECIPITATION_MASK;
    }

    public static int lightEmission(int facts) {
        return (facts >>> EMISSION_SHIFT) & EMISSION_MASK;
    }

    public static int blockClassFlags(int facts) {
        return (facts >>> CLASS_SHIFT) & BlockClasses.MASK;
    }

    /**
     * The first vertex's stamp when it carries one, else the live context. A quad's four vertices
     * come from one block, so one vertex speaks for the quad.
     */
    public static int resolve(ChunkVertexEncoder.Vertex[] vertices) {
        if (vertices.length > 0 && vertices[0] instanceof FornaxVertexFacts stamped) {
            int facts = stamped.fornax$facts();
            if (isStamped(facts)) {
                return facts;
            }
        }
        return snapshot();
    }
}

package dev.icehunter.fornax.pipeline;

import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.minecraft.world.level.biome.Biome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Agreement point: a_Normal.yz carries the u16 material id FornaxChunkVertex packs in,
 * low byte first, exactly as a pack's {@code chunk_vertex.glsl} decodes it back out; a_Normal.w
 * carries the biome precipitation TYPE that same decode hands to a pack as {@code _precipitates}.
 */
class FornaxChunkVertexTest {
    @AfterEach
    void reset() {
        MaterialIdContext.clear();
    }

    @Test
    void packsMaterialIdIntoNormalYzLittleEndian() {
        MaterialIdContext.set(0x1234);
        MaterialIdContext.setPrecipitation(MaterialIdContext.PRECIPITATION_NONE);

        ChunkVertexEncoder.Vertex[] vertices = ChunkVertexEncoder.Vertex.uninitializedQuad();
        // An UP-facing quad (all y=0) so deriveFaceIndex is deterministic: Direction.UP.ordinal() == 1.
        setVertex(vertices[0], 0, 0, 0);
        setVertex(vertices[1], 0, 0, 1);
        setVertex(vertices[2], 1, 0, 0);
        setVertex(vertices[3], 1, 0, 1);

        long ptr = MemoryUtil.nmemAlloc(FornaxChunkVertex.STRIDE * 4);
        try {
            new FornaxChunkVertex().getEncoder().write(ptr, 0, vertices, 0);

            assertEquals(1, MemoryUtil.memGetByte(ptr + 20) & 0xFF);  // faceIndex (UP)
            assertEquals(0x34, MemoryUtil.memGetByte(ptr + 21) & 0xFF); // material id low byte
            assertEquals(0x12, MemoryUtil.memGetByte(ptr + 22) & 0xFF); // material id high byte
            // w is not reserved: it carries the biome precipitation TYPE. Asserted at NONE here and
            // at RAIN and SNOW by the test below -- pinning the lane in ALL THREE states, since a
            // byte stuck at a constant would pass any one-sided check.
            assertEquals(MaterialIdContext.PRECIPITATION_NONE, MemoryUtil.memGetByte(ptr + 23) & 0xFF);
        } finally {
            MemoryUtil.nmemFree(ptr);
        }
    }

    /**
     * The precipitation TYPE reaches a_Normal.w intact, and without disturbing the material id
     * beside it.
     *
     * <p>The lane carries 0 none / 1 rain / 2 snow, not a mere 0/1 flag. That distinction is the whole
     * point of the test: a shader that only knows "precipitates" has to ask the CAMERA whether it is
     * snowing, which puddles a snowfield while the player stands in rain beside it and then dries
     * the entire world the instant they step across. SNOW must therefore arrive as 2 and not
     * collapse to 1 anywhere along the way.
     *
     * <p>Worth its own test rather than an extra assert: the type shares an int with the id, so a
     * shift or mask error shows up as the id changing when only the type was meant to, which is
     * exactly the kind of corruption that reads as random blocks having the wrong material.
     */
    @Test
    void packsPrecipitationTypeIntoNormalW() {
        int[] types = {
            MaterialIdContext.PRECIPITATION_NONE,
            MaterialIdContext.PRECIPITATION_RAIN,
            MaterialIdContext.PRECIPITATION_SNOW,
        };

        ChunkVertexEncoder.Vertex[] vertices = ChunkVertexEncoder.Vertex.uninitializedQuad();
        setVertex(vertices[0], 0, 0, 0);
        setVertex(vertices[1], 0, 0, 1);
        setVertex(vertices[2], 1, 0, 0);
        setVertex(vertices[3], 1, 0, 1);

        long ptr = MemoryUtil.nmemAlloc(FornaxChunkVertex.STRIDE * 4);
        try {
            for (int type : types) {
                MaterialIdContext.set(0x1234);
                MaterialIdContext.setPrecipitation(type);
                new FornaxChunkVertex().getEncoder().write(ptr, 0, vertices, 0);

                assertEquals(type, MemoryUtil.memGetByte(ptr + 23) & 0xFF, "w byte for type " + type);
                assertEquals(0x34, MemoryUtil.memGetByte(ptr + 21) & 0xFF, "id low byte for type " + type);
                assertEquals(0x12, MemoryUtil.memGetByte(ptr + 22) & 0xFF, "id high byte for type " + type);
            }
        } finally {
            MemoryUtil.nmemFree(ptr);
        }
    }

    /**
     * Vanilla's own {@code Biome.Precipitation} maps onto the wire values, exhaustively.
     *
     * <p>Both mesh paths hand the enum straight to {@link MaterialIdContext}, so this mapping IS the
     * contract -- and it is the one place a silent regression could reintroduce the bug without any
     * packing test noticing, by folding SNOW onto RAIN. Enumerating {@code values()} rather than
     * listing three constants also fails loudly if vanilla ever adds a fourth kind, which the switch
     * in {@code setPrecipitation} would no longer compile against anyway.
     */
    @Test
    void mapsVanillaPrecipitationEnumOntoDistinctWireValues() {
        assertEquals(3, Biome.Precipitation.values().length,
                "vanilla grew a precipitation kind; the wire mapping needs a value for it");

        MaterialIdContext.setPrecipitation(Biome.Precipitation.NONE);
        assertEquals(MaterialIdContext.PRECIPITATION_NONE, MaterialIdContext.getPrecipitation());

        MaterialIdContext.setPrecipitation(Biome.Precipitation.RAIN);
        assertEquals(MaterialIdContext.PRECIPITATION_RAIN, MaterialIdContext.getPrecipitation());

        MaterialIdContext.setPrecipitation(Biome.Precipitation.SNOW);
        assertEquals(MaterialIdContext.PRECIPITATION_SNOW, MaterialIdContext.getPrecipitation());
    }

    /**
     * An unresolved block reads as RAIN, not NONE.
     *
     * <p>Pinned because it is a deliberate asymmetry that looks like a bug: every block in the world
     * was wet in rain before this lane existed, so defaulting to NONE would stamp a permanently dry
     * patch into a mesh that then persists until the chunk is rebuilt. Both mesh mixins rely on this
     * for their {@code level == null} path.
     */
    @Test
    void clearLeavesPrecipitationAtRain() {
        MaterialIdContext.setPrecipitation(MaterialIdContext.PRECIPITATION_SNOW);
        MaterialIdContext.clear();

        assertEquals(MaterialIdContext.PRECIPITATION_RAIN, MaterialIdContext.getPrecipitation());
        assertEquals(0, MaterialIdContext.get());
    }

    @Test
    void pinsMaterialIdByteLayoutAtBoundaries() {
        // 0 = uncategorized, 255/256 straddle the byte boundary (a swapped decode survives probes
        // whose two bytes merely differ, but not these), 1023 = the display cap, 65535 = full u16
        // (proves no spill into byte3/w).
        int[] boundaryIds = {0, 255, 256, 1023, 65535};

        // Set explicitly rather than leaning on the ThreadLocal's zero-initialised state: clear()
        // leaves the lane at RAIN, so a test that merely assumed 0 here would pass or fail on JUnit's
        // method ordering.
        MaterialIdContext.setPrecipitation(MaterialIdContext.PRECIPITATION_NONE);

        ChunkVertexEncoder.Vertex[] vertices = ChunkVertexEncoder.Vertex.uninitializedQuad();
        setVertex(vertices[0], 0, 0, 0);
        setVertex(vertices[1], 0, 0, 1);
        setVertex(vertices[2], 1, 0, 0);
        setVertex(vertices[3], 1, 0, 1);

        long ptr = MemoryUtil.nmemAlloc(FornaxChunkVertex.STRIDE * 4);
        try {
            for (int id : boundaryIds) {
                MaterialIdContext.set(id);
                new FornaxChunkVertex().getEncoder().write(ptr, 0, vertices, 0);

                assertEquals(1, MemoryUtil.memGetByte(ptr + 20) & 0xFF, "face index for id " + id);
                assertEquals(id & 0xFF, MemoryUtil.memGetByte(ptr + 21) & 0xFF, "low byte for id " + id);
                assertEquals((id >> 8) & 0xFF, MemoryUtil.memGetByte(ptr + 22) & 0xFF, "high byte for id " + id);
                assertEquals(0, MemoryUtil.memGetByte(ptr + 23) & 0xFF, "w byte for id " + id);
            }
        } finally {
            MemoryUtil.nmemFree(ptr);
        }
    }

    /**
     * Every one of vanilla's sixteen light levels survives a_Position.w's UNORM16 round trip EXACTLY,
     * and does so ALONGSIDE every class-flag pattern rather than only on its own.
     *
     * <p>Bit-exactness is a load-bearing claim and not a nicety. The lane is a bit FIELD now, so an
     * inexact channel would not merely dim a lamp -- it would flip a class bit, and a block that
     * silently became coal ore (or stopped being one) is indistinguishable from a shader bug in a
     * screenshot. Asserted at ZERO tolerance for that reason, over the full cross product of the
     * sixteen levels and a set of flag patterns chosen to exercise every bit of the field: none, the
     * lowest bit, the HIGHEST representable bit, and all twelve at once.
     *
     * <p>The shader-side decode is asserted as the shader actually spells it -- {@code
     * uint(a_Position.w * 65535.0 + 0.5)} -- and not merely as the code that was written, so this
     * fails if the float round trip stops recovering the code even though the memory is correct.
     */
    @Test
    void packsEveryLightEmissionLevelExactlyIntoPositionW() {
        ChunkVertexEncoder.Vertex[] vertices = ChunkVertexEncoder.Vertex.uninitializedQuad();
        setVertex(vertices[0], 0, 0, 0);
        setVertex(vertices[1], 0, 0, 1);
        setVertex(vertices[2], 1, 0, 0);
        setVertex(vertices[3], 1, 0, 1);

        int[] flagPatterns = {0, BlockClasses.COAL, 1 << (BlockClasses.WIDTH - 1), BlockClasses.MASK};

        long ptr = MemoryUtil.nmemAlloc(FornaxChunkVertex.STRIDE * 4);
        try {
            for (int flags : flagPatterns) {
                for (int level = 0; level <= MaterialIdContext.MAX_LIGHT_EMISSION; level++) {
                    MaterialIdContext.set(0x1234);
                    MaterialIdContext.setLightEmission(level);
                    MaterialIdContext.setBlockClass(flags);
                    new FornaxChunkVertex().getEncoder().write(ptr, 0, vertices, 0);

                    String where = "level " + level + " flags " + flags;
                    int code = MemoryUtil.memGetShort(ptr + 6) & 0xFFFF;
                    assertEquals(level | (flags << 4), code, "a_Position.w code for " + where);

                    // What the shader actually computes, term for term (chunk_vertex.glsl).
                    int decoded = (int) (code / 65535.0f * 65535.0f + 0.5f);
                    assertEquals(level, decoded & 15, "level recovered by the shader for " + where);
                    assertEquals(flags, decoded >> 4, "class recovered by the shader for " + where);

                    // Position xyz must be untouched by the lane sharing the attribute with them.
                    // x = 0 normalizes to (0 - -8)/32 = 0.25, i.e. code 16384.
                    assertEquals(16384, MemoryUtil.memGetShort(ptr) & 0xFFFF, "x code for " + where);
                }
            }
        } finally {
            MemoryUtil.nmemFree(ptr);
        }
    }

    /**
     * The two facts sharing a_Position.w cannot reach each other's bits.
     *
     * <p>The failure this pins is silent in both directions and invisible in a screenshot until it is
     * badly wrong: emission spilling upward would make a torch read as coal ore, and a class flag
     * spilling downward would light every coal ore in the world at some fraction of glowstone.
     * Asserted as an independence property -- vary one input, the other's decoded value must not
     * move -- rather than as a list of expected codes, because a list would agree with any
     * consistent WRONG shift.
     */
    @Test
    void emissionAndClassFlagsOccupyDisjointBits() {
        ChunkVertexEncoder.Vertex[] vertices = ChunkVertexEncoder.Vertex.uninitializedQuad();
        setVertex(vertices[0], 0, 0, 0);
        setVertex(vertices[1], 0, 0, 1);
        setVertex(vertices[2], 1, 0, 0);
        setVertex(vertices[3], 1, 0, 1);

        long ptr = MemoryUtil.nmemAlloc(FornaxChunkVertex.STRIDE * 4);
        try {
            // Emission swept with the class held at COAL: the class must never move.
            for (int level = 0; level <= MaterialIdContext.MAX_LIGHT_EMISSION; level++) {
                MaterialIdContext.setLightEmission(level);
                MaterialIdContext.setBlockClass(BlockClasses.COAL);
                new FornaxChunkVertex().getEncoder().write(ptr, 0, vertices, 0);
                int code = MemoryUtil.memGetShort(ptr + 6) & 0xFFFF;
                assertEquals(BlockClasses.COAL, code >> 4, "class disturbed by level " + level);
            }
            // Class swept with a torch's level 14 held: the level must never move.
            for (int flags = 0; flags <= BlockClasses.MASK; flags++) {
                MaterialIdContext.setLightEmission(14);
                MaterialIdContext.setBlockClass(flags);
                new FornaxChunkVertex().getEncoder().write(ptr, 0, vertices, 0);
                int code = MemoryUtil.memGetShort(ptr + 6) & 0xFFFF;
                assertEquals(14, code & 15, "level disturbed by flags " + flags);
            }
        } finally {
            MemoryUtil.nmemFree(ptr);
        }
    }

    /**
     * The class lane defaults to NONE on clear, like emission and unlike precipitation.
     *
     * <p>Belonging to no category is what every block did before this lane existed, so it is the
     * neutral state. A leftover COAL flag would stamp itself into whichever block is meshed next, and
     * it would persist in that mesh until the chunk rebuilt -- the same failure the precipitation
     * lane shipped once when water inherited the previous block's flag.
     */
    @Test
    void clearLeavesBlockClassAtNone() {
        MaterialIdContext.setBlockClass(BlockClasses.COAL);
        MaterialIdContext.clear();

        assertEquals(BlockClasses.NONE, MaterialIdContext.getBlockClass());
    }

    /**
     * A flag pattern too wide for the lane throws rather than being silently masked.
     *
     * <p>Masking would not drop the bit, it would SHIFT it off the top of a 16-bit channel and read
     * back as a different class -- so a thirteenth category would arrive looking exactly like one of
     * the first twelve, with no error anywhere.
     */
    @Test
    void rejectsBlockClassOutsideTheLaneWidth() {
        assertThrows(IllegalArgumentException.class, () -> MaterialIdContext.setBlockClass(-1));
        assertThrows(IllegalArgumentException.class,
                () -> MaterialIdContext.setBlockClass(BlockClasses.MASK + 1));
    }

    /**
     * Every vertex of the quad carries the level, not just the first.
     *
     * <p>The encoder writes inside a four-iteration loop and the emission is hoisted OUT of it, so a
     * misplaced write would light one corner of every block and leave the interpolator to ramp it to
     * nothing across the face. That reads as a gradient, which looks deliberate.
     */
    @Test
    void everyVertexOfTheQuadCarriesTheSameLightEmission() {
        MaterialIdContext.setLightEmission(15);

        ChunkVertexEncoder.Vertex[] vertices = ChunkVertexEncoder.Vertex.uninitializedQuad();
        setVertex(vertices[0], 0, 0, 0);
        setVertex(vertices[1], 0, 0, 1);
        setVertex(vertices[2], 1, 0, 0);
        setVertex(vertices[3], 1, 0, 1);

        long ptr = MemoryUtil.nmemAlloc(FornaxChunkVertex.STRIDE * 4);
        try {
            new FornaxChunkVertex().getEncoder().write(ptr, 0, vertices, 0);
            for (int i = 0; i < 4; i++) {
                assertEquals(15, MemoryUtil.memGetShort(ptr + i * FornaxChunkVertex.STRIDE + 6) & 0xFFFF,
                        "a_Position.w for vertex " + i);
            }
        } finally {
            MemoryUtil.nmemFree(ptr);
        }
    }

    /**
     * An unresolved block emits NOTHING, the mirror image of precipitation defaulting to RAIN.
     *
     * <p>Both defaults are "what the world did before this lane existed", which points opposite ways:
     * every block was wet in rain, and no block glowed. A lane that defaulted to emitting would stamp
     * a glowing patch into a mesh that survives until the chunk is rebuilt.
     */
    @Test
    void clearLeavesLightEmissionAtZero() {
        MaterialIdContext.setLightEmission(15);
        MaterialIdContext.clear();

        assertEquals(0, MaterialIdContext.getLightEmission());
    }

    /**
     * A level outside vanilla's 0..15 throws rather than being silently masked.
     *
     * <p>The encoder's exactness argument is stated in terms of that range; a value outside it means
     * vanilla's contract moved, and clamping would hide that behind an image that merely looks
     * slightly wrong.
     */
    @Test
    void rejectsLightEmissionOutsideVanillaRange() {
        assertThrows(IllegalArgumentException.class, () -> MaterialIdContext.setLightEmission(-1));
        assertThrows(IllegalArgumentException.class, () -> MaterialIdContext.setLightEmission(16));
    }

    /**
     * {@link MaterialIdContext#setAtlasPage}/{@link MaterialIdContext#getAtlasPage} round-trip, and
     * default to page 0 on {@link MaterialIdContext#clear()} -- same neutral-default reasoning as
     * {@link #clearLeavesBlockClassAtNone}: page 0 is the neutral default for an unpaged atlas, so
     * a leftover nonzero page from whatever block was meshed before it must never survive a clear.
     */
    @Test
    void atlasPageRoundTripsAndDefaultsToZeroOnClear() {
        MaterialIdContext.setAtlasPage(3);
        assertEquals(3, MaterialIdContext.getAtlasPage());

        MaterialIdContext.clear();
        assertEquals(0, MaterialIdContext.getAtlasPage());
    }

    /** A negative page is rejected rather than silently stored -- there is no packed lane for this
     * value to overflow yet (see {@link FornaxChunkVertex#PAGE_INDEX_BIT_OFFSET}'s own doc), but a
     * negative page is nonsensical regardless of whether anything downstream reads it yet. */
    @Test
    void rejectsNegativeAtlasPage() {
        assertThrows(IllegalArgumentException.class, () -> MaterialIdContext.setAtlasPage(-1));
    }

    /**
     * {@link BlockClasses}' flag slice and {@link FornaxChunkVertex}'s reserved page-index slice
     * cannot overlap, and neither runs past the 16-bit lane they share.
     *
     * <p>Both slices sit above the 4-bit emission nibble at fixed offsets ({@code BlockClasses}
     * starts at bit 4; the page index is declared to start at {@link
     * FornaxChunkVertex#PAGE_INDEX_BIT_OFFSET}) -- this is the test that would catch either constant
     * drifting into the other's territory, since nothing in the encoder itself would notice (the page
     * index is unwritten today, so a silent overlap would only surface once a later phase starts
     * writing it, as class flags corrupting into the wrong bits or vice versa).
     */
    @Test
    void pageIndexAndBlockClassBitSlicesDoNotOverlap() {
        int emissionBits = 4;
        int blockClassStart = emissionBits;
        int blockClassEnd = blockClassStart + BlockClasses.WIDTH; // exclusive

        assertEquals(FornaxChunkVertex.PAGE_INDEX_BIT_OFFSET, blockClassEnd,
                "the page-index slice must start exactly where BlockClasses' slice ends, with no gap"
                        + " and no overlap");
        assertEquals(16, FornaxChunkVertex.PAGE_INDEX_BIT_OFFSET + FornaxChunkVertex.PAGE_INDEX_BIT_WIDTH,
                "the page-index slice must end exactly at the 16-bit lane's own boundary");
        assertEquals(32, FornaxChunkVertex.MAX_ATLAS_PAGES,
                "5 reserved bits address exactly 32 pages");
    }

    /**
     * COAL still round-trips through {@link FornaxChunkVertex#packBlockFacts} at the narrower 7-bit
     * {@link BlockClasses#WIDTH} -- pinned because {@link BlockClasses#WIDTH} shrank from 12 to 7 to
     * make room for the page-index slice above it (see that field's own doc), and COAL (bit 0 of the
     * flag field) sits well inside either width, but a regression that widened the mask again or
     * shifted the flag origin would silently coexist with a class flag corrupting into the page-index
     * bits above it.
     */
    @Test
    void coalRoundTripsAtTheNarrowerBlockClassWidth() {
        assertEquals(127, BlockClasses.MASK, "MASK must track WIDTH=7 exactly: (1 << 7) - 1");

        MaterialIdContext.setLightEmission(0);
        MaterialIdContext.setBlockClass(BlockClasses.COAL);

        ChunkVertexEncoder.Vertex[] vertices = ChunkVertexEncoder.Vertex.uninitializedQuad();
        setVertex(vertices[0], 0, 0, 0);
        setVertex(vertices[1], 0, 0, 1);
        setVertex(vertices[2], 1, 0, 0);
        setVertex(vertices[3], 1, 0, 1);

        long ptr = MemoryUtil.nmemAlloc(FornaxChunkVertex.STRIDE * 4);
        try {
            new FornaxChunkVertex().getEncoder().write(ptr, 0, vertices, 0);
            int code = MemoryUtil.memGetShort(ptr + 6) & 0xFFFF;
            assertEquals(BlockClasses.COAL, code >> 4 & BlockClasses.MASK, "COAL must survive the pack");
            // Nothing above bit 10 is written yet -- the reserved page-index slice must read back 0.
            assertEquals(0, code >> FornaxChunkVertex.PAGE_INDEX_BIT_OFFSET,
                    "the unwritten page-index slice must read as 0, not garbage from a mis-shifted flag");
        } finally {
            MemoryUtil.nmemFree(ptr);
        }
    }

    private static void setVertex(ChunkVertexEncoder.Vertex vertex, float x, float y, float z) {
        vertex.x = x;
        vertex.y = y;
        vertex.z = z;
        vertex.color = 0xFFFFFFFF;
        vertex.ao = 1.0f;
        vertex.u = 0.0f;
        vertex.v = 0.0f;
        vertex.light = 0;
    }
}

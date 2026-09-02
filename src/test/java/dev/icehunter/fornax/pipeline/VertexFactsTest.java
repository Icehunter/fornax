package dev.icehunter.fornax.pipeline;

import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the packed vertex facts: the layout round-trips every lane, a zero int is unstamped, and an
 * encoder given a stamped vertex ignores the cleared context.
 */
class VertexFactsTest {

    /** Hand-stamped vertex; the test JVM does not apply the mixin. */
    static final class StampedVertex extends ChunkVertexEncoder.Vertex implements FornaxVertexFacts {
        private int facts;

        @Override
        public int fornax$facts() {
            return facts;
        }

        @Override
        public void fornax$facts(int facts) {
            this.facts = facts;
        }
    }

    @AfterEach
    void reset() {
        MaterialIdContext.clear();
    }

    @Test
    void everyLaneRoundTripsThroughThePackedInt() {
        // 0x1234: any id that uses both bytes; 13 and 0x55: any emission and class inside their widths.
        int facts = VertexFacts.pack(0x1234, MaterialIdContext.PRECIPITATION_SNOW, 13, 0x55);
        assertTrue(VertexFacts.isStamped(facts));
        assertTrue(facts > 0, "a stamp must never be negative, bit 31 stays clear");
        assertEquals(0x1234, VertexFacts.materialId(facts));
        assertEquals(MaterialIdContext.PRECIPITATION_SNOW, VertexFacts.precipitation(facts));
        assertEquals(13, VertexFacts.lightEmission(facts));
        assertEquals(0x55, VertexFacts.blockClassFlags(facts));
    }

    @Test
    void aZeroIntIsUnstampedSoAnUntouchedVertexReadsTheContext() {
        assertFalse(VertexFacts.isStamped(0));
        MaterialIdContext.set(7);
        ChunkVertexEncoder.Vertex[] plain = ChunkVertexEncoder.Vertex.uninitializedQuad();
        assertEquals(7, VertexFacts.materialId(VertexFacts.resolve(plain)));
    }

    @Test
    void aReencodeAfterTheContextIsClearedKeepsTheBlocksFacts() {
        MaterialIdContext.set(1);
        MaterialIdContext.setPrecipitation(MaterialIdContext.PRECIPITATION_SNOW);
        MaterialIdContext.setLightEmission(9);
        MaterialIdContext.setBlockClass(BlockClasses.MASK);

        ChunkVertexEncoder.Vertex[] quad = new ChunkVertexEncoder.Vertex[4];
        for (int i = 0; i < 4; i++) {
            StampedVertex v = new StampedVertex();
            v.x = i & 1;
            v.y = (i >> 1) & 1;
            v.z = 0.0f;
            v.color = 0xFFFFFFFF;
            v.ao = 1.0f;
            v.light = 0;
            v.fornax$facts(VertexFacts.snapshot()); // as copyVertexTo does while the block is live
            quad[i] = v;
        }

        long first = MemoryUtil.nmemAlloc(FornaxChunkVertex.STRIDE * 4L);
        long second = MemoryUtil.nmemAlloc(FornaxChunkVertex.STRIDE * 4L);
        try {
            new FornaxChunkVertex().getEncoder().write(first, 0, quad, 0);
            MaterialIdContext.clear();
            new FornaxChunkVertex().getEncoder().write(second, 0, quad, 0);

            for (long i = 0; i < FornaxChunkVertex.STRIDE * 4L; i++) {
                assertEquals(MemoryUtil.memGetByte(first + i), MemoryUtil.memGetByte(second + i),
                        "byte " + i + " differs between the live encode and the re-encode after clear");
            }
            // The re-encode carried the facts, not the cleared context: the material id sits in
            // a_Normal.yz at bytes 21..22 of vertex 0.
            int id = (MemoryUtil.memGetByte(second + 21) & 0xFF) | ((MemoryUtil.memGetByte(second + 22) & 0xFF) << 8);
            assertEquals(1, id);
        } finally {
            MemoryUtil.nmemFree(first);
            MemoryUtil.nmemFree(second);
        }
    }
}

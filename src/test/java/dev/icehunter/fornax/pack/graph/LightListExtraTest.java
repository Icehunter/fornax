package dev.icehunter.fornax.pack.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class LightListExtraTest {
    @Test
    void byteSizeIsCameraPosPlusWindowPlusScanExtra() {
        assertEquals(48, new LightListExtra(0f, 0f, 0f, 0, 0, 0, 0, 0f).byteSize());
    }

    @Test
    void writesCameraThenWindowThenScanRadiusAtVec4AlignedOffsets() {
        LightListExtra extra = new LightListExtra(10f, 20f, 30f, 7, -1, 2, -3, 64f);
        // Direct buffer -- production always writes push constants through MemoryStack.malloc's
        // direct buffer (ComputePassRunner.run), same rationale as VoxelWaterReflExtraTest's own
        // direct-buffer note (no Unsafe-backed struct writes here, but keep the same real call shape).
        ByteBuffer buf = ByteBuffer.allocateDirect(32 + 48).order(ByteOrder.nativeOrder());
        extra.writeInto(buf, 32); // appended after the 32-byte PassParams base

        assertEquals(10f, buf.getFloat(32), 0f);        // camX at extra offset 0
        assertEquals(20f, buf.getFloat(32 + 4), 0f);    // camY at extra offset 4
        assertEquals(30f, buf.getFloat(32 + 8), 0f);    // camZ at extra offset 8
        // offset 32+12: unused vec4 w padding, not asserted
        assertEquals(7, buf.getInt(32 + 16));           // window.x = diameter at extra offset 16
        assertEquals(-1, buf.getInt(32 + 20));          // centerX
        assertEquals(2, buf.getInt(32 + 24));           // centerY
        assertEquals(-3, buf.getInt(32 + 28));          // centerZ
        assertEquals(64f, buf.getFloat(32 + 32), 0f);   // scanRadiusBlocks at extra offset 32
    }
}

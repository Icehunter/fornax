package dev.icehunter.fornax.pack.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class VoxelWaterReflExtraTest {
    @Test
    void byteSizeIsTwoMat4sPlusVec4PlusIvec4() {
        assertEquals(160, new VoxelWaterReflExtra(new Matrix4f(), new Matrix4f(),
                0f, 0f, 0f, 0, 0, 0, 0).byteSize());
    }

    @Test
    void writesMatricesThenCameraThenWindowAtStd140Offsets() {
        Matrix4f inv = new Matrix4f().scaling(2f);    // distinctive m00 = 2
        Matrix4f sun = new Matrix4f().scaling(3f);    // distinctive m00 = 3
        VoxelWaterReflExtra extra = new VoxelWaterReflExtra(inv, sun, 10f, 20f, 30f, 7, -1, 2, -3);
        // DIRECT, not allocate(): JOML's default Unsafe-backed Matrix4f.get(int, ByteBuffer) reads
        // the buffer's native address via sun.misc.Unsafe (MemUtil.MemUtilUnsafe#put) -- a heap
        // buffer's address field is 0, so an unsafe write through it segfaults the JVM. Production
        // always writes push constants through a direct buffer (ComputePassRunner's
        // MemoryStack.malloc), so a direct buffer here also matches the real call site.
        ByteBuffer buf = ByteBuffer.allocateDirect(32 + 160).order(ByteOrder.nativeOrder());
        extra.writeInto(buf, 32); // appended after the 32-byte PassParams base

        assertEquals(2f, buf.getFloat(32), 0f);       // invProjModelView m00 at extra offset 0
        assertEquals(3f, buf.getFloat(32 + 64), 0f);  // sunViewProj m00 at extra offset 64
        assertEquals(10f, buf.getFloat(32 + 128), 0f); // camX at extra offset 128
        assertEquals(20f, buf.getFloat(32 + 132), 0f);
        assertEquals(30f, buf.getFloat(32 + 136), 0f);
        assertEquals(7, buf.getInt(32 + 144));        // window.x = diameter at extra offset 144
        assertEquals(-1, buf.getInt(32 + 148));
        assertEquals(2, buf.getInt(32 + 152));
        assertEquals(-3, buf.getInt(32 + 156));
    }
}

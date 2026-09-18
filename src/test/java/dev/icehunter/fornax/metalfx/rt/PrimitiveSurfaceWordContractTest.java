package dev.icehunter.fornax.metalfx.rt;

import dev.icehunter.fornax.voxel.RtSectionGeometry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-level, deliberately: these kernels need a live Metal device and CI runs on Linux. What it
 * pins is the rule that lets one decode serve three different geometry records. Every record starts
 * with the same surface word, and two bits in that word say where the record keeps its UVs. Get the
 * bit wrong and a reader interpolates a neighbouring triangle's word as a texture coordinate, which
 * samples a real texel and reads as a shading bug rather than a decode one.
 */
class PrimitiveSurfaceWordContractTest {

    private static final Path SHADERS = Path.of("src/main/resources/assets/fornax/shaders_engine");

    private static String read(String name) throws IOException {
        return Files.readString(SHADERS.resolve(name));
    }

    /** Word 0 of a mesh record, so the same offset carries the surface word in every record. */
    @Test
    void theMeshPrimitiveRecordStartsWithItsSurfaceWord() throws IOException {
        assertTrue(read("rt_mesh_shadow.metal").contains(
                        "struct MeshShadowPrimitive { uint surface; uint tint; float2 uv0; float2 uv1; float2 uv2; };"),
                "the surface word must be first and the record must stay 32 bytes");
    }

    /**
     * The two UV-location bits must differ, or the decode cannot tell a supplement record's UVs at
     * byte 4 from a mesh record's at byte 8, and would read one pair of floats off by a word.
     */
    @Test
    void theMeshBitAndTheExactUvBitAreDifferentBits() throws IOException {
        int meshBit = 1 << 30;
        assertEquals(1 << 31, RtSectionGeometry.EXACT_UV_BIT,
                "the supplement's marker is bit 31; the kernel reads it as SURFACE_UV_AT_BYTE_4");
        assertNotEquals(meshBit, RtSectionGeometry.EXACT_UV_BIT);
        String query = read("rt_ray_query.metal");
        assertTrue(query.contains("constant uint SURFACE_UV_AT_BYTE_4 = 1u << 31;"));
        assertTrue(query.contains("constant uint SURFACE_UV_AT_BYTE_8 = 1u << 30;"));
        assertTrue(read("rt_mesh_shadow.metal").contains("constant uint MESH_SURFACE_BIT = 1u << 30;"),
                "the writer's bit and the reader's bit are one number");
    }

    /**
     * Byte 20 of a FornaxChunkVertex is the face, and the vertex stride is 24 bytes, so at a ushort
     * cursor the face is index 10. An off-by-one here reads the material id instead and every mesh
     * triangle reports a face derived from a block's material, which still decodes to a unit axis.
     */
    @Test
    void theDecodeReadsTheFaceFromVertexByteTwentyAndRejectsValuesOutsideTheSixDirections()
            throws IOException {
        String source = read("rt_mesh_shadow.metal");
        assertTrue(source.contains("uint face = packed[(triangle / 2) * 4 * 12 + 10] & 0xFFu;"),
                "the face byte is ushort index 10 of the quad's first vertex");
        assertTrue(source.contains("if (face > 5u) face = 0xFu;"),
                "a value outside 0..5 must become the unknown face, not a plausible wrong axis");
        assertTrue(source.contains("MESH_SURFACE_BIT | (face << 12u)"),
                "the face occupies bits 12-15, the field rt_face_normal masks");
    }

    /**
     * The supplement's rendered geometry is not axis aligned, so it stores a face value outside the
     * six directions and reports an unknown normal. A value inside 0..5 there would hand every
     * exact-geometry hit a confident wrong axis, which shades rather than fails.
     */
    @Test
    void theExactTriangleSupplementStoresAFaceValueThatNamesNoDirection() throws IOException {
        String writer = Files.readString(
                Path.of("src/main/java/dev/icehunter/fornax/voxel/RtSectionGeometry.java"));
        assertTrue(writer.contains("(6 << 12)"),
                "the supplement writes 6 into the face field");
        assertTrue(read("rt_face_normal.metal").contains("default: return float3(0.0)"),
                "and rt_face_normal must keep falling through to the zero vector for it");
    }
}

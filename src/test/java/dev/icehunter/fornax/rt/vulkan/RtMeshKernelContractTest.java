package dev.icehunter.fornax.rt.vulkan;

import dev.icehunter.fornax.pass.compute.ComputeShaderCompiler;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two Vulkan mesh kernels compile through the real shaderc here, so a GLSL error is a red test
 * rather than a load failure on a device. The rest pins the ABI they share with the Java side and
 * the Metal kernels: vertex decode, record layout, cutout threshold, the tier-with-validity store.
 */
class RtMeshKernelContractTest {

    private static String resource(String name) throws IOException {
        try (InputStream in = RtMeshKernelContractTest.class.getResourceAsStream("/assets/fornax/shaders_engine/" + name)) {
            assertTrue(in != null, name + " must ship in the jar");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void assertCompiles(String source, String name) {
        ByteBuffer spirv = ComputeShaderCompiler.compileToSpirv(source, name,
                Shaderc.shaderc_glsl_compute_shader, ComputeShaderCompiler.SpirvTarget.VULKAN_1_2);
        try {
            assertEquals(0x07230203, spirv.getInt(spirv.position()), name + " is SPIR-V");
        } finally {
            MemoryUtil.memFree(spirv);
        }
    }

    @Test
    void bothKernelsCompileAtTheVulkan12Target() throws IOException {
        assertCompiles(resource("rt_mesh_decode.comp"), "rt_mesh_decode.comp");
        assertCompiles(resource("rt_mesh_shadow.comp"), "rt_mesh_shadow.comp");
    }

    @Test
    void theDecodeReadsFornaxChunkVertexTheWayTheMetalKernelDoes() throws IOException {
        String decode = resource("rt_mesh_decode.comp");
        assertTrue(decode.contains("/ 2048.0 - 8.0"), "16-bit UNORM positions, block = 2048, origin -8");
        assertTrue(decode.contains("uint[6](0u, 1u, 2u, 2u, 3u, 0u)"), "the shared quad index ABI");
        assertTrue(decode.contains("uint s = vertex * 12u;"), "twelve 16-bit lanes per 24-byte vertex");
        assertTrue(decode.contains("/ 65535.0"), "16-bit UNORM UVs");
        assertTrue(decode.contains("ushortAt(packed, first + 10u) & 0xFFu"), "the face is byte 20 of the quad's first vertex");
        assertTrue(decode.contains("if (face > 5u) face = FACE_UNKNOWN;"));
        assertTrue(decode.contains("(colour & 0x00FFFFFFu) | (emission << 24u)"), "tint RGB plus the light level in the top byte");
        assertTrue(decode.contains("MESH_SURFACE_BIT = 1u << 30"), "bit 30 marks a mesh record: UVs at byte 8");
        assertTrue(decode.contains("MESH_SURFACE_BIT | (face << 12u)"));
        assertTrue(decode.contains("WORDS_PER_PRIMITIVE = 8u"), "32-byte records");
    }

    @Test
    void theDecodeIsDescriptorlessSoAThousandMeshesNeedNoSets() throws IOException {
        String decode = resource("rt_mesh_decode.comp");
        assertTrue(decode.contains("#extension GL_EXT_buffer_reference : require"));
        assertTrue(decode.contains("layout(push_constant) uniform Push"));
        assertFalse(decode.contains("layout(set ="), "no descriptor sets in the decode kernel");
    }

    @Test
    void thePushBlockAndRecordSizesAgreeWithTheJavaSide() {
        // 3 x uvec2 (24) + uint count (4) + one word of pad.
        assertEquals(32, MeshVulkanTracer.DECODE_PUSH_BYTES);
        assertEquals(32, MeshVulkanTracer.PRIMITIVE_BYTES);
        assertEquals(36, MeshVulkanTracer.POSITION_BYTES_PER_TRIANGLE);
        // 2 x mat4 (128) + vec4 (16) + 5 scalars (20) = 164, padded to 176 like the Metal struct.
        assertEquals(176, MeshVulkanTracer.CONSTANT_BYTES);
        assertEquals(0, MeshVulkanTracer.CONSTANT_BYTES % 16);
    }

    @Test
    void theShadowKernelKeepsTheRasterCutoutAndBothFaces() throws IOException {
        String shadow = resource("rt_mesh_shadow.comp");
        assertTrue(shadow.contains("const float CUTOUT_ALPHA = 0.1;"), "the raster shadow ABI's alpha cutoff");
        assertTrue(shadow.contains("gl_RayFlagsNoneEXT"), "opacity is the geometry's, not the ray's");
        assertFalse(shadow.contains("gl_RayFlagsNoOpaqueEXT"), "forcing every solid triangle through the loop is the cost of the whole trace");
        assertFalse(shadow.contains("gl_RayFlagsCullBackFacingTrianglesEXT"), "raster shadow pipelines disable face culling");
        assertFalse(shadow.contains("gl_RayFlagsOpaqueEXT"), "forcing opacity skips the cutout test");
        assertTrue(shadow.contains("rayQueryConfirmIntersectionEXT(query);"));
    }

    @Test
    void theShadowKernelWritesTierAndValidityInOneStoreAndNothingForASkippedRay() throws IOException {
        String shadow = resource("rt_mesh_shadow.comp");
        assertTrue(shadow.contains("imageStore(result, pixel, vec4(depth, float(c.tier), 0.0, 1.0));"),
                "G and A land in one store, so a texel never carries a tier without validity");
        assertTrue(shadow.contains("vec4(depth, 0.0, 0.0, 0.0)"), "a skipped ray writes neither");
        assertTrue(shadow.contains("if (any(greaterThan(abs(p), vec2(1.0))))"), "outside the captured volume is never certified");
    }

    @Test
    void theShadowKernelsBindingsMatchTheTracersLayoutInOrder() throws IOException {
        String shadow = resource("rt_mesh_shadow.comp");
        assertTrue(shadow.indexOf("binding = 0, std140) uniform MeshShadowConstants") > 0);
        assertTrue(shadow.indexOf("binding = 1) uniform accelerationStructureEXT scene") > 0);
        assertTrue(shadow.indexOf("binding = 2) uniform sampler2D atlas") > 0);
        assertTrue(shadow.indexOf("binding = 3, rgba32f) uniform writeonly image2D result") > 0);
        assertTrue(shadow.indexOf("binding = 4, std430) readonly buffer MeshTable") > 0);
        assertEquals(5, MeshVulkanTracer.SHADOW_BINDINGS.size());
    }

    @Test
    void theTracerStampsTheMeshTierIntoTheConstantsByName() throws IOException {
        String tracer = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/dev/icehunter/fornax/rt/vulkan/MeshVulkanTracer.java"));
        assertTrue(tracer.contains("block.putInt(160, RayTier.HARDWARE_MESH.ordinal());"),
                "the tier at byte 160, by name rather than as a literal");
    }
}

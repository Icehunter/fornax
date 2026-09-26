package dev.icehunter.fornax.rt.vulkan;

import dev.icehunter.fornax.metalfx.rt.RayQueryAbi;
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
 * The Vulkan buffer-query kernel writes the pack's hit buffer directly, so every offset it uses is
 * a contract with {@link RayQueryAbi} and the Metal kernel. The kernel compiles through the real
 * shaderc here; the rest pins the word table, the flag bits, the fill-mode read and the two atlas
 * encodings.
 */
class RtRayQueryKernelContractTest {

    private static String kernel() throws IOException {
        try (InputStream in = RtRayQueryKernelContractTest.class.getResourceAsStream("/assets/fornax/shaders_engine/rt_ray_query.comp")) {
            assertTrue(in != null, "rt_ray_query.comp must ship in the jar");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void theKernelCompilesAtTheVulkan12Target() throws IOException {
        ByteBuffer spirv = ComputeShaderCompiler.compileToSpirv(kernel(), "rt_ray_query.comp",
                Shaderc.shaderc_glsl_compute_shader, ComputeShaderCompiler.SpirvTarget.VULKAN_1_2);
        try {
            assertEquals(0x07230203, spirv.getInt(spirv.position()));
        } finally {
            MemoryUtil.memFree(spirv);
        }
    }

    @Test
    void theRecordLayoutIsRayQueryAbisWordTable() throws IOException {
        String k = kernel();
        assertEquals(9, RayQueryAbi.HIT_WORDS);
        assertTrue(k.contains("const uint HIT_WORDS = 9u;"));
        assertTrue(k.contains("hits[base] = floatBitsToUint(distance);"), "word 0 distance");
        assertTrue(k.contains("hits[base + 1u] = flags;"), "word 1 flags");
        assertTrue(k.contains("hits[base + 2u] = surface;"), "word 2 surface");
        assertTrue(k.contains("hits[base + 3u] = atlasUv;"), "word 3 atlas address");
        assertTrue(k.contains("hits[base + 4u] = floatBitsToUint(normal.x);"), "words 4-6 normal");
        assertTrue(k.contains("hits[base + 7u] = tier;"), "word 7 tier");
        assertTrue(k.contains("hits[base + 8u] = tint;"), "word 8 tint");
        assertEquals(RayQueryAbi.HIT_TIER_WORD, 7);
        assertEquals(RayQueryAbi.HIT_TINT_WORD, 8);
        assertEquals(8, RayQueryAbi.REQUEST_WORDS);
        assertTrue(k.contains("requests[index * 2u]") && k.contains("requests[index * 2u + 1u]"), "two vec4 a request");
    }

    @Test
    void theFlagBitsAndVersionMatchTheJavaConstants() throws IOException {
        String k = kernel();
        assertTrue(k.contains("const uint ABI_VERSION = 4u;"));
        assertEquals(4, RayQueryAbi.ABI_VERSION);
        assertTrue(k.contains("FLAG_FRONT_FACING = 1u"));
        assertEquals(1, RayQueryAbi.FLAG_FRONT_FACING);
        assertTrue(k.contains("FLAG_FACE_SHIFT = 8u"));
        assertEquals(8, RayQueryAbi.FLAG_FACE_SHIFT);
        assertTrue(k.contains("FACE_UNKNOWN = 0xFu"));
        assertEquals(0xF, RayQueryAbi.FACE_UNKNOWN);
        assertTrue(k.contains("FLAG_UV_KNOWN = 1u << 12"));
        assertEquals(1 << 12, RayQueryAbi.FLAG_UV_KNOWN);
        assertTrue(k.contains("FLAG_ATLAS_TEXEL_U16 = 1u << 13"));
        assertEquals(1 << 13, RayQueryAbi.FLAG_ATLAS_TEXEL_U16);
        assertTrue(k.contains("const float MISS_DISTANCE = -1.0;"));
        assertEquals(-1.0f, RayQueryAbi.MISS_DISTANCE);
    }

    @Test
    void fillModeLeavesAnAnsweredRecordAloneAndABadAbiWritesTierZero() throws IOException {
        String k = kernel();
        assertTrue(k.contains("if (c.fillMode != 0u && hits[base + 7u] != 0u) return;"),
                "one tier-word read per ray is what makes the buffer form cascade");
        assertTrue(k.contains("writeRecord(base, 0.0, 0u, 0u, 0u, vec3(0.0), 0u, 0u);"),
                "an ABI mismatch leaves an all-zero record, which reads as unanswered under any layout");
        assertTrue(k.indexOf("c.abiVersion != ABI_VERSION") < k.indexOf("vec4 originAndMin"),
                "the ABI is checked before any request is read at the wrong stride");
    }

    @Test
    void theTwoAtlasEncodingsShareOneTexelSelection() throws IOException {
        String k = kernel();
        assertTrue(k.contains("ivec2 atlasTexel(vec2 uv)"));
        assertTrue(k.contains("texelFetch(atlas, atlasTexel(uv), 0).a >= CUTOUT_ALPHA"), "exact mode tests the exact texel");
        assertTrue(k.contains("atlasUv = uint(texel.x) | (uint(texel.y) << 16u);"), "x low, y high");
        assertTrue(k.contains("atlasUv = packHalf2x16(uv);"), "packed half2 by default");
        assertTrue(k.contains("atlasSize.x > 65536 || atlasSize.y > 65536"), "an unrepresentable atlas is refused, not wrapped");
    }

    @Test
    void aMeshHitReportsZeroSurfaceItsTintAndAFaceNormal() throws IOException {
        String k = kernel();
        assertTrue(k.contains("(surface & SURFACE_UV_AT_BYTE_8) != 0u ? 0u : surface"), "a mesh triangle has no palette entry");
        assertTrue(k.contains("prims.words[o + 1u]"), "tint is word 1 of the mesh record");
        assertTrue(k.contains("vec3 faceNormal(uint surface)"));
        assertTrue(k.contains("default: return vec3(0.0);"), "an unnamed face is the zero vector, never a guess");
        assertTrue(k.contains("rayQueryGetIntersectionFrontFaceEXT(query, true)"));
        assertFalse(k.contains("gl_RayFlagsCullBackFacingTrianglesEXT"));
        assertTrue(k.contains("gl_RayFlagsNoneEXT"), "opacity is the geometry's, not the ray's");
        assertFalse(k.contains("gl_RayFlagsNoOpaqueEXT"));
        assertFalse(k.contains("gl_RayFlagsOpaqueEXT"));
    }

    @Test
    void thePushBlockAndBindingsAgreeWithTheTracer() throws IOException {
        String k = kernel();
        assertEquals(32, MeshVulkanTracer.QUERY_PUSH_BYTES);
        assertTrue(k.contains("layout(push_constant) uniform Push"));
        assertTrue(k.indexOf("binding = 0) uniform accelerationStructureEXT scene") > 0);
        assertTrue(k.indexOf("binding = 1, std430) readonly buffer Requests") > 0);
        assertTrue(k.indexOf("binding = 2, std430) buffer Hits") > 0);
        assertTrue(k.indexOf("binding = 3) uniform sampler2D atlas") > 0);
        assertTrue(k.indexOf("binding = 4, std430) readonly buffer MeshTable") > 0);
        assertEquals(5, MeshVulkanTracer.QUERY_BINDINGS.size());
        assertEquals(64, MeshVulkanTracer.QUERY_LOCAL_SIZE);
        assertTrue(k.contains("layout(local_size_x = 64) in;"));
    }
}

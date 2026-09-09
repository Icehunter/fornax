package dev.icehunter.fornax.pipeline;

import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the real encoder into native memory, then evaluates the position expression read from each
 * shipped decoder. The recognized expressions have scalar float32 equivalents here because a JVM
 * test has no vertex-input GPU stage. Unknown expressions fail instead of silently testing a stale
 * model. These checks establish section-space agreement, not raster coverage in a running client.
 */
class FornaxChunkPositionTest {
    private static final Path ENGINE_SHADER = Path.of(
            "src/main/resources/assets/fornax/shaders/include/chunk_vertex.glsl");
    private static final Path SAMPLE_SHADER = Path.of(
            "src/test/resources/packs/sample_pack/shaders/include/chunk_vertex.glsl");

    @AfterEach
    void reset() {
        MaterialIdContext.clear();
    }

    @Test
    void adjacentSectionEdgesDecodeToExactlyTheSamePositionOnEveryAxis() throws IOException {
        for (Path path : new Path[]{ENGINE_SHADER, SAMPLE_SHADER}) {
            ShaderDecode shader = ShaderDecode.read(path);
            for (int axis = 0; axis < 3; axis++) {
                // Section origins are multiples of 16; positive and negative neighbours both count.
                for (int origin : new int[]{-64, -16, 0, 16, 64}) {
                    float[] far = {0, 0, 0};
                    far[axis] = 16;
                    int[] farCodes = encodePosition(far);
                    int[] nearCodes = encodePosition(new float[]{0, 0, 0});
                    float left = origin + shader.decode(farCodes[axis]);
                    float right = origin + 16 + shader.decode(nearCodes[axis]);
                    assertEquals(left, right, 0.0f,
                            path + " axis " + axis + " origin " + origin + " has a section gap");
                    assertEquals(origin + 16.0f, left, 0.0f, "section edge must stay on its block plane");
                }
            }
        }
    }

    @Test
    void overlappingSixteenthBlockCoordinatesAgreeAcrossSectionOrigins() throws IOException {
        for (Path path : new Path[]{ENGINE_SHADER, SAMPLE_SHADER}) {
            ShaderDecode shader = ShaderDecode.read(path);
            for (int axis = 0; axis < 3; axis++) {
                // The overlap is [8,24) in one section and [-8,8) in its neighbour.
                // Sixteenths cover ordinary model coordinates, including section overhang.
                for (int step = 8 * 16; step < 24 * 16; step++) {
                    float position = step / 16.0f;
                    float[] a = {0, 0, 0};
                    float[] b = {0, 0, 0};
                    a[axis] = position;
                    b[axis] = position - 16;
                    float decodedA = shader.decode(encodePosition(a)[axis]);
                    float decodedB = 16 + shader.decode(encodePosition(b)[axis]);
                    assertEquals(position, decodedA, 0.0f, path + " must preserve model sixteenths");
                    assertEquals(decodedA, decodedB, 0.0f,
                            path + " axis " + axis + " overlap " + position);
                }
            }
        }
    }

    @Test
    void positionExtremaSaturateWithoutWrapping() throws IOException {
        // A 16-bit code over 32 blocks has 2048 steps per block; the highest code is one
        // step below +24. Explicit expected coordinates avoid deriving expectations from the encoder.
        float[] positions = {-100, -8, 0, 16, 23.99951171875f, 24, 100};
        int[] codes = {0, 0, 16384, 49152, 65535, 65535, 65535};
        float[] decoded = {-8, -8, 0, 16, 23.99951171875f, 23.99951171875f, 23.99951171875f};
        for (Path path : new Path[]{ENGINE_SHADER, SAMPLE_SHADER}) {
            ShaderDecode shader = ShaderDecode.read(path);
            for (int axis = 0; axis < 3; axis++) {
                for (int i = 0; i < positions.length; i++) {
                    float[] point = {0, 0, 0};
                    point[axis] = positions[i];
                    int code = encodePosition(point)[axis];
                    assertEquals(codes[i], code, "saturated code axis " + axis + " position " + positions[i]);
                    assertEquals(decoded[i], shader.decode(code), 0.0f,
                            path + " saturated position " + positions[i]);
                }
            }
        }
    }

    @Test
    void offGridPositionsRoundToTheNearestFixedPointStep() throws IOException {
        // Probe below, exactly at and above half a 1/2048 step around local zero.
        float[] positions = {0.0001220703125f, 0.000244140625f, 0.0003662109375f};
        int[] expected = {16384, 16385, 16385};
        for (int axis = 0; axis < 3; axis++) {
            for (int i = 0; i < positions.length; i++) {
                float[] point = {0, 0, 0};
                point[axis] = positions[i];
                assertEquals(expected[i], encodePosition(point)[axis], "rounding axis " + axis);
            }
        }
    }

    @Test
    void availableLivePackDecodesTheSamePositionCodesAsTheEngine() throws IOException {
        Path path = Path.of("../plague/shaders/include/chunk_vertex.glsl");
        assumeTrue(Files.isRegularFile(path), "live sibling pack is unavailable");
        ShaderDecode engine = ShaderDecode.read(ENGINE_SHADER);
        ShaderDecode pack = ShaderDecode.read(path);
        for (float position : new float[]{-8, -1, 0, 0.0625f, 8, 15.9375f, 16, 23.9375f, 24}) {
            int[] codes = encodePosition(new float[]{position, position, position});
            for (int axis = 0; axis < 3; axis++) {
                assertEquals(engine.decode(codes[axis]), pack.decode(codes[axis]), 0.0f,
                        "live pack position decoder disagrees at " + position + " axis " + axis);
            }
        }
    }

    @Test
    void positionEncodingPreservesUvAndEveryNonPositionByte() {
        ChunkVertexEncoder.Vertex[] vertices = quad(new float[]{0, 0, 0});
        MaterialIdContext.set(0x1234);
        MaterialIdContext.setPrecipitation(MaterialIdContext.PRECIPITATION_SNOW);
        MaterialIdContext.setLightEmission(13);
        MaterialIdContext.setBlockClass(BlockClasses.COAL);
        for (var vertex : vertices) {
            vertex.u = 0.25f;
            vertex.v = 0.75f;
            vertex.light = (11 << 4) | (7 << 20);
        }
        long ptr = MemoryUtil.nmemAlloc(FornaxChunkVertex.STRIDE * 4);
        try {
            long end = new FornaxChunkVertex().getEncoder().write(ptr, 0x5A, vertices, 0xC3);
            assertEquals(24, FornaxChunkVertex.STRIDE, "position grid must not change the vertex stride");
            assertEquals(ptr + 96, end, "four vertices still consume 96 bytes");
            for (int vertex = 0; vertex < 4; vertex++) {
                long base = ptr + vertex * FornaxChunkVertex.STRIDE;
                assertEquals(0x1D, MemoryUtil.memGetShort(base + 6) & 0xFFFF, "emission/class code");
                assertEquals(16384, MemoryUtil.memGetShort(base + 8) & 0xFFFF, "UV keeps UNORM65535 rounding");
                assertEquals(49151, MemoryUtil.memGetShort(base + 10) & 0xFFFF, "UV must not use position quantization");
                assertEquals(0xFFFFFFFF, MemoryUtil.memGetInt(base + 12), "tint/AO bytes");
                assertEquals(0xC35A070B, MemoryUtil.memGetInt(base + 16), "light/material/draw bytes");
                assertEquals(0x02123401, MemoryUtil.memGetInt(base + 20), "normal/material/precipitation bytes");
            }
        } finally {
            MemoryUtil.nmemFree(ptr);
        }
    }

    private static int[] encodePosition(float[] position) {
        long ptr = MemoryUtil.nmemAlloc(FornaxChunkVertex.STRIDE * 4);
        try {
            new FornaxChunkVertex().getEncoder().write(ptr, 0, quad(position), 0);
            return new int[]{MemoryUtil.memGetShort(ptr) & 0xFFFF,
                    MemoryUtil.memGetShort(ptr + 2) & 0xFFFF,
                    MemoryUtil.memGetShort(ptr + 4) & 0xFFFF};
        } finally {
            MemoryUtil.nmemFree(ptr);
        }
    }

    private static ChunkVertexEncoder.Vertex[] quad(float[] position) {
        var vertices = ChunkVertexEncoder.Vertex.uninitializedQuad();
        for (int i = 0; i < vertices.length; i++) {
            var vertex = vertices[i];
            vertex.x = position[0] + (i >= 2 ? 1 : 0);
            vertex.y = position[1];
            vertex.z = position[2] + (i % 2);
            vertex.color = 0xFFFFFFFF;
            vertex.ao = 1;
        }
        return vertices;
    }

    private record ShaderDecode(float minimum, float scale, boolean integerCode) {
        static ShaderDecode read(Path path) throws IOException {
            String source = Files.readString(path).replaceAll("(?s)/\\*.*?\\*/|//[^\\n]*", "")
                    .replaceAll("\\s+", "");
            float minimum = constant(source, "FORNAX_MODEL_MIN");
            if (source.contains("_vert_position=a_Position.xyz*FORNAX_MODEL_SIZE+FORNAX_MODEL_MIN;")) {
                return new ShaderDecode(minimum, constant(source, "FORNAX_MODEL_SIZE"), false);
            }
            assertTrue(source.contains("vec3positionCode=floor(a_Position.xyz*65535.0+0.5);"
                            + "_vert_position=positionCode/FORNAX_POSITION_SCALE+FORNAX_MODEL_MIN;"),
                    path + " position expression changed; update the scalar equivalent explicitly");
            return new ShaderDecode(minimum, constant(source, "FORNAX_POSITION_SCALE"), true);
        }

        private static float constant(String source, String name) {
            var match = Pattern.compile("constfloat" + name + "=([-0-9.]+);").matcher(source);
            assertTrue(match.find(), "missing shader constant " + name);
            return Float.parseFloat(match.group(1));
        }

        float decode(int code) {
            float unorm = code / 65535.0f;
            if (integerCode) {
                float recovered = (float) Math.floor(unorm * 65535.0f + 0.5f);
                return recovered / scale + minimum;
            }
            return unorm * scale + minimum;
        }
    }
}

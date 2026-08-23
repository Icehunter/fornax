package dev.icehunter.fornax.pass.compute;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComputeShaderCompilerTest {
    private static final String TRIVIAL_COMPUTE_SHADER = """
            #version 450
            layout(local_size_x = 1) in;
            layout(set = 0, binding = 0, std430) buffer Out { uint values[]; };
            void main() {
                values[gl_GlobalInvocationID.x] = gl_GlobalInvocationID.x * 2u;
            }
            """;

    @Test
    void compilesValidGlslToSpirv() {
        ByteBuffer spirv = ComputeShaderCompiler.compileToSpirv(TRIVIAL_COMPUTE_SHADER, "trivial.comp");
        assertTrue(spirv.remaining() > 4, "expected at least a SPIR-V magic-number word");
        // SPIR-V binaries begin with the magic number 0x07230203, little-endian in the buffer.
        assertEquals(0x07230203, spirv.getInt(spirv.position()));
    }

    @Test
    void rejectsInvalidGlslWithCompilerDiagnostics() {
        String broken = "#version 450\nvoid main() { this is not glsl; }\n";
        ComputeShaderCompileException ex = assertThrows(ComputeShaderCompileException.class,
                () -> ComputeShaderCompiler.compileToSpirv(broken, "broken.comp"));
        assertTrue(ex.getMessage().contains("broken.comp"), "error should name the shader for debuggability");
    }
}

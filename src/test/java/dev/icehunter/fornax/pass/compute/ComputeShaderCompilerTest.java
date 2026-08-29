package dev.icehunter.fornax.pass.compute;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

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

    // The CharSequence overload of shaderc_compile_into_spv auto-encodes its String arguments
    // through the calling thread's implicit MemoryStack frame, which LWJGL sizes at 64 KiB by
    // default for short-lived per-call native arguments. A fully-flattened compute shader that
    // imports a large include chain (e.g. clouds.glsl's dependency tree) routinely exceeds that,
    // throwing "OutOfMemoryError: Out of stack space" inside MemoryStack.nUTF8. Source size has no
    // reasonable upper bound this class should assume; 256 KiB of padding sits comfortably past the
    // default in both the JVM stack and the native MemoryStack frame, exercising the large-source
    // path rather than a near-threshold one.
    @Test
    void compilesASourceLargerThanTheDefaultMemoryStackFrame() {
        StringBuilder padding = new StringBuilder(TRIVIAL_COMPUTE_SHADER.length() + 300_000);
        padding.append("#version 450\n// padding to exceed the 64 KiB default MemoryStack frame:\n");
        while (padding.length() < 256 * 1024) {
            padding.append("// this line exists only to pad the source past the stack-frame default\n");
        }
        padding.append(TRIVIAL_COMPUTE_SHADER, TRIVIAL_COMPUTE_SHADER.indexOf('\n') + 1,
                TRIVIAL_COMPUTE_SHADER.length());
        ByteBuffer spirv = ComputeShaderCompiler.compileToSpirv(padding.toString(), "large.comp");
        assertTrue(spirv.remaining() > 4);
        assertEquals(0x07230203, spirv.getInt(spirv.position()));
    }

    /** shaderc exposes no getter for a compile-options handle, so the only stable unit-level pin for
     * this process-lifetime configuration is the setter at its construction site. The real outcome
     * is covered by compiling every pack compute shader in the cross-repository shader checks. */
    @Test
    void configuresPerformanceOptimizationForPackComputePipelines() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/pass/compute/ComputeShaderCompiler.java"));

        assertTrue(source.contains("shaderc_compile_options_set_optimization_level(options,"));
        assertTrue(source.contains("shaderc_optimization_level_performance"));
    }
}

package dev.icehunter.fornax.pass.compute;

import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;

/** GLSL -> SPIR-V compilation for compute shaders -- the one shader stage Blaze3D's own {@code
 * GlslCompiler} cannot produce (it is hardwired to vertex+fragment {@code RenderPipeline} pairs).
 * Uses the same shaderc native library Blaze3D's {@code GlslCompiler} already loads process-wide
 * (confirmed via its {@code shaderCompiler}/{@code shaderOptions} native-handle fields), so this
 * introduces no new native dependency beyond what the game already ships. */
public final class ComputeShaderCompiler {
    private ComputeShaderCompiler() {
    }

    // Lazily created, process-lifetime, reused across every call: shaderc's own C API is designed
    // for one compiler (and one options object, when the options are the same every time, as they
    // are here) to compile many shaders, not to be created and torn down per shader.
    // ensureRunnersBuilt() calls this in a tight loop (GraphRunner.java:1411) once per pack
    // activation, so per-call init/teardown overhead would be paid once per compute/particle shader
    // on every single reload. Render-thread only, like every other static Vulkan/shaderc-touching
    // class in this mod (see GraphRunner's own doc on the same convention): ComputePassRunner,
    // ParticlePassRunner, and VoxelDebugRaymarchPass all call this only from ensureRunnersBuilt() or
    // a one-shot render-thread setup, never from a worker thread.
    private static long compiler = 0L;
    private static long options = 0L;

    private static long compilerHandle() {
        if (compiler == 0L) {
            compiler = Shaderc.shaderc_compiler_initialize();
            if (compiler == 0L) {
                throw new ComputeShaderCompileException("shaderc_compiler_initialize failed");
            }
            options = Shaderc.shaderc_compile_options_initialize();
            if (options == 0L) {
                Shaderc.shaderc_compiler_release(compiler);
                compiler = 0L;
                throw new ComputeShaderCompileException("shaderc_compile_options_initialize failed");
            }
            // Pack compute shaders flatten their include graph before reaching shaderc. Performance
            // optimization removes dead helpers and folds the resulting module before a backend has
            // to translate and compile it into a native compute function. This is compiler policy,
            // not visual policy: shader semantics remain pack-authored while the shipped module stays
            // within the practical size accepted by real Vulkan backends.
            Shaderc.shaderc_compile_options_set_optimization_level(options,
                    Shaderc.shaderc_optimization_level_performance);
        }
        return compiler;
    }

    /** Releases the shared compiler/options, if created. Best-effort process-shutdown tidiness --
     * not required for correctness, since process exit reclaims everything either way. */
    public static void shutdown() {
        if (compiler != 0L) {
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
            compiler = 0L;
            options = 0L;
        }
    }

    /**
     * Compiles GLSL compute shader source to SPIR-V bytecode.
     *
     * @param glslSource the GLSL shader source code
     * @param debugName a human-readable name for the shader (used in error messages and debug output)
     * @return the compiled SPIR-V bytecode in a native (off-heap) buffer allocated via {@link
     * MemoryUtil#memAlloc} — the caller owns this memory and must release it with {@link
     * MemoryUtil#memFree} once done with it.
     * @throws ComputeShaderCompileException if shader compilation fails
     */
    public static ByteBuffer compileToSpirv(String glslSource, String debugName) {
        return compileToSpirv(glslSource, debugName, Shaderc.shaderc_glsl_compute_shader);
    }

    /**
     * As {@link #compileToSpirv(String, String)}, but for an arbitrary {@code shaderc_shader_kind}
     * -- {@code shaderc_glsl_vertex_shader} / {@code shaderc_glsl_fragment_shader} for the two stages
     * a {@code ParticlePassRunner} pipeline needs.
     *
     * <p>Everything below the {@code kind} argument is stage-agnostic (shaderc's compiler/options
     * lifecycle, the status check, the off-heap copy), which is why this stays one method here rather
     * than a second class duplicating that lifecycle: the class name reflects the first caller, not a
     * restriction. The caller still owns the returned buffer and must {@link MemoryUtil#memFree} it.
     *
     * <p>The Vulkan-GLSL authoring rules a {@code .comp} already follows apply verbatim to a vertex or
     * fragment source compiled here: shaderc's default target is Vulkan, so every uniform/sampler/
     * buffer needs an explicit {@code layout(binding = N)} (glslang does not auto-assign without
     * {@code --auto-bind-uniforms}, which is deliberately not enabled -- the binding numbers are the
     * pass's declared input order and must be authored, not guessed), and {@code #moj_import} is not a
     * thing on this path: nothing preprocesses it out of {@code RuntimeShaderPack.sourceOrNull}'s text.
     */
    public static ByteBuffer compileToSpirv(String glslSource, String debugName, int kind) {
        long compilerHandle = compilerHandle();
        // Heap-allocated (MemoryUtil.memUTF8), not the CharSequence overload: that one auto-encodes
        // its arguments through the calling thread's IMPLICIT MemoryStack frame, which LWJGL sizes
        // small (64 KiB default) for short-lived per-call native arguments. A fully-flattened
        // compute shader that imports a large include chain (e.g. the whole cloud-rendering
        // dependency tree) routinely runs well past that, throwing "OutOfMemoryError: Out of stack
        // space" inside MemoryStack.nUTF8. Source size has no reasonable upper bound this class
        // should assume; the raw-buffer overload below allocates off-heap instead of on a
        // fixed-size stack frame, matching how this method already handles the OUTPUT bytes a few
        // lines down.
        // source_text's length is taken from ByteBuffer.remaining(): LWJGL's generated binding
        // (org.lwjgl.util.shaderc.Shaderc.shaderc_compile_into_spv(ByteBuffer,...)) calls
        // memAddress(source_text) alongside source_text.remaining() and passes it as an explicit
        // size_t, not a NUL scan, so this one is NOT null-terminated. input_file_name/entry_point_name
        // ARE, matching that same overload's own checkNT1(...) assertions: they cross as bare char*
        // with no length argument at all.
        ByteBuffer sourceUtf8 = MemoryUtil.memUTF8(glslSource, false);
        ByteBuffer debugNameUtf8 = MemoryUtil.memUTF8(debugName, true);
        ByteBuffer entryPointUtf8 = MemoryUtil.memUTF8("main", true);
        try {
            long result = Shaderc.shaderc_compile_into_spv(compilerHandle, sourceUtf8, kind,
                    debugNameUtf8, entryPointUtf8, options);
            try {
                int status = Shaderc.shaderc_result_get_compilation_status(result);
                if (status != Shaderc.shaderc_compilation_status_success) {
                    String errorMessage = Shaderc.shaderc_result_get_error_message(result);
                    throw new ComputeShaderCompileException(
                            "compute shader compile failed for " + debugName + ": " + errorMessage);
                }
                long length = Shaderc.shaderc_result_get_length(result);
                ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result, length);
                ByteBuffer copy = MemoryUtil.memAlloc((int) length);
                copy.put(bytes).flip();
                return copy;
            } finally {
                Shaderc.shaderc_result_release(result);
            }
        } finally {
            MemoryUtil.memFree(entryPointUtf8);
            MemoryUtil.memFree(debugNameUtf8);
            MemoryUtil.memFree(sourceUtf8);
        }
    }
}

package dev.icehunter.fornax.metalfx.rt;

import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.metalfx.objc.Objc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads and compiles the three engine MSL kernels the Metal ray tracing spike needs ({@code
 * rt_expand.metal}, {@code rt_trace.metal}, {@code rt_debug.metal}) and builds their compute
 * pipeline states.
 *
 * <p>Every ObjC method this class calls to produce an object, {@code
 * newLibraryWithSource:options:error:}, {@code newFunctionWithName:}, {@code
 * newComputePipelineStateWithFunction:error:}, is a {@code new*} method, so under Cocoa's
 * create/copy/new/alloc ownership rule its return value already belongs to this caller. Nothing
 * here calls {@code retain}; the ids returned by {@link #compile} are already this class's own to
 * hold for the process lifetime, exactly like {@code MetalFxSupport}'s retained device.
 *
 * <p>Success is {@code id != 0}, not "no error text": Metal reports compiler warnings through the
 * same {@code NSError} out-parameter a real failure uses, so an {@link Objc.Result} with a nonzero
 * id and a non-null {@link Objc.Result#error} is a successful compile with warnings, logged at
 * DEBUG rather than treated as a failure.
 */
public final class MetalRtShaders {
    private static final String EXPAND_RESOURCE = "/assets/fornax/shaders_engine/rt_expand.metal";
    private static final String TRACE_RESOURCE = "/assets/fornax/shaders_engine/rt_trace.metal";
    private static final String SUN_DEPTH_RESOURCE = "/assets/fornax/shaders_engine/rt_sun_depth.metal";
    private static final String DEBUG_RESOURCE = "/assets/fornax/shaders_engine/rt_debug.metal";
    /** General caller-driven ray query. Not part of {@link #compile}: nothing in the frame loop
     * dispatches it yet, and a kernel compiled every pack load that no pass encodes is cost with no
     * consumer. Callers compile it through {@link #compileKernel} when they need it. */
    static final String RAY_QUERY_RESOURCE = "/assets/fornax/shaders_engine/rt_ray_query.metal";
    /** Shared face-to-normal decode, prepended to every kernel that needs it. */
    private static final String FACE_NORMAL_RESOURCE = "/assets/fornax/shaders_engine/rt_face_normal.metal";
    static final String RAY_QUERY_FUNCTION = "rt_ray_query";

    private MetalRtShaders() {
    }

    /** One compiled kernel's library, function and pipeline state ids. */
    public record CompiledKernel(long library, long function, long pipeline) {
        /** Releases the pipeline, function and library, in that order (each was retained
         * separately by its own {@code new*} method, so each needs its own release). */
        public void release() {
            Objc.msgSendVoid(pipeline, Objc.selector("release"));
            Objc.msgSendVoid(function, Objc.selector("release"));
            Objc.msgSendVoid(library, Objc.selector("release"));
        }
    }

    /** Every kernel this milestone needs, compiled and ready to dispatch. */
    public record Compiled(CompiledKernel expand, CompiledKernel trace, CompiledKernel debug, CompiledKernel sunDepth) {
        /** Releases every kernel's library, function and pipeline state. The teardown path a
         * caller (e.g. a pack reload or shutdown) uses to give these back once done with them. */
        public void release() {
            expand.release();
            trace.release();
            debug.release();
            sunDepth.release();
        }
    }

    /**
     * Compiles every engine kernel against {@code device} and builds their pipeline states.
     * Throws {@link IllegalStateException} naming the resource path (and including the
     * {@code NSError} text, when Metal raised one) on any failure: a missing resource, a compile
     * error, a missing kernel function, or a pipeline state that fails to build.
     *
     * <p>Runs inside its own autorelease pool: {@link Objc#nsString} and the {@code NSError}
     * lookups this class makes along the way return autoreleased objects, and this method may run
     * off the render thread's own per-frame pool (e.g. at pack load), so it needs one of its own.
     *
     * <p>If a later kernel fails to compile, every kernel already built is released before the
     * failure propagates, so a failed {@code compile} call leaves nothing behind for the caller to
     * clean up.
     */
    public static Compiled compile(long device) {
        long pool = Objc.autoreleasePoolPush();
        try {
            CompiledKernel expand = compileKernel(device, EXPAND_RESOURCE, "rt_expand");
            CompiledKernel trace;
            try {
                trace = compileKernel(device, TRACE_RESOURCE, "rt_trace");
            } catch (RuntimeException e) {
                expand.release();
                throw e;
            }
            CompiledKernel debug;
            try {
                debug = compileKernel(device, DEBUG_RESOURCE, "rt_debug");
            } catch (RuntimeException e) {
                expand.release();
                trace.release();
                throw e;
            }
            CompiledKernel sunDepth;
            try {
                sunDepth = compileKernel(device, SUN_DEPTH_RESOURCE, "rt_sun_depth");
            } catch (RuntimeException e) {
                expand.release();
                trace.release();
                debug.release();
                throw e;
            }
            return new Compiled(expand, trace, debug, sunDepth);
        } finally {
            Objc.autoreleasePoolPop(pool);
        }
    }

    /**
     * Compiles one kernel and builds its pipeline state. Package-private so a caller outside
     * {@link #compile}'s fixed set, and the tests, can build a single kernel without the whole
     * milestone's worth.
     */
    static CompiledKernel compileKernel(long device, String resourcePath, String functionName) {
        String source = prelude(resourcePath) + readResource(resourcePath);
        long library = compileSource(device, resourcePath, source);

        long function = Objc.msgSendId(library, Objc.selector("newFunctionWithName:"),
                Objc.nsString(functionName));
        if (function == 0) {
            throw new IllegalStateException(
                    "function " + functionName + " not found in " + resourcePath);
        }

        Objc.Result pipeline = Objc.msgSendIdIdErr(device,
                Objc.selector("newComputePipelineStateWithFunction:error:"), function);
        if (pipeline.id() == 0) {
            throw new IllegalStateException("failed to build pipeline state for " + functionName
                    + " in " + resourcePath + ": "
                    + (pipeline.error() != null ? pipeline.error() : "unknown error"));
        }
        if (pipeline.error() != null) {
            FornaxMod.LOGGER.debug("[Fornax] pipeline state for {} in {} built with a warning: {}",
                    functionName, resourcePath, pipeline.error());
        }

        return new CompiledKernel(library, function, pipeline.id());
    }

    /**
     * Source prepended to a kernel before compiling it. MSL resources here have no include path, so
     * a helper shared by two kernels is concatenated in rather than imported. A kernel absent from
     * this switch gets nothing, which is a compile error at its first use of a shared helper rather
     * than a silent miscompile.
     */
    private static String prelude(String resourcePath) {
        if (resourcePath.equals(SUN_DEPTH_RESOURCE)) {
            return readResource(TRACE_RESOURCE) + "\n";
        }
        if (resourcePath.equals(RAY_QUERY_RESOURCE) || resourcePath.equals(DEBUG_RESOURCE)) {
            return readResource(FACE_NORMAL_RESOURCE) + "\n";
        }
        return "";
    }

    /**
     * Compiles {@code source} into an {@code MTLLibrary}, naming {@code name} in any error.
     * Package-private so the test can compile a deliberately broken source string and check the
     * surfaced compiler message, without a matching kernel function to look up afterward.
     */
    static long compileSource(long device, String name, String source) {
        Objc.Result library = Objc.msgSendIdIdIdErr(device,
                Objc.selector("newLibraryWithSource:options:error:"), Objc.nsString(source), 0L);
        if (library.id() == 0) {
            throw new IllegalStateException("failed to compile " + name + ": "
                    + (library.error() != null ? library.error() : "unknown error"));
        }
        if (library.error() != null) {
            FornaxMod.LOGGER.debug("[Fornax] {} compiled with a warning: {}", name, library.error());
        }
        return library.id();
    }

    private static String readResource(String path) {
        try (InputStream in = MetalRtShaders.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing engine shader resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read engine shader resource " + path, e);
        }
    }
}

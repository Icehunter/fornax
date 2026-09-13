package dev.icehunter.fornax.metalfx.rt;

import dev.icehunter.fornax.metalfx.objc.Objc;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compiles the three engine ray tracing kernels against the real Metal device and checks every
 * pipeline state builds. Skips off this machine; only runs where {@link Objc#isLoaded()} is true.
 * Uses its own {@code MTLCreateSystemDefaultDevice()} device rather than {@code MetalFxSupport}'s
 * or {@code MetalRtSupport}'s: this test only needs a Metal device to compile against, not a
 * device that has passed either class's own availability probe.
 */
class MetalRtShadersTest {
    @Test
    void compilesAllThreeKernelsWithNonzeroPipelines() {
        Assumptions.assumeTrue(Objc.isLoaded());
        long device = Objc.createSystemDefaultMetalDevice();
        Assumptions.assumeTrue(device != 0);

        long pool = Objc.autoreleasePoolPush();
        try {
            MetalRtShaders.Compiled compiled = MetalRtShaders.compile(device);
            try {
                assertNotEquals(0L, compiled.expand().library(), "rt_expand library must build");
                assertNotEquals(0L, compiled.expand().function(), "rt_expand function must be found");
                assertNotEquals(0L, compiled.expand().pipeline(), "rt_expand pipeline state must build");
                assertNotEquals(0L, compiled.trace().library(), "rt_trace library must build");
                assertNotEquals(0L, compiled.trace().function(), "rt_trace function must be found");
                assertNotEquals(0L, compiled.trace().pipeline(), "rt_trace pipeline state must build");
                assertNotEquals(0L, compiled.debug().library(), "rt_debug library must build");
                assertNotEquals(0L, compiled.debug().function(), "rt_debug function must be found");
                assertNotEquals(0L, compiled.debug().pipeline(), "rt_debug pipeline state must build");
            } finally {
                compiled.release();
            }
        } finally {
            Objc.msgSendVoid(device, Objc.selector("release"));
            Objc.autoreleasePoolPop(pool);
        }
    }

    @Test
    void brokenSourceSurfacesTheCompilerMessage() {
        Assumptions.assumeTrue(Objc.isLoaded());
        long device = Objc.createSystemDefaultMetalDevice();
        Assumptions.assumeTrue(device != 0);

        long pool = Objc.autoreleasePoolPush();
        try {
            // Missing the expression after "=": clang's MSL front end reports this as
            // "expected expression" at the bad identifier, a phrase the "unknown error" fallback
            // text can never contain.
            String broken = "kernel void broken() { int x = ; }";
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> MetalRtShaders.compileSource(device, "broken.metal", broken));
            String message = e.getMessage();
            assertTrue(message != null && message.contains("broken.metal"),
                    "error must name the source: " + message);
            assertTrue(message.contains("error:") && message.contains("expected expression"),
                    "error must include the compiler's own diagnostic, not the fallback text: " + message);
        } finally {
            Objc.msgSendVoid(device, Objc.selector("release"));
            Objc.autoreleasePoolPop(pool);
        }
    }
}

package dev.icehunter.fornax.pipeline;

import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the vanilla members the FORWARD branch of {@code PreparedRenderTypeDeferredMixin} depends on.
 *
 * <p><b>Why this is a test and not a comment.</b> That mixin runs under {@code defaultRequire: 1}, so
 * an injector matching nothing is not a degraded feature -- it is a crash before the title screen.
 * {@code QuadParticleHookTargetsTest} exists for the particle hook for exactly this reason; this is
 * its counterpart for the forward one, and the forward branch adds a dependency the deferred branch
 * never had: it relies on {@code setPipeline} living in the SIX-argument {@code drawFromBuffer}
 * overload rather than the one-argument one that delegates to it. If vanilla ever inlines the
 * delegation the wrapper stops matching, and under {@code defaultRequire: 1} that is a startup crash.
 *
 * <p>It also stands in for a launch. The rule here is that the game is never started to find out; the
 * cheapest thing that turns "the injector should still match" into a fact is asserting the shapes it
 * matches against, offline, against the same jar the mod compiles with.
 */
public class ForwardHookTargetsTest {

    /**
     * The two wrapped calls must still be present with the descriptors the mixin names. Checked
     * against the constant pool because there is no reflective way to ask which calls a body makes.
     */
    @Test
    void drawFromBufferStillMakesBothWrappedCalls() throws Exception {
        String constants = readClassBytesAsLatin1(PreparedRenderType.class);
        assertTrue(constants.contains("createRenderPass"),
                "drawFromBuffer no longer opens its own render pass -- the deferred branch's"
                        + " render-pass wrapper matches nothing");
        assertTrue(constants.contains("setPipeline"),
                "drawFromBuffer no longer sets a pipeline -- the FORWARD branch lives entirely in that"
                        + " wrapper and would match nothing");
        assertTrue(constants.contains("(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"),
                "the setPipeline descriptor the pipeline wrapper targets is gone");
        assertTrue(constants.contains("(Ljava/util/function/Supplier;"
                        + "Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/Optional;"
                        + "Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/OptionalDouble;)"
                        + "Lcom/mojang/blaze3d/systems/RenderPass;"),
                "the createRenderPass overload the render-pass wrapper targets is gone");
    }

    /**
     * The six-argument overload the mixin names by full descriptor still exists.
     *
     * <p>Named rather than inferred: {@code drawFromBuffer} is overloaded, and the one-argument
     * {@code (ExecuteInfo)} form only delegates. Both the HEAD injector and both wrappers target the
     * six-argument form, so an overload change moves every one of them at once.
     */
    @Test
    void theSixArgumentDrawFromBufferOverloadStillExists() throws Exception {
        var method = PreparedRenderType.class.getDeclaredMethod("drawFromBuffer",
                com.mojang.blaze3d.buffers.GpuBuffer.class,
                com.mojang.blaze3d.buffers.GpuBuffer.class,
                com.mojang.blaze3d.IndexType.class,
                int.class, int.class, int.class);
        assertNotNull(method, "PreparedRenderType.drawFromBuffer(6 args) is gone");
    }

    /**
     * The pipeline the whole slot is built around still exists as a public constant, since
     * {@link ForwardPipelineMap}'s static initializer dereferences it at class-load time. A rename
     * would be a {@code NoSuchFieldError} on the first draw rather than anything legible.
     */
    @Test
    void bannerPatternPipelineStillExists() {
        assertNotNull(RenderPipelines.BANNER_PATTERN, "RenderPipelines.BANNER_PATTERN is gone");
        assertTrue(ForwardPipelineMap.size() >= 1, "ForwardPipelineMap mapped nothing");
    }

    /** Latin-1 keeps every byte a distinct char, so UTF8 constant-pool entries survive as substrings. */
    private static String readClassBytesAsLatin1(Class<?> type) throws Exception {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getResourceAsStream(resource)) {
            assertNotNull(in, "could not read " + resource);
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }
}

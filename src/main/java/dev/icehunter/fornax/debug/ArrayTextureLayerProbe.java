package dev.icehunter.fornax.debug;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.atlas.ArrayTextures;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Optional;

/**
 * One-shot GPU smoke test for phase-3 of the paged block atlas (see {@code dev.icehunter.fornax.atlas}):
 * proves an array texture allocated through {@link ArrayTextures} with {@code depthOrLayers=2} on this
 * MoltenVK backend is independently WRITABLE and SAMPLEABLE per layer, before any atlas holder class is
 * built on top of that assumption. Zero rendering-visible effect -- every resource this class touches is
 * a throwaway 1x1 texture created and exercised within a single keypress and closed when the GPU
 * round trip completes (see {@link #run}); it never opens a G-buffer or terrain handle, reads no live
 * render target, and only ever runs from an unbound-by-default keybind (see {@link FornaxDebugKeys}),
 * never automatically.
 *
 * <p><b>What is under test.</b> Blaze3D's public surface can neither create an array texture
 * ({@code GpuDevice.createTexture} throws for any non-cubemap {@code depthOrLayers > 1}) nor view one
 * (every stock {@code VulkanGpuTextureView} is {@code VK_IMAGE_VIEW_TYPE_2D} with
 * {@code layerCount(1)}), so {@link ArrayTextures} reaches the Vulkan backend directly and hand-builds
 * a {@code 2D_ARRAY} view -- see that class's doc for the decompile receipts. This probe is the
 * runtime proof that the resulting texture+view pair actually works end to end on this device: that
 * per-layer writes land where they claim to, and that the sampler unit really reads the layer the
 * shader asked for through the hand-built view.
 *
 * <p><b>Why "writable" and "sampleable" need two different proofs.</b> {@link
 * CommandEncoder#writeToTexture} is the only Blaze3D entry point that can address array layer &gt;= 1,
 * and it bounds-checks {@code depthOrLayer >= destination.getDepthOrLayers()}, throwing {@code
 * UnsupportedOperationException} out of range -- so a write that doesn't throw already IS the
 * writability proof. Sampling is not provable that cheaply: {@code CommandEncoder.copyTextureToBuffer}
 * (both overloads) and {@code copyTextureToTexture} all unconditionally throw {@code
 * UnsupportedOperationException} whenever either texture involved has {@code getDepthOrLayers() > 1}
 * (source-verified inside {@code CommandEncoder.java} itself, not a MoltenVK-specific quirk), so the
 * array texture can never be read back to the CPU directly. It also can never be a render-target
 * attachment ({@code createRenderPass} rejects any color/depth attachment with {@code
 * getDepthOrLayers() > 1} too). The only way left to prove a layer is really reaching the sampler unit
 * is a real shader round-trip: bind the array texture as a {@code sampler2DArray} input with the layer
 * selected at sample time via the array-index texture coordinate (exactly how the future atlas holder
 * will read it), render into an ordinary single-layer output texture (which IS a legal attachment),
 * and read THAT back instead. See {@code array_layer_probe.fsh} for the shader side of this.
 */
public final class ArrayTextureLayerProbe {
    private ArrayTextureLayerProbe() {
    }

    private static final int SOURCE_SIZE = 1; // 1x1 per layer -- only WHICH layer a sample lands on is under test, not filtering/addressing
    private static final int LAYER_COUNT = 2;
    private static final int OUTPUT_SIZE = 1;

    // std140 uniform block holding one int (the layer index): 16 bytes, padded to a full vec4 like
    // every other single-field uniform block in this codebase (see SsaaDownsamplePass's own
    // DOWNSAMPLE_SETTINGS_BUFFER_SIZE comment) rather than relying on the theoretical 4-byte minimum --
    // this probe's whole point is not trusting undocumented driver leniency.
    private static final int LAYER_UNIFORM_BUFFER_SIZE = 16;

    // Pure primary colors, alpha forced opaque -- 0/255 UNORM8 round-trips through a NEAREST-filtered
    // sample and an RGBA8_UNORM render target with zero quantization error, so a byte-exact match (or
    // mismatch) below means exactly what it looks like, not "close enough".
    private static final byte[] LAYER0_RGBA = {(byte) 0xFF, 0x00, 0x00, (byte) 0xFF}; // layer 0: red
    private static final byte[] LAYER1_RGBA = {0x00, (byte) 0xFF, 0x00, (byte) 0xFF}; // layer 1: green

    private static final BindGroupLayout BIND_GROUP = BindGroupLayout.builder()
            .withSampler("u_Source")
            .withUniform("u_LayerSelect", UniformType.UNIFORM_BUFFER)
            .build();

    // Screenquad blit shape, mirroring VoxelDebugRaymarchPass/SsaaDownsamplePass -- reuses vanilla's
    // core/screenquad vertex shader (no custom vertex stage needed) and writes into a plain
    // RGBA8_UNORM color target.
    private static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withBindGroupLayout(BIND_GROUP)
            .withLocation(Identifier.fromNamespaceAndPath("fornax", "array_layer_probe"))
            .withCull(false)
            .withVertexShader(Identifier.fromNamespaceAndPath("minecraft", "core/screenquad"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("fornax", "post/array_layer_probe"))
            .withDepthStencilState(Optional.empty())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
            .build();

    /**
     * Runs the write half of the round trip synchronously, submits the sample half to the GPU, and
     * returns; the per-layer sample verdicts and the final PASS/FAIL summary are logged (and pushed
     * to the actionbar) from the readback callbacks when the GPU actually finishes. That split is
     * measured, not stylistic: from this keybind-tick context {@code copyTextureToBuffer}'s callback
     * is DEFERRED until the frame's fence (observed live, 2026-08-21: both write PASS lines logged,
     * then the method returned with no sample line at all), unlike {@code GBufferReadbackDiagnostic}'s
     * mid-frame context where the same call runs its callback inline before returning. This probe
     * assumes neither: {@link Completion} below is armed for both orderings, so an inline callback
     * and a next-frame callback both produce exactly one verdict.
     *
     * <p>Every GPU resource is closed exactly once: on any path that fails before the sample
     * submissions, inline here; afterwards, ownership belongs to {@link Completion}, which closes
     * everything when the last callback (or synchronous skip) lands. If the GPU never completes the
     * copy, the resources leak until process exit -- acceptable for a keypress-driven throwaway
     * diagnostic, and the alternative (closing at return) is exactly the use-after-free this
     * rewrite removes. Never throws: a driver/API surprise is what this probe exists to catch, so
     * any exception is caught, logged as a FAIL, and folded into the verdict instead of propagating.
     *
     * @return a short summary for the actionbar; the definitive verdict follows asynchronously,
     *         e.g. {@code "[Fornax] Array layer probe verdict: PASS (4/4)"}
     */
    public static String run() {
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            FornaxMod.LOGGER.warn("[Fornax][array-probe] no GPU device available -- probe skipped");
            return "FAIL -- no GPU device";
        }

        ArrayTextures.Allocation array = null;
        GpuTexture outputTexture = null;
        GpuTextureView outputView = null;
        boolean write0;
        boolean write1;
        Completion completion;
        try {
            // The guarded public createTexture would throw here; ArrayTextures reaches the backend
            // directly and pairs the texture with a hand-built 2D_ARRAY view -- the seam this probe
            // exists to validate (see the class doc).
            array = ArrayTextures.create("Fornax Array Layer Probe Source",
                    GpuFormat.RGBA8_UNORM, SOURCE_SIZE, SOURCE_SIZE, LAYER_COUNT, 1);
            if (array == null) {
                FornaxMod.LOGGER.warn(
                        "[Fornax][array-probe] array textures unavailable on this backend (non-Vulkan) -- probe skipped");
                return "FAIL -- non-Vulkan backend";
            }

            CommandEncoder encoder = device.createCommandEncoder();

            write0 = writeLayer(encoder, array.texture(), 0, LAYER0_RGBA);
            write1 = writeLayer(encoder, array.texture(), 1, LAYER1_RGBA);

            outputTexture = device.createTexture("Fornax Array Layer Probe Output",
                    GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC,
                    GpuFormat.RGBA8_UNORM, OUTPUT_SIZE, OUTPUT_SIZE, 1, 1);
            outputView = device.createTextureView(outputTexture);

            // Ownership of all three resources transfers to the completion tracker here; from this
            // point every path -- submit, synchronous skip, or submit-time exception -- reports to
            // it exactly once per layer, and it closes everything when the count is full.
            completion = new Completion(array, outputTexture, outputView, write0, write1);
        } catch (RuntimeException e) {
            FornaxMod.LOGGER.error("[Fornax][array-probe] probe aborted by an unexpected exception", e);
            if (outputView != null) {
                outputView.close();
            }
            if (outputTexture != null) {
                outputTexture.close();
            }
            if (array != null) {
                array.close();
            }
            return "FAIL -- exception, see log";
        }

        submitSampleForLayer(device, array.view(), outputTexture, outputView, 0, LAYER0_RGBA, write0, completion);
        submitSampleForLayer(device, array.view(), outputTexture, outputView, 1, LAYER1_RGBA, write1, completion);

        int writesPassed = (write0 ? 1 : 0) + (write1 ? 1 : 0);
        return "writes " + writesPassed + "/2 -- sample verdict pending (GPU round trip)";
    }

    private static void submitSampleForLayer(GpuDevice device, GpuTextureView arrayView,
                                             GpuTexture outputTexture, GpuTextureView outputView,
                                             int layer, byte[] expectedRgba, boolean writeOk,
                                             Completion completion) {
        if (!writeOk) {
            FornaxMod.LOGGER.warn("[Fornax][array-probe] layer {} sample-back: SKIPPED (write failed)", layer);
            completion.sampleFinished(layer, false);
            return;
        }
        sampleAndVerify(device, device.createCommandEncoder(), arrayView, outputTexture, outputView,
                layer, expectedRgba, completion);
    }

    /**
     * Collects the four check results as they land -- the two write checks synchronously at
     * construction, the two sample checks whenever their readback callbacks fire (possibly inline,
     * possibly frames later; see {@link #run}'s doc) -- and, on the second sample result, logs the
     * final verdict, pushes it to the actionbar, and closes the three GPU resources it owns. All
     * callbacks and the constructing keybind handler run on the render thread, so plain fields are
     * enough (the same single-thread argument {@code GBufferReadbackDiagnostic.dumpRequested}
     * documents).
     */
    private static final class Completion {
        private final ArrayTextures.Allocation array;
        private final GpuTexture outputTexture;
        private final GpuTextureView outputView;
        private final boolean write0;
        private final boolean write1;
        private final boolean[] sampleResults = new boolean[LAYER_COUNT];
        private int samplesPending = LAYER_COUNT;

        Completion(ArrayTextures.Allocation array, GpuTexture outputTexture, GpuTextureView outputView,
                   boolean write0, boolean write1) {
            this.array = array;
            this.outputTexture = outputTexture;
            this.outputView = outputView;
            this.write0 = write0;
            this.write1 = write1;
        }

        void sampleFinished(int layer, boolean passed) {
            sampleResults[layer] = passed;
            samplesPending--;
            if (samplesPending > 0) {
                return;
            }
            int passedCount = (write0 ? 1 : 0) + (write1 ? 1 : 0)
                    + (sampleResults[0] ? 1 : 0) + (sampleResults[1] ? 1 : 0);
            boolean allPass = passedCount == 4;
            FornaxMod.LOGGER.info(
                    "[Fornax][array-probe] verdict: {} ({}/4 checks passed: write0={} write1={} sample0={} sample1={})",
                    allPass ? "PASS" : "FAIL", passedCount, write0, write1, sampleResults[0], sampleResults[1]);
            String summary = allPass ? "PASS (4/4)" : "FAIL (" + passedCount + "/4) -- see log";
            Minecraft.getInstance().gui.hud.setOverlayMessage(
                    Component.literal("[Fornax] Array layer probe verdict: " + summary), false);
            outputView.close();
            outputTexture.close();
            array.close();
        }
    }

    /**
     * Writes a single opaque solid-color pixel into {@code layer} via {@link
     * CommandEncoder#writeToTexture}, the only Blaze3D entry point that can address array layer &gt;=
     * 1. A successful call already IS the writability proof (see this class's own doc comment): {@code
     * writeToTexture} bounds-checks {@code depthOrLayer >= destination.getDepthOrLayers()} and throws
     * out of range, so reaching the PASS log line below means the layer was genuinely accepted, not
     * silently clamped to layer 0.
     */
    private static boolean writeLayer(CommandEncoder encoder, GpuTexture texture, int layer, byte[] rgba) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer pixel = stack.malloc(4).put(rgba).flip();
            encoder.writeToTexture(texture, pixel, 0, layer, 0, 0, SOURCE_SIZE, SOURCE_SIZE);
            FornaxMod.LOGGER.info("[Fornax][array-probe] layer {} write: PASS (wrote RGBA {})", layer, describe(rgba));
            return true;
        } catch (RuntimeException e) {
            FornaxMod.LOGGER.error("[Fornax][array-probe] layer {} write: FAIL ({})", layer, e.toString());
            return false;
        }
    }

    /**
     * Binds {@code sourceView} as {@code u_Source} (a {@code sampler2DArray}), selects {@code layer}
     * via the {@code u_LayerSelect} uniform, draws a full-screen triangle into {@code outputView}, and
     * submits a readback of {@code outputTexture} whose callback compares against {@code expectedRgba}
     * byte-for-byte, logs the per-layer verdict, and reports to {@code completion}. This is the only
     * proof in this class that a layer is reachable by the SAMPLER, not just by a direct write -- see
     * this class's own doc comment for why {@code copyTextureToBuffer} can't do that against the array
     * texture directly. The callback may run inline or frames later (see {@link #run}); either way it
     * runs on the render thread. The uniform and staging buffers are closed inside the callback (the
     * GPU is provably done with both once the copied bytes are readable); if submission itself throws,
     * they are closed here instead and the layer reports FAIL -- every path reports to
     * {@code completion} exactly once, so a failure here never aborts the other layer's check.
     */
    private static void sampleAndVerify(GpuDevice device, CommandEncoder encoder, GpuTextureView sourceView,
                                        GpuTexture outputTexture, GpuTextureView outputView,
                                        int layer, byte[] expectedRgba, Completion completion) {
        GpuBuffer layerBuffer = null;
        GpuBuffer readbackBuffer = null;
        boolean[] callbackRan = new boolean[1];
        try {
            layerBuffer = device.createBuffer(() -> "[Fornax] array-probe layer select " + layer,
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, LAYER_UNIFORM_BUFFER_SIZE);
            try (var data = layerBuffer.map(false, true)) {
                Std140Builder.intoBuffer(data.data()).putInt(layer).get();
            }

            GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            try (RenderPass pass = encoder.createRenderPass(() -> "Fornax Array Layer Probe Sample",
                    outputView, Optional.empty())) {
                pass.setPipeline(PIPELINE);
                pass.setUniform("u_LayerSelect", layerBuffer);
                pass.bindTexture("u_Source", sourceView, sampler);
                pass.draw(3, 1, 0, 0); // full-screen triangle from gl_VertexID, same as every other screenquad blit here
            }

            readbackBuffer = device.createBuffer(() -> "[Fornax] array-probe readback " + layer,
                    GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_HINT_CLIENT_STORAGE | GpuBuffer.USAGE_COPY_DST,
                    outputTexture.getFormat().blockSize());
            GpuBuffer uniformToClose = layerBuffer;
            GpuBuffer stagingToClose = readbackBuffer;
            encoder.copyTextureToBuffer(outputTexture, readbackBuffer, 0L, () -> {
                callbackRan[0] = true;
                byte[] actual = new byte[4];
                try (var read = stagingToClose.map(true, false)) {
                    for (int i = 0; i < 4; i++) {
                        actual[i] = read.data().get(i);
                    }
                }
                stagingToClose.close();
                uniformToClose.close();
                boolean match = Arrays.equals(actual, expectedRgba);
                FornaxMod.LOGGER.info(
                        "[Fornax][array-probe] layer {} sample-back: {} (expected RGBA {}, got RGBA {})",
                        layer, match ? "PASS" : "FAIL", describe(expectedRgba), describe(actual));
                completion.sampleFinished(layer, match);
            }, 0, 0, 0, OUTPUT_SIZE, OUTPUT_SIZE);
        } catch (RuntimeException e) {
            FornaxMod.LOGGER.error("[Fornax][array-probe] layer {} sample: FAIL ({})", layer, e.toString());
            if (!callbackRan[0]) {
                if (readbackBuffer != null) {
                    readbackBuffer.close();
                }
                if (layerBuffer != null) {
                    layerBuffer.close();
                }
                completion.sampleFinished(layer, false);
            }
        }
    }

    private static String describe(byte[] rgba) {
        return String.format("(%d, %d, %d, %d)", rgba[0] & 0xFF, rgba[1] & 0xFF, rgba[2] & 0xFF, rgba[3] & 0xFF);
    }
}

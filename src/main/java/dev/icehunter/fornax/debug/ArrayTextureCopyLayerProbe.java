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
 * One-shot GPU round-trip proof for {@link ArrayTextures#copyLayer}: writes two colors into two
 * ordinary 2D textures, copies each into its own layer of a real array texture via
 * {@code copyLayer}, then shader-samples the array back, the same way {@link
 * ArrayTextureLayerProbe} proves a CPU-written layer. This proves the GPU-copy path specifically:
 * that a copied layer is visible to a sampler with correct barriers and no cross-layer
 * corruption. Zero rendering-visible effect; runs only from an unbound-by-default keybind (see
 * {@link FornaxDebugKeys}).
 *
 * <p>Deliberately does not share {@code ArrayTextureLayerProbe}'s pipeline/bind-group fields: both
 * probes are self-contained, throwaway diagnostics with no shared lifecycle.
 */
public final class ArrayTextureCopyLayerProbe {
    private ArrayTextureCopyLayerProbe() {
    }

    private static final int SIZE = 1; // 1x1: only whether a layer lands right is under test
    private static final int LAYER_COUNT = 2;
    private static final int LAYER_UNIFORM_BUFFER_SIZE = 16; // std140 int, padded to a vec4

    // Pure primary colors, alpha opaque: 0/255 UNORM8 round-trips through NEAREST with zero
    // quantization error.
    private static final byte[] LAYER0_RGBA = {0x00, 0x00, (byte) 0xFF, (byte) 0xFF}; // layer 0: blue
    private static final byte[] LAYER1_RGBA = {(byte) 0xFF, (byte) 0xFF, 0x00, (byte) 0xFF}; // layer 1: yellow

    private static final BindGroupLayout BIND_GROUP = BindGroupLayout.builder()
            .withSampler("u_Source")
            .withUniform("u_LayerSelect", UniformType.UNIFORM_BUFFER)
            .build();

    // Own location, distinct from ArrayTextureLayerProbe's pipeline, so the two never share a
    // debug/profiler label despite an identical shape.
    private static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withBindGroupLayout(BIND_GROUP)
            .withLocation(Identifier.fromNamespaceAndPath("fornax", "array_copy_layer_probe"))
            .withCull(false)
            .withVertexShader(Identifier.fromNamespaceAndPath("minecraft", "core/screenquad"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("fornax", "post/array_layer_probe"))
            .withDepthStencilState(Optional.empty())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
            .build();

    /**
     * Submits both copies and sample-back draws, then returns; the verdict and PASS/FAIL summary
     * are logged from the readback callbacks instead, since {@code copyTextureToBuffer}'s callback
     * can land a frame later.
     *
     * <p>Every GPU resource is closed exactly once, inline on an early failure or by
     * {@link Completion} once both layers' verdicts land. Never throws.
     */
    public static String run() {
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            FornaxMod.LOGGER.warn("[Fornax][array-copy-probe] no GPU device available: probe skipped");
            return "FAIL: no GPU device";
        }

        ArrayTextures.Allocation array = null;
        GpuTexture source0 = null;
        GpuTextureView source0View = null;
        GpuTexture source1 = null;
        GpuTextureView source1View = null;
        GpuTexture outputTexture = null;
        GpuTextureView outputView = null;
        boolean copy0;
        boolean copy1;
        Completion completion;
        try {
            array = ArrayTextures.create("Fornax Array Copy-Layer Probe Destination",
                    GpuFormat.RGBA8_UNORM, SIZE, SIZE, LAYER_COUNT, 1);
            if (array == null) {
                FornaxMod.LOGGER.warn(
                        "[Fornax][array-copy-probe] array textures unavailable on this backend (non-Vulkan): probe skipped");
                return "FAIL: non-Vulkan backend";
            }

            // Ordinary, non-array 2D textures: the shape a real G-buffer/resolve input actually is.
            CommandEncoder encoder = device.createCommandEncoder();
            source0 = device.createTexture("Fornax Array Copy-Layer Probe Source 0",
                    GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1);
            source0View = device.createTextureView(source0);
            source1 = device.createTexture("Fornax Array Copy-Layer Probe Source 1",
                    GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1);
            source1View = device.createTextureView(source1);
            writeSolidColor(encoder, source0, LAYER0_RGBA);
            writeSolidColor(encoder, source1, LAYER1_RGBA);

            // The seam under test: a raw GPU copy into each layer.
            copy0 = copyLayer(source0View, array, 0);
            copy1 = copyLayer(source1View, array, 1);

            outputTexture = device.createTexture("Fornax Array Copy-Layer Probe Output",
                    GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC,
                    GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1);
            outputView = device.createTextureView(outputTexture);

            completion = new Completion(array, source0, source0View, source1, source1View,
                    outputTexture, outputView, copy0, copy1);
        } catch (RuntimeException e) {
            FornaxMod.LOGGER.error("[Fornax][array-copy-probe] probe aborted by an unexpected exception", e);
            closeIfNotNull(outputView, outputTexture, source1View, source1, source0View, source0, array);
            return "FAIL: exception, see log";
        }

        submitSampleForLayer(device, array.view(), outputTexture, outputView, 0, LAYER0_RGBA, copy0, completion);
        submitSampleForLayer(device, array.view(), outputTexture, outputView, 1, LAYER1_RGBA, copy1, completion);

        int copiesPassed = (copy0 ? 1 : 0) + (copy1 ? 1 : 0);
        return "copies " + copiesPassed + "/2: sample verdict pending (GPU round trip)";
    }

    private static void closeIfNotNull(AutoCloseable... resources) {
        for (AutoCloseable resource : resources) {
            if (resource != null) {
                try {
                    resource.close();
                } catch (Exception ignored) {
                    // Best-effort cleanup on an already-failed path; nothing left to report to.
                }
            }
        }
    }

    private static boolean copyLayer(GpuTextureView source, ArrayTextures.Allocation destination, int layer) {
        try {
            ArrayTextures.copyLayer(source, destination, layer);
            FornaxMod.LOGGER.info("[Fornax][array-copy-probe] layer {} copy: PASS (recorded)", layer);
            return true;
        } catch (RuntimeException e) {
            FornaxMod.LOGGER.error("[Fornax][array-copy-probe] layer {} copy: FAIL ({})", layer, e.toString());
            return false;
        }
    }

    private static void writeSolidColor(CommandEncoder encoder, GpuTexture texture, byte[] rgba) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer pixel = stack.malloc(4).put(rgba).flip();
            encoder.writeToTexture(texture, pixel, 0, 0, 0, 0, SIZE, SIZE);
        }
    }

    private static void submitSampleForLayer(GpuDevice device, GpuTextureView arrayView,
                                             GpuTexture outputTexture, GpuTextureView outputView,
                                             int layer, byte[] expectedRgba, boolean copyOk,
                                             Completion completion) {
        if (!copyOk) {
            FornaxMod.LOGGER.warn("[Fornax][array-copy-probe] layer {} sample-back: SKIPPED (copy failed)", layer);
            completion.sampleFinished(layer, false);
            return;
        }
        sampleAndVerify(device, device.createCommandEncoder(), arrayView, outputTexture, outputView,
                layer, expectedRgba, completion);
    }

    /** Same collection/close shape as {@link ArrayTextureLayerProbe.Completion}; see that class's
     * doc for why plain fields (no synchronization) are enough on the render thread. */
    private static final class Completion {
        private final ArrayTextures.Allocation array;
        private final GpuTexture source0;
        private final GpuTextureView source0View;
        private final GpuTexture source1;
        private final GpuTextureView source1View;
        private final GpuTexture outputTexture;
        private final GpuTextureView outputView;
        private final boolean copy0;
        private final boolean copy1;
        private final boolean[] sampleResults = new boolean[LAYER_COUNT];
        private int samplesPending = LAYER_COUNT;

        Completion(ArrayTextures.Allocation array, GpuTexture source0, GpuTextureView source0View,
                   GpuTexture source1, GpuTextureView source1View, GpuTexture outputTexture,
                   GpuTextureView outputView, boolean copy0, boolean copy1) {
            this.array = array;
            this.source0 = source0;
            this.source0View = source0View;
            this.source1 = source1;
            this.source1View = source1View;
            this.outputTexture = outputTexture;
            this.outputView = outputView;
            this.copy0 = copy0;
            this.copy1 = copy1;
        }

        void sampleFinished(int layer, boolean passed) {
            sampleResults[layer] = passed;
            samplesPending--;
            if (samplesPending > 0) {
                return;
            }
            int passedCount = (copy0 ? 1 : 0) + (copy1 ? 1 : 0)
                    + (sampleResults[0] ? 1 : 0) + (sampleResults[1] ? 1 : 0);
            boolean allPass = passedCount == 4;
            FornaxMod.LOGGER.info(
                    "[Fornax][array-copy-probe] verdict: {} ({}/4 checks passed: copy0={} copy1={} sample0={} sample1={})",
                    allPass ? "PASS" : "FAIL", passedCount, copy0, copy1, sampleResults[0], sampleResults[1]);
            String summary = allPass ? "PASS (4/4)" : "FAIL (" + passedCount + "/4): see log";
            Minecraft.getInstance().gui.hud.setOverlayMessage(
                    Component.literal("[Fornax] Array copy-layer probe verdict: " + summary), false);
            outputView.close();
            outputTexture.close();
            source1View.close();
            source1.close();
            source0View.close();
            source0.close();
            array.close();
        }
    }

    private static void sampleAndVerify(GpuDevice device, CommandEncoder encoder, GpuTextureView sourceView,
                                        GpuTexture outputTexture, GpuTextureView outputView,
                                        int layer, byte[] expectedRgba, Completion completion) {
        GpuBuffer layerBuffer = null;
        GpuBuffer readbackBuffer = null;
        boolean[] callbackRan = new boolean[1];
        try {
            layerBuffer = device.createBuffer(() -> "[Fornax] array-copy-probe layer select " + layer,
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, LAYER_UNIFORM_BUFFER_SIZE);
            try (var data = layerBuffer.map(false, true)) {
                Std140Builder.intoBuffer(data.data()).putInt(layer).get();
            }

            GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            try (RenderPass pass = encoder.createRenderPass(() -> "Fornax Array Copy-Layer Probe Sample",
                    outputView, Optional.empty())) {
                pass.setPipeline(PIPELINE);
                pass.setUniform("u_LayerSelect", layerBuffer);
                pass.bindTexture("u_Source", sourceView, sampler);
                pass.draw(3, 1, 0, 0);
            }

            readbackBuffer = device.createBuffer(() -> "[Fornax] array-copy-probe readback " + layer,
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
                        "[Fornax][array-copy-probe] layer {} sample-back: {} (expected RGBA {}, got RGBA {})",
                        layer, match ? "PASS" : "FAIL", describe(expectedRgba), describe(actual));
                completion.sampleFinished(layer, match);
            }, 0, 0, 0, SIZE, SIZE);
        } catch (RuntimeException e) {
            FornaxMod.LOGGER.error("[Fornax][array-copy-probe] layer {} sample: FAIL ({})", layer, e.toString());
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

package dev.icehunter.fornax.pack.graph;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import dev.icehunter.fornax.pack.PassSpec;
import dev.icehunter.fornax.pipeline.GBuffer;
import dev.icehunter.fornax.pipeline.GBufferManager;
import net.minecraft.client.Minecraft;

/**
 * Generalizes the hardcoded pipeline's raw GPU-to-GPU copies -- {@code TaaManager}'s
 * copy-in-from-mainRenderTarget / copy-blended-back-out dance around the TAA blend, and
 * {@code HistoryBufferManager}/{@code SsaoManager}/{@code SsrManager}'s own commit copies -- into
 * a single, shader-less {@code copy} pass type: exactly one input, exactly one output, one
 * {@code CommandEncoder.copyTextureToTexture} call.
 */
public final class CopyRunner {
    private CopyRunner() {
    }

    public static void run(PassSpec spec, TargetRegistry registry) {
        if (spec.inputs().size() != 1 || spec.outputs().size() != 1) {
            throw new IllegalArgumentException("Fornax graph: copy pass '" + spec.name()
                    + "' must declare exactly one input and one output");
        }

        if (spec.outputs().get(0).equals("builtin.sceneDepth")) {
            // builtin.sceneDepth is a write-only sink (the main render target's depth) with no valid
            // read-view, so it's special-cased here rather than in GraphInputResolver -- resolving it
            // generically there would let some other pass illegitimately treat it as a readable input.
            // Dimensions come from GBufferManager, not Math.min(src,dst), to exactly match the old
            // hardcoded engine copy this pass replaces.
            GBuffer gbuffer = GBufferManager.getInstance();
            RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
                    gbuffer.getDepthTexture(),
                    Minecraft.getInstance().gameRenderer.mainRenderTarget().getDepthTexture(),
                    0, 0, 0, 0, 0, gbuffer.getWidth(), gbuffer.getHeight());
            return;
        }

        GpuTexture src = GraphInputResolver.resolveTexture(spec.inputs().get(0), registry);
        GpuTexture dst = GraphInputResolver.resolveTexture(spec.outputs().get(0), registry);

        int width = Math.min(src.getWidth(0), dst.getWidth(0));
        int height = Math.min(src.getHeight(0), dst.getHeight(0));

        RenderSystem.getDevice().createCommandEncoder()
                .copyTextureToTexture(src, dst, 0, 0, 0, 0, 0, width, height);
    }
}

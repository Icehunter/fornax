package dev.icehunter.fornax.util;

import net.minecraft.client.Minecraft;

/**
 * Asks the client to rebuild the level renderer -- {@code Minecraft.levelExtractor.allChanged()},
 * the exact call Sodium's own {@code Config.onRendererReload()} makes for options flagged
 * {@code REQUIRES_RENDERER_RELOAD} (javap-confirmed against the real sodium-fabric-0.9.0 jar).
 * This triggers Sodium's full renderer reload, which does two things: it marks every chunk section
 * dirty for a full remesh via the level-changed path, and it tears down and recreates the chunk
 * renderer, which is what recompiles the cached terrain {@code RenderPipeline}s through
 * {@code createShader}.
 *
 * <p>MUST run whenever {@code GraphRunner.isActive()} flips or the active pack changes: the terrain
 * shader redirect, the {@code USE_DEFERRED} constant, and the 5-attachment G-buffer pipeline state
 * are all baked into those cached pipelines at compile time, while the per-frame render-pass
 * attachment count follows the CURRENT isActive() value -- flipping one without rebuilding the
 * other crashes {@code RenderPass.setPipeline} with "color attachment count must match pipeline
 * color target state count" on the shaders on/off toggle.
 */
public final class RendererReload {
    private RendererReload() {
    }

    public static void request() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null) {
            minecraft.levelExtractor.allChanged();
        }
    }
}

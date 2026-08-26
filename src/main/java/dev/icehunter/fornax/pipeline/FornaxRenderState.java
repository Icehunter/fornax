package dev.icehunter.fornax.pipeline;

/**
 * The LATCHED render-activity state every pipeline-affecting decision reads -- never the live
 * config. Three compile-time facts (the terrain shader id redirect, the {@code USE_DEFERRED}
 * constant, the 5-attachment color-target-state set) and one per-frame fact (the G-buffer render
 * pass's attachment count) must all agree for any given terrain pipeline; letting each read
 * {@code FornaxConfig.get().shadersEnabled} live means a settings apply flips the per-frame reads
 * instantly while pipelines stay compiled under the old state -- crashing {@code
 * RenderPass.setPipeline} with "color attachment count must match pipeline color target state
 * count": the renderer reload alone does NOT recompile pipelines, because
 * {@code ShaderChunkRenderer.programs} is a process-wide static cache whose {@code delete()} is a
 * no-op -- javap-verified against sodium-fabric-0.9.0).
 *
 * <p>The latch advances at exactly one boundary: {@code SodiumWorldRenderer.initRenderer()} (see
 * {@code SodiumWorldRendererReloadMixin}), which runs synchronously inside both renderer-reload
 * paths (Sodium's {@code REQUIRES_RENDERER_RELOAD} flag handling and Fornax's own {@code
 * RendererReload.request()}, both via {@code LevelExtractor.allChanged()}) and at world load --
 * always between frames, never mid terrain rendering. The same hook clears Sodium's static
 * pipeline cache, so every pipeline compiled after the flip and every render pass created after it
 * observe the same state, and every frame before it observes the old state consistently.
 */
public final class FornaxRenderState {
    private static volatile boolean active;

    private FornaxRenderState() {
    }

    /** The latched "pack graph drives rendering" flag -- the ONLY activity signal mixins may read. */
    public static boolean isActive() {
        return active;
    }

    /**
     * Advances the latch. Call ONLY from the renderer-recreation boundary ({@code
     * SodiumWorldRendererReloadMixin}) -- advancing anywhere else reintroduces the torn-state crash
     * this class exists to prevent.
     */
    public static void latch(boolean newActive) {
        active = newActive;
    }
}

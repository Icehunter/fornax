package dev.icehunter.fornax.pipeline;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.icehunter.fornax.pack.GraphSpec;
import dev.icehunter.fornax.pack.TargetSpec;
import dev.icehunter.fornax.pack.graph.TargetBasis;
import dev.icehunter.fornax.pack.graph.TargetInstance;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The engine-guaranteed scene-color history: one ping-ponged target, written once per frame from
 * whatever {@code builtin.output} ends up holding -- under EVERY {@link
 * dev.icehunter.fornax.config.AaMethod} (OFF/TAA/SSAA/TAAU), not just when a pack's own graph
 * happens to run a temporal pass. Packs never declare this target themselves; {@link #injectInto}
 * is how {@code GraphRunner.rebuild} adds it to a loaded pack's target set so {@code
 * TargetRegistry}/{@code TargetPlan} allocate and ping-pong it exactly like any other {@code
 * history = true} target, and a pack's own graph references it purely by name --
 * {@code sceneHistory.history} -- as though it were engine-provided built-in input.
 *
 * <p><b>Design decision</b> (resolves a spec ambiguity): the original spec described sceneHistory
 * as written by an unconditional post-resolve GRAPH copy pass (the slot {@code taa_copy_in} held
 * before this task retired it). Under TAAU the graph runs at render (low) resolution, so a
 * graph-level copy pass would capture low-res color at the wrong basis. The write therefore
 * happens ENGINE-SIDE, once, at {@code GameRenderer.renderLevel} RETURN, from the final NATIVE
 * {@code builtin.output} -- one code path for every method, inheriting cleanly once the RT phase
 * lands. The pack graph itself carries no sceneHistory pass; SSR/resolve read {@code
 * sceneHistory.history} via normalized UV, so it is declared {@link TargetBasis#OUTPUT} -- it
 * always sizes off native output resolution, even once a later task points render resolution
 * below native under TAAU, so this history buffer never loses native detail regardless of what
 * resolution the graph itself ran at.
 */
public final class SceneHistory {
    /** Names the engine-owned ping-pong target the registry allocates -- packs reference it only as {@code sceneHistory.history}. */
    public static final String TARGET = "sceneHistory";

    private SceneHistory() {
    }

    /**
     * rgba8, scale 1.0, OUTPUT-basis (always native resolution regardless of render scale), always
     * history-backed, never {@code enabled_if}-gated -- it exists under every method, including OFF.
     */
    public static TargetSpec spec() {
        return new TargetSpec(TARGET, "rgba8", 1.0, true, null, TargetBasis.OUTPUT);
    }

    /**
     * Adds the engine-owned target to {@code graph}'s declared target set if not already present.
     * Idempotent, since {@code GraphRunner.rebuild} calls this on every (re)build -- a graph that
     * already carries the entry (every rebuild after the first) is returned unchanged rather than
     * gaining a second one.
     */
    public static GraphSpec injectInto(GraphSpec graph) {
        if (graph.targets().containsKey(TARGET)) {
            return graph;
        }
        Map<String, TargetSpec> targets = new LinkedHashMap<>(graph.targets());
        targets.put(TARGET, spec());
        return new GraphSpec(targets, graph.textures(), graph.passes());
    }

    /**
     * The physical texture the end-of-frame copy must write: the post-swap HISTORY slot, not
     * {@code current}. Phase matters here, and it's easy to get backwards: {@code
     * TargetRegistry.swapHistory()} runs at the end of {@code GraphRunner.finish()}, which is
     * MID-{@code renderLevel} -- well before the engine's RETURN-time copy. So by the time the
     * copy runs, this frame's swap has already happened, and next frame's graph passes read
     * {@code sceneHistory.history} BEFORE next frame's own swap -- i.e. they read whatever is
     * sitting in the history slot right now. Writing {@code current} instead (the intuitive
     * choice) parks this frame's color where readers only see it after ONE MORE swap: two frames
     * stale in steady state, and a never-written (black) history slot on the second frame ever.
     * See {@code SceneHistoryPhaseTest} for the frame-by-frame simulation pinning this.
     */
    public static GpuTexture writeSlot(TargetInstance sceneHistory) {
        return sceneHistory.historyTexture();
    }

    /**
     * {@link #writeSlot} as a render-attachment view -- under TAA/TAAU the reconstruct pass
     * renders its unsharpened accumulation directly into this slot (same slot, same post-swap
     * phase as the copy) and thereby REPLACES {@link #copyFinalColor} as the frame's sceneHistory
     * writer, so the presentation sharpen can live outside the temporal feedback loop entirely.
     */
    public static GpuTextureView writeSlotView(TargetInstance sceneHistory) {
        return sceneHistory.historyView();
    }

    /**
     * The view holding the PREVIOUS frame's final color for a POST-SWAP reader -- the engine
     * reconstruct pass, which runs at {@code renderLevel} RETURN, after this frame's {@code
     * swapHistory()} (end of {@code GraphRunner.finish()}, mid-{@code renderLevel}) already ran.
     * Post-swap, {@code view()}/current is the slot LAST frame's end-of-frame copy wrote -- one
     * frame old, correct; {@code historyView()} is the slot written two copies ago -- TWO frames
     * stale. Pack passes get one-frame-old data from {@code sceneHistory.history} only because
     * they run PRE-swap; a post-swap reader naively copying that read (the original reconstruct
     * bug) blends toward an image a full frame behind the motion vectors, which at a 0.9 temporal
     * weight is velocity-proportional trailing on every camera move. {@code SceneHistoryPhaseTest}
     * pins this slot choice frame by frame alongside {@link #writeSlot}'s.
     */
    public static GpuTextureView reconstructReadSlot(TargetInstance sceneHistory) {
        return sceneHistory.view();
    }

    /**
     * GPU copy of the final native color into {@link #writeSlot} -- the slot next frame's graph
     * passes will read as {@code sceneHistory.history} (see writeSlot's phase note). Called once
     * per frame, unconditionally, from {@code GameRendererMixin} at the true end of {@code
     * renderLevel} -- after supersampling's own downsample-and-restore has already run, so
     * {@code finalColor} is always the native-resolution frame regardless of method.
     */
    public static void copyFinalColor(GpuTextureView finalColor, TargetInstance sceneHistory, int width, int height) {
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
                finalColor.texture(), writeSlot(sceneHistory), 0, 0, 0, 0, 0, width, height);
    }
}

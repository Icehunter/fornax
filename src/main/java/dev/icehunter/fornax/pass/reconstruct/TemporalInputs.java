package dev.icehunter.fornax.pass.reconstruct;

import org.jspecify.annotations.Nullable;

/**
 * The single contract deciding whether this frame's temporal-reconstruct inputs -- the G-buffer's
 * motion and depth attachments, and the engine {@code sceneHistory} target -- carry data that
 * THIS frame's geometry actually wrote.
 *
 * <p><b>Why this exists as its own predicate.</b> A null-only check -- "are the targets
 * ALLOCATED?" -- is not the same question as whether they carry data THIS frame wrote. {@code
 * GBufferManager} has no teardown path at all -- {@code ensureSize} only ever REPLACES its
 * instance, and {@code GraphRunner.closeCurrent()} never touches it -- and flipping the master
 * shaders-enabled toggle off with a pack still selected runs {@code ShadersEnabledFlip.apply(false)},
 * which deliberately does NOT unload the pack, so {@code GraphRunner.registry} (and with it {@code
 * sceneHistoryTarget()}) stays alive too. Both handles therefore survive the toggle while {@code
 * GraphRunner.prepare()} early-returns on the latched {@code isActive()} and nothing writes a
 * single motion vector. A null-only guard sees two live handles, skips nothing, and hands the
 * reconstruct a FROZEN G-buffer: {@code reconstruct.fsh} reprojects with the last deferred frame's
 * motion, the disocclusion guard compares two stale depths and finds them identical, and at {@code
 * taaBlendFactor} 0.9 nine tenths of every pixel comes from history -- vanilla rendering under a
 * heavy ghost of the moment the user turned shaders off.
 *
 * <p><b>The contract, stated rather than inferred.</b> Motion and depth are trustworthy exactly
 * when the pack graph is driving rendering, because that is the same latch ({@link
 * dev.icehunter.fornax.pipeline.FornaxRenderState}) the terrain pipelines' deferred G-buffer
 * writes are compiled against. It advances only at the renderer-recreation boundary -- between
 * frames, never mid-render -- so unlike a live config read it cannot disagree with itself between
 * {@code renderLevel}'s HEAD and RETURN. Allocation remains necessary but is no longer sufficient.
 *
 * <p><b>Consequence, deliberate:</b> with shaders off (or no pack) TAA/TAAU/METALFX do nothing.
 * That is correct, not a degradation to apologise for -- vanilla-only rendering produces no motion
 * vectors, and a temporal method with no motion is strictly worse than none. {@code aaMethod} is an
 * engine setting independent of any pack, but every method it offers except OFF and SSAA needs data
 * only a pack can produce. SSAA is unaffected: its box downsample reads the colour target alone.
 *
 * <p><b>Rejected:</b> nulling {@code GBufferManager}'s instance on the shaders-off flip. It would
 * fix the same symptom, but it makes the guard depend on getting a GPU-resource lifetime right
 * (every other teardown in {@code closeCurrent()} is fenced by an explicit device wait-idle after
 * two live MoltenVK crashes), and it would leave the predicate still saying "allocated" while
 * meaning "written". Also rejected: keying the guard on {@code FornaxConfig.get().shadersEnabled}
 * directly -- that is the LIVE value, and reading it is the exact torn-state mistake {@code
 * FornaxRenderState} was created to prevent.
 */
public final class TemporalInputs {
    /** Why the reconstruct's inputs cannot be trusted this frame, in the order the checks apply. */
    public enum Unavailable {
        /**
         * Checked FIRST, and the reason the other two are not enough on their own: with the graph
         * inactive, a still-allocated G-buffer holds the last deferred frame's data forever.
         */
        GRAPH_INACTIVE("the pack graph is not driving rendering (shaders off, no pack loaded,"
                + " or a renderer reload has not landed yet), so no motion vectors are written"),
        NO_GBUFFER("no GBuffer is built"),
        NO_SCENE_HISTORY("sceneHistory has no allocated target");

        private final String reason;

        Unavailable(String reason) {
            this.reason = reason;
        }

        /** Log-ready phrase, completing "Skipping TAA/TAAU reconstruct this frame: ...". */
        public String reason() {
            return this.reason;
        }
    }

    private TemporalInputs() {
    }

    /**
     * Why this frame's reconstruct inputs are untrustworthy, or {@code null} when they are usable.
     *
     * <p>Takes plain booleans rather than the live objects so the contract is decidable without a
     * GPU, a pack or a Minecraft instance -- the three bugs of this family (sky reprojection,
     * particle deferral, this one) were all a pass consuming data nothing had written, and none was
     * catchable by a test until the "may I consume it" question stopped being spelled inline.
     *
     * @param graphActive             {@code GraphRunner.isActive()} -- the LATCHED flag, never live config
     * @param gbufferBuilt            {@code GBufferManager.getInstance() != null}
     * @param sceneHistoryAllocated   {@code GraphRunner.sceneHistoryTarget() != null}
     */
    @Nullable
    public static Unavailable unavailable(boolean graphActive, boolean gbufferBuilt,
            boolean sceneHistoryAllocated) {
        if (!graphActive) {
            return Unavailable.GRAPH_INACTIVE;
        }
        if (!gbufferBuilt) {
            return Unavailable.NO_GBUFFER;
        }
        if (!sceneHistoryAllocated) {
            return Unavailable.NO_SCENE_HISTORY;
        }
        return null;
    }
}

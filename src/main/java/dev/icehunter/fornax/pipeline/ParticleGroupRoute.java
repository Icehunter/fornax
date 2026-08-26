package dev.icehunter.fornax.pipeline;

/**
 * What {@code QuadParticleFeatureRenderer.executeGroup} should do with one prepared particle group:
 * leave it entirely alone, defer it into the G-buffer, or substitute the pack's program on vanilla's
 * own forward draw.
 *
 * <p><b>Why one three-valued decision rather than two independent booleans.</b> Both wrappers in
 * {@code QuadParticleDeferredMixin} sit on the same instruction stream and must agree about a single
 * group: the render-pass wrapper decides whether to REPLACE the attachments, and the pipeline wrapper
 * decides which variant to bind. Chaining a second {@code @WrapOperation} onto the same instruction to
 * carry a second decision was the obvious alternative and is rejected outright -- with two wrappers on
 * one call, which one fired for a given draw is not attributable from a log, making a route bug
 * indistinguishable from a pipeline bug at the point of failure. One enum, taken once at HEAD, read
 * twice.
 *
 * <p><b>The two rules are disjoint by construction, not by ordering.</b>
 * {@link DeferredGeometryPipelines#wantsDeferredParticleGroup} requires the group to be NON-translucent
 * and {@link DeferredGeometryPipelines#wantsForwardParticleGroup} requires it to BE translucent, so no
 * input can satisfy both. {@code ParticleGroupRouteContractTest} asserts that over the whole input
 * space rather than trusting the order of the two tests below.
 */
public enum ParticleGroupRoute {
    /** Draw exactly as vanilla would: vanilla's pass, vanilla's pipeline, no Fornax uniforms. */
    VANILLA,

    /**
     * The SOLID arm. The render pass is rewritten to Fornax's five G-buffer attachments and each
     * layer's pipeline is swapped for its deferred variant, so the resolve lights and fogs the
     * particle with everything else. Without this ~30 particle families are painted over by the
     * pack's tonemap and are simply invisible.
     */
    DEFER,

    /**
     * The TRANSLUCENT arm. Vanilla's render pass, target, blend and ordering are kept exactly as
     * issued and ONLY the pipeline is substituted, so the pack's program composites display-referred
     * colour into the already-tonemapped frame -- which is what lets it fog smoke in place without
     * losing the blend that keeps partial-alpha sprites from reading as solid rectangles.
     */
    FORWARD;

    /**
     * Resolves the route from the group's own facts and the frame's state.
     *
     * <p>Pure, and deliberately takes every input as a parameter rather than reading
     * {@code FornaxRenderState} / {@code GBufferManager} / {@code Minecraft} itself: the whole value of
     * this being a function is that a test can sweep it exhaustively, and a static read would need a
     * running renderer to exercise a single case.
     *
     * @param groupTranslucent      {@code PreparedGroup.translucent} -- the arm flag vanilla itself
     *                              branches on when choosing a render target. NOT
     *                              {@code executeGroup}'s trailing boolean, which is
     *                              {@code strictlyOrdered} and unrelated.
     * @param anyLayerTranslucent   whether the group's layer map holds at least one translucent layer
     * @param allLayersTranslucent  whether it holds at least one layer and every one is translucent
     * @param packActive            a Fornax pack is loaded and rendering
     * @param shadowPhase           this is the shadow-casting replay of {@code executeSolid}
     * @param gBufferPresent        a G-buffer exists to defer into
     * @param separateParticlesTarget {@code LevelRenderer.particlesTarget() != null} -- the
     *                              {@code improvedTransparency} option ("Improved Transparency" in
     *                              Video Settings), where a translucent group draws into a separate
     *                              transparency buffer instead of the tonemapped frame. NOT the
     *                              {@code graphicsMode} / "Fabulous!" setting, which 26.2 does not
     *                              have: the transparency post chain reads its own boolean, and
     *                              {@code graphicsMode}'s absence from {@code options.txt} says
     *                              nothing about this input
     */
    public static ParticleGroupRoute decide(boolean groupTranslucent, boolean anyLayerTranslucent,
                                            boolean allLayersTranslucent, boolean packActive,
                                            boolean shadowPhase, boolean gBufferPresent,
                                            boolean separateParticlesTarget) {
        if (DeferredGeometryPipelines.wantsDeferredParticleGroup(
                groupTranslucent, anyLayerTranslucent, packActive, shadowPhase, gBufferPresent)) {
            return DEFER;
        }
        if (DeferredGeometryPipelines.wantsForwardParticleGroup(
                groupTranslucent, allLayersTranslucent, packActive, shadowPhase, separateParticlesTarget)) {
            return FORWARD;
        }
        return VANILLA;
    }

    /**
     * Why {@link #decide} returned {@link #VANILLA} for these inputs, named as the FIRST condition that
     * refused -- or {@code null} when it did not return {@code VANILLA} at all.
     *
     * <p><b>This exists because {@code route=VANILLA} alone says nothing about which of five booleans
     * refused.</b> Both rules above are plain conjunctions of five booleans; without this, every way of
     * landing on VANILLA is indistinguishable in the log from every other way. The reason is computed
     * from the same inputs the decision is, in the same call, and asserted by test rather than left to
     * the log site to remember.
     *
     * <p><b>Which rule is reported is decided by the ARM, not by trying both.</b> A non-translucent
     * group can only ever have been a candidate for deferral and a translucent one only ever for the
     * forward route -- the two rules are disjoint on exactly that bit -- so naming "the rule that was
     * being tested" is unambiguous, and reporting the OTHER rule's failures would be noise that reads
     * like a cause.
     *
     * <p><b>Order within a rule is the order the conditions are worth acting on</b>, not the order they
     * appear in the conjunction: a user can turn Improved Transparency off, and cannot do anything at
     * all about a shadow-phase replay. Only the first is reported because a conjunction has exactly one
     * blocking cause at a time, and listing four would put the actionable one third.
     */
    @org.jspecify.annotations.Nullable
    public static String refusalReason(boolean groupTranslucent, boolean anyLayerTranslucent,
                                       boolean allLayersTranslucent, boolean packActive,
                                       boolean shadowPhase, boolean gBufferPresent,
                                       boolean separateParticlesTarget) {
        if (decide(groupTranslucent, anyLayerTranslucent, allLayersTranslucent, packActive,
                shadowPhase, gBufferPresent, separateParticlesTarget) != VANILLA) {
            return null;
        }
        if (!packActive) {
            return "no Fornax pack is rendering (FornaxRenderState.isActive() is false)";
        }
        if (shadowPhase) {
            return "this is the shadow-casting replay of executeSolid, which particles do not"
                    + " contribute to";
        }
        if (groupTranslucent) {
            // The FORWARD rule. Improved Transparency first: it is the only one of these a user can
            // change, and it is the one that actually fired in the field.
            if (separateParticlesTarget) {
                return "Improved Transparency is ON (Video Settings -> Improved Transparency;"
                        + " options.txt improvedTransparency:true). Vanilla then draws this group into"
                        + " LevelRenderer.particlesTarget(), a separate transparency buffer composited"
                        + " later, instead of the frame the pack has already tonemapped -- so the pack's"
                        + " program is not substituted and translucent particles stay unfogged. Turn"
                        + " Improved Transparency off to fog them";
            }
            if (!allLayersTranslucent) {
                return "the group's layer map is empty or mixed, so not every layer in it is"
                        + " translucent -- a group that satisfies neither rule stays vanilla in both"
                        + " directions";
            }
            return "no condition reported -- refusalReason is out of step with wantsForwardParticleGroup";
        }
        // The DEFERRED rule.
        if (anyLayerTranslucent) {
            return "the group holds a translucent layer, which cannot be deferred: it would draw after"
                    + " the graph resolves and its blend would be dropped";
        }
        if (!gBufferPresent) {
            return "no G-buffer exists this frame to defer into";
        }
        return "no condition reported -- refusalReason is out of step with wantsDeferredParticleGroup";
    }
}

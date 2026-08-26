package dev.icehunter.fornax.mixin.vanilla;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.icehunter.fornax.pack.GeometrySlot;
import dev.icehunter.fornax.pack.graph.GraphRunner;
import dev.icehunter.fornax.pipeline.ChunkRenderContextHolder;
import dev.icehunter.fornax.pipeline.DeferredGeometryPipelines;
import dev.icehunter.fornax.pipeline.ForwardPipelineMap;
import dev.icehunter.fornax.pipeline.FornaxRenderState;
import dev.icehunter.fornax.pipeline.GBuffer;
import dev.icehunter.fornax.pipeline.GBufferManager;
import dev.icehunter.fornax.pipeline.GeometryPipelineMap;
import dev.icehunter.fornax.pipeline.ParticleGroupRoute;
import dev.icehunter.fornax.pipeline.SlotReachabilityCensus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/**
 * Routes BOTH particle arms to the pack: the SOLID arm into Fornax's G-buffer the way
 * {@link PreparedRenderTypeDeferredMixin} does for every {@code RenderType}-driven draw, and the
 * TRANSLUCENT arm through the FORWARD mechanism the same class runs for banner patterns.
 *
 * <p><b>Why a second hook at all.</b> Particles never reach that chokepoint.
 * {@code QuadParticleFeatureRenderer.executeGroup} builds its own render pass straight off the
 * {@code CommandEncoder} and its private static {@code drawLayers} calls {@code setPipeline} and
 * {@code drawIndexed} directly, so {@code PreparedRenderType.drawFromBuffer} is never involved --
 * bytecode-verified, and corroborated by the engine's own pipeline census, which never listed
 * {@code opaque_particle}. This is the same class of bypass {@code graph.toml} already records for
 * weather.
 *
 * <p><b>The bug this closes.</b> Solid-arm particles draw into {@code mainRenderTarget}'s colour
 * during {@code executeSolid} when this mixin does not run; {@code GraphRunner.finishDeferred()} then
 * runs at that method's return and the pack's tonemap writes {@code builtin.output} full-screen,
 * painting over them. That makes roughly thirty particle families invisible with any pack that claims
 * a non-terrain slot: crit sparks, block- and item-break puffs, explosions, redstone dust, bubbles,
 * drips, notes, hearts, portal, lava, falling dust and leaves, and the rest of {@code Layer.OPAQUE}.
 * Smoke and flame are on the translucent arm already, unrelated to this deferral. It is the same
 * mechanism {@code GeometryPipelineMap} records for {@code item_cutout}.
 *
 * <p><b>The two arms take OPPOSITE routes, and that asymmetry is load-bearing.</b> See
 * {@link DeferredGeometryPipelines#wantsDeferredParticleGroup} and
 * {@link DeferredGeometryPipelines#wantsForwardParticleGroup} for the two rules. The solid arm draws
 * BEFORE the graph resolves and carries no blend ({@code OPAQUE_PARTICLE} is {@code PARTICLE_SNIPPET}
 * with no colour-target state of its own), so deferring it is a state-preserving substitution. The
 * translucent arm draws AFTER the resolve and carries {@code BlendFunction.TRANSLUCENT}, so deferring
 * it would make it invisible AND drop its blend -- and instead it takes the forward route, where
 * vanilla's pass, target, blend and ordering are all kept by construction because nothing rewrites
 * them.
 *
 * <p><b>ONE THREE-VALUED DECISION, NOT TWO BOOLEANS OR TWO WRAPPERS.</b> {@link ParticleGroupRoute} is
 * resolved once at the head of {@code executeGroup} and read by both wrappers below. Adding a second
 * {@code @WrapOperation} to the {@code setPipeline} instruction to carry the forward case was the
 * obvious alternative and is rejected: chained MixinExtras wrappers on one instruction make "which
 * branch fired" unattributable from a log.
 *
 * <p><b>Both halves must agree, and only the deferred half can disagree fatally.</b> A rewritten pass
 * binds five attachments and the pipeline must declare five matching colour targets; binding five to
 * vanilla's one-target pipeline is a validation error, which is why the deferred route is refused
 * unless EVERY layer in the group resolves. The forward route has no such coupling -- the pass is
 * vanilla's own, so a layer that resolves to nothing simply draws with vanilla's own pipeline.
 */
@Mixin(QuadParticleFeatureRenderer.class)
public abstract class QuadParticleDeferredMixin {

    @Shadow
    @Final
    private List<?> groups;

    /**
     * This group's route, shared by the two wrappers below.
     *
     * <p>Static rather than an instance field because {@code drawLayers} -- where {@code setPipeline}
     * actually happens -- is static and has no route back to the renderer instance. Safe because the
     * whole {@code executeGroup} -> {@code drawLayers} sequence runs synchronously on the render
     * thread, and because the head injector below rewrites this on EVERY call: a group that throws
     * mid-draw cannot strand a stale route for the next one, which is the failure mode a try/finally
     * would otherwise be needed to prevent.
     */
    @Unique
    private static ParticleGroupRoute fornax$groupRoute = ParticleGroupRoute.VANILLA;

    /**
     * The last decision reported for each arm (index 0 = solid, 1 = translucent), packed as the seven
     * route inputs plus the resolved route, so each arm reports once and then goes quiet -- but reports
     * AGAIN if any input or the outcome changes. {@code -1} is "nothing reported yet", which no packed
     * value can collide with.
     *
     * <p>Worth the lines because the two ways this hook fails are indistinguishable from the outside
     * and have opposite fixes: "the hook ran and declined" points at the pack or at a setting, while
     * "the hook never ran" points at the injector. Absence of {@code noteDeferredPass} alone cannot
     * tell them apart, which is the exact confusion the pipeline census was added to end at the
     * chokepoint -- and particles never reach that census at all.
     *
     * <p><b>Keyed on the DECISION, not on the arm.</b> A plain per-arm latch was the first design and
     * it hides the truth twice over. The first translucent group of a session is whatever happened to
     * be on screen thirteen seconds into world load, so a latch pins the report to a group that may
     * not be representative; and a route that CHANGES mid-session -- a pack reload, a G-buffer rebuild,
     * or the user toggling the very video setting the reason names -- is then never reported at all,
     * which is precisely the state a user acts on. Keying on the inputs keeps the steady-state output
     * at one line per arm while making a changed answer visible.
     *
     * <p><b>An int rather than the composed line, because this method runs per group per frame.</b>
     * Comparing formatted strings would build one on every call and discard it, which is per-frame
     * garbage on the render thread for an instrument that logs twice a session. The message is
     * composed only once the key has already said it differs.
     */
    @Unique
    private static final int[] fornax$lastReport = {-1, -1};

    @Inject(method = "executeGroup", at = @At("HEAD"))
    private void fornax$decideGroupRoute(FeatureFrameContext context, int groupIndex,
                                         List<?> submits, boolean strictlyOrdered, CallbackInfo ci) {
        if (groupIndex < 0 || groupIndex >= this.groups.size()) {
            fornax$groupRoute = ParticleGroupRoute.VANILLA;
            return;
        }
        QuadParticlePreparedGroupAccessor group =
                (QuadParticlePreparedGroupAccessor) this.groups.get(groupIndex);
        Map<SingleQuadParticle.Layer, ?> layers = group.fornax$layers();

        // ONE scan, both summaries. `any` is what disqualifies deferral and `all` is what qualifies
        // the forward route, so a MIXED group -- which vanilla's phase split does not produce today,
        // and which nothing here should depend on it never producing -- satisfies neither and stays
        // vanilla in both directions.
        boolean anyLayerTranslucent = false;
        boolean allLayersTranslucent = !layers.isEmpty();
        for (SingleQuadParticle.Layer layer : layers.keySet()) {
            if (layer.translucent()) {
                anyLayerTranslucent = true;
            } else {
                allLayersTranslucent = false;
            }
        }

        // Every input read ONCE, into a local, and the same local used for the decision and for the
        // report. Re-reading FornaxRenderState / GBufferManager / particlesTarget() at the log site
        // was the obvious alternative and would make the log free to disagree with the decision it
        // claims to explain -- which is the whole failure being fixed here, reintroduced one layer down.
        boolean groupTranslucent = group.fornax$translucent();
        boolean packActive = FornaxRenderState.isActive();
        boolean shadowPhase = DeferredGeometryPipelines.isShadowPhase();
        boolean gBufferPresent = GBufferManager.getInstance() != null;
        boolean separateParticlesTarget = fornax$drawsIntoSeparateParticlesTarget();

        ParticleGroupRoute route = ParticleGroupRoute.decide(groupTranslucent, anyLayerTranslucent,
                allLayersTranslucent, packActive, shadowPhase, gBufferPresent, separateParticlesTarget);
        String reason = ParticleGroupRoute.refusalReason(groupTranslucent, anyLayerTranslucent,
                allLayersTranslucent, packActive, shadowPhase, gBufferPresent, separateParticlesTarget);

        if (route == ParticleGroupRoute.DEFER && !fornax$everyLayerHasADeferredVariant(layers)) {
            // A SIXTH way to refuse, and the only one the pure rule cannot see: it depends on shader
            // compilation, not on frame state. Named here rather than left to fall out as a bare
            // route=VANILLA, which is exactly the silence this whole change exists to remove.
            route = ParticleGroupRoute.VANILLA;
            reason = "a layer in this group has no deferred variant -- the pack ships no program for"
                    + " its slot, or its GLSL did not compile (the error is logged above)";
        }
        fornax$groupRoute = route;

        int key = (groupTranslucent ? 1 : 0) | (anyLayerTranslucent ? 2 : 0)
                | (allLayersTranslucent ? 4 : 0) | (packActive ? 8 : 0)
                | (shadowPhase ? 16 : 0) | (gBufferPresent ? 32 : 0)
                | (separateParticlesTarget ? 64 : 0) | (route.ordinal() << 7);
        int arm = groupTranslucent ? 1 : 0;
        if (fornax$lastReport[arm] == key) {
            return;
        }
        fornax$lastReport[arm] = key;

        // Every input of the rule that was tested, ALWAYS, plus the first condition that refused.
        // Logging the decision without its inputs makes `route=VANILLA` unattributable in the field;
        // logging the inputs without naming the blocking one leaves the reader to re-derive a
        // seven-term conjunction from a log line.
        dev.icehunter.fornax.FornaxMod.LOGGER.info(String.format(
                "[Fornax][diag] particle group reached the hook: arm=%s route=%s"
                        + " (groupTranslucent=%s anyLayerTranslucent=%s allLayersTranslucent=%s"
                        + " packActive=%s shadowPhase=%s gBufferPresent=%s improvedTransparency=%s)%s",
                groupTranslucent ? "translucent" : "solid", route,
                groupTranslucent, anyLayerTranslucent, allLayersTranslucent,
                packActive, shadowPhase, gBufferPresent, separateParticlesTarget,
                reason == null ? "" : " -- REFUSED: " + reason));
    }

    /**
     * Whether every layer in a group about to be deferred has a five-target variant. If any does not,
     * the pass and the pipeline disagree and the draw is a validation error rather than a
     * wrong-looking particle.
     *
     * <p>A {@code Layer} carries its own {@code RenderPipeline} and its constructor is public, so "all
     * opaque layers use {@code OPAQUE_PARTICLE}" is a fact about today's constants, not a guarantee --
     * an unmapped pipeline reaching a five-attachment pass is precisely what this refuses, by dropping
     * the WHOLE GROUP back to vanilla rather than by binding something that does not fit.
     *
     * <p>Called only once the deferral rule has already passed, and that ordering is load-bearing:
     * resolving a variant COMPILES a pipeline, so asking this of a group that was going to be refused
     * anyway would build the very blend-dropping translucent variant the rule exists to prevent. The
     * forward route needs no equivalent pre-flight and deliberately does not do one -- it can fall
     * back per layer for free, because vanilla's own render pass is still the one being drawn into.
     */
    @Unique
    private boolean fornax$everyLayerHasADeferredVariant(Map<SingleQuadParticle.Layer, ?> layers) {
        for (SingleQuadParticle.Layer layer : layers.keySet()) {
            GeometrySlot slot = GeometryPipelineMap.slotOf(layer.pipeline());
            if (slot == null || DeferredGeometryPipelines.deferredVariantOf(layer.pipeline(), slot) == null) {
                return false;
            }
        }
        return true;
    }

    /**
     * Vanilla's own target choice, read rather than reproduced.
     *
     * <p>{@code executeGroup} computes {@code particlesTarget != null && group.translucent} and draws
     * the group into {@code LevelRenderer.particlesTarget()} when that holds, else into
     * {@code mainRenderTarget} (bytecode 45-63 of {@code executeGroup} in 26.2).
     *
     * <p><b>Non-null when the {@code improvedTransparency} option is on -- "Improved Transparency" in
     * Video Settings -- and NOT when {@code graphicsMode} is Fabulous, which 26.2 no longer has.</b>
     * The particles target is then a SEPARATE {@code RGBA8_UNORM} buffer, cleared to {@code (0,0,0,0)},
     * that {@code post/transparency.fsh} composites over {@code main} later with a premultiplied
     * "over". See {@link DeferredGeometryPipelines#wantsForwardParticleGroup} for the full chain and
     * for why the refusal this feeds is conservative rather than required.
     *
     * <p>Only the group flag half of vanilla's condition is left to the route rule, which already
     * requires {@code groupTranslucent} for the forward case; this reports the other half.
     */
    @Unique
    private static boolean fornax$drawsIntoSeparateParticlesTarget() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return false;
        }
        LevelRenderer levelRenderer = minecraft.levelRenderer;
        return levelRenderer != null && levelRenderer.particlesTarget() != null;
    }

    @WrapOperation(
            method = "executeGroup",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/blaze3d/systems/RenderPass;"
            )
    )
    private RenderPass fornax$particleGBufferPass(
            CommandEncoder encoder, Supplier<String> label, GpuTextureView color, Optional<Integer> colorClear,
            GpuTextureView depth, OptionalDouble depthClear, Operation<RenderPass> original) {

        // ONLY the deferred route rewrites the pass. The forward route's entire definition is that it
        // does NOT -- vanilla's colour target, depth attachment, blend and ordering are preserved by
        // being left alone rather than by being reproduced, which is why a forward draw cannot lose
        // the blend that keeps partial-alpha smoke from reading as solid rectangles.
        GBuffer gbuffer = fornax$groupRoute == ParticleGroupRoute.DEFER ? GBufferManager.getInstance() : null;
        if (gbuffer == null) {
            if (fornax$groupRoute == ParticleGroupRoute.DEFER) {
                // Dropping the route as well as falling through keeps the pipeline wrapper in step: a
                // G-buffer that vanished between the head injector and here must leave BOTH halves
                // vanilla, not one of each. Only the DEFER route is dropped -- a FORWARD group has no
                // G-buffer to lose and must keep its route.
                fornax$groupRoute = ParticleGroupRoute.VANILLA;
            }
            return original.call(encoder, label, color, colorClear, depth, depthClear);
        }

        // Keyed on OPAQUE_PARTICLE because the pass is opened before any layer's pipeline is known,
        // and the head gate has already established that every layer in a deferred group is
        // non-translucent. Absence of this line from a session log is what distinguishes "the hook
        // declined" from "the hook never ran".
        DeferredGeometryPipelines.noteDeferredPass(
                RenderPipelines.OPAQUE_PARTICLE, gbuffer.getWidth(), gbuffer.getHeight());

        // Depth comes from the G-buffer so particles sort against other deferred geometry. The
        // first actual writer clears all attachments through its load ops; later writers load.
        RenderPassDescriptor descriptor = GBufferManager.claimWriterDescriptor(label, gbuffer);

        return encoder.createRenderPass(descriptor);
    }

    @WrapOperation(
            method = "drawLayers",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"
            )
    )
    private static void fornax$particleGBufferPipeline(RenderPass pass, RenderPipeline pipeline,
                                                       Operation<Void> original) {
        switch (fornax$groupRoute) {
            case VANILLA -> original.call(pass, pipeline);
            case FORWARD -> fornax$particleForwardPipeline(pass, pipeline, original);
            case DEFER -> fornax$particleDeferredPipeline(pass, pipeline, original);
        }
    }

    /**
     * The FORWARD substitution, mirroring {@code PreparedRenderTypeDeferredMixin}'s own forward branch
     * -- same table, same variant builder, same two uniforms, same reasons.
     *
     * <p>Every refusal falls back to vanilla's own pipeline rather than abandoning the draw, and that
     * is only safe because the render pass was never rewritten: there is one colour attachment either
     * way, so a vanilla pipeline and a forward variant are interchangeable at this instruction. The
     * deferred branch below cannot do this and does not try.
     */
    @Unique
    private static void fornax$particleForwardPipeline(RenderPass pass, RenderPipeline pipeline,
                                                       Operation<Void> original) {
        GeometrySlot slot = ForwardPipelineMap.slotOf(pipeline);
        if (slot == null) {
            // A layer the forward table does not claim, inside a group that qualified. Not an error --
            // draw it exactly as vanilla would.
            original.call(pass, pipeline);
            return;
        }
        // Particles never reach notePipelineSeen (they reach no chokepoint at all), so without this
        // the census could never distinguish "no smoke has been on screen" from "the hook saw smoke
        // and declined it" -- the two causes that need opposite fixes.
        SlotReachabilityCensus.noteSlotReached(slot);

        RenderPipeline variant = DeferredGeometryPipelines.forwardVariantOf(pipeline, slot);
        if (variant == null) {
            DeferredGeometryPipelines.noteForwardDeclined(pipeline, slot,
                    "no forward variant built -- the pack ships no program for this slot, or its GLSL"
                            + " did not compile (the error is logged above)");
            original.call(pass, pipeline);
            return;
        }

        GpuBufferSlice globals = ChunkRenderContextHolder.getUniformBuffer();
        var options = GraphRunner.optionsBuffer();
        if (globals == null || options == null) {
            // Both are DECLARED by the forward variant's bind group layout, so binding the pipeline
            // without being able to set them is a bind-group mismatch rather than a degraded frame.
            DeferredGeometryPipelines.noteForwardDeclined(pipeline, slot,
                    "the pack's uniform buffers are not available this frame (globals="
                            + (globals != null) + ", packOptions=" + (options != null) + ")");
            original.call(pass, pipeline);
            return;
        }

        original.call(pass, variant);
        // Both, every time. setUniform never validates the name -- it is a map write -- so a missed
        // bind does not throw here; it throws at DRAW time and only in a dev environment, and in a
        // shipped build it is silent garbage instead. The uniforms map also PERSISTS across
        // setPipeline within one render pass, and a particle group binds a pipeline PER LAYER, so a
        // later layer that skipped its binds would silently inherit the previous layer's slices.
        pass.setUniform("u_Globals", globals);
        pass.setUniform("u_PackOptions", options.currentBuffer());
        DeferredGeometryPipelines.noteForwardSubstitution(pipeline, slot);
    }

    @Unique
    private static void fornax$particleDeferredPipeline(RenderPass pass, RenderPipeline pipeline,
                                                        Operation<Void> original) {
        GeometrySlot slot = GeometryPipelineMap.slotOf(pipeline);
        if (slot != null) {
            // Same reason as the forward branch: particles reach no chokepoint, so the census would
            // otherwise never see this slot at all and could not tell an inert pass from an idle one.
            SlotReachabilityCensus.noteSlotReached(slot);
        }
        RenderPipeline variant = slot == null ? null : DeferredGeometryPipelines.deferredVariantOf(pipeline, slot);
        original.call(pass, variant != null ? variant : pipeline);
        if (variant == null) {
            // Unreachable: the head gate rejected the whole group unless every layer resolved. Kept
            // so that a future change which breaks that invariant renders wrongly rather than
            // silently binding a one-target pipeline into a five-attachment pass.
            return;
        }

        // Fornax's per-frame uniforms, which the deferred variant's extra bind group declares and the
        // pack's particle program imports. Without them the program can texture a billboard and
        // nothing else -- no previous-frame matrices, so no motion vectors, so particles smear under
        // TAA exactly the way the sky did.
        GpuBufferSlice globals = ChunkRenderContextHolder.getUniformBuffer();
        if (globals != null) {
            pass.setUniform("u_Globals", globals);
        }
        SlotReachabilityCensus.noteSlotSubstituted(slot);
    }
}

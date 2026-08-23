package dev.icehunter.fornax.mixin.vanilla;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.icehunter.fornax.atlas.LabPbrDrawTextureRegistry;
import dev.icehunter.fornax.atlas.LabPbrGeometryBindings;
import dev.icehunter.fornax.atlas.PreparedRenderTypeLabPbrOwner;
import dev.icehunter.fornax.pack.GeometrySlot;
import dev.icehunter.fornax.pipeline.ChunkRenderContextHolder;
import dev.icehunter.fornax.pipeline.DeferredGeometryPipelines;
import dev.icehunter.fornax.pipeline.FornaxRenderState;
import dev.icehunter.fornax.pipeline.GBuffer;
import dev.icehunter.fornax.pipeline.GBufferManager;
import dev.icehunter.fornax.pipeline.ForwardPipelineMap;
import dev.icehunter.fornax.pipeline.GeometryPipelineMap;
import dev.icehunter.fornax.pass.shadow.ShadowMapManager;
import dev.icehunter.fornax.pack.graph.GraphRunner;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/**
 * Routes claimed geometry draws into Fornax's G-buffer instead of the single colour target vanilla
 * opens for them.
 *
 * <p>{@code PreparedRenderType.drawFromBuffer} is the chokepoint for essentially every
 * {@code RenderType}-driven draw -- entities, block entities, hand, items, text, crumbling, outlines
 * -- so hooking it once covers all of them rather than one renderer at a time. It hardcodes a
 * one-colour-plus-depth render pass, which is exactly what has to change for deferred shading.
 *
 * <p>Both halves must agree or nothing works: the render pass binds five attachments, and the
 * pipeline must declare five matching {@code ColorTargetState}s ({@link DeferredGeometryPipelines}).
 * Binding five attachments to vanilla's one-target pipeline is a validation error, so this mixin
 * swaps both together or neither.
 *
 * <p>Deliberately conservative about when it engages: only when a pack is active, the slot is
 * claimed, a G-buffer exists, and a deferred variant built successfully. Any of those failing leaves
 * the draw exactly as vanilla issued it, which is why an unclaimed slot or a broken pack program
 * degrades to normal rendering rather than a missing entity.
 */
@Mixin(PreparedRenderType.class)
public abstract class PreparedRenderTypeDeferredMixin implements PreparedRenderTypeLabPbrOwner {
    @Nullable
    private Identifier fornax$labPbrOwner;
    private long fornax$labPbrGeneration = -1L;

    @Override
    public void fornax$setLabPbrOwner(Identifier owner, long generation) {
        this.fornax$labPbrOwner = owner;
        this.fornax$labPbrGeneration = generation;
    }

    @Override
    @Nullable
    public Identifier fornax$getLabPbrOwner() {
        return this.fornax$labPbrOwner;
    }

    @Override
    public long fornax$getLabPbrGeneration() {
        return this.fornax$labPbrGeneration;
    }

    /**
     * Resolves the deferred variant for this draw, or {@code null} to leave it alone. Shared by both
     * wrappers below so the render pass and the pipeline can never disagree about whether this draw
     * is deferred.
     */
    @Nullable
    private RenderPipeline fornax$deferredVariant(RenderPipeline pipeline) {
        // Census of every pipeline that actually reaches this chokepoint, once each. Without it,
        // "the hook ran and declined" and "the hook never saw this draw" are indistinguishable, and
        // they have completely different fixes.
        DeferredGeometryPipelines.notePipelineSeen(pipeline);
        if (!FornaxRenderState.isActive()) {
            return null;
        }
        // GUI item draws share the world's item pipelines and must never be deferred -- see
        // DeferredGeometryPipelines.guiPhase for the crash this prevents.
        if (DeferredGeometryPipelines.isGuiPhase()) {
            return null;
        }
        GeometrySlot slot = GeometryPipelineMap.slotOf(pipeline);
        if (slot == null) {
            return null;
        }
        if (GBufferManager.getInstance() == null) {
            // Distinct from "not claimed": the pack wants this slot deferred but there is no G-buffer
            // to defer into. Logged once per pipeline so a systematically-missing entity kind is
            // attributable, rather than looking identical to a slot nobody claimed.
            DeferredGeometryPipelines.noteNoGBuffer(pipeline, slot);
            return null;
        }
        return DeferredGeometryPipelines.deferredVariantOf(pipeline, slot);
    }

    /**
     * Binds the pack's FORWARD program for this draw, keeping vanilla's render pass exactly as it was
     * opened. Returns whether it did; {@code false} leaves the caller to bind vanilla's own pipeline.
     *
     * <p>Every refusal below is logged once per pipeline with its reason. That is not decoration: a
     * forward slot produces no G-buffer pass and no shadow replay, so there is no other trace it could
     * leave, and "the hook declined" would otherwise be indistinguishable from "the hook never saw
     * this draw" -- the confusion that cost the weather pass its entire life.
     *
     * <p>The shadow phase is excluded upstream rather than here: a forward slot never casts
     * ({@code GeometrySlot.castsShadow()} is false for it, asserted by
     * {@code ShadowCasterSlotContractTest}), so its draws are already cancelled at HEAD during the
     * replay and never arrive.
     */
    private boolean fornax$forwardSubstitute(RenderPass pass, RenderPipeline pipeline,
                                             Operation<Void> original) {
        GeometrySlot slot = ForwardPipelineMap.slotOf(pipeline);
        if (slot == null) {
            return false;
        }
        if (!FornaxRenderState.isActive()) {
            return false;
        }
        // Item pipelines are shared between the world and the GUI, and while no forward-mapped
        // pipeline draws GUI items today, the guard is kept for the same reason the deferred path
        // keeps it: the crash it prevents is a scissor-out-of-bounds with a cause nowhere near the
        // symptom, and the day a forward slot covers ITEM_TRANSLUCENT it would arrive without warning.
        if (DeferredGeometryPipelines.isGuiPhase()) {
            DeferredGeometryPipelines.noteForwardDeclined(pipeline, slot, "GUI phase");
            return false;
        }
        RenderPipeline variant = DeferredGeometryPipelines.forwardVariantOf(pipeline, slot);
        if (variant == null) {
            DeferredGeometryPipelines.noteForwardDeclined(pipeline, slot,
                    "no forward variant built -- the pack ships no program for this slot, or its GLSL"
                            + " did not compile (the error is logged above)");
            return false;
        }
        GpuBufferSlice globals = ChunkRenderContextHolder.getUniformBuffer();
        var options = GraphRunner.optionsBuffer();
        if (globals == null || options == null) {
            // Both are DECLARED by the variant's bind group layout, so binding the pipeline without
            // being able to set them is a bind-group mismatch, not a degraded frame. Falling back to
            // vanilla is the only safe answer.
            DeferredGeometryPipelines.noteForwardDeclined(pipeline, slot,
                    "the pack's uniform buffers are not available this frame (globals="
                            + (globals != null) + ", packOptions=" + (options != null) + ")");
            return false;
        }
        // Through the wrapped operation, not a direct pass.setPipeline: this is the call vanilla was
        // going to make, with a different argument, and routing it through `original` keeps any other
        // mod's wrapper in the chain intact. The deferred branch below does the same.
        original.call(pass, variant);
        // Both uniforms, every time. Blaze3D's setUniform never validates the NAME -- it is a map
        // write, and an unknown name is silently stored (read off 26.2's GlRenderPass and
        // VulkanRenderPass; neither inspects the name or the bound pipeline) -- so a missed bind does
        // not throw here. It throws at DRAW time, and only in a dev environment, where
        // GlRenderPass.VALIDATION checks that every uniform the LAYOUT declares was set. In a shipped
        // build a missed bind is silent garbage instead. Setting both unconditionally makes that
        // impossible either way.
        //
        // The uniforms map also PERSISTS across setPipeline within one render pass, so a later draw in
        // the same pass that binds a variant declaring u_Globals and forgets to set it would silently
        // inherit this slice. Another reason both are set on every substitution rather than once.
        pass.setUniform("u_Globals", globals);
        pass.setUniform("u_PackOptions", options.currentBuffer());
        DeferredGeometryPipelines.noteForwardSubstitution(pipeline, slot);
        return true;
    }

    /**
     * Drops draws that must not happen, before any render pass is opened for them.
     *
     * <p>Two cases. During the shadow-casting replay, a pipeline with no shadow variant has nothing to
     * contribute to the shadow map -- and letting it fall through would open vanilla's own pass and
     * draw it to the SCREEN a second time. Harmless for opaque geometry, visibly wrong for anything
     * blended, which is most of what lands here: blob shadows, leashes, text.
     *
     * <p>Second, a pack casting real entity shadows can suppress vanilla's blob-shadow decal, which
     * otherwise sits under every entity as a dark ellipse competing with the real shadow. Opt-in by
     * declaring HIDE_VANILLA_BLOB_SHADOWS: absent counts as off, so a pack that says nothing keeps
     * vanilla's behaviour.
     */
    @Inject(method = "drawFromBuffer(Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/IndexType;III)V",
            at = @At("HEAD"), cancellable = true)
    private void fornax$skipUnwantedDraws(GpuBuffer vertexBuffer, GpuBuffer indexBuffer, IndexType indexType,
                                          int baseVertex, int firstIndex, int indexCount, CallbackInfo ci) {
        PreparedRenderType self = (PreparedRenderType) (Object) this;
        RenderPipeline pipeline = self.pipeline();

        if (DeferredGeometryPipelines.isShadowPhase()
                && !(DeferredGeometryPipelines.isPlayerCastPhase()
                        && DeferredGeometryPipelines.PLAYER_CAST_TO_GBUFFER)) {
            GeometrySlot castSlot = GeometryPipelineMap.slotOf(pipeline);
            boolean casts = castSlot != null && castSlot.castsShadow()
                    && DeferredGeometryPipelines.shadowVariantOf(pipeline, false) != null;
            if (!casts) {
                DeferredGeometryPipelines.noteShadowSkip(pipeline);
                ci.cancel();
            } else {
                DeferredGeometryPipelines.noteShadowDraw(pipeline);
                DeferredGeometryPipelines.notePlayerDraw(pipeline);
            }
            return;
        }

        if (pipeline == RenderPipelines.ENTITY_SHADOW
                && FornaxRenderState.isActive()
                && GraphRunner.isCompileOptionEnabled("HIDE_VANILLA_BLOB_SHADOWS")) {
            ci.cancel();
        }
    }

    @WrapOperation(
            method = "drawFromBuffer(Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/IndexType;III)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/blaze3d/systems/RenderPass;"
            )
    )
    private RenderPass fornax$maybeDeferredRenderPass(
            CommandEncoder encoder, Supplier<String> label, GpuTextureView color, Optional<Integer> colorClear,
            GpuTextureView depth, OptionalDouble depthClear, Operation<RenderPass> original) {

        PreparedRenderType self = (PreparedRenderType) (Object) this;

        if (DeferredGeometryPipelines.isShadowPhase() && DeferredGeometryPipelines.isPlayerCastPhase()
                && DeferredGeometryPipelines.PLAYER_CAST_TO_GBUFFER) {
            // [TEMPORARY DIAGNOSTIC] fall through to the normal deferred path below, so the player
            // caster's geometry is drawn into the G-buffer and can be SEEN. If it appears in the
            // right place, the transform is sound and only the light projection is wrong; if it
            // appears somewhere else, the transform is the fault and the error is visible directly.
        } else if (DeferredGeometryPipelines.isShadowPhase()) {
            // Shadow-casting re-execution: same prepared draws, aimed at the shadow map instead.
            // Draws with no shadow variant were already cancelled at HEAD, so anything arriving here
            // casts.
            GpuTextureView shadowDepth = ShadowMapManager.getView();
            GpuTextureView shadowDummy = ShadowMapManager.getDummyColorView();
            if (shadowDepth == null || shadowDummy == null) {
                return original.call(encoder, label, color, colorClear, depth, depthClear);
            }
            RenderPassDescriptor shadowDescriptor = RenderPassDescriptor.create(() -> "Entities (Shadow)")
                    .withColorAttachment(shadowDummy, Optional.empty())
                    .withDepthAttachment(shadowDepth, OptionalDouble.empty())
                    .withRenderArea(new RenderPass.RenderArea(0, 0,
                            shadowDepth.getWidth(0), shadowDepth.getHeight(0)));
            return encoder.createRenderPass(shadowDescriptor);
        }

        if (fornax$deferredVariant(self.pipeline()) == null) {
            return original.call(encoder, label, color, colorClear, depth, depthClear);
        }

        GBuffer gbuffer = GBufferManager.getInstance();
        // Records which pipelines actually get a G-buffer pass, and at what size relative to the
        // target vanilla was going to use. A size mismatch here would put geometry off-screen or at
        // the wrong scale while still "succeeding", which is the shape of a silently invisible draw.
        DeferredGeometryPipelines.noteDeferredPass(self.pipeline(), gbuffer.getWidth(), gbuffer.getHeight());
        // Depth comes from the G-buffer, not vanilla's target. Whichever real deferred draw arrives
        // first clears all attachments through its load ops; subsequent draws preserve them.
        RenderPassDescriptor descriptor = GBufferManager.claimWriterDescriptor(label, gbuffer);

        return encoder.createRenderPass(descriptor);
    }

    @WrapOperation(
            method = "drawFromBuffer(Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/IndexType;III)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"
            )
    )
    private void fornax$maybeDeferredPipeline(RenderPass pass, RenderPipeline pipeline, Operation<Void> original) {
        if (DeferredGeometryPipelines.isShadowPhase()
                && !(DeferredGeometryPipelines.isPlayerCastPhase()
                        && DeferredGeometryPipelines.PLAYER_CAST_TO_GBUFFER)) {
            // One variant for every caster. The player is submitted under the same camera as the
            // replayed draws, so it reconstructs its world position identically -- giving it a
            // pass-through variant instead was what stopped it casting at all.
            GeometrySlot drawSlot = GeometryPipelineMap.slotOf(pipeline);
            RenderPipeline shadowVariant = (drawSlot == null || !drawSlot.castsShadow())
                    ? null : DeferredGeometryPipelines.shadowVariantOf(pipeline, false);
            original.call(pass, shadowVariant != null ? shadowVariant : pipeline);
            GpuBufferSlice shadowGlobals = ChunkRenderContextHolder.getUniformBuffer();
            if (shadowVariant != null && shadowGlobals != null) {
                pass.setUniform("u_Globals", shadowGlobals);
            }
            return;
        }

        RenderPipeline variant = fornax$deferredVariant(pipeline);
        if (variant == null) {
            // --- The FORWARD branch -----------------------------------------------------------
            //
            // Reached only when the deferred path declined, which for a forward-mapped pipeline is
            // always: the two tables are disjoint, so GeometryPipelineMap.slotOf returned null above.
            //
            // NOTE WHAT IS NOT HERE. There is no counterpart in fornax$maybeDeferredRenderPass, and
            // that absence IS the feature: vanilla's render pass -- its single colour target, its
            // depth attachment, its place in the frame -- is kept exactly as issued. Substituting the
            // pass is what "deferred" means; substituting only the pipeline is what "forward" means.
            // A forward slot that rewrote the pass would be a deferred slot with extra steps, and
            // would lose the blend and the ordering that are the entire reason it exists.
            if (fornax$forwardSubstitute(pass, pipeline, original)) {
                return;
            }
        }
        original.call(pass, variant != null ? variant : pipeline);
        if (variant == null) {
            return;
        }
        // Bind Fornax's per-frame uniforms, which the deferred variant's extra bind group declares.
        // Slot programs need them to do anything beyond texturing: previous-frame matrices for motion
        // vectors (without which entities smear under TAA), sun direction, sky and water state.
        //
        // Sourced from the same holder the graph's own fullscreen passes read, so a slot program and
        // a post pass in the same frame always agree about where the camera was.
        GpuBufferSlice globals = ChunkRenderContextHolder.getUniformBuffer();
        if (globals != null) {
            pass.setUniform("u_Globals", globals);
        }

        GeometrySlot slot = GeometryPipelineMap.slotOf(pipeline);
        if (slot != null && LabPbrGeometryBindings.hasLabPbrSidecars(slot)) {
            PreparedRenderType prepared = (PreparedRenderType) (Object) this;
            LabPbrGeometryBindings.Binding binding;
            if (slot == GeometrySlot.BLOCK_ENTITIES) {
                binding = LabPbrGeometryBindings.resolve(slot, TextureAtlas.LOCATION_BLOCKS,
                        Minecraft.getInstance().getResourceManager());
            } else {
                Optional<Identifier> owner = LabPbrDrawTextureRegistry.ownerOf(prepared);
                binding = owner.isPresent() && LabPbrDrawTextureRegistry.isCurrent(prepared)
                        ? LabPbrGeometryBindings.resolve(owner.get(),
                                Minecraft.getInstance().getResourceManager())
                        : LabPbrGeometryBindings.neutral();
            }
            pass.bindTexture("u_NormalTex", binding.normalView(), binding.normalSampler());
            pass.bindTexture("u_MaterialTex", binding.materialView(), binding.materialSampler());
        }
    }
}

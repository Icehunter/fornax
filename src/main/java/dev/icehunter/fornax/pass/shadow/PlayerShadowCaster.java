package dev.icehunter.fornax.pass.shadow;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.mixin.vanilla.LevelRendererStateAccessor;
import dev.icehunter.fornax.pipeline.DeferredGeometryPipelines;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.phys.Vec3;

/**
 * Submits the player specifically, so they cast a shadow.
 *
 * <p>Shadow casting normally works by replaying the draws vanilla already prepared for the camera.
 * The player defeats that: in first person the model is never built at all, and even in third person
 * it is absent from the set by the time the shadow phase runs -- so there is nothing to replay, and
 * everyone in the world casts a shadow except you.
 *
 * <p>The fix is to build the player's draws separately. Vanilla's submission API is public and takes
 * an explicit collector, so the player can be extracted and submitted into a storage of our own,
 * prepared, and executed while the shadow phase is active. Because that storage holds nothing but the
 * player, no per-draw tagging is needed to keep them out of the G-buffer -- the separation is
 * structural.
 *
 * <p><b>Submitted under the CAMERA's transform, not the light's.</b> That is deliberate, and it was
 * arrived at the expensive way. A shadow pass that RE-SUBMITS geometry has to swap the camera to
 * the light first, so that the shadow shader receives geometry already in light space. Fornax does
 * not re-submit: it REPLAYS the draws vanilla already prepared, and its shadow program reconstructs
 * a world position and reprojects that through the light matrix. Adopting the light-space camera
 * swap here put the player through both transforms, so it landed outside the shadow
 * map and cast nothing at all -- while every other entity, going through the plain reconstruction
 * path, cast correctly. The villager casting a shadow while the player did not was the evidence that
 * the reconstruction path works and the swap was the anomaly.
 */
public final class PlayerShadowCaster {
    private static boolean reportedFailure;

    /**
     * A dispatcher of our own, with its own buffers.
     *
     * <p>Vanilla keeps exactly one {@code PreparedFrame} and marks it in-use for the duration of the
     * level render, so asking the game's dispatcher to prepare a second frame from inside
     * {@code executeSolid} throws "PreparedFrame already in use". Borrowing the game's
     * {@code RenderBuffers} would sidestep that error and introduce a far worse one: the main frame is
     * mid-execution and actively writing those buffers, so the player's geometry would be interleaved
     * into someone else's draw.
     *
     * <p>Built lazily on first use and kept for the session -- construction allocates buffer pools, so
     * doing it per frame would be indefensible.
     */
    private static FeatureRenderDispatcher shadowDispatcher;
    private static RenderBuffers shadowBuffers;


    private PlayerShadowCaster() {}

    /**
     * Builds and draws the player's geometry. Must be called with the shadow phase already active, so
     * the draw site aims it at the shadow map.
     */
    /**
     * Submits and draws the player. Call with the shadow phase active, so the draw site aims it at
     * the shadow map.
     */
    public static void cast() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.levelRenderer == null || minecraft.gameRenderer == null) {
            return;
        }

        try {
            LevelRenderState levelState =
                    ((LevelRendererStateAccessor) minecraft.levelRenderer).fornax$levelRenderState();
            if (levelState == null || levelState.cameraRenderState == null) {
                return;
            }

            float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            EntityRenderDispatcher entities = minecraft.getEntityRenderDispatcher();

            // Extracted fresh rather than reused from levelRenderState.entityRenderStates: in first
            // person the player is not in that list at all, which is the case this exists to cover.
            EntityRenderState state = entities.extractEntity(player, partialTick);

            // Offset from the RENDER camera, not from cameraRenderState.pos.
            //
            // Measured, not assumed: drawing this caster into the G-buffer put the copy exactly at the
            // camera rather than at the player, which means state.x/y/z - cameraRenderState.pos came
            // out ~zero -- that field tracks the player, not the eye. In third person the two are
            // metres apart, and the difference is precisely the displacement seen.
            Vec3 cameraPos = levelState.cameraRenderState.pos;
            SubmitNodeStorage storage = new SubmitNodeStorage();

            // Submitted exactly as vanilla would for the camera. The shadow program does the rest,
            // the same way it does for every replayed entity draw.
            entities.submit(state, levelState.cameraRenderState,
                    state.x - cameraPos.x, state.y - cameraPos.y, state.z - cameraPos.z,
                    new PoseStack(), storage);

            if (shadowDispatcher == null) {
                shadowDispatcher = new FeatureRenderDispatcher(shadowBuffers = new RenderBuffers(1),
                        minecraft.getModelManager(), minecraft.getAtlasManager(), minecraft.font,
                        minecraft.gameRenderer.gameRenderState());
            }

            DeferredGeometryPipelines.setPlayerCastPhase(true);
            try {
                shadowDispatcher.renderAllFeatures(storage);
            } finally {
                DeferredGeometryPipelines.setPlayerCastPhase(false);
                // Recycle this dispatcher's staged vertex memory. THE frame-lifecycle call this
                // class owes its own RenderBuffers, and omitting it leaked the GPU dry in ~2.5
                // minutes.
                //
                // RenderBuffers holds a StagedVertexBuffer -- GPU staging memory that renderAllFeatures
                // writes the player's geometry into every frame. endFrame() is what returns it to the
                // pool. Vanilla calls it once per frame on the buffers it owns (LevelRenderer.endFrame);
                // these buffers are private to this class, driven manually outside vanilla's frame
                // loop, so nothing else was ever going to make that call. Every frame's staged player
                // model was simply retained.
                //
                // Measured, not deduced (2026-07-28): one 16K + one 48K IOAccelerator allocation per
                // frame, 1:1 with frames rendered, which Metal backs in 256 MiB heaps -- ~7x
                // amplification into ~87 MiB/s of address-space growth and VK_ERROR_DEVICE_LOST
                // (kIOGPUCommandBufferCallbackErrorOutOfMemory) at 140-160s, every session. Skipping
                // this one method with the whole rest of the shadow path still running -- shadow map,
                // shadow terrain pass, entity replay -- took the leak to zero, which is what isolated
                // it here.
                //
                // In the finally so a throwing renderAllFeatures still recycles: dropping the frame's
                // geometry is survivable, silently retaining it forever is not.
                shadowBuffers.endFrame();
            }
        } catch (RuntimeException e) {
            reportFailure(e);
        }
    }

    private static void reportFailure(RuntimeException e) {
        // A missing shadow is worth far less than a lost session. Reported once so a persistent
        // failure stays visible without flooding.
        if (!reportedFailure) {
            reportedFailure = true;
            FornaxMod.LOGGER.error("[Fornax] Could not submit the player as a shadow caster --"
                    + " the player will cast no shadow this session.", e);
        }
    }
}

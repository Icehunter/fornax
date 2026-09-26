package dev.icehunter.fornax.pass.mirror;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.mixin.vanilla.LevelRendererStateAccessor;
import dev.icehunter.fornax.pack.GeometrySlot;
import dev.icehunter.fornax.pipeline.DeferredGeometryPipelines;
import dev.icehunter.fornax.pipeline.PlayerMirrorTargets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;

import java.util.EnumMap;
import java.util.Map;

/**
 * Submits the player so it casts a reflection into one slot's {@link PlayerMirrorTargets}
 * instance.
 *
 * <p>Each slot keeps its own instance ({@link #forSlot}), not a shared holder: {@link
 * GeometrySlot#PLAYER_MIRROR}, {@link GeometrySlot#PLAYER_MIRROR_X} and {@link
 * GeometrySlot#PLAYER_MIRROR_Z} each need a separate {@code FeatureRenderDispatcher}/{@code
 * RenderBuffers} pair, with {@code endFrame} called once per frame from a {@code finally} block.
 * Sharing one {@code RenderBuffers} across several {@code renderAllFeatures} calls in the same
 * frame would reuse its staged vertex ring before the first call's draws are consumed, garbling
 * geometry with no error (the staging rule {@code PlayerShadowCaster} follows for its single
 * instance, here needed three times over). {@code FeatureSolidFeaturesGraphMixin} calls {@code
 * forSlot(slot).cast()} once per claimed, consumed, valid slot, each call finishing submit, draw,
 * and {@code endFrame} before the next slot's call starts. Calls never interleave or nest.
 *
 * <p>Modelled on {@code PlayerShadowCaster}, for the same reason that class exists: the player is
 * missing from vanilla's prepared draw set by the time this runs. In first person the model is
 * never built; in third person it has already drawn with nothing left to replay. So the mirror
 * pass has nothing to submit against unless the player is extracted and submitted again. The
 * dispatcher and buffers are separate for the same reason: vanilla's one {@code PreparedFrame} is
 * marked in use for the level render, and its {@code RenderBuffers} are being written by the frame
 * in flight.
 *
 * <p>Submitted under the camera's transform, like the shadow caster: the mirror draws in the main
 * camera's screen space, since a flat reflection sits at the same screen UV as the pixel it
 * reflects, so no second viewpoint is needed. The vertex stage reflects the player across the
 * plane {@code slot} names, after rebuilding the same camera-relative world position the shadow
 * variant uses; nothing here reprojects through another camera. Extraction and submission below
 * are identical for every slot; only the slot's variant and MRT, chosen by {@code
 * DeferredGeometryPipelines.activeMirrorSlot()}, differ.
 */
public final class PlayerMirrorCaster {
    private static final Map<GeometrySlot, PlayerMirrorCaster> INSTANCES = new EnumMap<>(GeometrySlot.class);

    /** The instance for {@code slot}, created on first use and kept for the session. */
    public static synchronized PlayerMirrorCaster forSlot(GeometrySlot slot) {
        return INSTANCES.computeIfAbsent(slot, PlayerMirrorCaster::new);
    }

    /** Calls {@link #resetForNewPack()} on every slot's instance, for {@link
     * DeferredGeometryPipelines#invalidate()} to call once, regardless of which slot (if any)
     * failed before. */
    public static void resetAllForNewPack() {
        for (GeometrySlot slot : PlayerMirrorTargets.slots()) {
            forSlot(slot).resetForNewPack();
        }
    }

    private final GeometrySlot slot;
    private boolean reportedFailure;

    /** See {@code PlayerShadowCaster.shadowDispatcher}/{@code shadowBuffers} for why this is a
     * separate dispatcher rather than the game's. Built lazily and kept for the session. */
    private FeatureRenderDispatcher mirrorDispatcher;
    private RenderBuffers mirrorBuffers;

    // Diagnostic: checks whether extractEntity's state carries a lit packedLight, or whether it
    // enters this caster's private dispatcher already zero. Gated on a wall-clock interval, like
    // MemoryWatchdog/FrameGenPresenter.maybeLogCadence: once a second is enough to read from a log
    // file, and logging every frame would flood it with no extra information. Kept per instance so
    // three slots logging in the same second print three distinct lines instead of one.
    private static final long LIGHT_LOG_INTERVAL_NANOS = 1_000_000_000L;
    private long lastLightLogNanos;

    private PlayerMirrorCaster(GeometrySlot slot) {
        this.slot = slot;
    }

    /**
     * Clears this slot's {@link PlayerMirrorTargets} instance, then builds and draws the player's
     * reflected geometry into it. Call with this slot's phase already active (see {@link
     * DeferredGeometryPipelines#setActiveMirrorSlot}), so the draw site aims it at the right MRT
     * and pipeline variant.
     *
     * <p>Cleared here rather than left to a render-pass load op: see {@link
     * PlayerMirrorTargets#clear()} for why the MRT can take several separate pipeline draws in one
     * phase, with none of them reliably first.
     */
    public void cast() {
        PlayerMirrorTargets.forSlot(slot).clear();

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

            // Extracted fresh rather than reused from levelRenderState.entityRenderStates, for the
            // same reason as PlayerShadowCaster: in first person the player is not in that list.
            EntityRenderState state = entities.extractEntity(player, partialTick);

            // [PlayerMirror] diagnostic: checks whether extractEntity already returns a zero
            // packedLight (the value this caster's private dispatcher is the first thing to read;
            // PlayerShadowCaster's depth-only program never reads it), or whether the zero appears
            // after this point.
            long nowNanos = System.nanoTime();
            if (nowNanos - lastLightLogNanos >= LIGHT_LOG_INTERVAL_NANOS) {
                lastLightLogNanos = nowNanos;
                int lightCoords = state.lightCoords;
                FornaxMod.LOGGER.info("[PlayerMirror] {} extracted state.lightCoords=0x{} block={} sky={}",
                        slot.token(), Integer.toHexString(lightCoords), LightCoordsUtil.block(lightCoords),
                        LightCoordsUtil.sky(lightCoords));
            }

            // cameraRenderState.pos is the player, not the eye: the same trap PlayerShadowCaster's
            // doc records, measured there and reused here rather than re-derived.
            Vec3 cameraPos = levelState.cameraRenderState.pos;
            SubmitNodeStorage storage = new SubmitNodeStorage();

            entities.submit(state, levelState.cameraRenderState,
                    state.x - cameraPos.x, state.y - cameraPos.y, state.z - cameraPos.z,
                    new PoseStack(), storage);

            if (mirrorDispatcher == null) {
                mirrorDispatcher = new FeatureRenderDispatcher(mirrorBuffers = new RenderBuffers(1),
                        minecraft.getModelManager(), minecraft.getAtlasManager(), minecraft.font,
                        minecraft.gameRenderer.gameRenderState());
            }

            DeferredGeometryPipelines.setActiveMirrorSlot(slot);
            try {
                mirrorDispatcher.renderAllFeatures(storage);
            } finally {
                DeferredGeometryPipelines.setActiveMirrorSlot(null);
                // Recycles this dispatcher's staged vertex memory. In the finally so a throwing
                // draw still recycles it. See PlayerShadowCaster.cast() for the device-loss leak
                // the same omission caused there: one unrecycled StagedVertexBuffer allocation per
                // frame, growing address space until MoltenVK lost the device.
                mirrorBuffers.endFrame();
            }
        } catch (RuntimeException e) {
            reportFailure(e);
        }
    }

    private void reportFailure(RuntimeException e) {
        if (!reportedFailure) {
            reportedFailure = true;
            FornaxMod.LOGGER.error("[Fornax] Could not submit the player as a {} mirror caster."
                    + " No reflection will draw for this slot this session.", slot.token(), e);
        }
    }

    /**
     * Drops the log-once latch above. Called from {@link DeferredGeometryPipelines#invalidate()}
     * (via {@link #resetAllForNewPack()}) on every pack load. The log message says "this session,"
     * but a failure is usually a fact about the pack or the frame it was captured under, such as a
     * bad reentry or a program that failed to resolve, not about how long the JVM has run. A new
     * pack, or an engine build reloaded into the same session, deserves a fresh attempt to report
     * rather than staying silent because of what loaded before it.
     */
    public void resetForNewPack() {
        reportedFailure = false;
    }
}

package dev.icehunter.fornax.mixin.vanilla;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import dev.icehunter.fornax.pack.graph.GraphRunner;
import dev.icehunter.fornax.pipeline.SkyFrameState;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels vanilla's clouds pass when the active pack owns clouds ({@link
 * GraphRunner#packOwnsClouds()}) and commits {@link SkyFrameState}'s clouds did-cancel flag
 * (u_SkyState.z) for the frame either way. Injected at HEAD of {@code addCloudsPass} -- pass
 * REGISTRATION time, mirroring {@link LevelRendererSkyPassMixin}'s own timing rationale (before
 * the frame graph executes, therefore before any terrain draw writes u_Globals). The caller that
 * registers both passes calls {@code addSkyPass} before {@code addCloudsPass} every frame
 * (bytecode-verified against the real MC 26.2 jar), so {@code LevelRendererSkyPassMixin}'s commit
 * -- which zeroes the clouds lane, see {@link SkyFrameState}'s field comment -- has always
 * already run by the time this injection fires; this ordering comes from vanilla's own call
 * sequence, not from {@code fornax.mixins.json}'s list order (which has no bearing on
 * cross-method injection ordering).
 *
 * <p>Vanilla's own {@code cloudStatus != OFF && alpha(cloudColor) > 0} guard runs inside
 * {@code addCloudsPass} BEFORE this HEAD injection is ever reached (it is the caller of
 * {@code addCloudsPass}, {@code renderClouds}, that checks it) -- so cancelling here only ever
 * replaces clouds vanilla would actually have drawn this frame, never invents a cancellation
 * vanilla wouldn't have painted anyway.
 *
 * <p>2026-07-15 wind-clock-freeze fix: this mixin used to also commit the wind clock
 * (u_SkyState.w) from its own {@code gameTime}/{@code partialTick} parameters -- which meant the
 * clock only ever advanced on frames where THIS injection fired, i.e. never with clouds off (or
 * any frame {@code packOwnsClouds()} is false), freezing wave animation. {@code
 * GlobalUniformsWriteMixin} now computes the wind clock live every frame instead (see its own doc
 * comment), independent of whether this mixin -- or vanilla's clouds pass at all -- runs. This
 * mixin keeps its one remaining job: the did-cancel flag.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererCloudsPassMixin {
    @Inject(method = "addCloudsPass", at = @At("HEAD"), cancellable = true)
    private void fornax$maybeOwnClouds(FrameGraphBuilder frameGraphBuilder, CloudStatus cloudStatus,
            Vec3 cameraPos, long gameTime, float partialTick, int cloudColor, float cloudHeight,
            int cloudRange, CallbackInfo ci) {
        // BEFORE the ownership branch, on purpose. cloudHeight is the value vanilla is about to
        // draw at, and every mod that moves the cloud layer (Sodium Extra's `cloud_height` among
        // them) has already had its say by the time this argument exists -- so reading the argument
        // needs no knowledge of which mod won, or that any mod is present. Committing it outside
        // the branch means a pack that is NOT currently painting clouds still knows where they
        // belong the moment it starts.
        SkyFrameState.commitCloudAltitude(cloudHeight);

        if (GraphRunner.packOwnsClouds()) {
            SkyFrameState.commitClouds(true);
            ci.cancel();
        }
        // Not cancelled: the flag stays at the 0 the sky mixin's per-frame reset left it at --
        // vanilla clouds draw, the pack's march pass sees flag 0 and outputs nothing.
    }
}

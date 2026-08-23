package dev.icehunter.fornax.mixin.vanilla;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.icehunter.fornax.pass.FrameGenPresenter;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The double-present seam (Task 6): injects {@link FrameGenPresenter#presentGeneratedIfReady}
 * immediately BEFORE vanilla's own {@code windowSurface.blitFromTexture(...)} call inside {@code
 * Minecraft.renderFrame(boolean)}'s late {@code "present"} profiler section.
 *
 * <p><b>Revised from an earlier design that injected before {@code acquireNextTexture()}
 * instead</b> (a code-review pass caught a temporal-ordering bug in that version, verified against
 * the bytecode below before this fix). Discovery on the deobf jar (26.2, {@code javap -c -p} on
 * {@code Minecraft.renderFrame(Z)V}) found the acquire/blit/present triple is NOT co-located at
 * present time the way a naive "double present" would assume:
 * <ul>
 *   <li>The method HEAD early-returns if {@code windowSurface.isAcquired()} is already true --
 *   vanilla itself never re-enters with an outstanding acquire.
 *   <li>{@code windowSurface.acquireNextTexture()} runs EARLY, right after the surface
 *   reconfigure-if-needed block, well BEFORE any of this frame's extract/render work.
 *   <li>{@code GameRenderer.render()} (which reaches {@code renderLevel()} and, deep inside it,
 *   {@code FrameGenPass.run()} -- the call that actually PRODUCES this frame's generated image)
 *   runs AFTER that early acquire.
 *   <li>{@code windowSurface.blitFromTexture(encoder, mainRenderTarget.getColorTextureView())} and
 *   {@code windowSurface.present()} both run later still, in the method's own {@code "present"}
 *   profiler section, each individually preceded by its own {@code isAcquired()} check.
 * </ul>
 *
 * <p><b>Why the early (pre-acquire) site was wrong:</b> injecting before {@code
 * acquireNextTexture()} runs BEFORE this same {@code renderFrame} invocation's own {@code
 * GameRenderer.render()} has produced this frame's generated image -- {@code
 * FrameGenPass.generatedFrameReady()} at that point could only ever reflect the PREVIOUS
 * invocation's result. Presenting that stale image immediately before the real frame gives
 * presented scene-times that step backward every other frame (verified: {@code ...,
 * N-1.5, N, N-0.5, N+1, N+0.5, ...}) -- oscillating judder, not the intended smoothing. Firing
 * instead immediately before vanilla's OWN {@code blitFromTexture} call -- after this invocation's
 * {@code GameRenderer.render()} has already run -- means {@code generatedFrameReady()} reflects
 * THIS frame's own interpolation between the previous and current native color
 * ({@code G(N-1,N)}, time {@code N-0.5}), giving a monotonic presented sequence: {@code ...,
 * N-1, N-0.5, N, N+0.5, N+1, ...}.
 *
 * <p><b>Why this late site is still safe against the single-acquire-slot invariant:</b> {@code
 * GpuSurface} (also javap'd directly) tracks state with two private booleans, {@code
 * hasImageAcquired} and {@code hasBlittedTexture} -- exactly one outstanding acquire, exactly one
 * blit per acquire cycle, no per-call handle or counter. At this injection site vanilla's own
 * earlier {@code isAcquired()} check (further up the same "present" section, before this method's
 * own call site is reached) has already passed, so the surface is guaranteed
 * acquired-but-not-yet-blitted on entry. {@link FrameGenPresenter#presentGeneratedIfReady} blits the
 * generated frame into THAT already-acquired image, presents it (clearing the acquired flag), then
 * reacquires a FRESH image before returning -- leaving the surface exactly acquired-not-blitted
 * again, precisely the state vanilla's own immediately-following (completely untouched) {@code
 * blitFromTexture}/{@code present} calls expect for the real frame. Never a second outstanding
 * acquire, never a double blit on one acquire cycle, never any of vanilla's own internal indices
 * touched. See {@link FrameGenPresenter}'s own class header for the full phase-by-phase exception
 * safety reasoning (staging/blit/present/reacquire failures each leave a distinct, individually
 * verified surface state).
 *
 * <p>Targets the exact {@code GpuSurface.blitFromTexture(CommandEncoder, GpuTextureView)} invocation
 * (there is only one call to this method, and only one call to {@code acquireNextTexture()}, inside
 * {@code renderFrame} -- confirmed by grepping the full disassembly, so the target descriptor is
 * unambiguous within this method) rather than a fixed bytecode offset, so this stays correct across
 * the surrounding conditionals: this injection only ever runs on the same path vanilla's own blit
 * would have run on anyway.
 */
@Mixin(Minecraft.class)
public abstract class PresentSeamMixin {
    @Shadow
    @Final
    private GpuSurface windowSurface;

    @Inject(
            method = "renderFrame",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/GpuSurface;blitFromTexture(Lcom/mojang/blaze3d/systems/CommandEncoder;Lcom/mojang/blaze3d/textures/GpuTextureView;)V",
                    shift = At.Shift.BEFORE
            )
    )
    private void fornax$presentGeneratedFrame(CallbackInfo ci) {
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        FrameGenPresenter.presentGeneratedIfReady(this.windowSurface, encoder);
    }
}

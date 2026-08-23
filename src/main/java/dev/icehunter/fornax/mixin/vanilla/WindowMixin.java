package dev.icehunter.fornax.mixin.vanilla;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.platform.Window;
import dev.icehunter.fornax.pass.ssaa.SsaaManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Makes {@link Window#getWidth()}/{@link Window#getHeight()} report the SSAA-scaled resolution
 * while a scaled render is in flight, so anything downstream computing viewport/GUI/scissor sizes
 * from the window (rather than from {@code GameRenderer.mainRenderTarget} directly) sees a
 * consistent size. Targets {@code com.mojang.blaze3d.platform.Window}, the class that actually
 * declares {@code getWidth()}/{@code getHeight()} -- not the similarly named
 * {@code com.mojang.blaze3d.opengl.GlBackend}, which has no such methods.
 *
 * <p>{@link GameRendererMixin} reads native size via {@code Window.getScreenWidth/Height}
 * (unaffected by this mixin) rather than {@code getWidth/getHeight}, to avoid feeding an
 * already-scaled size back in as if it were native.
 */
@Mixin(Window.class)
public class WindowMixin {
    @ModifyReturnValue(method = "getWidth", at = @At("RETURN"))
    private int fornax$ssaaScaleWidth(int original) {
        // isFrameActive(), not isActive(): getWidth/getHeight are also queried by GUI/options-screen
        // layout code that runs outside renderLevel at native resolution. isActive() only reflects
        // whether an SSAA preset is selected, not whether a scaled frame is actually in progress, so
        // using it here would scale sizes even when no scaled render is in flight.
        return SsaaManager.isFrameActive()
                ? Math.round(original * SsaaManager.getScaleFactor())
                : original;
    }

    @ModifyReturnValue(method = "getHeight", at = @At("RETURN"))
    private int fornax$ssaaScaleHeight(int original) {
        return SsaaManager.isFrameActive()
                ? Math.round(original * SsaaManager.getScaleFactor())
                : original;
    }
}

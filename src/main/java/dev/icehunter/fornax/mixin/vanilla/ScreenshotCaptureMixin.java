package dev.icehunter.fornax.mixin.vanilla;

import dev.icehunter.fornax.debug.ScreenshotCapture;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Runs at the start of a normal F2, before vanilla reads the same framebuffer. The PNG is still
 * written. The last graph's targets still exist here. With no pack or no config file this does
 * nothing. Debug panorama is skipped. */
@Mixin(Screenshot.class)
public class ScreenshotCaptureMixin {
    @Inject(method = "grab(Lnet/minecraft/client/Minecraft;Z)V", at = @At("HEAD"))
    private static void fornax$captureRawTargets(Minecraft minecraft, boolean debug, CallbackInfo ci) {
        if (debug && SharedConstants.DEBUG_PANORAMA_SCREENSHOT) return;
        ScreenshotCapture.capture(minecraft);
    }
}

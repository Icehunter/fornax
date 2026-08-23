package dev.icehunter.fornax.mixin.sodium;

import net.caffeinemc.mods.sodium.client.gpu.device.context.DrawContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@code DrawContext.getCameraTranslation(int, int, float)}, which is declared
 * {@code protected static} on the parent class. Subclass mixins cannot {@code @Shadow} inherited
 * members, so both backend mixins call through this invoker instead.
 */
@Mixin(DrawContext.class)
public interface DrawContextInvoker {
    @Invoker("getCameraTranslation")
    static float fornax$getCameraTranslation(int chunkBlockPos, int cameraBlockPos, float cameraFraction) {
        throw new AssertionError();
    }
}

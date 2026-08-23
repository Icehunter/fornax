package dev.icehunter.fornax.mixin.vanilla;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@code LevelRenderer}'s private per-frame render state.
 *
 * <p>Needed for shadow casting: {@code LevelRenderState.cameraRenderState} is the camera state
 * {@code EntityRenderDispatcher.submit} requires, and it exists nowhere else reachable. Everything
 * else on that object is already public -- only the field holding it is not.
 */
@Mixin(LevelRenderer.class)
public interface LevelRendererStateAccessor {
    @Accessor("levelRenderState")
    LevelRenderState fornax$levelRenderState();
}

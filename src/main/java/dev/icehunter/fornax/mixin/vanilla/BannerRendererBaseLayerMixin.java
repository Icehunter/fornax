package dev.icehunter.fornax.mixin.vanilla;

import com.llamalad7.mixinextras.sugar.Local;
import dev.icehunter.fornax.pack.graph.GraphRunner;
import dev.icehunter.fornax.pipeline.BannerBaseLayerRoute;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BannerRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.resources.model.sprite.SpriteGetter;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.world.item.DyeColor;
import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps a banner's base dye in the same deferred draw as its cloth lighting.
 *
 * <p>{@code BannerRenderer.submitBanner} submits the flag model untinted through
 * {@code ENTITY_SOLID}, then {@code submitPatterns} paints an opaque base-colour layer over the
 * same geometry through {@code BANNER_PATTERN}. The latter runs after the deferred resolve, so it
 * erases the pack-lit result. Tinting the existing flag submission and dropping only that redundant
 * base layer preserves one shaded cloth draw; the real pattern masks still use vanilla's ordered
 * forward blend.</p>
 */
@Mixin(BannerRenderer.class)
public abstract class BannerRendererBaseLayerMixin {
    @ModifyArg(
            method = "submitBanner",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModel"
                            + "(Lnet/minecraft/client/model/Model;Ljava/lang/Object;"
                            + "Lcom/mojang/blaze3d/vertex/PoseStack;III"
                            + "Lnet/minecraft/client/resources/model/sprite/SpriteId;"
                            + "Lnet/minecraft/client/resources/model/sprite/SpriteGetter;I"
                            + "Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V",
                    ordinal = 1
            ),
            index = 5
    )
    private static int fornax$tintDeferredFlag(int original,
            @Local(argsOnly = true) DyeColor baseColor) {
        return GraphRunner.isActive()
                ? BannerBaseLayerRoute.deferredFlagColor(baseColor)
                : original;
    }

    @Inject(method = "submitPatternLayer", at = @At("HEAD"), cancellable = true)
    private static <S> void fornax$skipRedundantForwardBase(
            SpriteGetter sprites,
            PoseStack poseStack,
            OrderedSubmitNodeCollector collector,
            int light,
            int overlay,
            Model<S> model,
            S modelState,
            SpriteId sprite,
            DyeColor dye,
            ModelFeatureRenderer.CrumblingOverlay crumblingOverlay,
            CallbackInfo ci) {
        if (GraphRunner.isActive() && BannerBaseLayerRoute.suppressForwardLayer(sprite)) {
            ci.cancel();
        }
    }
}

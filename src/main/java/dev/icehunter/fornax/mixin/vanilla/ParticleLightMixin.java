package dev.icehunter.fornax.mixin.vanilla;

import dev.icehunter.fornax.pack.graph.GraphRunner;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.TerrainParticle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.LightCoordsUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps block-breaking debris legible when deferred emitter lighting owns terrain illumination.
 *
 * <p>{@link TerrainParticle} is rendered later through Minecraft's forward particle shader, outside
 * the pack G-buffer and analytic-light composite. Its inherited light lookup samples only the
 * particle's exact cell. Breaking debris is commonly spawned just inside the solid block being
 * removed, so that lookup returns darkness even while the adjacent exposed face is strongly lit.
 * Against deferred-lit terrain those opaque, camera-facing quads appear as hard black rectangles.
 *
 * <p>The underlying vanilla light field is still available while emitter lighting is enabled. For
 * terrain debris only, sample the six face-adjacent cells as vanilla smooth block lighting does and
 * keep the brightest packed block/sky pair. This preserves real vanilla occlusion (a sealed wall's
 * exterior neighbours remain dark), avoids a full-bright particle hack, and leaves every other
 * particle type and the no-pack/emitters-off paths byte-for-byte vanilla.
 */
@Mixin(Particle.class)
public abstract class ParticleLightMixin {
    @Shadow @Final protected ClientLevel level;
    @Shadow protected double x;
    @Shadow protected double y;
    @Shadow protected double z;

    @Inject(method = "getLightCoords", at = @At("HEAD"), cancellable = true)
    private void fornax$lightTerrainDebrisFromExposedNeighbours(
            float partialTick, CallbackInfoReturnable<Integer> cir) {
        if (!((Object) this instanceof TerrainParticle)
                || !GraphRunner.isCompileOptionEnabled("EMITTER_LIGHTS")) {
            return;
        }

        BlockPos origin = BlockPos.containing(this.x, this.y, this.z);
        if (!this.level.hasChunkAt(origin)) {
            return;
        }

        int light = LightCoordsUtil.getLightCoords(this.level, origin);
        for (Direction direction : Direction.values()) {
            BlockPos neighbour = origin.relative(direction);
            if (this.level.hasChunkAt(neighbour)) {
                light = LightCoordsUtil.max(light,
                        LightCoordsUtil.getLightCoords(this.level, neighbour));
            }
        }
        cir.setReturnValue(light);
    }
}

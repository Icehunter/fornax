package dev.icehunter.fornax.mixin.vanilla;

import dev.icehunter.fornax.atlas.AtlasGenerationSchedule;
import dev.icehunter.fornax.atlas.AtlasGenerationSchedule.RebuildScope;
import dev.icehunter.fornax.atlas.BlockAtlasOverflow;
import dev.icehunter.fornax.atlas.BlockAtlasPagedLayout;
import dev.icehunter.fornax.atlas.LabPbrAtlasFingerprint;
import dev.icehunter.fornax.atlas.LabPbrAtlasPair;
import dev.icehunter.fornax.atlas.LabPbrGeometryBindings;
import dev.icehunter.fornax.atlas.LabPbrSidecarSurvey;
import dev.icehunter.fornax.atlas.MaterialMapAtlas;
import dev.icehunter.fornax.atlas.NormalMapAtlas;
import dev.icehunter.fornax.atlas.SpriteHeightRanges;
import dev.icehunter.fornax.pass.compute.VulkanComputeBackend;
import dev.icehunter.fornax.pipeline.SpriteBoundsTexture;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Frees this atlas location's Fornax-owned GPU generation before vanilla allocates the next one --
 * but only when something actually changed, and only starting the countdown
 * {@link AtlasGenerationSchedule} needs before the freed VRAM is genuinely reclaimed, not before.
 *
 * <p>Every Fornax atlas subsystem hooked off this same {@code upload} method (the LabPBR sidecar
 * lanes, the paged block-atlas overflow layers, the sprite-bounds grid) must close the old
 * generation before the new one publishes, not after. Publish-then-close would leave BOTH
 * generations of every one of these resident at once for a resource-pack switch, on top of
 * vanilla's own old+new base atlas textures -- multiple gigabytes, unbounded by anything closing
 * them any sooner, a native-crash-class VRAM risk across back-to-back switches.
 *
 * <p><b>Closing this earlier is not enough on its own.</b> Closing unconditionally here and letting
 * the new generation build immediately, at this same {@code upload} RETURN, is still unsafe:
 * Blaze3D's own destroy ring only reclaims a closed texture's VRAM on the SECOND
 * {@code VulkanCommandEncoder.submit()} after the close, and {@code submit()} runs once per real
 * rendered frame; {@code upload()} has zero of those in between, so old and new would still be
 * 100% resident together the whole time. See {@link AtlasGenerationSchedule}'s own doc for the
 * mechanism this mixin relies on: it only RELEASES here (still worth doing early -- it starts the
 * destroy ring's clock as soon as possible) and schedules the actual rebuild after three
 * render-loop-separated polls, once reclamation has genuinely had time to happen.
 *
 * <p><b>Only the part that changed.</b> The two lanes' own fingerprint-skip path (measured at
 * 14.6s/~24s combined on a 512x pack, meant to make an unchanged F3+T reload or shader-only F8 free)
 * depends on this mixin computing both fingerprints itself before touching anything, the same way
 * each listener already does internally, rather than clearing the published pair unconditionally on
 * every reload -- an unconditional clear would leave {@code existing} always null by the time each
 * listener's own {@code existing != null && fingerprint.equals(...)} check runs, defeating the skip
 * entirely. An unchanged non-block atlas is a complete no-op. An unchanged block atlas retains
 * its exact sidecar pair and animation state, but still retires and defers the albedo overflow
 * array and sprite grid because their bytes come from the vanilla atlas, not those fingerprints.
 *
 * <p>{@code HEAD} of {@code upload} runs strictly before vanilla's own {@code createTexture} for
 * this generation (which itself calls {@code releaseTextures()} first -- vanilla releases before
 * it allocates too) and there is no draw between {@code HEAD} and {@code RETURN}, so releasing here
 * never produces an observable frame with nothing published: {@code
 * DefaultChunkRendererTextureBindMixin} already binds {@link
 * dev.icehunter.fornax.atlas.LabPbrNeutralTextures}'s neutral fallback whenever a lane's pair is
 * null (the same path a fresh install with no pack loaded takes), {@link BlockAtlasOverflow} has
 * its own neutral 1x1 array for the same reason, and {@link SpriteBoundsTexture#view()} returns a
 * device-owned zero RGBA32F texel during the pending window -- that fallback stays bound for the
 * few polls {@link AtlasGenerationSchedule}'s countdown takes, rather than lazily allocating a
 * cross-generation grid before the terminal rebuild.
 *
 * <p>Gated on {@link LabPbrGeometryBindings#isMirroredAtlasOwner}, the same set the sidecar build
 * hook itself is gated on -- every other atlas upload (particles, items, chest, banner_patterns's
 * OWN reload notwithstanding since it IS a mirrored owner, etc.) has nothing here to release and
 * must not pay a GPU idle wait it does not need.
 */
@Mixin(TextureAtlas.class)
public class TextureAtlasReleaseGenerationMixin {
    @Shadow
    @Final
    private Identifier location;

    @Inject(method = "upload", at = @At("HEAD"))
    private void fornax$releasePreviousGeneration(SpriteLoader.Preparations preparations, CallbackInfo ci) {
        if (!LabPbrGeometryBindings.isMirroredAtlasOwner(this.location)) {
            return;
        }

        ResourceManager resourceManager = Minecraft.getInstance().getResourceManager();
        BlockAtlasPagedLayout pagedLayout = this.location.equals(TextureAtlas.LOCATION_BLOCKS)
                ? BlockAtlasPagedLayout.current() : null;
        boolean sidecarsUnchanged = fornax$unchanged(
                this.location, preparations, resourceManager, pagedLayout);
        RebuildScope scope = AtlasGenerationSchedule.scopeFor(this.location, sidecarsUnchanged);
        if (scope == RebuildScope.NONE) {
            return;
        }

        VulkanComputeBackend.waitForGpuIdleBeforeDestroy();
        if (scope.rebuildSidecars()) {
            LabPbrAtlasPair.replace(this.location, null);
        }
        if (scope == RebuildScope.BLOCK_FULL) {
            SpriteHeightRanges.replaceAll(List.of());
        }
        if (scope.rebuildBlockResources()) {
            BlockAtlasOverflow.releaseCurrent();
            SpriteBoundsTexture.destroy();
        }
        AtlasGenerationSchedule.scheduleRelease(
                this.location, preparations, resourceManager, pagedLayout, scope);
    }

    /**
     * Mirrors each listener's own fingerprint-skip check (see {@code
     * NormalMapAtlasReloadListener.build}/{@code MaterialMapAtlasReloadListener.build}) so this hook
     * can decide, before releasing anything, whether this reload actually needs to. Both lanes must
     * match for the reload to count as unchanged -- a pack shipping a new {@code _n} map with an
     * unchanged {@code _s} one still needs a real release+rebuild.
     */
    private static boolean fornax$unchanged(Identifier atlasLocation, SpriteLoader.Preparations preparations,
                                            ResourceManager resourceManager,
                                            @Nullable BlockAtlasPagedLayout pagedLayout) {
        NormalMapAtlas existingNormal = NormalMapAtlas.getInstance(atlasLocation);
        MaterialMapAtlas existingMaterial = MaterialMapAtlas.getInstance(atlasLocation);
        if (existingNormal == null || existingMaterial == null) {
            return false;
        }
        List<TextureAtlasSprite> sprites = new ArrayList<>(preparations.regions().values());
        String normalFingerprint = LabPbrAtlasFingerprint.compute(atlasLocation, preparations,
                LabPbrSidecarSurvey.survey(sprites, resourceManager, "_n"), pagedLayout);
        String materialFingerprint = LabPbrAtlasFingerprint.compute(atlasLocation, preparations,
                LabPbrSidecarSurvey.survey(sprites, resourceManager, "_s"), pagedLayout);
        return normalFingerprint.equals(existingNormal.fingerprint())
                && materialFingerprint.equals(existingMaterial.fingerprint());
    }
}

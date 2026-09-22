package dev.icehunter.fornax.mixin.sodium;

import dev.icehunter.fornax.pipeline.TerrainMeshRevision;
import dev.icehunter.fornax.pipeline.TerrainMeshRevisions;
import dev.icehunter.fornax.pipeline.TerrainMeshRevisionStats;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observes public terrain-storage mutations without changing rendering. HEAD is intentional:
 * a mutation may release old geometry before failing, so RETURN would preserve a stale stamp.
 * Resize and deletion invalidate all slots because their backing addresses may change together.
 * Freshness is recorded with or without an active pack; when inactive nothing consumes it and
 * rendering is unchanged. Gating this data would let a later RT enablement reuse stale meshes. */
@Mixin(SectionRenderDataStorage.class)
public class SectionRenderDataStorageRevisionMixin implements TerrainMeshRevision {
    @Unique
    private final TerrainMeshRevisions fornax$meshRevisions = new TerrainMeshRevisions(RenderRegion.REGION_SIZE);

    @Override
    public long fornax$revision(int section) {
        return fornax$meshRevisions.revision(section);
    }

    // The arena segment's public class name differs between supported runtime APIs. Only the
    // section index is consumed: do not link the unused argument to either concrete segment type.
    @Inject(method = "setVertexData", at = @At("HEAD"))
    private void fornax$beforeVertexReplacement(int section, @Coerce Object allocation, int[] vertexCounts, CallbackInfo ci) {
        fornax$meshRevisions.invalidate(section);
        TerrainMeshRevisionStats.count(TerrainMeshRevisionStats.Site.VERTEX_DATA);
    }

    /** Newer arena APIs relocate individual segments without resizing the entire buffer. */
    @Inject(method = "onVertexSegmentChanged(I)V", at = @At("HEAD"), require = 0)
    private void fornax$beforeSegmentRelocation(int section, CallbackInfo ci) {
        fornax$meshRevisions.invalidate(section);
    }

    @Inject(method = {"removeVertexData(I)V", "removeData(I)V"}, at = @At("HEAD"))
    private void fornax$beforeSectionRemoval(int section, CallbackInfo ci) {
        fornax$meshRevisions.invalidate(section);
        TerrainMeshRevisionStats.count(TerrainMeshRevisionStats.Site.SECTION_REMOVAL);
    }

    // onVertexSegmentChanged does not exist on this Sodium version (require = 0 makes that inject
    // a no-op here), so relocation arrives through onBufferResized and is counted only here.
    @Inject(method = {"onBufferResized()V", "delete()V"}, at = @At("HEAD"))
    private void fornax$beforeStorageReplacement(CallbackInfo ci) {
        fornax$meshRevisions.invalidateAll();
        TerrainMeshRevisionStats.count(TerrainMeshRevisionStats.Site.STORAGE_REPLACEMENT);
    }
}

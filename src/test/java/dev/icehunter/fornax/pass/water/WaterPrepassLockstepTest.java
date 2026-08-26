package dev.icehunter.fornax.pass.water;

import dev.icehunter.fornax.pipeline.FornaxRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WaterPrepassLockstepTest {
    @Test void waterPrepassIsNotDeferredButIsTranslucent() {
        assertFalse(FornaxRenderPasses.isDeferred(FornaxRenderPasses.WATER_PREPASS),
                "WATER_PREPASS must be excluded from the 5-attachment deferred path");
        assertTrue(FornaxRenderPasses.isWaterPrepass(FornaxRenderPasses.WATER_PREPASS));
        // Index-path law: translucent sections' stored
        // element offsets are only valid against the per-region sorted index arena, which
        // DefaultChunkRenderer binds ONLY when the pass reports translucent. A non-translucent
        // identity read those offsets against the unrelated shared quad-index buffer -> garbage
        // indices -> sparse wedge-shaped water coverage. Pipeline/CTS/attachment selection does NOT
        // depend on this flag: every lockstep mixin branches on isWaterPrepass identity first.
        assertTrue(FornaxRenderPasses.WATER_PREPASS.isTranslucent(),
                "must report translucent so the sorted local index arena is bound for its geometry");
    }
    @Test void waterPrepassSourcesTranslucentGeometry() {
        assertSame(DefaultTerrainRenderPasses.TRANSLUCENT,
                FornaxRenderPasses.sourceGeometryPass(FornaxRenderPasses.WATER_PREPASS));
    }
    @Test void nonWaterPassesUnaffected() {
        assertFalse(FornaxRenderPasses.isWaterPrepass(DefaultTerrainRenderPasses.SOLID));
        assertFalse(FornaxRenderPasses.isWaterPrepass(FornaxRenderPasses.SHADOW));
    }

    @Test void waterDepthConsumersStayAllocatedWhenOpaqueReflectionsAreOff() {
        assertTrue(WaterSurfaceManager.shouldRenderPrepass(true, 3),
                "SSR_WATER_MODE owns the shared water-depth prepass; opaque SSR quality must not disable it");
        assertFalse(WaterSurfaceManager.shouldRenderPrepass(true, 1));
        assertFalse(WaterSurfaceManager.shouldRenderPrepass(false, 3));
    }
}

package dev.icehunter.fornax.pipeline;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.icehunter.fornax.pack.GeometrySlot;
import net.minecraft.client.renderer.RenderPipelines;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link DeferredGeometryPipelines#mirrorVariantOf} is safe to call headless, with no live pack and
 * no GPU device: {@code GeometryProgramSource.replacementIdentifierFor} returns {@code null}
 * immediately when no pack is active, before ever reaching {@code RenderSystem.getDevice()}. This
 * is the same reason {@code shadowVariantOf} is callable in a unit test.
 *
 * <p>The pack's {@code player_mirror} programs do not exist yet, so every vanilla pipeline must
 * resolve to no variant. This is the "nothing to contribute" contract this method's comment
 * states, checked across every {@code RenderPipelines} constant the way {@link
 * EndPortalSlotContractTest} scans the same field set for a different question.
 */
class PlayerMirrorVariantOfTest {

    @Test
    void everyVanillaPipelineResolvesToNoMirrorVariantBeforeP1() throws Exception {
        int checked = 0;
        for (Field field : RenderPipelines.class.getDeclaredFields()) {
            if (!RenderPipeline.class.equals(field.getType())) {
                continue;
            }
            field.setAccessible(true);
            RenderPipeline pipeline = (RenderPipeline) field.get(null);
            for (GeometrySlot slot : PlayerMirrorTargets.slots()) {
                assertNull(DeferredGeometryPipelines.mirrorVariantOf(pipeline, slot),
                        "no pack is active in this test, so " + field.getName()
                                + " must resolve to no player-mirror variant for " + slot.token());
            }
            checked++;
        }
        assertEquals(true, checked > 0, "sanity: RenderPipelines must declare at least one pipeline");
    }

    @Test
    void repeatedCallsAgreeAndDoNotThrow() {
        RenderPipeline base = RenderPipelines.ENTITY_CUTOUT;
        RenderPipeline first = DeferredGeometryPipelines.mirrorVariantOf(base, GeometrySlot.PLAYER_MIRROR);
        RenderPipeline second = DeferredGeometryPipelines.mirrorVariantOf(base, GeometrySlot.PLAYER_MIRROR);
        assertEquals(first, second, "a miss must resolve the same way every call, not flap");
    }

    /**
     * The mirror cache must be keyed by (base pipeline, slot) together, never by base alone:
     * keying by base alone would hand the floor's cached variant to a wall pass reading the
     * identical base pipeline. This is not observable from behaviour here, since every variant is
     * {@code null} until the pack's mirror programs exist, so a base-alone key and a (base, slot)
     * key would look identical from outside. This test instead pins the cache key's equality
     * directly: the same base pipeline paired with two different slots must never compare equal.
     */
    @Test
    void theCacheKeyTreatsDifferentSlotsOnTheSameBaseAsDistinct() {
        RenderPipeline base = RenderPipelines.ENTITY_CUTOUT;
        var floorKey = new DeferredGeometryPipelines.MirrorCacheKey(base, GeometrySlot.PLAYER_MIRROR);
        var xKey = new DeferredGeometryPipelines.MirrorCacheKey(base, GeometrySlot.PLAYER_MIRROR_X);
        var zKey = new DeferredGeometryPipelines.MirrorCacheKey(base, GeometrySlot.PLAYER_MIRROR_Z);
        var floorKeyAgain = new DeferredGeometryPipelines.MirrorCacheKey(base, GeometrySlot.PLAYER_MIRROR);

        assertFalse(floorKey.equals(xKey), "the floor and X-wall keys share a base but must not be equal");
        assertFalse(floorKey.equals(zKey), "the floor and Z-wall keys share a base but must not be equal");
        assertFalse(xKey.equals(zKey), "the two wall keys share a base but must not be equal");
        assertEquals(floorKey, floorKeyAgain, "the same (base, slot) pair must still compare equal");
        assertEquals(floorKey.hashCode(), floorKeyAgain.hashCode(), "equal keys must hash equal");
    }
}

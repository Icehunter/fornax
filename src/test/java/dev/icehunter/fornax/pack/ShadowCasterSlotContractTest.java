package dev.icehunter.fornax.pack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins which slots are replayed into the shadow map.
 *
 * <p>This exists because of a real regression: the draw site used "this pipeline is mapped to any
 * slot" as its test for "this pipeline casts a shadow". Mapping additional pipelines was meant to be
 * inert until a pack claimed them, but under that test every newly-mapped slot was immediately
 * enlisted as a shadow caster -- so clouds, rain, particles, lightning and block outlines were all
 * replayed into the shadow map and each built its own compiled pipeline variant. The scene shaded
 * through a shadow map full of billboards and streaks, and the frame rate fell by two thirds.
 *
 * <p>The lesson worth keeping is that "is this slot known?" and "should this slot cast?" are different
 * questions, and answering the second with the first makes an inert-looking table edit change how the
 * whole scene shades.
 */
public class ShadowCasterSlotContractTest {
    @Test
    void solidWorldGeometryCasts() {
        for (GeometrySlot slot : new GeometrySlot[]{
                GeometrySlot.TERRAIN, GeometrySlot.ENTITIES, GeometrySlot.ENTITIES_TRANSLUCENT,
                GeometrySlot.BLOCK_ENTITIES, GeometrySlot.BLOCK_ENTITIES_TRANSLUCENT}) {
            assertTrue(slot.castsShadow(), "slot '" + slot.token() + "' should cast a shadow");
        }
    }

    @Test
    void atmosphereAndScreenFurnitureDoNotCast() {
        for (GeometrySlot slot : new GeometrySlot[]{
                GeometrySlot.CLOUDS, GeometrySlot.WEATHER, GeometrySlot.PARTICLES,
                GeometrySlot.LIGHTNING, GeometrySlot.BEACON_BEAM, GeometrySlot.LINES,
                GeometrySlot.SKY_BASIC, GeometrySlot.SKY_TEXTURED, GeometrySlot.ARMOR_GLINT,
                GeometrySlot.SPIDER_EYES, GeometrySlot.DAMAGED_BLOCK}) {
            assertFalse(slot.castsShadow(),
                    "slot '" + slot.token() + "' must not be replayed into the shadow map -- it is"
                            + " volumetric, transient or screen furniture, and casting from it fills the"
                            + " shadow map with geometry the eye never reads as solid");
        }
    }

    /**
     * A FORWARD slot must never cast, and this is a rule about the mechanism rather than a judgement
     * about banners.
     *
     * <p>The shadow replay re-executes the same prepared draws with the shadow map bound and swaps in a
     * depth-only pipeline whose vertex stage reprojects through the light. A forward slot's whole
     * definition is that its render pass is NOT rewritten -- so it has no route into that replay, and
     * the draw site relies on exactly this being false to cancel its draws at HEAD during the shadow
     * phase. If a forward slot ever answered true here, its pattern layers would be drawn to the SCREEN
     * a second time during the replay (the shadow branch falls through to vanilla's own pass when it
     * finds no shadow variant), which for blended geometry is visibly wrong.
     *
     * <p>Banner patterns would be a poor caster anyway -- they are decals on cloth that already casts,
     * so replaying them adds nothing but a duplicate silhouette -- but that is the lesser reason.
     */
    @Test
    void forwardSlotsNeverCast() {
        for (GeometrySlot slot : GeometrySlot.values()) {
            if (!slot.rendersForward()) {
                continue;
            }
            assertFalse(slot.castsShadow(),
                    "slot '" + slot.token() + "' is FORWARD and must not cast: the shadow replay has no"
                            + " route to a slot whose render pass is never rewritten, and the draw site"
                            + " depends on castsShadow() being false to cancel it during the replay"
                            + " rather than drawing it to the screen twice");
        }
        // Pinned by name too, so deleting rendersForward() from BANNER_PATTERNS does not quietly make
        // the loop above vacuous.
        assertTrue(GeometrySlot.BANNER_PATTERNS.rendersForward());
        assertFalse(GeometrySlot.BANNER_PATTERNS.castsShadow());
    }
}

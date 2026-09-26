package dev.icehunter.fornax.pipeline;

import dev.icehunter.fornax.pack.GeometrySlot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GeometrySlot#PLAYER_MIRROR} is a dedicated-pass slot, the same shape as {@link
 * GeometrySlot#SHADOW_ENTITIES}, rather than a normal deferred slot. {@code PlayerMirrorCaster}
 * routes to it.
 */
class PlayerMirrorSlotContractTest {

    @Test
    void theTokenParsesToTheSameConstant() {
        assertSame(GeometrySlot.PLAYER_MIRROR,
                GeometrySlot.parse("player_mirror", "some_pass", "graph.toml"));
    }

    /** A hook routes to it (PlayerMirrorCaster), the same claim {@link
     * GeometrySlot#SHADOW_ENTITIES} makes about itself; see {@link GeometrySlot#isRendered()}'s
     * comment for what this predicate means. */
    @Test
    void aHookRoutesToIt() {
        assertTrue(GeometrySlot.PLAYER_MIRROR.isRendered());
    }

    /**
     * This slot draws into a dedicated set of render targets ({@link PlayerMirrorTargets}) through
     * a dedicated engine pass: not the standard deferred G-buffer, and not vanilla's forward target
     * either. That is a third shape {@code rendersForward()} has no name for, so this must read
     * false here the same way it does for {@link GeometrySlot#SHADOW_ENTITIES}, which is drawn the
     * same dedicated-pass way.
     */
    @Test
    void isNeitherDeferredNorForwardInTheEnumsOwnTerms() {
        assertFalse(GeometrySlot.PLAYER_MIRROR.rendersForward());
    }

    /** A reflection does not block light, the same reasoning {@link GeometrySlot#END_PORTAL}
     * documents. */
    @Test
    void castsNoShadow() {
        assertFalse(GeometrySlot.PLAYER_MIRROR.castsShadow());
    }

    @Test
    void tokenIsPlayerMirror() {
        assertEquals("player_mirror", GeometrySlot.PLAYER_MIRROR.token());
    }

    // --- PLAYER_MIRROR_X and PLAYER_MIRROR_Z share every property above, per their comment;
    // checked here rather than assumed. ----------------------------------------------------------

    @Test
    void theXAndZTokensParseToTheirOwnConstants() {
        assertSame(GeometrySlot.PLAYER_MIRROR_X,
                GeometrySlot.parse("player_mirror_x", "some_pass", "graph.toml"));
        assertSame(GeometrySlot.PLAYER_MIRROR_Z,
                GeometrySlot.parse("player_mirror_z", "some_pass", "graph.toml"));
    }

    @Test
    void aHookRoutesToTheWallSlotsToo() {
        assertTrue(GeometrySlot.PLAYER_MIRROR_X.isRendered());
        assertTrue(GeometrySlot.PLAYER_MIRROR_Z.isRendered());
    }

    @Test
    void theWallSlotsAreAlsoNeitherDeferredNorForward() {
        assertFalse(GeometrySlot.PLAYER_MIRROR_X.rendersForward());
        assertFalse(GeometrySlot.PLAYER_MIRROR_Z.rendersForward());
    }

    @Test
    void theWallSlotsAlsoCastNoShadow() {
        assertFalse(GeometrySlot.PLAYER_MIRROR_X.castsShadow());
        assertFalse(GeometrySlot.PLAYER_MIRROR_Z.castsShadow());
    }

    @Test
    void theWallTokensMatchTheirOwnNames() {
        assertEquals("player_mirror_x", GeometrySlot.PLAYER_MIRROR_X.token());
        assertEquals("player_mirror_z", GeometrySlot.PLAYER_MIRROR_Z.token());
    }
}

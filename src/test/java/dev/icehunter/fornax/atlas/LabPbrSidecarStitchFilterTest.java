package dev.icehunter.fornax.atlas;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What may and may not be left out of the block atlas.
 *
 * <p>This filter deletes sprites from what vanilla stitches, so the interesting tests are the ones
 * about what it must NOT delete. A false positive here does not degrade a surface, it renders it as
 * the missing-texture chequerboard.
 */
class LabPbrSidecarStitchFilterTest {
    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("minecraft", path);
    }

    @Test
    void aSidecarWhoseBaseSpriteIsPresentIsDropped() {
        Set<Identifier> drop = LabPbrSidecarStitchFilter.sidecarsToDrop(
                List.of(id("block/stone"), id("block/stone_n"), id("block/stone_s")));

        assertEquals(Set.of(id("block/stone_n"), id("block/stone_s")), drop);
    }

    @Test
    void aTextureThatMerelyENDSInTheSuffixIsKept() {
        // The rule is "is a sidecar OF something", not "looks like one". A pack shipping a block
        // called `glass_s` with no `glass` beside it has a model pointing at that sprite, and
        // dropping it would render the block as the missing texture -- the one failure mode this
        // change can cause, and the reason the base-present test exists at all.
        Set<Identifier> drop = LabPbrSidecarStitchFilter.sidecarsToDrop(
                List.of(id("block/glass_s"), id("block/wool_n"), id("block/dirt")));

        assertTrue(drop.isEmpty(), "nothing here is a sidecar of anything: " + drop);
    }

    @Test
    void namespacesDoNotLeakIntoEachOther() {
        // A modded pack can ship `mod:block/panel_n` while vanilla has `minecraft:block/panel`.
        // Those are different textures and the sidecar rule must not join them across namespaces.
        Set<Identifier> drop = LabPbrSidecarStitchFilter.sidecarsToDrop(List.of(
                id("block/panel"),
                Identifier.fromNamespaceAndPath("othermod", "block/panel_n")));

        assertTrue(drop.isEmpty(), "a base in another namespace is not this sprite's base: " + drop);
    }

    @Test
    void vanillaAloneLosesNothing() {
        // No vanilla block or item texture ends in _n or _s -- verified against the 26.2 client jar
        // -- so a player with no resource pack must get a bit-identical atlas. Represented here by
        // the shape that matters: a set with no sidecar suffixes at all yields an empty drop set,
        // and the mixin returns the caller's own list unchanged when that happens.
        Set<Identifier> drop = LabPbrSidecarStitchFilter.sidecarsToDrop(
                List.of(id("block/stone"), id("block/dirt"), id("entity/conduit/base"),
                        id("entity/bell/bell_body")));

        assertTrue(drop.isEmpty(), "vanilla ships no sidecars: " + drop);
    }

    @Test
    void aSuffixWithNoNameLeftIsNotASidecar() {
        // "block/_n" would strip to "block/", which is not a texture name and which Identifier
        // rejects outright. Answering "not a sidecar" keeps the caller free of exception handling
        // and is also the honest answer.
        Set<Identifier> drop = LabPbrSidecarStitchFilter.sidecarsToDrop(
                List.of(id("block/_n"), id("block/_s")));

        assertTrue(drop.isEmpty(), "a bare suffix is not a sidecar: " + drop);
    }

    @Test
    void continuityCtmTilesAreCoveredByTheSameRule() {
        // Continuity registers CTM tiles under continuity_reserved/, and Fornax reads THEIR sidecars
        // from the source files too (see LabPbrSidecarLocator). It happens not to register the
        // sidecars today -- measured: 451 tiles, no suffixed ones -- but the rule must hold if it
        // ever starts, because nothing would sample them from the atlas then either.
        Set<Identifier> drop = LabPbrSidecarStitchFilter.sidecarsToDrop(List.of(
                id("continuity_reserved/ctm/cobblestone/2"),
                id("continuity_reserved/ctm/cobblestone/2_n"),
                id("continuity_reserved/ctm/cobblestone/2_s")));

        assertEquals(2, drop.size(), "both CTM sidecars are droppable: " + drop);
        assertFalse(drop.contains(id("continuity_reserved/ctm/cobblestone/2")),
                "the tile itself is drawn and must stay");
    }

    @Test
    void aSidecarOfASidecarIsNotInvented() {
        // `stone_n_s` is a real filename shape (a pack tool that suffixed twice). Its base
        // `stone_n` is present, so it IS droppable -- and `stone_n` is droppable on `stone`. Both
        // go, neither is confused for the colour sprite, and the recursion terminates because the
        // rule looks one step back, never transitively.
        Set<Identifier> drop = LabPbrSidecarStitchFilter.sidecarsToDrop(
                List.of(id("block/stone"), id("block/stone_n"), id("block/stone_n_s")));

        assertEquals(Set.of(id("block/stone_n"), id("block/stone_n_s")), drop);
    }
}

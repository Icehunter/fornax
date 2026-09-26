package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.FornaxPackError;
import dev.icehunter.fornax.pack.GraphSpec;
import dev.icehunter.fornax.pack.PackTomlLoader;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A pack that names a player-mirror builtin ({@code builtin.mirrorAlbedo} and its three siblings)
 * must also claim {@code GeometrySlot.PLAYER_MIRROR} with a geometry pass in the same graph, or
 * the reference is refused at load time. {@link dev.icehunter.fornax.pipeline.PlayerMirrorTargets}
 * is allocated only while that slot is claimed, so an unclaimed reference would otherwise resolve
 * to nothing every frame, forever. The closest existing case for this kind of check is the {@code
 * voxelSourceWindow}/{@code voxelEmitterPool} cross-pass requirement in {@link GraphValidator},
 * followed here.
 */
class PlayerMirrorBuiltinValidationTest {

    private static void validate(String resolveInputs, boolean claimTheSlot) {
        validate(resolveInputs, claimTheSlot ? "player_mirror" : null);
    }

    /** {@code claimedSlot} is the slot token a geometry pass claims, or {@code null} to add no
     * claiming pass at all. This lets per-family tests claim the wrong slot on purpose, not only
     * "the matching slot" or "no slot". */
    private static void validate(String resolveInputs, String claimedSlot) {
        String mirrorPass = claimedSlot != null ? """

                [[pass]]
                name = "mirror_geometry"
                type = "geometry"
                slot = "%s"
                program = "shaders/player_mirror"
                """.formatted(claimedSlot) : "";
        GraphSpec g = PackTomlLoader.loadGraph(new StringReader("""
                [[pass]]
                name = "resolve"
                type = "fullscreen"
                shader = "shaders/post/resolve.fsh"
                inputs = [%s]
                outputs = ["builtin.output"]
                %s
                """.formatted(resolveInputs, mirrorPass)), "graph.toml");
        GraphValidator.validate(g, Map.of(), 1920, 1080);
    }

    @Test
    void everyMirrorBuiltinIsRefusedWithoutTheSlotClaimed() {
        for (String builtin : new String[]{"builtin.mirrorAlbedo", "builtin.mirrorNormal",
                "builtin.mirrorMaterial", "builtin.mirrorDepth"}) {
            assertThrows(FornaxPackError.class, () -> validate("\"" + builtin + "\"", false),
                    builtin + " must be refused when no pass claims player_mirror");
        }
    }

    @Test
    void everyMirrorBuiltinIsAcceptedWithTheSlotClaimed() {
        for (String builtin : new String[]{"builtin.mirrorAlbedo", "builtin.mirrorNormal",
                "builtin.mirrorMaterial", "builtin.mirrorDepth"}) {
            assertDoesNotThrow(() -> validate("\"" + builtin + "\"", true),
                    builtin + " must be accepted once a geometry pass claims player_mirror");
        }
    }

    @Test
    void aPassReferencingNoMirrorBuiltinNeedsNoClaim() {
        assertDoesNotThrow(() -> validate("\"builtin.depth\"", false));
    }

    // --- Per-family slot matching: claiming one mirror slot must not satisfy a builtin tied to
    // another slot. ----------------------------------------------------------------------------

    @Test
    void everyXWallBuiltinIsRefusedWithoutTheXSlotClaimed() {
        for (String builtin : new String[]{"builtin.mirrorXAlbedo", "builtin.mirrorXNormal",
                "builtin.mirrorXMaterial", "builtin.mirrorXDepth"}) {
            assertThrows(FornaxPackError.class, () -> validate("\"" + builtin + "\"", (String) null),
                    builtin + " must be refused when no pass claims player_mirror_x");
        }
    }

    @Test
    void everyZWallBuiltinIsAcceptedWithTheZSlotClaimed() {
        for (String builtin : new String[]{"builtin.mirrorZAlbedo", "builtin.mirrorZNormal",
                "builtin.mirrorZMaterial", "builtin.mirrorZDepth"}) {
            assertDoesNotThrow(() -> validate("\"" + builtin + "\"", "player_mirror_z"),
                    builtin + " must be accepted once a geometry pass claims player_mirror_z");
        }
    }

    @Test
    void claimingTheFloorDoesNotSatisfyAWallBuiltinReference() {
        // The case this check catches: PLAYER_MIRROR_BUILTIN_SLOTS must map builtin.mirrorXAlbedo
        // to PLAYER_MIRROR_X specifically, not to any mirror slot. Claiming the floor slot
        // ("player_mirror") must still refuse a reference to the X-wall builtin.
        assertThrows(FornaxPackError.class,
                () -> validate("\"builtin.mirrorXAlbedo\"", "player_mirror"),
                "claiming the floor slot must not satisfy a reference to the X-wall's own builtin");
    }

    @Test
    void claimingTheXWallDoesNotSatisfyAZWallBuiltinReference() {
        assertThrows(FornaxPackError.class,
                () -> validate("\"builtin.mirrorZAlbedo\"", "player_mirror_x"),
                "claiming the X-wall slot must not satisfy a reference to the Z-wall's own builtin");
    }
}

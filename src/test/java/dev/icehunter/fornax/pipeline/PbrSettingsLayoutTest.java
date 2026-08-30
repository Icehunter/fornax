package dev.icehunter.fornax.pipeline;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the {@code u_PbrSettings} std140 contract between Java and GLSL.
 *
 * <p><b>The failure this exists to catch is SILENT.</b> The block's members are matched to Java
 * writes positionally, with nothing but a name to connect them -- and the name exists only on the
 * GLSL side. Insert a member in one place and not the other and BOTH sides still compile, both are
 * individually well-formed, no validation layer objects, and the shader reads its neighbour's float:
 * a POM depth that is really a POM quality, or an exposure that is really a saturation. It renders
 * confidently and wrongly. That is a far worse failure than a crash, and it is the one this file
 * converts into a red test.
 *
 * <p>The Java half of the drift is already gone by construction -- {@code updatePbrSettings()}
 * iterates {@link PbrSettingsLayout#MEMBERS} rather than restating the order -- so what is left to
 * pin is the GLSL half, of which there are three declarations in this repo and one in the Plague
 * pack (asserted by {@code PlaguePackLoadsTest}, which is where the pack-locating logic lives).
 */
class PbrSettingsLayoutTest {

    /**
     * Glass refraction strength has to reach terrain, and has to arrive off with no pack.
     *
     * <p>Unbridged, the identifier is either a link failure or a silent compile-time default: its
     * consumer is terrain.fsh's forward arm, which has no {@code u_PackOptions} block. The fallback
     * is 0 because a non-zero engine default would be Fornax deciding how much a pane bends.
     */
    @Test
    void refractStrengthRidesTheGeometryBridgeAndDefaultsOff() {
        PbrSettingsLayout.Member refract = PbrSettingsLayout.MEMBERS.stream()
                .filter(member -> member.option().equals("u_RefractStrength"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "u_RefractStrength must reach terrain through u_PbrSettings: its only"
                                + " consumer is the forward translucent arm, which has no"
                                + " u_PackOptions block to receive it"));
        assertEquals(0.0f, refract.fallback(),
                "with no pack loaded the engine must not refract on its own");
        assertEquals(PbrSettingsLayout.MEMBERS.getLast(), refract,
                "APPEND ONLY: u_RefractStrength must stay last, or every short-prefix declaration"
                        + " of this block shifts by one float");
    }

    @Test
    void waveSpeedRidesTheGeometryBridgeAtNeutralFallback() {
        PbrSettingsLayout.Member waveSpeed = PbrSettingsLayout.MEMBERS.stream()
                .filter(member -> member.option().equals("u_WaveSpeed"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "u_WaveSpeed must reach terrain through u_PbrSettings so surface waves and"
                                + " volumetric shaft projection share one runtime clock"));
        assertEquals(1.0f, waveSpeed.fallback());
    }

    @Test
    void retiredWaterMaterialControlsDoNotRideTheGeometryBridge() {
        List<String> names = PbrSettingsLayout.MEMBERS.stream()
                .map(PbrSettingsLayout.Member::option).toList();
        assertFalse(names.contains("u_WaterSurfaceMaterial"));
        assertFalse(names.contains("u_WaterMaterialScale"));
        assertFalse(names.contains("u_WaterMaterialStrength"));
    }

    @Test
    void memberNamesAreUniqueAndNonEmpty() {
        Set<String> seen = new HashSet<>();
        for (PbrSettingsLayout.Member member : PbrSettingsLayout.MEMBERS) {
            assertFalse(member.option().isBlank(), "a u_PbrSettings member has a blank option name");
            assertTrue(seen.add(member.option()),
                    "u_PbrSettings declares " + member.option() + " twice -- the second write would"
                            + " land at a different offset from the one the shader reads");
        }
        assertFalse(PbrSettingsLayout.MEMBERS.isEmpty());
    }

    /**
     * The buffer must be big enough for every member, and 16-byte aligned.
     *
     * <p>Sized too small, the writes past the end are what a mapped {@code MappableRingBuffer} does
     * with an overrun -- not an exception. The literal this replaced was {@code 32}, exactly right
     * for the eight members the block had at the time and silently wrong for the ninth.
     */
    @Test
    void sizeCoversEveryMemberAndIsAligned() {
        assertTrue(PbrSettingsLayout.SIZE_BYTES >= PbrSettingsLayout.MEMBERS.size() * Float.BYTES,
                "u_PbrSettings buffer is " + PbrSettingsLayout.SIZE_BYTES + " bytes but "
                        + PbrSettingsLayout.MEMBERS.size() + " floats need "
                        + (PbrSettingsLayout.MEMBERS.size() * Float.BYTES));
        assertEquals(0, PbrSettingsLayout.SIZE_BYTES % 16,
                "a uniform block's size must be a multiple of 16 under std140");
    }

    /**
     * Fornax's OWN built-in fallback terrain shader, and the bundled test-pack fixtures, must each
     * declare a legal PREFIX of the layout.
     *
     * <p>Short declarations are correct and deliberate -- no member carries an explicit
     * {@code layout(offset=)}, so a shader that needs only the first two members may declare only
     * those two, and appending to the layout leaves it untouched. What is NOT legal is a prefix that
     * disagrees on a NAME or on ORDER, because then the shader reads the wrong offset. This asserts
     * exactly that distinction, and unlike the Plague assertion it can never skip: these files ship
     * inside this repo.
     */
    @Test
    void everyBundledDeclarationIsAPrefixOfTheLayout() throws Exception {
        List<String> expected = PbrSettingsLayout.MEMBERS.stream().map(PbrSettingsLayout.Member::option).toList();

        List<Path> declarations = List.of(
                Path.of("src/main/resources/assets/fornax/shaders/blocks/terrain.fsh"),
                Path.of("src/test/resources/packs/sample_pack/shaders/blocks/terrain.fsh"));

        List<String> checked = new ArrayList<>();
        for (Path shader : declarations) {
            assertTrue(Files.isRegularFile(shader), "expected to find " + shader);
            List<String> members = PbrSettingsBlockParser.membersOf(shader);
            if (members.isEmpty()) {
                continue;
            }
            checked.add(shader.toString());
            assertTrue(members.size() <= expected.size(),
                    shader + " declares " + members.size() + " u_PbrSettings members but the layout"
                            + " defines only " + expected.size());
            assertEquals(expected.subList(0, members.size()), members,
                    shader + " declares u_PbrSettings members that are not a prefix of"
                            + " PbrSettingsLayout.MEMBERS. std140 matches Java writes to GLSL members"
                            + " BY POSITION, so this shader is reading a different float from the one"
                            + " Fornax writes -- and it compiles cleanly either way.");
        }
        assertFalse(checked.isEmpty(),
                "no bundled shader was found declaring u_PbrSettings, so this test asserted nothing"
                        + " -- the parser or the paths above have gone stale");
    }
}

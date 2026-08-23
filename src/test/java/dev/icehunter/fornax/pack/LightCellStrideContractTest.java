package dev.icehunter.fornax.pack;

import dev.icehunter.fornax.voxel.BrickGridUpload;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightCellStrideContractTest {
    private static String branches(String name, int highValue, int standardValue) {
        return "#if LIGHT_CELL_DETAIL == 1\n"
                + "const int " + name + " = " + highValue + ";                      // == LIGHT_CELLS_PER_SECTION_AXIS_HIGH\n"
                + "#else\n"
                + "const int " + name + " = " + standardValue + ";                       // == LIGHT_CELLS_PER_SECTION_AXIS_STANDARD\n"
                + "#endif\n";
    }

    @Test
    void acceptsAShaderMirroringTheCurrentValuesAtBothTiers() {
        Map<String, String> sources = Map.of(
                "shaders/compute/light_inject.comp",
                branches("CELLS_PER_AXIS", BrickGridUpload.LIGHT_CELLS_PER_SECTION_AXIS_HIGH,
                        BrickGridUpload.LIGHT_CELLS_PER_SECTION_AXIS_STANDARD));
        assertDoesNotThrow(() -> LightCellStrideContract.validate(sources));
    }

    @Test
    void acceptsTheElPrefixedGbufferResolveMirror() {
        Map<String, String> sources = Map.of(
                "shaders/post/gbuffer_resolve.fsh",
                branches("EL_CELLS_PER_AXIS", BrickGridUpload.LIGHT_CELLS_PER_SECTION_AXIS_HIGH,
                        BrickGridUpload.LIGHT_CELLS_PER_SECTION_AXIS_STANDARD));
        assertDoesNotThrow(() -> LightCellStrideContract.validate(sources));
    }

    @Test
    void rejectsAStaleHighBranch() {
        int stale = BrickGridUpload.LIGHT_CELLS_PER_SECTION_AXIS_HIGH + 8; // e.g. 24 instead of 16
        Map<String, String> sources = Map.of(
                "shaders/compute/light_inject.comp",
                branches("CELLS_PER_AXIS", stale, BrickGridUpload.LIGHT_CELLS_PER_SECTION_AXIS_STANDARD));

        FornaxPackError error =
                assertThrows(FornaxPackError.class, () -> LightCellStrideContract.validate(sources));
        assertTrue(error.getMessage().contains(String.valueOf(stale)),
                "error names the stale High-branch value the shader declared: " + error.getMessage());
    }

    @Test
    void rejectsAStaleStandardBranch() {
        int stale = BrickGridUpload.LIGHT_CELLS_PER_SECTION_AXIS_STANDARD + 8; // e.g. 16 instead of 8
        Map<String, String> sources = Map.of(
                "shaders/compute/light_propagate.comp",
                branches("CELLS_PER_AXIS", BrickGridUpload.LIGHT_CELLS_PER_SECTION_AXIS_HIGH, stale));

        FornaxPackError error =
                assertThrows(FornaxPackError.class, () -> LightCellStrideContract.validate(sources));
        assertTrue(error.getMessage().contains(String.valueOf(stale)),
                "error names the stale Standard-branch value the shader declared: " + error.getMessage());
    }

    @Test
    void catchesAStaleInactiveBranchRegardlessOfWhichTierIsCurrentlySelected() {
        // The whole point of validating BOTH branches unconditionally: a shader author fixes the
        // High branch but leaves Standard stale (or vice versa) -- this must fail at load time, not
        // silently wait for a user to switch tiers and get garbage with no correlated code change.
        Map<String, String> highBranchStale = Map.of(
                "shaders/compute/light_inject.comp",
                branches("CELLS_PER_AXIS", 12, BrickGridUpload.LIGHT_CELLS_PER_SECTION_AXIS_STANDARD));
        assertThrows(FornaxPackError.class, () -> LightCellStrideContract.validate(highBranchStale));

        Map<String, String> standardBranchStale = Map.of(
                "shaders/compute/light_inject.comp",
                branches("CELLS_PER_AXIS", BrickGridUpload.LIGHT_CELLS_PER_SECTION_AXIS_HIGH, 12));
        assertThrows(FornaxPackError.class, () -> LightCellStrideContract.validate(standardBranchStale));
    }

    @Test
    void ignoresShadersThatDoNotDeclareTheBranchedConstant() {
        Map<String, String> sources = Map.of(
                "shaders/post/ssao.fsh", "const int SAMPLES = 16;\nvoid main() {}\n");
        assertDoesNotThrow(() -> LightCellStrideContract.validate(sources));
    }

    @Test
    void ignoresAnUnconditionalDeclarationWithNoBranches() {
        // A shader that hasn't adopted the LIGHT_CELL_DETAIL branch shape at all (the pre-tier form)
        // doesn't match this pattern and is silently skipped -- same "no match, no validation" behavior
        // PaletteStrideContract has for shaders that don't touch the palette. Guards against a false
        // positive on old fixture/test text, not a substitute for adopting the branch.
        Map<String, String> sources =
                Map.of("shaders/compute/light_inject.comp", "const int CELLS_PER_AXIS = 8;\n");
        assertDoesNotThrow(() -> LightCellStrideContract.validate(sources));
    }

    @Test
    void toleratesTheWhitespaceAndCommentVariationsTheRealMirrorsUse() {
        int hi = BrickGridUpload.LIGHT_CELLS_PER_SECTION_AXIS_HIGH;
        int std = BrickGridUpload.LIGHT_CELLS_PER_SECTION_AXIS_STANDARD;
        Map<String, String> sources = Map.of(
                "a.comp", "#if LIGHT_CELL_DETAIL == 1\nconst int CELLS_PER_AXIS = " + hi + ";\n#else\n"
                        + "const int CELLS_PER_AXIS = " + std + ";\n#endif\n",
                "b.comp", "#if  LIGHT_CELL_DETAIL==1\nconst  int   CELLS_PER_AXIS=" + hi + " ;   // hi\n#else\n"
                        + "const  int   CELLS_PER_AXIS=" + std + " ;   // std\n#endif\n");
        assertDoesNotThrow(() -> LightCellStrideContract.validate(sources));
    }

    @Test
    void requiresBothBranchesToNameTheSameConstant() {
        // A malformed/mismatched pair (High branch names a different constant than Standard) must not
        // false-positive-match as if it were a valid pair -- the backreference in the pattern means
        // this simply doesn't match at all (silently skipped, same as any other non-conforming text),
        // rather than cross-wiring two unrelated declarations into one bogus "pair."
        Map<String, String> sources = Map.of("weird.comp",
                "#if LIGHT_CELL_DETAIL == 1\nconst int CELLS_PER_AXIS = 99;\n#else\n"
                        + "const int EL_CELLS_PER_AXIS = 99;\n#endif\n");
        assertDoesNotThrow(() -> LightCellStrideContract.validate(sources));
    }
}

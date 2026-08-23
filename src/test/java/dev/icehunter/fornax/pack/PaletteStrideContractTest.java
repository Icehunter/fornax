package dev.icehunter.fornax.pack;

import dev.icehunter.fornax.voxel.BrickGridUpload;
import dev.icehunter.fornax.voxel.SectionHarvester;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaletteStrideContractTest {
    private static final String ENGINE_RAYMARCH =
            "/assets/fornax/shaders_engine/voxel_debug_raymarch.comp";

    @Test
    void rejectsAShaderMirroringAStaleStride() {
        int stale = BrickGridUpload.PALETTE_ENTRY_WORDS - 16;
        Map<String, String> sources = Map.of(
                "shaders/post/celestial_shadow.fsh", "const int PALETTE_ENTRY_WORDS = " + stale + ";\n");

        FornaxPackError error =
                assertThrows(FornaxPackError.class, () -> PaletteStrideContract.validate(sources));
        assertTrue(error.getMessage().contains(String.valueOf(stale)),
                "error names the stale value the shader declared: " + error.getMessage());
    }

    @Test
    void acceptsAShaderMirroringTheCurrentStride() {
        Map<String, String> sources = Map.of(
                "shaders/post/celestial_shadow.fsh",
                "const int PALETTE_ENTRY_WORDS = " + BrickGridUpload.PALETTE_ENTRY_WORDS + ";\n");
        assertDoesNotThrow(() -> PaletteStrideContract.validate(sources));
    }

    @Test
    void ignoresShadersThatDoNotTouchThePalette() {
        Map<String, String> sources = Map.of(
                "shaders/post/ssao.fsh", "const int SAMPLES = 16;\nvoid main() {}\n");
        assertDoesNotThrow(() -> PaletteStrideContract.validate(sources));
    }

    @Test
    void toleratesTheWhitespaceVariationsTheRealMirrorsUse() {
        // The four real mirrors are not formatted identically (trailing comments, extra spacing before
        // the comment). The pattern must match all of them, or the guard silently checks nothing.
        int n = BrickGridUpload.PALETTE_ENTRY_WORDS;
        Map<String, String> sources = Map.of(
                "a.comp", "const int PALETTE_ENTRY_WORDS = " + n + ";",
                "b.comp", "const int PALETTE_ENTRY_WORDS = " + n + ";    // == BrickGridUpload",
                "c.comp", "const  int   PALETTE_ENTRY_WORDS=" + n + " ;");
        assertDoesNotThrow(() -> PaletteStrideContract.validate(sources));

        Map<String, String> stale = Map.of("c.comp", "const  int   PALETTE_ENTRY_WORDS=" + (n - 16) + " ;");
        assertThrows(FornaxPackError.class, () -> PaletteStrideContract.validate(stale));
    }

    @Test
    void engineOwnRaymarchShaderMirrorsTheCurrentStride() {
        // PaletteStrideContract only validates PACK sources; the engine's own compute shader ships in
        // this jar and so is covered here instead. Same failure mode if it drifts: wrong entry, silent
        // garbage in the voxel debug view.
        String source = readEngineResource(ENGINE_RAYMARCH);
        Matcher matcher =
                Pattern.compile("const\\s+int\\s+PALETTE_ENTRY_WORDS\\s*=\\s*(\\d+)\\s*;").matcher(source);
        assertTrue(matcher.find(), "engine raymarch shader declares PALETTE_ENTRY_WORDS");
        assertEquals(BrickGridUpload.PALETTE_ENTRY_WORDS, Integer.parseInt(matcher.group(1)),
                ENGINE_RAYMARCH + " must mirror BrickGridUpload.PALETTE_ENTRY_WORDS");
    }

    @Test
    void engineOwnRaymarchShaderMirrorsTheCurrentEntriesPerSlot() {
        // The engine raymarch shader spells the entries-per-slot term as its own named constant
        // (PALETTE_ENTRIES_PER_SLOT), unlike the three real pack mirrors below which inline the literal
        // as PALETTE_ENTRY_WORDS * N -- so this needs its own pattern, same as PALETTE_ENTRY_WORDS above
        // has its own dedicated test rather than routing through PaletteStrideContract (out of scope --
        // see that class's javadoc).
        String source = readEngineResource(ENGINE_RAYMARCH);
        Matcher matcher =
                Pattern.compile("const\\s+int\\s+PALETTE_ENTRIES_PER_SLOT\\s*=\\s*(\\d+)\\s*;").matcher(source);
        assertTrue(matcher.find(), "engine raymarch shader declares PALETTE_ENTRIES_PER_SLOT");
        assertEquals(SectionHarvester.MAX_PALETTE_ENTRIES, Integer.parseInt(matcher.group(1)),
                ENGINE_RAYMARCH + " must mirror SectionHarvester.MAX_PALETTE_ENTRIES");
    }

    @Test
    void rejectsAShaderMirroringAStaleWordsPerSlotTerm() {
        int stale = SectionHarvester.MAX_PALETTE_ENTRIES + 160; // e.g. the old 256 once the engine is 96
        Map<String, String> sources = Map.of("shaders/post/celestial_shadow.fsh",
                "const int PALETTE_ENTRY_WORDS = " + BrickGridUpload.PALETTE_ENTRY_WORDS + ";\n"
                        + "const int PALETTE_WORDS_PER_SLOT = PALETTE_ENTRY_WORDS * " + stale + ";\n");

        FornaxPackError error =
                assertThrows(FornaxPackError.class, () -> PaletteStrideContract.validate(sources));
        assertTrue(error.getMessage().contains(String.valueOf(stale)),
                "error names the stale entries-per-slot value the shader declared: " + error.getMessage());
    }

    @Test
    void acceptsAShaderMirroringTheCurrentWordsPerSlotTerm() {
        Map<String, String> sources = Map.of("shaders/post/celestial_shadow.fsh",
                "const int PALETTE_ENTRY_WORDS = " + BrickGridUpload.PALETTE_ENTRY_WORDS + ";\n"
                        + "const int PALETTE_WORDS_PER_SLOT = PALETTE_ENTRY_WORDS * "
                        + SectionHarvester.MAX_PALETTE_ENTRIES + ";\n");
        assertDoesNotThrow(() -> PaletteStrideContract.validate(sources));
    }

    @Test
    void toleratesTheWhitespaceVariationsTheRealWordsPerSlotMirrorsUse() {
        // The three real mirrors (celestial_shadow.fsh, voxel_water_refl.comp, light_inject.comp) are
        // not formatted identically (trailing comments, extra spacing). The pattern must match all of
        // them, or the guard silently checks nothing -- same precedent as the PALETTE_ENTRY_WORDS
        // whitespace test above.
        int n = SectionHarvester.MAX_PALETTE_ENTRIES;
        Map<String, String> sources = Map.of(
                "a.comp", "const int PALETTE_WORDS_PER_SLOT = PALETTE_ENTRY_WORDS * " + n + ";",
                "b.comp", "const int PALETTE_WORDS_PER_SLOT = PALETTE_ENTRY_WORDS * " + n
                        + ";    // == BrickGridUpload",
                "c.comp", "const  int   PALETTE_WORDS_PER_SLOT=PALETTE_ENTRY_WORDS  *  " + n + " ;");
        assertDoesNotThrow(() -> PaletteStrideContract.validate(sources));

        Map<String, String> stale =
                Map.of("c.comp", "const  int   PALETTE_WORDS_PER_SLOT=PALETTE_ENTRY_WORDS  *  " + (n + 160) + " ;");
        assertThrows(FornaxPackError.class, () -> PaletteStrideContract.validate(stale));
    }

    private static String readEngineResource(String path) {
        try (InputStream in = PaletteStrideContractTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "engine resource missing: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("could not read " + path, e);
        }
    }
}

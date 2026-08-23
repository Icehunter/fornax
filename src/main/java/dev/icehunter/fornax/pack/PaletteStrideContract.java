package dev.icehunter.fornax.pack;

import dev.icehunter.fornax.voxel.BrickGridUpload;
import dev.icehunter.fornax.voxel.SectionHarvester;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Load-time validation that every pack shader mirroring the brick-grid palette stride agrees with the
 * values {@link BrickGridUpload} and {@link SectionHarvester} actually pack with.
 *
 * <p>The palette is a fixed-stride table: a shader resolves an entry as
 * {@code slot * PALETTE_ENTRY_WORDS * MAX_PALETTE_ENTRIES + index * PALETTE_ENTRY_WORDS}, so BOTH the
 * per-entry word count and the entries-per-slot count are baked into the addressing math of every
 * consumer. GLSL cannot see Java's constants, so each consumer hand-mirrors
 * {@code const int PALETTE_ENTRY_WORDS = N;} and, as the entries-per-slot term of a
 * {@code PALETTE_WORDS_PER_SLOT} declaration, {@code SectionHarvester.MAX_PALETTE_ENTRIES} itself --
 * real pack mirrors write this as {@code const int PALETTE_WORDS_PER_SLOT =
 * PALETTE_ENTRY_WORDS * N;}. The entries-per-slot term moved 256 -> 96; see {@code
 * SectionHarvester.MAX_PALETTE_ENTRIES}'s own doc for the census data behind it. A mirror left
 * stale does not fail, it silently reads a different entry than the one Java wrote and renders
 * plausible-looking garbage (wrong shadow cutouts, wrong injected light colors) with no error
 * anywhere -- which is why this check exists instead of trusting mirrors kept correct by hand.
 *
 * <p>The engine and a pack are separate repos that deploy independently, so a jar carrying a new
 * stride can meet a pack still on the old one at any time. This check turns that skew into a
 * {@link FornaxPackError} at pack load/apply, where the UI can show it, exactly as {@link
 * ShaderImports} does for unresolvable imports.
 *
 * <p>Scope note: this validates PACK sources. The engine's own
 * {@code shaders_engine/voxel_debug_raymarch.comp} ships inside this jar and so cannot skew against it;
 * its mirror is covered by a unit test instead.
 */
public final class PaletteStrideContract {
    /** Matches the hand-mirrored {@code PALETTE_ENTRY_WORDS} declaration, tolerating the whitespace
     * variations across the mirrors. */
    private static final Pattern ENTRY_WORDS_DECL =
            Pattern.compile("const\\s+int\\s+PALETTE_ENTRY_WORDS\\s*=\\s*(\\d+)\\s*;");

    /** Matches the hand-mirrored entries-per-slot term of {@code PALETTE_WORDS_PER_SLOT}, in the exact
     * form the three real pack mirrors use: {@code const int PALETTE_WORDS_PER_SLOT =
     * PALETTE_ENTRY_WORDS * N;}. Deliberately anchored to the {@code PALETTE_ENTRY_WORDS * N} shape
     * (not just any {@code PALETTE_WORDS_PER_SLOT = N}) so this can never accidentally match something
     * else entirely and validate the wrong number. */
    private static final Pattern WORDS_PER_SLOT_DECL = Pattern.compile(
            "const\\s+int\\s+PALETTE_WORDS_PER_SLOT\\s*=\\s*PALETTE_ENTRY_WORDS\\s*\\*\\s*(\\d+)\\s*;");

    private PaletteStrideContract() {}

    /**
     * @param sources pack shader sources keyed pack-root-relative ("shaders/post/celestial_shadow.fsh"),
     *                the same map {@link ShaderImports#validate} takes
     */
    public static void validate(Map<String, String> sources) {
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Matcher entryWordsMatcher = ENTRY_WORDS_DECL.matcher(source.getValue());
            while (entryWordsMatcher.find()) {
                int mirrored = Integer.parseInt(entryWordsMatcher.group(1));
                if (mirrored != BrickGridUpload.PALETTE_ENTRY_WORDS) {
                    throw new FornaxPackError(source.getKey(), "PALETTE_ENTRY_WORDS",
                            "declares PALETTE_ENTRY_WORDS = " + mirrored + ", but this engine packs the "
                                    + "brick-grid palette at " + BrickGridUpload.PALETTE_ENTRY_WORDS
                                    + " words per entry (layout version "
                                    + BrickGridUpload.PALETTE_LAYOUT_VERSION + "). Every palette read in "
                                    + "this shader would address the wrong entry and render silent "
                                    + "garbage. Update the pack to match this engine version.");
                }
            }

            Matcher wordsPerSlotMatcher = WORDS_PER_SLOT_DECL.matcher(source.getValue());
            while (wordsPerSlotMatcher.find()) {
                int mirrored = Integer.parseInt(wordsPerSlotMatcher.group(1));
                if (mirrored != SectionHarvester.MAX_PALETTE_ENTRIES) {
                    throw new FornaxPackError(source.getKey(), "PALETTE_WORDS_PER_SLOT",
                            "declares PALETTE_WORDS_PER_SLOT = PALETTE_ENTRY_WORDS * " + mirrored
                                    + ", but this engine packs " + SectionHarvester.MAX_PALETTE_ENTRIES
                                    + " palette entries per slot. Every palette read in this shader would "
                                    + "address the wrong slot and render silent garbage. Update the pack "
                                    + "to match this engine version.");
                }
            }
        }
    }
}

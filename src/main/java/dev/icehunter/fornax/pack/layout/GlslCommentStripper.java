package dev.icehunter.fornax.pack.layout;

/**
 * Removes GLSL comments and the blank space they leave behind, restoring original line numbers with
 * {@code #line} directives.
 *
 * <p><b>The defect this exists for.</b> {@link RuntimeShaderPack} registers into the client's real
 * {@code PackRepository}, so vanilla's {@code ShaderManager} enumerates our sources every resource
 * reload and hands them to Mojang's {@code GlslPreprocessor}. Before each {@code #moj_import} it
 * calls {@code isDirectiveDisabled} to decide whether that directive is commented out, using:
 *
 * <pre>
 *   (?:^|\v)(?:\s|/\*(?:[^*]|\*+[^*&#47;])*\*+/|(//[^\v]*))*\z
 * </pre>
 *
 * <p>That is a quantified group wrapping an alternation, and Java's matcher recurses ONCE PER
 * ITERATION of it -- which is why the crash stack is tens of thousands of
 * {@code Loop}/{@code Branch}/{@code GroupHead}/{@code GroupTail} frames ending in
 * {@code isDirectiveDisabled}. The reload task dies, and Minecraft's recovery is to drop every
 * selected resource pack, so a shader-preprocessing limit presented as "resource failed to load"
 * and cost the user their texture pack on every launch.
 *
 * <p><b>Why comments are removed rather than blanked out.</b> The regex's comment alternative
 * consumes a whole comment block in ONE iteration, while {@code \s} matches ONE CHARACTER per
 * iteration. Replacing comment bytes with equivalent whitespace would turn a few hundred iterations
 * into a hundred thousand for a large comment block -- fewer bytes, far deeper recursion. The cost
 * here is iteration count, not source size.
 *
 * <p><b>What this does instead.</b> Comments are removed AND the blank lines they leave are dropped,
 * so the run of whitespace this regex must walk before any directive is as short as possible. Line
 * numbers survive through {@code #line} directives -- the same mechanism Mojang's own preprocessor
 * uses when it splices imports. Line numbers are not optional: a GLSL compile error reports one, and
 * {@code check_shaders.sh}, the pack-load tests and Fornax's own pass-failure logging all assume it
 * indexes the file as written.
 *
 * <p>Fornax's own paths ({@code sourceOrNull}, {@code sourcesSnapshot}) keep the UNSTRIPPED text, so
 * compute-pass compilation and {@code VanillaShaderOverrides.extract} see the source as authored.
 */
public final class GlslCommentStripper {

    private GlslCommentStripper() {}

    /**
     * @param source GLSL text
     * @return the text with comments and the resulting blank lines removed, and {@code #line}
     *         directives inserted wherever lines were dropped so reported line numbers still match
     *         the original file
     */
    public static String strip(String source) {
        String[] lines = source.split("\n", -1);
        StringBuilder out = new StringBuilder(source.length() / 3);
        boolean inBlock = false;
        // Original 1-based line number that the NEXT emitted line will correspond to if we emit it
        // without a #line directive. Starts at 1; every emitted line advances it by one.
        int expected = 1;
        boolean emittedAny = false;

        for (int i = 0; i < lines.length; i++) {
            int lineNo = i + 1;
            StringBuilder kept = new StringBuilder(lines[i].length());
            String line = lines[i];
            for (int c = 0; c < line.length(); c++) {
                char ch = line.charAt(c);
                if (inBlock) {
                    if (ch == '*' && c + 1 < line.length() && line.charAt(c + 1) == '/') {
                        inBlock = false;
                        c++;
                    }
                    continue;
                }
                if (ch == '/' && c + 1 < line.length()) {
                    char next = line.charAt(c + 1);
                    if (next == '/') {
                        break; // rest of the line is a comment
                    }
                    if (next == '*') {
                        inBlock = true;
                        c++;
                        continue;
                    }
                }
                kept.append(ch);
            }

            String text = kept.toString();
            if (text.isBlank()) {
                continue; // dropped: this is the whitespace the regex would otherwise have to walk
            }

            // #version must be the very first thing in the source, so never precede it with #line.
            boolean isVersion = text.stripLeading().startsWith("#version");
            if (!isVersion && lineNo != expected) {
                // GLSL: after `#line N`, the following line is line N.
                out.append("#line ").append(lineNo).append('\n');
                expected = lineNo;
            }
            out.append(text).append('\n');
            expected++;
            emittedAny = true;
        }

        if (!emittedAny) {
            return "";
        }
        return out.toString();
    }
}

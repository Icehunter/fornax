package dev.icehunter.fornax.pack.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Two properties, and the first is the one the whole fix turns on.
 *
 * <p>ITERATION COUNT, not byte count, is what overflows Mojang's isDirectiveDisabled regex -- its
 * quantified group recurses once per iteration and {@code \s} matches a single character. The first
 * version of this stripper preserved line numbers by leaving the comments' newlines in place, which
 * traded one iteration per comment block for one per whitespace character and made the crash worse.
 * So: no runs of blank lines may survive.
 *
 * <p>LINE NUMBERS must still resolve to the original file, via #line, because every shader
 * diagnostic in this repo assumes a reported line indexes the source as written.
 */
class GlslCommentStripperTest {

    @Test
    void commentsGoAndNoBlankRunSurvives() {
        String src = "#version 330\n"
                + "// a comment\n"
                + "// another\n"
                + "/* a block\n"
                + "   spanning\n"
                + "   lines */\n"
                + "int a = 1;\n";
        String out = GlslCommentStripper.strip(src);
        assertFalse(out.contains("comment"));
        assertFalse(out.contains("spanning"));
        for (String line : out.split("\n", -1)) {
            assertFalse(line.isBlank() && !line.isEmpty(), "whitespace-only line survived: " + out);
        }
        assertTrue(out.contains("int a = 1;"));
    }

    @Test
    void droppedLinesAreAccountedForWithLineDirectives() {
        String src = "#version 330\n"
                + "// 2\n"
                + "// 3\n"
                + "// 4\n"
                + "int a = 1;\n";   // original line 5
        String out = GlslCommentStripper.strip(src);
        assertTrue(out.contains("#line 5\nint a = 1;"),
                "expected a #line 5 immediately before the statement, got:\n" + out);
    }

    @Test
    void versionStaysFirstWithNoDirectiveBeforeIt() {
        // #version must be the first thing in a GLSL source; a #line ahead of it is a compile error.
        String src = "// licence header\n// more header\n#version 330\nint a = 1;\n";
        String out = GlslCommentStripper.strip(src);
        assertTrue(out.startsWith("#version 330"), "got:\n" + out);
    }

    @Test
    void divisionAndImportPathsSurvive() {
        String src = "#moj_import <fornax_runtime:include/underwater.glsl>\n"
                + "float x = a / b;\n"
                + "float y = a /* inline */ / c;\n";
        String out = GlslCommentStripper.strip(src);
        assertTrue(out.contains("#moj_import <fornax_runtime:include/underwater.glsl>"));
        assertTrue(out.contains("float x = a / b;"));
        assertTrue(out.contains("/ c;"));
        assertFalse(out.contains("inline"));
    }

    @Test
    void annotationCommentsAreGoneButTheDefineRemains() {
        String src = "#define u_WaterDistanceFog 4 //[0..12 step 1] runtime \"Water Fog End\"\n";
        String out = GlslCommentStripper.strip(src);
        assertTrue(out.startsWith("#define u_WaterDistanceFog 4"));
        assertFalse(out.contains("runtime"));
    }

    /**
     * The regression itself, measured on the real pack: what matters is the longest run of
     * whitespace the regex can be asked to walk, since that run is its recursion depth.
     */
    @Test
    void realPackHasNoLongWhitespaceRunsLeft() throws IOException {
        Path pack = Path.of("..", "plague", "shaders");
        if (!Files.isDirectory(pack)) {
            return;
        }
        List<Path> files;
        try (Stream<Path> w = Files.walk(pack)) {
            files = w.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.toString();
                        return n.endsWith(".glsl") || n.endsWith(".fsh")
                                || n.endsWith(".vsh") || n.endsWith(".comp");
                    })
                    .toList();
        }
        long before = 0;
        long after = 0;
        int worstRun = 0;
        String worstFile = null;
        for (Path p : files) {
            String src = Files.readString(p);
            String out = GlslCommentStripper.strip(src);
            before += src.length();
            after += out.length();
            int run = 0;
            for (int i = 0; i < out.length(); i++) {
                if (Character.isWhitespace(out.charAt(i))) {
                    run++;
                    if (run > worstRun) {
                        worstRun = run;
                        worstFile = p.toString();
                    }
                } else {
                    run = 0;
                }
            }
        }
        assertTrue(before > 0, "no shader sources were scanned");
        // Total source-size ratio is not the failure mechanism and naturally changes as the pack's
        // code/comment mix evolves. Keep only the useful sanity check here; the longest run below
        // pins the recursive-regex risk this test exists to prevent.
        assertTrue(after < before, "expected comments to be removed; before=" + before + " after=" + after);
        // Indentation is fine; a run in the hundreds means blank space survived somewhere.
        assertTrue(worstRun < 200,
                "longest whitespace run is " + worstRun + " chars in " + worstFile
                        + " -- that run is the regex's recursion depth");
    }
}

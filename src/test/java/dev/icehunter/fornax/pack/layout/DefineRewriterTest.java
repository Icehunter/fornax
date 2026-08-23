package dev.icehunter.fornax.pack.layout;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DefineRewriterTest {
    @Test
    void compileValueIsSubstitutedIntoTheDefineLine() {
        String source = "#define SSR_QUALITY 1 //[0 1 2] compile \"Reflections\"";

        String rewritten = DefineRewriter.rewrite(source, Map.of(), Map.of("SSR_QUALITY", "2"));

        assertEquals("#define SSR_QUALITY 2 //[0 1 2] compile \"Reflections\"", rewritten);
    }

    @Test
    void commentedBooleanIsUncommentedWhenTurnedOn() {
        String source = "// #define VIGNETTE //[] compile \"Vignette\"";

        String rewritten = DefineRewriter.rewrite(source, Map.of(), Map.of("VIGNETTE", "1"));

        assertEquals("#define VIGNETTE //[] compile \"Vignette\"", rewritten);
    }

    @Test
    void commentedBooleanStaysCommentedWhenTurnedOff() {
        String source = "// #define VIGNETTE //[] compile \"Vignette\"";

        String rewritten = DefineRewriter.rewrite(source, Map.of(), Map.of("VIGNETTE", "0"));

        assertEquals(source, rewritten);
    }

    @Test
    void runtimeDefineLineIsReplacedWithAComment() {
        String source = "#define SSAO_RADIUS 2.0 //[0.5..4.0 step 0.1] runtime \"SSAO Radius\"";

        String rewritten = DefineRewriter.rewrite(source, Map.of(), Map.of());

        assertFalse(rewritten.contains("#define"));
        assertTrue(rewritten.startsWith("//"));
        assertTrue(rewritten.contains("SSAO_RADIUS"));
    }

    @Test
    void linesWithoutAnnotationsPassThroughUnchanged() {
        String source = String.join("\n",
                "#version 150",
                "vec3 color = texture(u_Albedo, v_TexCoord).rgb;",
                "// just a plain comment",
                "");

        String rewritten = DefineRewriter.rewrite(source, Map.of(), Map.of());

        assertEquals(source, rewritten);
    }

    @Test
    void multiLineSourceRewritesOnlyAnnotatedLines() {
        String source = String.join("\n",
                "#version 150",
                "#define SSR_QUALITY 1 //[0 1 2] compile \"Reflections\"",
                "vec3 color = vec3(0.0);",
                "#define SSAO_RADIUS 2.0 //[0.5..4.0 step 0.1] runtime \"SSAO Radius\"");

        String rewritten = DefineRewriter.rewrite(source, Map.of(), Map.of("SSR_QUALITY", "0"));
        String[] lines = rewritten.split("\n", -1);

        assertEquals("#version 150", lines[0]);
        assertEquals("#define SSR_QUALITY 0 //[0 1 2] compile \"Reflections\"", lines[1]);
        assertEquals("vec3 color = vec3(0.0);", lines[2]);
        assertFalse(lines[3].contains("#define"));
    }
}

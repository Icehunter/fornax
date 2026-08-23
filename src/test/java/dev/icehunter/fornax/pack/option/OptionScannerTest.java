package dev.icehunter.fornax.pack.option;

import dev.icehunter.fornax.pack.FornaxPackError;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class OptionScannerTest {
    @Test
    void scansAcrossFilesDeclarationOrder() {
        Map<String, String> src = new LinkedHashMap<>();
        src.put("a.fsh", "#define A 1 //[0 1] compile \"A\"\n#define B 2.0 //[0..4] runtime \"B\"\n");
        src.put("b.fsh", "#define C 3.0 //[0..9] runtime \"C\"\n");
        Map<String, PackOption> out = OptionScanner.scan(src);
        assertEquals(java.util.List.of("A", "B", "C"), java.util.List.copyOf(out.keySet()));
    }

    @Test
    void agreeingDuplicatesMerge() {
        Map<String, String> src = new LinkedHashMap<>();
        src.put("a.fsh", "#define A 1 //[0 1] compile \"A\"\n");
        src.put("b.fsh", "#define A 1 //[0 1] compile \"A\"\n");
        assertEquals(1, OptionScanner.scan(src).size());
    }

    @Test
    void conflictingDuplicatesThrow() {
        Map<String, String> src = new LinkedHashMap<>();
        src.put("a.fsh", "#define A 1 //[0 1] compile \"A\"\n");
        src.put("b.fsh", "#define A 2 //[0 1 2] runtime \"A\"\n");
        FornaxPackError e = assertThrows(FornaxPackError.class, () -> OptionScanner.scan(src));
        assertEquals("A", e.key());
        assertTrue(e.reason().toLowerCase().contains("conflict"));
    }

    @Test
    void malformedAnnotationReportsFileAndLine() {
        Map<String, String> src = new LinkedHashMap<>();
        src.put("a.fsh", "// fine\n#define OK 1 //[0 1] compile \"OK\"\n");
        src.put("b.fsh", "vec3 c;\n#define BAD 1 //[0 1] \"Bad\"\n");
        FornaxPackError e = assertThrows(FornaxPackError.class, () -> OptionScanner.scan(src));
        assertEquals("b.fsh", e.file());
        assertEquals("BAD", e.key());
        assertTrue(e.reason().contains("line 2"));
    }

    @Test
    void labelDivergenceIsConflict() {
        Map<String, String> src = new LinkedHashMap<>();
        src.put("a.fsh", "#define A 1 //[0 1] compile \"Ambient\"\n");
        src.put("b.fsh", "#define A 1 //[0 1] compile \"Occlusion\"\n");
        FornaxPackError e = assertThrows(FornaxPackError.class, () -> OptionScanner.scan(src));
        assertEquals("A", e.key());
        assertTrue(e.reason().toLowerCase().contains("conflict"));
    }

    @Test
    void enumNameDivergenceIsConflict() {
        Map<String, String> src = new LinkedHashMap<>();
        src.put("a.fsh", "#define A 1 //[0 1] compile \"A\" {0=\"Off\" 1=\"On\"}\n");
        src.put("b.fsh", "#define A 1 //[0 1] compile \"A\" {0=\"Off\" 1=\"Fancy\"}\n");
        FornaxPackError e = assertThrows(FornaxPackError.class, () -> OptionScanner.scan(src));
        assertEquals("A", e.key());
        assertTrue(e.reason().toLowerCase().contains("conflict"));
    }
}

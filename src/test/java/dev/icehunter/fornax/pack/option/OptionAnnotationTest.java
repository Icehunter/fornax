package dev.icehunter.fornax.pack.option;

import dev.icehunter.fornax.pack.FornaxPackError;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class OptionAnnotationTest {
    @Test
    void runtimeFloatSliderWithRange() {
        PackOption o = OptionAnnotation.parseLine(
                "#define SSAO_RADIUS 2.0    //[0.5..4.0 step 0.1] runtime  \"SSAO Radius\"").orElseThrow();
        assertEquals("SSAO_RADIUS", o.name());
        assertEquals(OptionType.RUNTIME, o.type());
        assertEquals("2.0", o.rawDefault());
        assertEquals(0.5, o.range().min());
        assertEquals(4.0, o.range().max());
        assertEquals(0.1, o.range().step());
        assertEquals("SSAO Radius", o.label());
        assertFalse(o.isBoolean());
    }

    @Test
    void compileEnumWithNames() {
        PackOption o = OptionAnnotation.parseLine(
                "#define SSR_QUALITY 1  //[0 1 2] compile \"Reflections\" {0=\"Off\" 1=\"Fancy\" 2=\"Fast\"}").orElseThrow();
        assertEquals(OptionType.COMPILE, o.type());
        assertEquals(java.util.List.of("0", "1", "2"), o.allowedValues());
        // The FIRST entry must parse cleanly too: the brace-stripping regression turned '{0="Off"'
        // into key "{0", leaving value 0 with no display name in the settings UI.
        assertEquals("Off", o.enumNames().get("0"));
        assertEquals("Fancy", o.enumNames().get("1"));
        assertEquals("Fast", o.enumNames().get("2"));
    }

    @Test
    void activeBooleanDefaultsOn() {
        PackOption o = OptionAnnotation.parseLine("#define VIGNETTE //[] compile \"Vignette\"").orElseThrow();
        assertTrue(o.isBoolean());
        assertTrue(o.booleanDefaultOn());
    }

    @Test
    void commentedBooleanDefaultsOff() {
        PackOption o = OptionAnnotation.parseLine("// #define VIGNETTE //[] compile \"Vignette\"").orElseThrow();
        assertTrue(o.isBoolean());
        assertFalse(o.booleanDefaultOn());
    }

    @Test
    void plainDefineWithoutAnnotationIgnored() {
        assertEquals(Optional.empty(), OptionAnnotation.parseLine("#define SSAO_SAMPLE_COUNT 16"));
    }

    @Test
    void ordinaryCommentIgnored() {
        assertEquals(Optional.empty(), OptionAnnotation.parseLine("// just a comment"));
    }

    @Test
    void rangeWithoutStepDefaultsToHundredthOfSpan() {
        PackOption o = OptionAnnotation.parseLine("#define R 2.0 //[0.0..4.0] runtime \"R\"").orElseThrow();
        assertEquals(0.0, o.range().min());
        assertEquals(4.0, o.range().max());
        assertEquals(0.04, o.range().step(), 1e-9);
    }

    @Test
    void missingTypeKeywordIsError() {
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> OptionAnnotation.parseLine("#define X 1 //[0 1] \"X\"", "a.fsh", 3));
        assertEquals("a.fsh", e.file());
        assertEquals("X", e.key());
        assertTrue(e.reason().contains("line 3"));
        assertTrue(e.reason().toLowerCase().contains("malformed"));
    }

    @Test
    void unclosedBracketIsError() {
        assertThrows(FornaxPackError.class,
                () -> OptionAnnotation.parseLine("#define X 1 //[0 1 compile \"X\"", "a.fsh", 1));
    }

    @Test
    void missingLabelQuotesIsError() {
        assertThrows(FornaxPackError.class,
                () -> OptionAnnotation.parseLine("#define X 1 //[0 1] compile X", "a.fsh", 1));
    }

    @Test
    void unclosedEnumBraceIsError() {
        assertThrows(FornaxPackError.class,
                () -> OptionAnnotation.parseLine("#define X 1 //[0 1] compile \"X\" {0=\"Off\"", "a.fsh", 1));
    }

    @Test
    void commentedValuedDeclarationIsError() {
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> OptionAnnotation.parseLine("// #define R 2.0 //[0.5..4.0] runtime \"R\""));
        assertEquals("R", e.key());
        assertTrue(e.reason().contains("boolean"));
    }

    @Test
    void booleanWithValueTokenIsError() {
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> OptionAnnotation.parseLine("#define VIGNETTE 1 //[] compile \"Vignette\""));
        assertEquals("VIGNETTE", e.key());
    }

    @Test
    void valuedFormWithoutValueTokenIsError() {
        assertThrows(FornaxPackError.class,
                () -> OptionAnnotation.parseLine("#define X //[0 1] compile \"X\""));
    }

    @Test
    void enumNamesOnRangeFormIsError() {
        assertThrows(FornaxPackError.class,
                () -> OptionAnnotation.parseLine("#define R 2.0 //[0..4] runtime \"R\" {0=\"a\"}"));
    }

    @Test
    void nonNumericRangeBoundsAreError() {
        assertThrows(FornaxPackError.class,
                () -> OptionAnnotation.parseLine("#define R 2.0 //[a..b] runtime \"R\""));
    }

    @Test
    void booleanCarriesNoEnumNames() {
        PackOption o = OptionAnnotation.parseLine("#define VIGNETTE //[] compile \"Vignette\"").orElseThrow();
        assertTrue(o.enumNames().isEmpty());
    }

    @Test
    void defaultValueNotAmongBracketKeysIsError() {
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> OptionAnnotation.parseLine(
                        "#define SSR_QUALITY 0.0  //[0 1 2 3] compile \"Reflections\""
                                + " {0=\"Off\" 1=\"Fancy\" 2=\"Fast\" 3=\"Fastest\"}", "a.fsh", 5));
        assertEquals("SSR_QUALITY", e.key());
        assertTrue(e.reason().contains("line 5"));
        assertTrue(e.reason().contains("0.0"));
        assertTrue(e.reason().toLowerCase().contains("default"));
    }
}

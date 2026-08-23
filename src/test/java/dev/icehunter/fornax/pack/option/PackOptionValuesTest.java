package dev.icehunter.fornax.pack.option;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PackOptionValuesTest {
    private static final PackOption RUNTIME_RANGE = new PackOption("SSAO_RADIUS", OptionType.RUNTIME,
            new OptionRange(0.0, 4.0, 0.1), List.of(), false, false, "0.5", "SSAO Radius", Map.of());
    private static final PackOption RUNTIME_ENUM_NOT_SLIDER = new PackOption("SSAO_QUALITY", OptionType.RUNTIME,
            null, List.of("0", "1"), false, false, "0", "SSAO Quality", Map.of("0", "Low", "1", "High"));
    private static final PackOption COMPILE_BOOL = new PackOption("BLOOM_ENABLED", OptionType.COMPILE,
            null, List.of(), true, false, "0", "Bloom", Map.of());
    private static final PackOption COMPILE_ENUM = new PackOption("SSR_QUALITY", OptionType.COMPILE,
            null, List.of("0", "1", "2"), false, false, "1", "SSR Quality",
            Map.of("0", "Off", "1", "Fast", "2", "Fancy"));

    @Test
    void rendersAsSliderOnlyForRuntimeRangeInSlidersList() {
        assertTrue(PackOptionValues.rendersAsSlider(RUNTIME_RANGE, List.of("SSAO_RADIUS")));
        assertFalse(PackOptionValues.rendersAsSlider(RUNTIME_RANGE, List.of("SOMETHING_ELSE")));
        assertFalse(PackOptionValues.rendersAsSlider(RUNTIME_ENUM_NOT_SLIDER, List.of("SSAO_QUALITY")));
        assertFalse(PackOptionValues.rendersAsSlider(COMPILE_BOOL, List.of("BLOOM_ENABLED")));
    }

    @Test
    void toBooleanValueReadsZeroAndFalseAsOff() {
        assertFalse(PackOptionValues.toBooleanValue("0"));
        assertFalse(PackOptionValues.toBooleanValue("false"));
        assertFalse(PackOptionValues.toBooleanValue("FALSE"));
        assertTrue(PackOptionValues.toBooleanValue("1"));
        assertTrue(PackOptionValues.toBooleanValue("true"));
    }

    @Test
    void toCompileIntConvertsBooleanAndEnum() {
        assertEquals(1, PackOptionValues.toCompileInt(COMPILE_BOOL, "1"));
        assertEquals(0, PackOptionValues.toCompileInt(COMPILE_BOOL, "0"));
        assertEquals(2, PackOptionValues.toCompileInt(COMPILE_ENUM, "2"));
    }

    @Test
    void canonicalizeBooleanFromTomlLiterals() {
        assertEquals("1", PackOptionValues.canonicalize(COMPILE_BOOL, Boolean.TRUE));
        assertEquals("0", PackOptionValues.canonicalize(COMPILE_BOOL, Boolean.FALSE));
        assertEquals("1", PackOptionValues.canonicalize(COMPILE_BOOL, 1L));
        assertEquals("0", PackOptionValues.canonicalize(COMPILE_BOOL, 0L));
    }

    @Test
    void canonicalizeRangeFromNumericToml() {
        assertEquals("1.5", PackOptionValues.canonicalize(RUNTIME_RANGE, 1.5));
        assertEquals("2.0", PackOptionValues.canonicalize(RUNTIME_RANGE, 2L));
    }

    @Test
    void canonicalizeEnumFromIntegerToml() {
        assertEquals("2", PackOptionValues.canonicalize(COMPILE_ENUM, 2L));
        assertEquals("2", PackOptionValues.canonicalize(COMPILE_ENUM, "2"));
    }

    @Test
    void valuesDifferUsesNumericToleranceForRange() {
        assertFalse(PackOptionValues.valuesDiffer(RUNTIME_RANGE, "1.5", 1.5));
        assertFalse(PackOptionValues.valuesDiffer(RUNTIME_RANGE, "1.5000001", 1.5));
        assertTrue(PackOptionValues.valuesDiffer(RUNTIME_RANGE, "1.0", 1.5));
    }

    @Test
    void valuesDifferUsesExactMatchForBooleanAndEnum() {
        assertFalse(PackOptionValues.valuesDiffer(COMPILE_BOOL, "1", Boolean.TRUE));
        assertTrue(PackOptionValues.valuesDiffer(COMPILE_BOOL, "0", Boolean.TRUE));
        assertFalse(PackOptionValues.valuesDiffer(COMPILE_ENUM, "1", 1L));
        assertTrue(PackOptionValues.valuesDiffer(COMPILE_ENUM, "0", 1L));
    }
}

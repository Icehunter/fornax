package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.FornaxPackError;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EnabledIfExprTest {
    @Test
    void bareNameTruthy() {
        EnabledIfExpr e = EnabledIfExpr.parse("SSAO_ENABLED");
        assertTrue(e.evaluate(Map.of("SSAO_ENABLED", 1)));
        assertFalse(e.evaluate(Map.of()));
    }

    @Test
    void equalityComparison() {
        EnabledIfExpr e = EnabledIfExpr.parse("SSR_QUALITY == 1");
        assertTrue(e.evaluate(Map.of("SSR_QUALITY", 1)));
        assertFalse(e.evaluate(Map.of("SSR_QUALITY", 0)));
        assertFalse(e.evaluate(Map.of("SSR_QUALITY", 2)));
    }

    @Test
    void inequalityComparison() {
        EnabledIfExpr e = EnabledIfExpr.parse("SSR_QUALITY != 0");
        assertTrue(e.evaluate(Map.of("SSR_QUALITY", 1)));
        assertFalse(e.evaluate(Map.of("SSR_QUALITY", 0)));
    }

    @Test
    void andOrOperators() {
        EnabledIfExpr e = EnabledIfExpr.parse("A > 1 && B < 3");
        assertTrue(e.evaluate(Map.of("A", 2, "B", 2)));
        assertFalse(e.evaluate(Map.of("A", 1, "B", 2)));
        assertFalse(e.evaluate(Map.of("A", 2, "B", 3)));
    }

    @Test
    void negation() {
        EnabledIfExpr e = EnabledIfExpr.parse("!VIGNETTE");
        assertTrue(e.evaluate(Map.of()));
        assertFalse(e.evaluate(Map.of("VIGNETTE", 1)));
    }

    @Test
    void referencedNamesCollected() {
        EnabledIfExpr e = EnabledIfExpr.parse("A == 1 && B");
        assertEquals(Set.of("A", "B"), e.referencedNames());
    }

    @Test
    void malformedExpressionThrows() {
        assertThrows(FornaxPackError.class, () -> EnabledIfExpr.parse("A ==="));
    }
}

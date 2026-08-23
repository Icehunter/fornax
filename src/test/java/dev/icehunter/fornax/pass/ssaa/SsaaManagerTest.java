package dev.icehunter.fornax.pass.ssaa;

import dev.icehunter.fornax.config.AaMethod;
import dev.icehunter.fornax.config.FornaxConfig;
import dev.icehunter.fornax.config.TaauRatio;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SsaaManager is Fornax's general render-scale manager (name kept to limit blast radius): the
 * scale it tracks now legitimately runs below 1.0 for TAAU, not just above 1.0 for SSAA, so
 * {@code isActive()}'s predicate flips from "supersampling" (scale > 1) to "any non-1:1 render
 * scale" (scale != 1). {@code needsOffscreenTarget()} is the separate question of whether the
 * method needs an off-screen target at all -- true for every method except OFF, since TAA at
 * scale 1.0 still needs a distinct texture for the temporal reconstruct pass.
 *
 * <p>{@code FornaxConfig.get()}'s returned {@link dev.icehunter.fornax.config.FornaxSettings} has
 * public mutable fields (the same seam {@code SodiumConfigEntry}'s bindings use), so tests here
 * mutate {@code aaMethod}/{@code ssaaPreset}/{@code taauRatio} directly and restore the defaults
 * afterward rather than reaching for {@code FornaxConfig.install}, which is package-private to
 * {@code dev.icehunter.fornax.config} and not visible from this package.
 */
class SsaaManagerTest {
    @AfterEach
    void resetSettings() {
        FornaxConfig.get().aaMethod = AaMethod.TAA;
        FornaxConfig.get().ssaaPreset = SsaaPreset.X2;
        FornaxConfig.get().taauRatio = TaauRatio.BALANCED;
        SsaaManager.setScaleFactorForTesting(1.0f);
    }

    @Test
    void isActiveTrueForAnyNonUnityScale() {
        SsaaManager.setScaleFactorForTesting(0.67f);
        assertTrue(SsaaManager.isActive());

        SsaaManager.setScaleFactorForTesting(2.0f);
        assertTrue(SsaaManager.isActive());
    }

    @Test
    void isActiveFalseAtUnityScale() {
        SsaaManager.setScaleFactorForTesting(1.0f);
        assertFalse(SsaaManager.isActive());
    }

    @Test
    void needsOffscreenTargetFalseOnlyForOff() {
        FornaxConfig.get().aaMethod = AaMethod.OFF;
        assertFalse(SsaaManager.needsOffscreenTarget());

        FornaxConfig.get().aaMethod = AaMethod.TAA;
        assertTrue(SsaaManager.needsOffscreenTarget());

        FornaxConfig.get().aaMethod = AaMethod.SSAA;
        assertTrue(SsaaManager.needsOffscreenTarget());

        FornaxConfig.get().aaMethod = AaMethod.TAAU;
        assertTrue(SsaaManager.needsOffscreenTarget());
    }

    @Test
    void applyCurrentScaleUsesSsaaPresetOnlyUnderSsaa() {
        FornaxConfig.get().aaMethod = AaMethod.SSAA;
        FornaxConfig.get().ssaaPreset = SsaaPreset.X4;

        SsaaManager.applyCurrentScale();

        assertEquals(SsaaPreset.X4.linearScale(), SsaaManager.getScaleFactor(), 1e-6f);
    }

    @Test
    void applyCurrentScaleUsesTaauRatioOnlyUnderTaau() {
        FornaxConfig.get().aaMethod = AaMethod.TAAU;
        FornaxConfig.get().taauRatio = TaauRatio.PERFORMANCE;

        SsaaManager.applyCurrentScale();

        assertEquals(TaauRatio.PERFORMANCE.perAxisScale(), SsaaManager.getScaleFactor(), 1e-6f);
    }

    @Test
    void applyCurrentScalePinsUnityForTaaAndOff() {
        FornaxConfig.get().aaMethod = AaMethod.TAA;
        SsaaManager.applyCurrentScale();
        assertEquals(1.0f, SsaaManager.getScaleFactor(), 1e-6f);

        FornaxConfig.get().aaMethod = AaMethod.OFF;
        SsaaManager.applyCurrentScale();
        assertEquals(1.0f, SsaaManager.getScaleFactor(), 1e-6f);
    }
}

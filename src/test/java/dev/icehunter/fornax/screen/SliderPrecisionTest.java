package dev.icehunter.fornax.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A slider must be able to SHOW every value its own step can reach.
 *
 * <p>YACL's default float formatter prints one decimal. Plague's POM Depth steps 0.01, so a stored
 * 0.18 rendered as "0.2" and two adjacent steps looked identical -- the author reported the sliders
 * as broken when the stored values had been correct all along. A label that misreports a working
 * control is worse than a wrong value, because it costs trust in the control.
 */
class SliderPrecisionTest {

    @Test
    void precisionComesFromTheStep() {
        assertEquals(0, YaclPackRows.decimalsForStep(8.0), "POM Quality / Distance step 8");
        assertEquals(0, YaclPackRows.decimalsForStep(1.0), "toggles and debug ordinals");
        assertEquals(1, YaclPackRows.decimalsForStep(0.1));
        assertEquals(2, YaclPackRows.decimalsForStep(0.05), "POM Height Contrast");
        assertEquals(2, YaclPackRows.decimalsForStep(0.01), "POM Depth -- the reported case");
        assertEquals(3, YaclPackRows.decimalsForStep(0.005));
    }

    @Test
    void floatParseNoiseDoesNotInflatePrecision() {
        // A step read back through a float keeps a tail; counting by string inspection would answer
        // 7 here and render "0.0500001". The scaled-rint count answers 2.
        assertEquals(2, YaclPackRows.decimalsForStep((double) 0.05f));
        assertEquals(2, YaclPackRows.decimalsForStep((double) 0.01f));
    }

    @Test
    void degenerateStepsFallBackRatherThanThrow() {
        assertEquals(2, YaclPackRows.decimalsForStep(0.0), "a step-less range still needs a label");
        assertEquals(2, YaclPackRows.decimalsForStep(Double.NaN));
        assertEquals(4, YaclPackRows.decimalsForStep(0.000001), "capped, not unbounded");
    }

    @Test
    void theReportedCaseRendersItsOwnValue() {
        assertEquals("0.18", String.format("%." + YaclPackRows.decimalsForStep(0.01) + "f", 0.18f));
        assertEquals("0.48", String.format("%." + YaclPackRows.decimalsForStep(0.01) + "f", 0.48f));
        assertEquals("64", String.format("%." + YaclPackRows.decimalsForStep(8.0) + "f", 64.0f));
    }
}

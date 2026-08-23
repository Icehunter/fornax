package dev.icehunter.fornax.atlas;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LabPbrNeutralTexturesTest {
    @Test
    void fixedFallbacksAreSemanticAbsenceRatherThanAlbedo() {
        assertEquals(0xFF_80_80_FF, LabPbrNeutralTextures.NORMAL_ARGB);
        assertEquals(0xFF_00_00_00, LabPbrNeutralTextures.MATERIAL_ARGB);
    }
}

package dev.icehunter.fornax.pass.ssaa;

/**
 * SSAA presets, named by pixel-count multiplier (the common AA-marketing convention -- "16x" means
 * 16x total pixels, not 16x per dimension). linearScale is the actual per-dimension multiplier
 * SsaaManager/SsaaDownsamplePass use, i.e. sqrt(pixelCountMultiplier).
 *
 * <p>X9 was removed from this ladder: a saved config still holding it deserializes as {@code null}
 * (Gson maps an unknown enum constant to null, never an error) and the v3 migration step in
 * {@link dev.icehunter.fornax.config.FornaxSettings#migrate} normalizes it to {@code X4} -- the
 * nearest lower factor at the time of removal ({@code X8} arrived after that contract was fixed).
 * OFF likewise survives only for legacy deserialization (see the v2 step there).
 */
public enum SsaaPreset {
    OFF(1.0f),
    X1_5(1.2247449f),  // sqrt(1.5)
    X2(1.4142136f),    // sqrt(2)
    X4(2.0f),          // sqrt(4)
    X8(2.8284271f),    // sqrt(8)
    X16(4.0f);         // sqrt(16)

    private final float linearScale;

    SsaaPreset(float linearScale) {
        this.linearScale = linearScale;
    }

    public float linearScale() {
        return this.linearScale;
    }

    /** Total pixel-count multiplier this preset represents, used for the VRAM estimate. */
    public float pixelCountMultiplier() {
        return this.linearScale * this.linearScale;
    }
}

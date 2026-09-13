package dev.icehunter.fornax.pack;

/** Pack gate, receiving-distance source, units, and maximum filter support for celestial RT. */
public record RayTracedShadowSpec(String enabledIf, String distanceOption, int blocksPerUnit,
                                 float filterGuardTexels) {
    public RayTracedShadowSpec(String enabledIf, String distanceOption, int blocksPerUnit) {
        this(enabledIf, distanceOption, blocksPerUnit, 0);
    }
}

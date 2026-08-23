package dev.icehunter.fornax.pipeline;

/**
 * Names and count of the geometry-input sampler slots appended to Sodium's shared terrain bind
 * group (descriptor set 0). Fixed at class-init because {@code ShaderChunkRenderer.BIND_GROUP} is
 * a process-wide static built once, before any pack loads -- the slot count cannot vary per pack.
 * A pack's declared {@code inputs = [...]} map onto {@code u_GeomInput0..RESERVED-1} in order;
 * undeclared slots are bound to the noise texture as a safe non-garbage default.
 */
public final class GeometryInputs {
    private GeometryInputs() {}

    /**
     * Number of geometry-input sampler slots reserved on the shared terrain bind group.
     *
     * <p>Eight keeps the complete terrain layout at twelve samplers (the four engine/terrain
     * samplers plus these slots), below Metal's sixteen-sampler stage limit while leaving packs
     * enough room for persistent simulations and authored geometry displacement. Unused slots bind
     * the existing neutral fallback and cost no texture samples.
     */
    public static final int RESERVED = 8;

    public static String slot(int index) {
        return "u_GeomInput" + index;
    }
}

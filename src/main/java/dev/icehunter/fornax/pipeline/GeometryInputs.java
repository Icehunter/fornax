package dev.icehunter.fornax.pipeline;

/**
 * Names and count of the geometry-input sampler slots appended to Sodium's shared terrain bind
 * group (descriptor set 0). Fixed at class-init because {@code ShaderChunkRenderer.BIND_GROUP} is
 * a process-wide static built once, before any pack loads -- the slot count cannot vary per pack.
 * Leading texture inputs map onto {@code u_GeomInput0..RESERVED-1}; appended terrain buffers
 * map onto {@code u_GeomBuffer0..BUFFER_RESERVED-1}. Unused textures bind noise, unused buffers zero.
 */
public final class GeometryInputs {
    private GeometryInputs() {}

    /**
     * Number of geometry-input sampler slots reserved on the shared terrain bind group.
     *
     * <p>Eight keeps the complete terrain layout at fifteen samplers (the four engine/terrain
     * samplers, three overflow atlas pages and these slots), below Metal's sixteen-sampler stage limit while leaving packs
     * enough room for persistent simulations and authored geometry displacement. Unused slots bind
     * the existing neutral fallback and cost no texture samples.
     */
    public static final int RESERVED = 8;

    /** Match the eight texture slots with a bounded, independent R32_UINT buffer bank.
     * Buffer descriptors do not consume the fifteen occupied sampler bindings. */
    public static final int BUFFER_RESERVED = 8;

    public static String bufferSlot(int index) {
        return "u_GeomBuffer" + index;
    }

    public static String slot(int index) {
        return "u_GeomInput" + index;
    }
}

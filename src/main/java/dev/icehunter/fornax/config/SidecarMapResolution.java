package dev.icehunter.fornax.config;

/**
 * How much of a resource pack's authored labPBR sidecar resolution ({@code _n} normal and
 * {@code _s} specular maps) is kept in Fornax's own atlases.
 *
 * <p><b>Why this is a setting.</b> {@code PbrSidecarAtlasScale} sizes a sidecar atlas from the
 * block atlas's dimensions and used to be governed only by a hardcoded resident-byte budget,
 * derived against the smallest device in the fleet. On a large pack that silently cost the user the
 * resolution their pack author actually shipped, with no way to see or change it: measured on a
 * 512x pack with the paged block atlas active, full resolution needs ~1.43 GB per atlas, so both
 * landed on half and every map was resampled. The log said so and nothing else did.
 *
 * <p>Each tier is a CAP on the scale exponent, not a forced value. The chooser still only ever
 * steps DOWN from the resolution the pack asked for, so a pack shipping maps below its own albedo
 * resolution is unaffected by {@link #FULL}; and the device's maximum texture dimension and the
 * resident-byte ceiling both still apply underneath, so no tier can allocate past what the hardware
 * can hold. A tier chooses how much detail to ASK for; the limits decide what is actually possible.
 *
 * <p><b>{@link #HALF} is the default, deliberately.</b> It is what the previous byte budget already
 * produced on the large packs this exists for, so the machines that motivated the setting see no
 * change. Note the consequence for SMALL packs, stated rather than buried: a pack whose atlas fits
 * comfortably used to reach full resolution on the byte budget alone and now caps at half until the
 * user selects {@link #FULL}. That is the cost of a predictable default, and it is one click.
 *
 * <p>Changing this rebuilds the atlases, so it takes effect on a resource reload — the settings
 * screen triggers one on save rather than leaving the row silently inert until F3+T.
 */
public enum SidecarMapResolution {
    /**
     * The resolution the pack shipped, hardware permitting. Costs the most VRAM by far -- ~1.43 GB
     * per atlas, two atlases, on a 512x pack with the paged block atlas.
     *
     * <p>Carries NO byte ceiling, and that is the point rather than an oversight: the ceiling
     * exists to stop a machine being surprised, and a user who selects FULL is not being surprised.
     * Silently denying an explicit choice would make the tier a lie. The device's maximum texture
     * dimension and the scale floor still apply, so this cannot allocate without bound.
     */
    FULL(0, Long.MAX_VALUE),

    /** Half the authored resolution per axis, a quarter of the texels. The default. */
    HALF(-1, Limits.DEFAULT_MAX_ATLAS_BYTES),

    /** A quarter per axis, a sixteenth of the texels. For small-VRAM machines and large packs. */
    QUARTER(-2, Limits.DEFAULT_MAX_ATLAS_BYTES);

    /**
     * Held in a nested class because Java forbids an enum constant from forward-referencing a
     * static field of its own enum, and the constants above need this value.
     */
    private static final class Limits {
        /**
         * Resident-byte ceiling for one atlas on the bounded tiers, mip chain included. 512 MB,
         * derived against the smallest device in the fleet -- a 3 GB card. At HALF and QUARTER the
         * scale cap has usually already brought the atlas well under this, so it is a backstop for
         * an unusually large pack rather than the primary control it used to be.
         */
        static final long DEFAULT_MAX_ATLAS_BYTES = 512L * 1024L * 1024L;

        private Limits() {
        }
    }

    private final int maxLog2Scale;
    private final long maxAtlasBytes;

    SidecarMapResolution(int maxLog2Scale, long maxAtlasBytes) {
        this.maxLog2Scale = maxLog2Scale;
        this.maxAtlasBytes = maxAtlasBytes;
    }

    /** Resident-byte ceiling for ONE sidecar atlas at this tier, mip chain included. */
    public long maxAtlasBytes() {
        return this.maxAtlasBytes;
    }

    /**
     * Offset applied to the scale the pack asked for. Zero keeps it; each step down halves the
     * sidecar's resolution per axis relative to what was authored.
     */
    public int log2ScaleOffset() {
        return this.maxLog2Scale;
    }
}

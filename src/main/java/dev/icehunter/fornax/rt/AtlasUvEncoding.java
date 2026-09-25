package dev.icehunter.fornax.rt;

/** How hit word 3 is packed. Explicit wire values keep zero meaning packed half2, the default. */
public enum AtlasUvEncoding {
    PACKED_HALF(0),
    TEXEL_U16(1);

    private final int wireValue;

    AtlasUvEncoding(int wireValue) {
        this.wireValue = wireValue;
    }

    public int wireValue() {
        return wireValue;
    }

    /** Two unsigned 16-bit coordinates represent indices 0..65535; larger atlases must not wrap. */
    public void validateAtlasDimensions(long width, long height) {
        if (this == TEXEL_U16 && (width <= 0 || height <= 0 || width > 65536 || height > 65536)) {
            throw new IllegalArgumentException("texel_u16 atlas dimensions must be between 1 and 65536, got "
                    + width + "x" + height);
        }
    }
}

package dev.icehunter.fornax.pack.material;

/** The active pack's {@link MaterialScalars} snapshot -- rebuilt every time {@link
 * MaterialResolution#refresh()} runs (pack (re)activation, {@code TAGS_LOADED}), mirroring
 * exactly how {@link BlockMaterials#install} keeps its own snapshot current. Returns an empty-category
 * (all-zero-emission) instance when no pack is active, never null -- callers never need a null check. */
public final class MaterialScalarsHolder {
    private static volatile MaterialScalars current = MaterialScalars.build(java.util.List.of());

    private MaterialScalarsHolder() {
    }

    public static MaterialScalars current() {
        return current;
    }

    public static void install(MaterialScalars scalars) {
        current = scalars;
    }
}

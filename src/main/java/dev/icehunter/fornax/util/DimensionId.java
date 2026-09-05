package dev.icehunter.fornax.util;

import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Which dimension the camera is in, as one number.
 *
 * <p>The same number reaches a shader as {@code u_WorldBounds.w} and a pack graph as
 * {@code dimension} in a {@code runtime_enabled_if}. Both read it here, so a pack cannot gate a pass
 * on one meaning and shade it under another.
 *
 * <p>The level's identity, not its sky kind: {@code Skybox.NONE} would fold the Nether in with
 * every custom skyless dimension, the one thing a pack needs to tell apart.
 */
public final class DimensionId {
    public static final int UNKNOWN = 0;
    public static final int OVERWORLD = 1;
    public static final int NETHER = 2;
    public static final int END = 3;

    private DimensionId() {}

    public static int of(@Nullable Level level) {
        if (level == null) {
            return UNKNOWN;
        }
        if (level.dimension() == Level.OVERWORLD) {
            return OVERWORLD;
        }
        if (level.dimension() == Level.NETHER) {
            return NETHER;
        }
        if (level.dimension() == Level.END) {
            return END;
        }
        return UNKNOWN;
    }
}

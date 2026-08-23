package dev.icehunter.fornax.atlas;

import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/** Runtime attachment implemented by the prepared-draw mixin. */
public interface PreparedRenderTypeLabPbrOwner {
    void fornax$setLabPbrOwner(Identifier owner, long generation);

    @Nullable
    Identifier fornax$getLabPbrOwner();

    long fornax$getLabPbrGeneration();
}

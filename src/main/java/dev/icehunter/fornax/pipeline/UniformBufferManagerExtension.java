package dev.icehunter.fornax.pipeline;

import com.mojang.blaze3d.buffers.GpuBuffer;

/**
 * Interface-injection surface for the two Fornax-only members {@link UniformBufferManagerMixin}
 * adds to the official {@code net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager}.
 * Mixin merges these method bodies into the real class's bytecode at runtime, but Fornax code
 * compiled against the official (unmodified) Sodium jar has no compile-time-visible declaration of
 * them on {@code UniformBufferManager} itself -- callers must cast through this interface, mirroring
 * the same pattern official Sodium's own {@code GameRendererStorage}/{@code FogStorage} use, and this
 * codebase's existing {@code TextureAtlasAccessor}.
 */
public interface UniformBufferManagerExtension {
    void updatePbrSettings();

    GpuBuffer getPbrSettingsBuffer();
}

package dev.icehunter.fornax.util;

/**
 * A Vulkan/Metal failure this engine already knows is unrecoverable for the rest of the device's
 * lifetime, distinct from an ordinary bug a broad catch is meant to survive.
 *
 * <p>{@link VulkanMetalInterop} raises these for {@code "interop timeline wait failed"},
 * {@code "vkEndCommandBuffer failed"}, {@code "interop fence timeout"},
 * {@code "Metal command buffer/blit encoder nil"} -- a bare {@code IllegalStateException} would give
 * no catch a way to distinguish these from an ordinary logic error without string-matching the
 * message. A named type is what {@link GpuFatalErrors#rethrowIfFatal} needs to tell the two apart.
 *
 * <p>{@code com.mojang.blaze3d.GpuDeviceLossException} is the OTHER fatal signal this engine deals
 * with, raised by Blaze3D itself rather than this engine, and is handled the same way by {@link
 * GpuFatalErrors#rethrowIfFatal} without needing a wrapper -- it already has a distinct type.
 */
public final class GpuFatalException extends RuntimeException {
    public GpuFatalException(String message) {
        super(message);
    }
}

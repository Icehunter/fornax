package dev.icehunter.fornax.pipeline;

/**
 * Size of the widened Vulkan push-constant block Fornax's terrain draws use — the single source
 * of truth shared by the writer ({@code DrawContextVKMixin}, which pushes the block per region)
 * and the pipeline-layout declaration ({@code VulkanRenderPipelineMixin}, which must declare a
 * range at least this large or the driver silently drops every byte pushed past the declared
 * range).
 *
 * <p>std430 layout of the block (see {@code terrain.vsh}'s {@code FornaxPushConstants}):
 * {@code vec3 u_RegionOffset} at 0, {@code int u_CurrentTime} at 12, {@code uint u_RegionID} at
 * 16, pad to 32, {@code vec3 u_SunDirection} at 32, pad to 48, {@code vec3 u_PrevRegionOffset}
 * at 48 — ending at 60. Official Sodium's own block is only the first 20 bytes; everything above
 * that is Fornax's.
 */
public final class TerrainPushConstants {
    public static final int BLOCK_SIZE = 60;

    private TerrainPushConstants() {
    }
}

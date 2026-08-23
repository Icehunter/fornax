package dev.icehunter.fornax.pack.graph;

import java.nio.ByteBuffer;

/**
 * Extra push-constant payload for the {@code light_list_build} compute pass (analytic-lights
 * milestone, M1), appended after the shared 32-byte {@code PassParams} base -- mirrors {@code
 * EmitterLightExtra}'s own camera/window fields exactly (same field order, same byte offsets), plus
 * one more field this pass alone needs: the scan radius.
 *
 * <ul>
 *   <li>{@code cameraPos} (vec4: xyz + pad, 16 B) -- absolute world camera position, for the
 *       scan-radius cull in {@code light_list_build.comp}.</li>
 *   <li>{@code window} (ivec4: diameter, center section x/y/z, 16 B) -- the live voxel window, same
 *       shape/derivation as {@code EmitterLightExtra}'s (radius is derivable: (diameter-1)/2).</li>
 *   <li>{@code scanExtra} (vec4: x = scan radius in blocks + pad, 16 B) -- threaded as a push
 *       constant (not a compile-time #define) so it can become a runtime option later without a
 *       shader recompile.</li>
 * </ul>
 *
 * <p>All members 16-byte aligned (no scalar-after-vec3 hazard, the voxel_debug_raymarch push-block
 * discipline). Total 48 bytes; total push = 32 (PassParams.PUSH_CONSTANT_BASE_SIZE) + 48 = 80 B,
 * within every target GPU's push-constant limit (Apple/MoltenVK 4 KB, Vulkan spec minimum 128).
 *
 * <p>{@code light_list_build.comp}'s own {@code PushConstants} block MUST keep the {@code vec3
 * sunDir} field at offset 16 (unused by this pass, but reserved by the shared PassParams base that
 * {@code ComputePassRunner} writes unconditionally for every compute pass) so its {@code cameraPos}/
 * {@code window}/{@code scanExtra} fields land at the same offsets 32/48/64 this class writes to --
 * see {@code light_list_build.comp}'s own header comment.
 */
public record LightListExtra(float camX, float camY, float camZ,
                              int diameter, int centerX, int centerY, int centerZ,
                              float scanRadiusBlocks)
        implements ExtraPushConstants {
    public static final int BYTE_SIZE = 48;

    @Override
    public int byteSize() {
        return BYTE_SIZE;
    }

    @Override
    public void writeInto(ByteBuffer buffer, int offset) {
        buffer.putFloat(offset, camX);
        buffer.putFloat(offset + 4, camY);
        buffer.putFloat(offset + 8, camZ);
        // offset+12: unused (vec4 w padding)
        buffer.putInt(offset + 16, diameter);
        buffer.putInt(offset + 20, centerX);
        buffer.putInt(offset + 24, centerY);
        buffer.putInt(offset + 28, centerZ);
        buffer.putFloat(offset + 32, scanRadiusBlocks);
        // offset+36..47: unused (vec4 padding)
    }
}

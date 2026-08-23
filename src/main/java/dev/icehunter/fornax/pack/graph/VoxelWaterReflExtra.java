package dev.icehunter.fornax.pack.graph;

import java.nio.ByteBuffer;
import org.joml.Matrix4f;

/**
 * Extra push-constant payload for the {@code voxel_water_refl} compute pass, appended after the
 * shared 32-byte {@code PassParams} base (mirrors {@code EmitterLightExtra}). Carries what a compute
 * pass cannot get any other way (no u_Globals / u_PassParams uniform for compute):
 * <ul>
 *   <li>{@code invProjModelView} (mat4, 64 B) -- NDC + reversed-Z depth -> camera-relative world pos,
 *       to reconstruct the water surface point and mirror direction.</li>
 *   <li>{@code sunViewProj} (mat4, 64 B) -- world -> shadow-map clip, to light hits from the sun
 *       shadow map (populated but UNUSED by the Task 1 spike kernel; Task 3 samples it).</li>
 *   <li>{@code cameraAbs} (vec4, xyz + pad, 16 B) -- absolute world origin, to convert a
 *       camera-relative hit into brick-grid space.</li>
 *   <li>{@code window} (ivec4: diameter, center section x/y/z, 16 B) -- the live voxel window.</li>
 * </ul>
 * All members 16-byte aligned (no scalar-after-vec3 hazard). Total 160 bytes; total push =
 * 32 + 160 = 192 B, within every target GPU's push-constant limit (Apple/MoltenVK 4 KB, NVIDIA 256).
 */
public record VoxelWaterReflExtra(Matrix4f invProjModelView, Matrix4f sunViewProj,
                                  float camX, float camY, float camZ,
                                  int diameter, int centerX, int centerY, int centerZ)
        implements ExtraPushConstants {
    public static final int BYTE_SIZE = 160;

    @Override
    public int byteSize() {
        return BYTE_SIZE;
    }

    @Override
    public void writeInto(ByteBuffer buf, int offset) {
        invProjModelView.get(offset, buf);      // column-major, bytes offset..offset+63
        sunViewProj.get(offset + 64, buf);      // bytes offset+64..offset+127
        buf.putFloat(offset + 128, camX);
        buf.putFloat(offset + 132, camY);
        buf.putFloat(offset + 136, camZ);
        buf.putFloat(offset + 140, 0f);         // vec4 pad
        buf.putInt(offset + 144, diameter);
        buf.putInt(offset + 148, centerX);
        buf.putInt(offset + 152, centerY);
        buf.putInt(offset + 156, centerZ);
    }
}

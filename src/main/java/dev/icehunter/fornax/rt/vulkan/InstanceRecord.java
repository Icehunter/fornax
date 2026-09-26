package dev.icehunter.fornax.rt.vulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Writes one {@code VkAccelerationStructureInstanceKHR}: the 64-byte record a top-level structure
 * build reads for each bottom-level structure it places.
 *
 * <p>Pure byte layout, no LWJGL type, so the record a build receives is pinned by a unit test
 * rather than trusted from a binding. The layout is the Vulkan specification's:
 *
 * <pre>
 *  0  VkTransformMatrixKHR transform            3x4 floats, ROW-major, translation in column 3
 * 48  uint32  instanceCustomIndex : 24 | mask : 8
 * 52  uint32  instanceShaderBindingTableRecordOffset : 24 | flags : 8
 * 56  uint64  accelerationStructureReference      the BLAS's device address
 * </pre>
 *
 * <p>Every instance this engine places is a pure translation (a mesh's grid-relative origin), so
 * the rotation block is the identity. The custom index is what a ray query reads back as
 * {@code gl_InstanceCustomIndexEXT} / {@code rayQueryGetIntersectionInstanceCustomIndexEXT}: it is
 * how a hit finds its mesh's primitive records, and it is 24 bits wide, so a slot number past
 * {@link #MAX_CUSTOM_INDEX} would silently alias another mesh's records.
 */
public final class InstanceRecord {

    public static final int BYTES = 64;
    public static final int TRANSFORM_OFFSET = 0;
    public static final int CUSTOM_INDEX_AND_MASK_OFFSET = 48;
    public static final int SBT_OFFSET_AND_FLAGS_OFFSET = 52;
    public static final int REFERENCE_OFFSET = 56;

    /** {@code VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR}: both faces block, as the
     * raster shadow pipeline and the Metal tier already have it. */
    public static final int FLAG_TRIANGLE_FACING_CULL_DISABLE = 0x1;

    /** {@code VK_GEOMETRY_INSTANCE_FORCE_OPAQUE_BIT_KHR}. Never set here: an opaque instance skips
     * the per-candidate alpha test, so every leaf and pane would block light as a solid block. */
    public static final int FLAG_FORCE_OPAQUE = 0x4;

    /** Every ray this engine casts uses mask 0xFF, so one visible-to-all byte here. */
    public static final int MASK_ALL = 0xFF;

    public static final int MAX_CUSTOM_INDEX = (1 << 24) - 1;

    private InstanceRecord() {
    }

    /**
     * @param out          little-endian buffer with at least {@link #BYTES} remaining from {@code offset}
     * @param customIndex  0..{@link #MAX_CUSTOM_INDEX}, the mesh slot a hit reads back
     * @param flags        {@code VK_GEOMETRY_INSTANCE_*} bits; must not include {@link #FLAG_FORCE_OPAQUE}
     * @param blasAddress  the bottom-level structure's device address, nonzero
     */
    public static void write(ByteBuffer out, int offset, float translateX, float translateY, float translateZ,
            int customIndex, int flags, long blasAddress) {
        if (out.order() != ByteOrder.LITTLE_ENDIAN) {
            throw new IllegalArgumentException("instance records are little-endian; set the buffer order first");
        }
        if (customIndex < 0 || customIndex > MAX_CUSTOM_INDEX) {
            throw new IllegalArgumentException("custom index " + customIndex + " outside 0.." + MAX_CUSTOM_INDEX);
        }
        if ((flags & ~0xFF) != 0) {
            throw new IllegalArgumentException("instance flags are 8 bits, got 0x" + Integer.toHexString(flags));
        }
        if ((flags & FLAG_FORCE_OPAQUE) != 0) {
            throw new IllegalArgumentException("an opaque instance skips the alpha test; never set FORCE_OPAQUE");
        }
        if (blasAddress == 0) {
            throw new IllegalArgumentException("a null structure reference places nothing and reports nothing");
        }
        if (!Float.isFinite(translateX) || !Float.isFinite(translateY) || !Float.isFinite(translateZ)) {
            throw new IllegalArgumentException("instance translation must be finite");
        }
        int at = offset + TRANSFORM_OFFSET;
        // Row-major 3x4: each row is (rotation x3, translation), rotation identity.
        out.putFloat(at, 1f).putFloat(at + 4, 0f).putFloat(at + 8, 0f).putFloat(at + 12, translateX);
        out.putFloat(at + 16, 0f).putFloat(at + 20, 1f).putFloat(at + 24, 0f).putFloat(at + 28, translateY);
        out.putFloat(at + 32, 0f).putFloat(at + 36, 0f).putFloat(at + 40, 1f).putFloat(at + 44, translateZ);
        out.putInt(offset + CUSTOM_INDEX_AND_MASK_OFFSET, (customIndex & MAX_CUSTOM_INDEX) | (MASK_ALL << 24));
        // No shader binding table: ray queries have none, so the 24-bit record offset is zero.
        out.putInt(offset + SBT_OFFSET_AND_FLAGS_OFFSET, flags << 24);
        out.putLong(offset + REFERENCE_OFFSET, blasAddress);
    }
}

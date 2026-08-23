package dev.icehunter.fornax.pass.reconstruct;

import com.mojang.blaze3d.buffers.Std140Builder;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins {@code u_ReconstructSettings}'s std140 buffer layout at the pure-JVM level, mirroring the
 * offset comment on {@link ReconstructPass#SETTINGS_BUFFER_SIZE}: two texel-size vec2s, a jitter
 * vec2, two scalars, the ratio flag, and the sky-reprojection mat4, writing the exact same {@code
 * Std140Builder} call sequence {@code ReconstructPass.reconstruct} uses into a buffer sized to
 * {@link ReconstructPass#settingsBufferSize()} and asserting it never overflows.
 */
class ReconstructSettingsTest {
    @Test
    void settingsBufferSizeIsOneHundredAndTwelveBytes() {
        assertEquals(112, ReconstructPass.settingsBufferSize());
    }

    @Test
    void std140SequenceFitsWithoutOverflow() {
        ByteBuffer buffer = ByteBuffer.allocate(ReconstructPass.settingsBufferSize());

        assertDoesNotThrow(() -> Std140Builder.intoBuffer(buffer)
                .putVec2(1.0f / 1920.0f, 1.0f / 1080.0f)   // u_SourceTexelSize, offset 0
                .putVec2(1.0f / 2560.0f, 1.0f / 1440.0f)   // u_OutputTexelSize, offset 8
                .putVec2(new Vector2f(0.125f, -0.125f))    // u_JitterOffsetNdc, offset 16
                .putFloat(0.9f)                             // u_BlendFactor, offset 24
                .putFloat(0.5f)                             // u_Sharpen, offset 28
                .putFloat(1.0f)                             // u_RatioIsOne, offset 32
                .putMat4f(new Matrix4f())                   // u_SkyReprojection, offset 48
                .get());
    }

    /**
     * The mat4 lands at 48, not 36: {@code putMat4f} aligns to 16 before writing its 64 bytes.
     * Pinning the total written length pins that padding, so a field inserted before it can never
     * silently shift the matrix out from under {@code reconstruct.fsh}'s declared block layout.
     * ({@code get()} flips the buffer, so the bytes written are its LIMIT, not its position.)
     */
    @Test
    void skyReprojectionMatrixIsAlignedToOffsetFortyEight() {
        ByteBuffer buffer = ByteBuffer.allocate(ReconstructPass.settingsBufferSize());

        ByteBuffer written = Std140Builder.intoBuffer(buffer)
                .putVec2(1.0f, 1.0f)
                .putVec2(1.0f, 1.0f)
                .putVec2(1.0f, 1.0f)
                .putFloat(1.0f)
                .putFloat(1.0f)
                .putFloat(1.0f)             // the scalar tail ends at 36
                .putMat4f(new Matrix4f())
                .get();

        assertEquals(112, written.limit(), "mat4 must start at 48 and run 64 bytes");
    }

    @Test
    void std140SequenceOverflowsAnUndersizedBuffer() {
        ByteBuffer tooSmall = ByteBuffer.allocate(36);

        assertThrows(RuntimeException.class, () -> Std140Builder.intoBuffer(tooSmall)
                .putVec2(1.0f, 1.0f)
                .putVec2(1.0f, 1.0f)
                .putVec2(1.0f, 1.0f)
                .putFloat(1.0f)
                .putFloat(1.0f)
                .putFloat(1.0f)   // the 36th byte -- exactly the buffer's capacity, no room left
                .align(48)        // must fail: nothing to round up into
                .get());
    }
}

package dev.icehunter.fornax.atlas;

import org.jspecify.annotations.Nullable;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reads just a PNG's pixel dimensions, without decoding it.
 *
 * <p>Needed because the sidecar atlases must be SIZED before any sidecar is blitted into them: the
 * atlas has to be big enough that every sprite's rectangle can hold that sprite's own map at the
 * resolution the pack shipped it at, and that is only knowable once every sidecar's dimensions are.
 * Decoding all of them twice to find out would cost, on the user's 64x + 512x-maps pack, about
 * 1.5 GB of {@code NativeImage} and several seconds -- for two numbers that sit in the first 24
 * bytes of each file.
 *
 * <p>The PNG spec (ISO 15948, clause 5) fixes those 24 bytes exactly: an 8-byte signature, then a
 * chunk length and type, then IHDR's width and height as big-endian 32-bit integers. IHDR is
 * required to be the FIRST chunk, so no chunk walking is needed and no other chunk can appear
 * first.
 */
public final class PngHeader {
    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    /** A PNG's dimensions in pixels. */
    public record Size(int width, int height) {}

    private PngHeader() {
    }

    /**
     * @return the image's dimensions, or {@code null} if the stream is not a PNG or is truncated --
     * treated by callers exactly like a sidecar that is not there, which is the honest reading:
     * a file we cannot measure is a file we cannot place.
     */
    public static @Nullable Size read(InputStream in) throws IOException {
        DataInputStream data = new DataInputStream(in);
        byte[] signature = new byte[SIGNATURE.length];
        try {
            data.readFully(signature);
            for (int i = 0; i < SIGNATURE.length; i++) {
                if (signature[i] != SIGNATURE[i]) {
                    return null;
                }
            }
            data.skipBytes(8); // IHDR chunk length (4) + chunk type "IHDR" (4)
            int width = data.readInt();
            int height = data.readInt();
            return width > 0 && height > 0 ? new Size(width, height) : null;
        } catch (java.io.EOFException e) {
            return null;
        }
    }
}

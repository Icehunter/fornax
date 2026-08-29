package dev.icehunter.fornax.pack;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * A pack-shipped 3D texture asset: a fixed 16-byte little-endian header (width, height, depth,
 * format tag, each a u32) followed by {@code width*height*depth*format.bytesPerTexel()} raw texel
 * bytes, x-fastest then y then z. No image codec is involved: {@code NativeImage} (Blaze3D's only
 * decoder) is 2D-only, so this format exists purely to give the engine's volume-texture path
 * something to read without a new third-party dependency.
 */
public record RawVolumeAsset(int width, int height, int depth, Format format, ByteBuffer texels) {

    private static final int HEADER_BYTES = 16;

    public enum Format {
        R8(1), RGBA8(4);

        private final int bytesPerTexel;

        Format(int bytesPerTexel) {
            this.bytesPerTexel = bytesPerTexel;
        }

        public int bytesPerTexel() {
            return bytesPerTexel;
        }

        static Format fromTag(int tag) throws IOException {
            return switch (tag) {
                case 0 -> R8;
                case 1 -> RGBA8;
                default -> throw new IOException("unrecognized volume format tag: " + tag
                        + " (expected 0=R8 or 1=RGBA8)");
            };
        }
    }

    /** Parses a {@code [textures.*] format = "..."} TOML value. Case-insensitive. */
    public static Format parseFormat(String token) {
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "r8" -> Format.R8;
            case "rgba8" -> Format.RGBA8;
            default -> throw new IllegalArgumentException(
                    "unrecognized volume texture format '" + token + "' (expected r8 or rgba8)");
        };
    }

    public static RawVolumeAsset read(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length < HEADER_BYTES) {
            throw new IOException("volume asset truncated: file is " + bytes.length
                    + " bytes, header alone needs " + HEADER_BYTES);
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int width = buf.getInt();
        int height = buf.getInt();
        int depth = buf.getInt();
        int formatTag = buf.getInt();
        if (width <= 0 || height <= 0 || depth <= 0) {
            throw new IOException("volume asset header declares non-positive dimensions: "
                    + width + "x" + height + "x" + depth);
        }
        Format format = Format.fromTag(formatTag);
        long expectedTexelBytes = (long) width * height * depth * format.bytesPerTexel();
        long actualTexelBytes = bytes.length - HEADER_BYTES;
        if (actualTexelBytes < expectedTexelBytes) {
            throw new IOException("volume asset truncated: header declares " + width + "x" + height
                    + "x" + depth + " " + format + " (" + expectedTexelBytes
                    + " texel bytes) but only " + actualTexelBytes + " bytes follow the header");
        }
        ByteBuffer texels = buf.slice().limit((int) expectedTexelBytes);
        return new RawVolumeAsset(width, height, depth, format, texels);
    }
}

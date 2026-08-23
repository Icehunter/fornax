package dev.icehunter.fornax.atlas;

import com.mojang.blaze3d.platform.NativeImage;
import dev.icehunter.fornax.FornaxMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Cross-process cache for a labPBR sidecar atlas's level-0 base image and overflow layers, keyed by
 * {@link LabPbrAtlasFingerprint}. Stage A ({@link LabPbrAtlasPair}'s in-session skip) already makes
 * an unchanged F8 free; this is what gives a COLD BOOT the same win, since a fresh process has
 * nothing in memory to compare against.
 *
 * <p><b>Deliberately caches level 0 only, not the full mip chain.</b> Reading level 0 back skips the
 * two costs believed to dominate the measured 14.6s/9.7s build times -- per-sprite PNG decode +
 * resample ({@code blitSidecar}) and edge extrusion -- while {@code upload}'s existing
 * {@code downsampleLevel} mip-generation loop still runs unchanged, on whichever level-0 image it's
 * handed (freshly built or disk-loaded, it cannot tell the difference). This is a strict subset of
 * what a full-mip-chain cache would do, chosen because it needs no changes to {@code upload}'s own
 * per-level loop at all -- if a future measurement shows mip generation is still a meaningful share
 * of a cache-hit boot, extending the format to cover every level is the natural next step, not a
 * redesign.
 *
 * <p>Not consulted for animated sidecars' ongoing per-tick content -- those are re-decoded by {@code
 * LabPbrAnimatedSidecar.load} regardless of cache hit/miss (bounded by the animated-sprite count,
 * far smaller than the static one) so runtime ticking keeps working; the cached level-0 bytes only
 * save the STATIC {@code blitSidecar} work and the one-time extrusion pass.
 */
public final class LabPbrAtlasDiskCache {
    private static final int MAGIC = 0x4C424143; // "LBAC"
    private static final int FORMAT_VERSION = 1;
    private static final int FINGERPRINT_HEX_LENGTH = 64; // hex-encoded SHA-256
    private static final int HEADER_SIZE = 4 + 4 + 4 + 4 + 4 + FINGERPRINT_HEX_LENGTH;

    private LabPbrAtlasDiskCache() {
    }

    // Single background thread: writes are enqueued in submission order and this engine has no
    // other use for a writer here, so one dedicated, named, daemon thread is simpler than sharing a
    // pool built for something else. Daemon so an unclean exit never hangs the process on a write.
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "fornax-labpbr-cache-writer");
        thread.setDaemon(true);
        return thread;
    });

    private static Path cacheDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("fornax_labpbr_cache");
    }

    // One slot per (atlasLocation, lane) -- current pack only, overwritten on every write, so disk
    // usage stays bounded to roughly one pack's worth of sidecar data rather than accumulating every
    // pack ever loaded.
    private static Path cacheFile(Identifier atlasLocation, String lane) {
        String safeName = atlasLocation.toString().replace(':', '_').replace('/', '_');
        return cacheDir().resolve(safeName + "_" + lane + ".bin");
    }

    /** A loaded base image plus its overflow layers. Caller owns closing it. */
    record Loaded(NativeImage base, NativeImage[] layers) implements AutoCloseable {
        @Override
        public void close() {
            base.close();
            for (NativeImage layer : layers) {
                layer.close();
            }
        }
    }

    /**
     * Attempts to load a previously-cached entry matching {@code fingerprint} exactly, including
     * dimensions. Returns {@code null} on any miss, mismatch, or I/O failure -- every one of those
     * degrades to "build fresh" at the call site, never throws.
     */
    @Nullable
    static Loaded tryRead(Identifier atlasLocation, String lane, String fingerprint,
                          int width, int height, int layerCount) {
        return tryReadFile(cacheFile(atlasLocation, lane), fingerprint, width, height, layerCount);
    }

    /** The {@link Path}-based core of {@link #tryRead}, split out so a test can exercise the real
     * read/write logic against a {@code @TempDir} without going through {@code
     * FabricLoader.getInstance()} -- which throws {@code IllegalStateException} outside a real game
     * environment (confirmed elsewhere in this test suite, e.g. {@code MetaBindingTest}). */
    @Nullable
    static Loaded tryReadFile(Path file, String fingerprint, int width, int height, int layerCount) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE);
            readFully(channel, header);
            header.flip();
            if (header.getInt() != MAGIC || header.getInt() != FORMAT_VERSION) {
                return null;
            }
            int cachedWidth = header.getInt();
            int cachedHeight = header.getInt();
            int cachedLayers = header.getInt();
            byte[] fingerprintBytes = new byte[FINGERPRINT_HEX_LENGTH];
            header.get(fingerprintBytes);
            String cachedFingerprint = new String(fingerprintBytes, StandardCharsets.US_ASCII);
            if (cachedWidth != width || cachedHeight != height || cachedLayers != layerCount
                    || !fingerprint.equals(cachedFingerprint)) {
                return null;
            }

            NativeImage base = readImage(channel, width, height);
            NativeImage[] layers = new NativeImage[layerCount];
            try {
                for (int i = 0; i < layerCount; i++) {
                    layers[i] = readImage(channel, width, height);
                }
            } catch (IOException | RuntimeException e) {
                base.close();
                for (NativeImage layer : layers) {
                    if (layer != null) {
                        layer.close();
                    }
                }
                throw e;
            }
            return new Loaded(base, layers);
        } catch (IOException | RuntimeException e) {
            FornaxMod.LOGGER.warn("[LabPBR] Disk cache read failed for {}; building fresh", file, e);
            return null;
        }
    }

    private static NativeImage readImage(FileChannel channel, int width, int height) throws IOException {
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, width, height, false);
        try {
            readFully(channel, image.getPixelBytes());
            return image;
        } catch (IOException | RuntimeException e) {
            image.close();
            throw e;
        }
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new EOFException("labPBR disk cache file truncated");
            }
        }
    }

    /**
     * Asynchronously persists a freshly-built level-0 base image and overflow layers. Copies each
     * image's bytes to the heap (one bulk {@code ByteBuffer.get}, not per-pixel) before returning, so
     * the caller can close its {@code NativeImage}s immediately afterward exactly as it does today --
     * the background thread only ever touches its own heap copies, never native memory the render
     * thread might free concurrently. Never blocks the caller beyond that copy; any failure past this
     * point is logged and the entry abandoned, never retried, never thrown back to the caller.
     */
    static void writeAsync(Identifier atlasLocation, String lane, String fingerprint,
                           NativeImage base, NativeImage[] layers) {
        byte[] baseBytes = copyBytes(base);
        byte[][] layerBytes = new byte[layers.length][];
        for (int i = 0; i < layers.length; i++) {
            layerBytes[i] = copyBytes(layers[i]);
        }
        int width = base.getWidth();
        int height = base.getHeight();
        Path targetFile = cacheFile(atlasLocation, lane);
        WRITER.execute(() -> writeNow(targetFile, fingerprint, width, height, baseBytes, layerBytes));
    }

    /** Test-only synchronous entry point for {@link #writeNow} -- see {@link #tryReadFile}'s doc for
     * why the {@code Identifier}/{@code FabricLoader} path can't be exercised from a plain test. */
    static void writeFileSync(Path targetFile, String fingerprint, int width, int height,
                              byte[] baseBytes, byte[][] layerBytes) {
        writeNow(targetFile, fingerprint, width, height, baseBytes, layerBytes);
    }

    private static byte[] copyBytes(NativeImage image) {
        ByteBuffer pixels = image.getPixelBytes().duplicate(); // duplicate: never disturb the
        // caller's own buffer position/limit, which upload()'s GPU-upload path reads right after.
        byte[] bytes = new byte[pixels.remaining()];
        pixels.get(bytes);
        return bytes;
    }

    private static void writeNow(Path targetFile, String fingerprint, int width, int height,
                                 byte[] baseBytes, byte[][] layerBytes) {
        Path parent = targetFile.toAbsolutePath().getParent();
        Path tempFile;
        try {
            Files.createDirectories(parent);
            tempFile = Files.createTempFile(parent, targetFile.getFileName().toString(), ".tmp");
        } catch (IOException e) {
            FornaxMod.LOGGER.warn("[LabPBR] Could not create disk cache temp file for {}; skipping", targetFile, e);
            return;
        }
        try (FileChannel channel = FileChannel.open(tempFile,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE);
            header.putInt(MAGIC).putInt(FORMAT_VERSION).putInt(width).putInt(height).putInt(layerBytes.length);
            header.put(fingerprint.getBytes(StandardCharsets.US_ASCII));
            header.flip();
            channel.write(header);
            channel.write(ByteBuffer.wrap(baseBytes));
            for (byte[] layer : layerBytes) {
                channel.write(ByteBuffer.wrap(layer));
            }
            moveIntoPlace(tempFile, targetFile);
            long total = HEADER_SIZE + (long) baseBytes.length
                    + Arrays.stream(layerBytes).mapToLong(b -> b.length).sum();
            FornaxMod.LOGGER.info("[LabPBR] Disk cache written: {} ({} bytes)",
                    targetFile.getFileName(), total);
        } catch (IOException e) {
            FornaxMod.LOGGER.warn("[LabPBR] Failed to write disk cache {}", targetFile, e);
            deleteQuietly(tempFile);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Best-effort cleanup of a temp file; a leftover .tmp costs disk space, not correctness.
        }
    }

    // Same tmp-then-atomic-move-with-fallback shape as PersistentPipelineCache/PackValuesFile.
    private static void moveIntoPlace(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Best-effort process shutdown: waits briefly for any in-flight write so a quit doesn't race a
     * half-written temp file. A write still in flight past the timeout is abandoned, not corrupted --
     * the target file is only ever replaced by the final atomic move. */
    public static void shutdown() {
        WRITER.shutdown();
        try {
            if (!WRITER.awaitTermination(10, TimeUnit.SECONDS)) {
                FornaxMod.LOGGER.warn("[LabPBR] Disk cache writer did not finish within 10s at shutdown");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

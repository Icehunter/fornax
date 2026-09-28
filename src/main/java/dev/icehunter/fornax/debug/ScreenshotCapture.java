package dev.icehunter.fornax.debug;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.pack.graph.GraphRunner;
import net.minecraft.client.Minecraft;
import org.joml.Vector2fc;

import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Raw crops of graph targets, taken only on F2. Separate from the F10 pass capture. No per-frame GPU work. */
public final class ScreenshotCapture {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final ScreenshotCaptureRequest.Budget BUDGET = new ScreenshotCaptureRequest.Budget();
    // The config is small; refuse a large file before parsing it.
    private static final long MAX_CONFIG_BYTES = 64 * 1024;
    private static Frame graphFrame;
    private static PresentedFrame completedFrame;

    private ScreenshotCapture() {}

    record Frame(int frameCounter, float jitterX, float jitterY, float previousJitterX, float previousJitterY,
                 int renderWidth, int renderHeight, long shaderGeneration, Map<String, Integer> compileOptions,
                 double cameraX, double cameraY, double cameraZ) {
        Frame { compileOptions = Map.copyOf(compileOptions); }
    }

    record PresentedFrame(Frame graph, int nativeWidth, int nativeHeight) {}

    /** Called when targets are rebuilt and when the pack closes. A frame that did not finish keeps no snapshot. */
    public static void invalidateCompletedFrame() {
        graphFrame = null;
        completedFrame = null;
    }

    /** Stores the values while they still match this frame's draws, before history swap and jitter advance. */
    public static void completedFrame(int frameCounter, Vector2fc currentJitter, Vector2fc previousJitter,
                                      int width, int height, long generation, Map<String, Integer> options,
                                      double x, double y, double z) {
        graphFrame = new Frame(frameCounter, currentJitter.x(), currentJitter.y(), previousJitter.x(),
                previousJitter.y(), width, height, generation, options, x, y, z);
    }

    /** Keeps the snapshot only if the graph frame reached the end of the native frame. F2 never reads next-frame jitter. */
    public static void presentedFrame(int frameCounter, int width, int height) {
        completedFrame = promote(graphFrame, frameCounter, width, height);
        graphFrame = null;
    }

    static PresentedFrame promote(Frame frame, int frameCounter, int width, int height) {
        return frame != null && frame.frameCounter() == frameCounter && width > 0 && height > 0
                ? new PresentedFrame(frame, width, height) : null;
    }

    public static void capture(Minecraft minecraft) {
        if (!GraphRunner.isActive() || minecraft.level == null) return;
        Path game = minecraft.gameDirectory.toPath();
        Path config = game.resolve("config/fornax-screenshot-capture.json");
        if (!Files.isRegularFile(config)) return;
        Session session = null;
        try {
            Path directory = game.resolve("fornax-screenshot-captures")
                    .resolve(System.currentTimeMillis() + "-" + UUID.randomUUID());
            Files.createDirectories(directory);
            session = new Session(directory, completedFrame);
            if (Files.size(config) > MAX_CONFIG_BYTES) throw new IllegalArgumentException("Capture config exceeds 64 KiB");
            ScreenshotCaptureRequest request = ScreenshotCaptureRequest.parse(Files.readString(config));
            session.manifest.put("request", request);
            PresentedFrame frame = completedFrame;
            if (frame == null || frame.graph().shaderGeneration() != GraphRunner.shaderCacheGeneration()) {
                throw new IllegalStateException("No completed graph frame for this shader generation");
            }
            var main = minecraft.gameRenderer.mainRenderTarget();
            if (main.width != frame.nativeWidth() || main.height != frame.nativeHeight()) {
                throw new IllegalStateException("Native framebuffer changed size after the completed frame");
            }
            // Check the crop against the screen size before touching the GPU.
            request.crop().scaled(main.width, main.height, main.width, main.height);
            List<String> references = new ArrayList<>(request.targets());
            if (!references.contains("builtin.output")) references.add("builtin.output");
            List<Readback> plans = new ArrayList<>();
            long bytes = 0;
            for (String reference : references) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("reference", reference);
                entry.put("phase", "after graph history swap; exact reference, no aliasing");
                entry.put("screenshotAnchor", reference.equals("builtin.output"));
                session.entries.add(entry);
                try {
                    GpuTextureView view = GraphRunner.screenshotCaptureView(reference);
                    if (view == null || view.isClosed() || view.texture().isClosed()) {
                        throw new IllegalArgumentException("Missing or closed texture: " + reference);
                    }
                    var texture = view.texture();
                    entry.put("format", texture.getFormat().name());
                    entry.put("textureWidth", view.getWidth(0));
                    entry.put("textureHeight", view.getHeight(0));
                    entry.put("baseMipLevel", view.baseMipLevel());
                    entry.put("layers", texture.getDepthOrLayers());
                    var crop = request.crop().scaled(main.width, main.height, view.getWidth(0), view.getHeight(0));
                    entry.put("crop", crop);
                    long count = crop.bytes(texture.getFormat(), texture.getDepthOrLayers());
                    entry.put("rowBytes", Math.multiplyExact(crop.width(), texture.getFormat().blockSize()));
                    entry.put("bytes", count);
                    if ((texture.usage() & GpuTexture.USAGE_COPY_SRC) == 0) {
                        throw new IllegalArgumentException("Texture has no COPY_SRC usage: " + reference);
                    }
                    bytes = Math.addExact(bytes, count);
                    String file = "texture-" + (session.entries.size() - 1) + ".bin";
                    entry.put("file", file);
                    entry.put("status", "pending");
                    plans.add(new Readback(view, crop, count, file, entry));
                } catch (Exception error) {
                    entry.put("status", "unavailable");
                    entry.put("error", error.toString());
                }
            }
            session.reservation = BUDGET.reserve(bytes);
            if (session.reservation == null) {
                throw new IllegalStateException("Capture exceeds 16 MiB/request or 8 pending/128 MiB staging budget");
            }
            session.manifest.put("reservedBytes", bytes);
            // Set the pending count first: a GPU callback may run at once.
            session.pending = plans.size();
            session.write();
            for (Readback plan : plans) session.queue(plan);
            session.queued();
            FornaxMod.LOGGER.info("[Fornax] F2 raw capture frame {}: {}", frame.graph().frameCounter(), directory);
        } catch (Exception error) {
            if (session != null) session.reject(error.toString());
            FornaxMod.LOGGER.warn("[Fornax] F2 raw capture unavailable: {}", error.toString());
        }
    }

    private record Readback(GpuTextureView view, ScreenshotCaptureRequest.Region crop, long bytes,
                            String file, Map<String, Object> entry) {}

    static final class Session {
        final Path directory;
        final Map<String, Object> manifest = new LinkedHashMap<>();
        final List<Map<String, Object>> entries = new ArrayList<>();
        ScreenshotCaptureRequest.Reservation reservation;
        int pending;
        boolean allQueued;
        String failure;

        Session(Path directory, PresentedFrame frame) {
            this.directory = directory;
            manifest.put("version", 1);
            manifest.put("requestedAtEpochMillis", System.currentTimeMillis());
            manifest.put("frame", frame);
            manifest.put("byteOrder", ByteOrder.nativeOrder().toString());
            manifest.put("rowOrder", "raw GPU image rows, tightly packed; no flip or conversion");
            manifest.put("coordinateSpace", "native GPU image coordinates, scaled outward for each target");
            manifest.put("pngComparison", "Minecraft 26.2 F2 flips GPU rows vertically and forces alpha to 255; compare RGB");
            manifest.put("textures", entries);
            write();
        }

        void queue(Readback plan) {
            GpuBuffer staging;
            try {
                staging = RenderSystem.getDevice().createBuffer(() -> "Fornax F2 capture " + plan.file(),
                        GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_HINT_CLIENT_STORAGE,
                        plan.bytes());
            } catch (Exception error) {
                retired(plan.entry(), error.toString());
                return;
            }
            // The copy may call back at once; the buffer must be freed only once.
            AtomicBoolean retired = new AtomicBoolean();
            try {
                var crop = plan.crop();
                RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(plan.view().texture(), staging,
                        0L, () -> {
                            if (!retired.compareAndSet(false, true)) return;
                            String error = null;
                            try (var mapping = staging.map(true, false)) {
                                byte[] raw = new byte[Math.toIntExact(plan.bytes())];
                                var buffer = mapping.data().duplicate().clear();
                                buffer.limit(raw.length);
                                buffer.get(raw);
                                Files.write(directory.resolve(plan.file()), raw);
                                synchronized (this) {
                                    plan.entry().put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)));
                                }
                            } catch (Exception failure) {
                                error = failure.toString();
                            } finally {
                                try { staging.close(); } catch (Exception closeError) { error = closeError.toString(); }
                                retired(plan.entry(), error);
                            }
                        }, plan.view().baseMipLevel(), crop.x(), crop.y(), crop.width(), crop.height());
            } catch (Exception error) {
                if (retired.compareAndSet(false, true)) {
                    try { staging.close(); } catch (Exception closeError) { error.addSuppressed(closeError); }
                    retired(plan.entry(), error.toString());
                }
            }
        }

        synchronized void retired(Map<String, Object> entry, String error) {
            entry.put("status", error == null ? "captured" : "failed");
            if (error != null) entry.put("error", error);
            pending--;
            write();
        }

        synchronized void queued() { allQueued = true; write(); }

        synchronized void reject(String error) {
            failure = error;
            for (var entry : entries) if ("pending".equals(entry.get("status"))) {
                entry.put("status", "unavailable");
                entry.put("error", error);
            }
            allQueued = true;
            write();
        }

        synchronized void write() {
            boolean complete = allQueued && pending == 0;
            if (complete && reservation != null) { reservation.close(); reservation = null; }
            manifest.put("status", failure != null ? "rejected" : !complete ? "pending"
                    : entries.stream().allMatch(entry -> "captured".equals(entry.get("status"))) ? "complete" : "incomplete");
            if (failure != null) manifest.put("error", failure);
            manifest.put("pendingCallbacks", pending);
            try {
                Files.writeString(directory.resolve("manifest.json"), JSON.toJson(manifest));
            } catch (Exception error) {
                FornaxMod.LOGGER.warn("[Fornax] Cannot write F2 capture manifest {}: {}", directory, error.toString());
            }
        }
    }
}

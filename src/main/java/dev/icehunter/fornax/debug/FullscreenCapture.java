package dev.icehunter.fornax.debug;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.pack.PassSpec;
import net.minecraft.client.Minecraft;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One request, one graph frame, raw texture bytes. Copies go out before and after the chosen
 * draw, outside any render pass, and count only once their callback comes back. Layered textures,
 * stencil and buffer inputs are left out on purpose. */
public final class FullscreenCapture {
    // Measured: two passes at 3456x2234 come to 78 bytes per pixel, 602,214,912 bytes. 1 GiB
    // covers that and still caps a debug request.
    static final long MAX_BYTES = 1024L * 1024 * 1024;
    // Cap for one image. The 1 GiB above is for the whole chain of passes.
    static final long MAX_TEXTURE_BYTES = 512L * 1024 * 1024;
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static boolean requested;
    private static long frame;
    private static Session active;
    private static Session inFlight;

    private FullscreenCapture() {}

    public static void request() { requested = true; }

    public static void beginFrame(Map<String, Integer> compileValues) {
        if (!requested && active == null) { frame++; return; }
        beginFrame(Minecraft.getInstance().gameDirectory.toPath(), compileValues);
    }

    static void beginFrame(Path game, Map<String, Integer> compileValues) {
        frame++;
        if (active != null) endFrame();
        if (!requested) return;
        requested = false;
        if (inFlight != null && inFlight.pending != 0) {
            FornaxMod.LOGGER.warn("[Fornax] Capture still waiting for readback callbacks; request ignored");
            return;
        }
        Path config = game.resolve("config/fornax-capture.json");
        if (!Files.isRegularFile(config)) return; // With no such file the readback key does its usual job.
        try {
            List<String> names = parsePasses(Files.readString(config));
            Path directory = game.resolve("fornax-captures").resolve(System.currentTimeMillis() + "-" + UUID.randomUUID());
            Files.createDirectories(directory);
            active = new Session(directory, names, compileValues);
            inFlight = active;
            active.write();
            FornaxMod.LOGGER.info("[Fornax] Raw fullscreen capture armed for frame {}: {}", frame, directory);
        } catch (Exception error) {
            FornaxMod.LOGGER.error("[Fornax] Cannot arm fullscreen capture: {}", error.toString());
        }
    }

    public static boolean isSelected(String name) {
        return active != null && active.passes.containsKey(name) && !active.passes.get(name).started;
    }

    public static Capture before(PassSpec spec, boolean[] bufferInputs, List<GpuTextureView> inputs,
                                 List<String> samplers, GpuTextureView output) {
        Session session = active;
        if (session == null) return null;
        Capture capture = session.passes.get(spec.name());
        if (capture == null || capture.started) return null;
        capture.started = true;
        capture.data.put("shader", spec.shader());
        capture.expectedInputs = spec.inputs().size();
        capture.data.put("shaderCacheGeneration", dev.icehunter.fornax.pack.graph.GraphRunner.shaderCacheGeneration());
        capture.data.put("blend", spec.blend());
        try {
            for (boolean buffer : bufferInputs) if (buffer) {
                throw new IllegalArgumentException("Buffer-input passes cannot be captured by this texture-only facility");
            }
            TexturePlan plan = planTextures(output, inputs, MAX_BYTES - session.reserved);
            session.reserved += plan.bytes();
            for (int index = 0; index < inputs.size(); index++) {
                String unavailable = plan.inputs().get(index).unavailableReason();
                if (unavailable == null) {
                    capture.texture(inputs.get(index), spec.inputs().get(index), index, samplers.get(index), false);
                } else {
                    capture.unavailableTexture(inputs.get(index), spec.inputs().get(index), index,
                            samplers.get(index), unavailable);
                }
            }
            capture.output = output;
            capture.outputName = spec.outputs().getFirst();
            capture.data.put("status", "pending");
            // Not MAP_READ or COPY_SRC. Never rebuild these and pass them off as what the shader got.
            capture.unavailableUniform("u_Globals");
            capture.unavailableUniform("u_PackOptions");
            session.write();
            return capture;
        } catch (Exception error) {
            capture.fail(error.toString());
            return null;
        }
    }

    public static void failed(String name, String error) {
        if (active != null && active.passes.containsKey(name)) active.passes.get(name).fail(error);
    }

    public static void endFrame() {
        Session session = active;
        active = null;
        if (session == null) return;
        session.ended = true;
        for (Capture capture : session.passes.values()) {
            if (!capture.started) capture.fail("Selected pass did not execute in this graph frame");
            else if (!capture.drawn && !capture.data.containsKey("error")) capture.fail("Selected pass did not finish its draw");
        }
        session.write();
    }

    static List<String> parsePasses(String source) {
        JsonObject object = JsonParser.parseString(source).getAsJsonObject();
        List<String> names = new ArrayList<>();
        if (object.has("passes")) object.getAsJsonArray("passes").forEach(value -> names.add(value.getAsString()));
        else if (object.has("pass")) names.add(object.get("pass").getAsString());
        // Four on purpose: this takes a short chain of passes, not a whole frame.
        if (names.isEmpty() || names.size() > 4 || new LinkedHashSet<>(names).size() != names.size()) {
            throw new IllegalArgumentException("Capture requires one to four distinct pass names");
        }
        for (String name : names) if (!name.matches("[A-Za-z0-9_.-]+") || name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException("Invalid capture pass name: " + name);
        }
        return List.copyOf(names);
    }

    static long textureBytes(GpuFormat format, int width, int height, int layers) {
        if (layers != 1 || width < 1 || height < 1 || format.hasStencilAspect()) {
            throw new IllegalArgumentException("Capture requires a non-stencil, single-layer 2D texture");
        }
        long bytes = Math.multiplyExact(Math.multiplyExact((long) width, height), format.blockSize());
        if (bytes > MAX_TEXTURE_BYTES) throw new IllegalArgumentException("Texture exceeds 512 MiB per-texture capture budget");
        return bytes;
    }

    record InputPlan(long bytes, String unavailableReason) {}
    record TexturePlan(long bytes, List<InputPlan> inputs) {}

    static TexturePlan planTextures(GpuTextureView output, List<GpuTextureView> inputs, long remainingBytes) {
        long bytes = bytesFor(output);
        List<InputPlan> planned = new ArrayList<>();
        for (GpuTextureView input : inputs) {
            long inputBytes = validatedTextureBytes(input);
            if ((input.texture().usage() & GpuTexture.USAGE_COPY_SRC) == 0) {
                planned.add(new InputPlan(0, "Texture is not allocated with COPY_SRC: " + input.texture().getLabel()));
            } else {
                planned.add(new InputPlan(inputBytes, null));
                bytes = Math.addExact(bytes, inputBytes);
            }
        }
        if (bytes > remainingBytes) throw new IllegalArgumentException("Capture exceeds 1 GiB total byte budget");
        return new TexturePlan(bytes, List.copyOf(planned));
    }

    private static long bytesFor(GpuTextureView view) {
        long bytes = validatedTextureBytes(view);
        if ((view.texture().usage() & GpuTexture.USAGE_COPY_SRC) == 0) {
            throw new IllegalArgumentException("Texture is not allocated with COPY_SRC: " + view.texture().getLabel());
        }
        return bytes;
    }

    private static long validatedTextureBytes(GpuTextureView view) {
        if (view == null || view.isClosed() || view.texture().isClosed()) {
            throw new IllegalArgumentException("Missing or closed texture input");
        }
        return textureBytes(view.texture().getFormat(), view.getWidth(0), view.getHeight(0), view.texture().getDepthOrLayers());
    }

    public static final class Capture {
        private final Session session;
        private final String name;
        private final Map<String, Object> data = new LinkedHashMap<>();
        private final List<Map<String, Object>> inputs = new ArrayList<>();
        private final List<Map<String, Object>> outputs = new ArrayList<>();
        private final List<Map<String, Object>> uniforms = new ArrayList<>();
        private int expectedInputs;
        private boolean started;
        private boolean drawn;
        private GpuTextureView output;
        private String outputName;

        private Capture(Session session, String name) {
            this.session = session;
            this.name = name;
            data.put("name", name);
            data.put("status", "pending");
            data.put("inputs", inputs);
            data.put("outputs", outputs);
            data.put("uniforms", uniforms);
        }

        public void uniform(String uniformName, ByteBuffer bytes) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", uniformName);
            String file = name + "-" + uniformName + ".bin";
            entry.put("file", file);
            ByteBuffer copy = bytes.duplicate();
            byte[] raw = new byte[copy.remaining()];
            copy.get(raw);
            entry.put("bytes", raw.length);
            try {
                Files.write(session.directory.resolve(file), raw);
                entry.put("status", "captured");
            } catch (Exception error) {
                entry.put("status", "failed");
                entry.put("error", error.toString());
                fail(error.toString());
            }
            uniforms.removeIf(existing -> uniformName.equals(existing.get("name")));
            uniforms.add(entry);
        }

        public void sampler(int index, String kind) {
            if (index < inputs.size()) inputs.get(index).put("sampler", kind);
        }

        private boolean texturesComplete() {
            return drawn && inputs.size() == expectedInputs && outputs.size() == 1
                    && inputs.stream().allMatch(entry -> "captured".equals(entry.get("status")))
                    && outputs.stream().allMatch(entry -> "captured".equals(entry.get("status")));
        }

        private void unavailableUniform(String uniformName) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", uniformName);
            entry.put("status", "unavailable");
            entry.put("error", "Bound buffer has no safe CPU readback contract");
            uniforms.add(entry);
        }

        public void afterDraw() {
            drawn = true;
            try {
                texture(output, outputName, 0, "attachment", true);
                session.write();
            } catch (Exception error) {
                fail(error.toString());
            }
        }

        private void unavailableTexture(GpuTextureView view, String ref, int index, String sampler, String reason) {
            Map<String, Object> entry = textureMetadata(view, ref, index, sampler);
            entry.put("capturedMipLevels", 0);
            entry.put("bytes", 0);
            entry.put("status", "unavailable");
            entry.put("error", reason);
            inputs.add(entry); // Keep positional slots even when this binding cannot be copied.
        }

        private static Map<String, Object> textureMetadata(GpuTextureView view, String ref, int index, String sampler) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("index", index);
            entry.put("name", ref);
            entry.put("sampler", sampler);
            entry.put("format", view.texture().getFormat().name());
            entry.put("width", view.getWidth(0));
            entry.put("height", view.getHeight(0));
            entry.put("baseMipLevel", view.baseMipLevel());
            entry.put("mipLevels", view.mipLevels());
            return entry;
        }

        private void texture(GpuTextureView view, String ref, int index, String sampler, boolean isOutput) {
            long bytes = bytesFor(view);
            Map<String, Object> entry = textureMetadata(view, ref, index, sampler);
            String file = name + (isOutput ? "-output-" : "-input-") + index + ".bin";
            entry.put("capturedMipLevels", 1);
            entry.put("bytes", bytes);
            entry.put("file", file);
            entry.put("status", "pending");
            (isOutput ? outputs : inputs).add(entry);
            GpuBuffer staging = RenderSystem.getDevice().createBuffer(() -> "Fornax capture " + ref,
                    GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_HINT_CLIENT_STORAGE, bytes);
            session.pending++;
            try {
                RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(view.texture(), staging, 0L, () -> {
                    try (var mapped = staging.map(true, false)) {
                        ByteBuffer buffer = mapped.data().duplicate().clear();
                        buffer.limit(Math.toIntExact(bytes));
                        byte[] raw = new byte[Math.toIntExact(bytes)];
                        buffer.get(raw);
                        Files.write(session.directory.resolve(file), raw);
                        entry.put("status", "captured");
                    } catch (Exception error) {
                        entry.put("status", "failed");
                        entry.put("error", error.toString());
                        fail(error.toString());
                    } finally {
                        try {
                            staging.close();
                        } catch (Exception error) {
                            entry.put("status", "failed");
                            entry.put("error", error.toString());
                            data.put("error", error.toString());
                        } finally {
                            session.pending--;
                            session.write();
                        }
                    }
                }, view.baseMipLevel(), 0, 0, view.getWidth(0), view.getHeight(0));
            } catch (Exception error) {
                try { staging.close(); } catch (Exception closeError) { error.addSuppressed(closeError); }
                session.pending--;
                entry.put("status", "failed");
                entry.put("error", error.toString());
                fail(error.toString());
            }
        }

        private void fail(String error) {
            data.put("error", error);
            data.put("status", "failed");
            session.write();
        }
    }

    private static final class Session {
        final Path directory;
        final Map<String, Capture> passes = new LinkedHashMap<>();
        final Map<String, Object> manifest = new LinkedHashMap<>();
        int pending;
        long reserved;
        boolean ended;

        Session(Path directory, List<String> names, Map<String, Integer> compileValues) {
            this.directory = directory;
            names.forEach(name -> passes.put(name, new Capture(this, name)));
            manifest.put("version", 1);
            manifest.put("frame", frame);
            manifest.put("byteOrder", ByteOrder.nativeOrder().toString());
            manifest.put("rowOrder", "GPU image order; no vertical flip");
            manifest.put("compileOptions", new LinkedHashMap<>(compileValues));
            manifest.put("passes", passes.values().stream().map(capture -> capture.data).toList());
        }

        void write() {
            boolean failed = false;
            boolean unavailable = false;
            for (Capture capture : passes.values()) {
                if (capture.data.containsKey("error")) failed = true;
                else if (ended && pending == 0) {
                    capture.data.put("status", capture.texturesComplete() ? "captured" : "incomplete");
                }
                unavailable |= capture.inputs.stream().anyMatch(entry -> "unavailable".equals(entry.get("status")))
                        || capture.uniforms.stream().anyMatch(entry -> "unavailable".equals(entry.get("status")));
            }
            manifest.put("textureCaptureComplete", ended && pending == 0 && !failed
                    && passes.values().stream().allMatch(Capture::texturesComplete));
            manifest.put("replayComplete", false); // Base mip only; no globals, options or resolved shader.
            manifest.put("status", failed ? "failed" : !ended || pending != 0 ? "pending" : unavailable ? "incomplete" : "complete");
            manifest.put("reservedBytes", reserved);
            manifest.put("pendingCallbacks", pending);
            try {
                Files.writeString(directory.resolve("manifest.json"), JSON.toJson(manifest));
            } catch (Exception error) {
                FornaxMod.LOGGER.error("[Fornax] Failed writing capture manifest {}: {}", directory, error.toString());
            }
        }
    }
}

package dev.icehunter.fornax.debug;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.GpuFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Named targets and a crop in screen pixels. No aliases, no resampling. */
record ScreenshotCaptureRequest(List<String> targets, Region crop) {
    // Sixteen targets is enough for a debug chain, not a whole graph.
    static final int MAX_TARGETS = 16;
    // Eight 16 MiB requests may be in flight at once, 128 MiB in all.
    static final long MAX_BYTES = 16L * 1024 * 1024;
    static final int MAX_PENDING = 8;
    static final long MAX_PENDING_BYTES = MAX_PENDING * MAX_BYTES;

    ScreenshotCaptureRequest {
        targets = List.copyOf(targets);
        if (targets.isEmpty() || targets.size() > MAX_TARGETS || new HashSet<>(targets).size() != targets.size()) {
            throw new IllegalArgumentException("targets requires 1 to 16 distinct texture references");
        }
        for (String target : targets) {
            if (!target.matches("[A-Za-z][A-Za-z0-9_.-]*") || target.contains("..")) {
                throw new IllegalArgumentException("Invalid texture reference: " + target);
            }
        }
        if (crop == null) throw new IllegalArgumentException("crop is required");
    }

    static ScreenshotCaptureRequest parse(String source) {
        JsonObject object = JsonParser.parseString(source).getAsJsonObject();
        requireKeys(object, Set.of("targets", "crop"));
        List<String> targets = new ArrayList<>();
        for (var value : object.getAsJsonArray("targets")) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("targets must contain string references");
            }
            targets.add(value.getAsString());
        }
        JsonObject crop = object.getAsJsonObject("crop");
        requireKeys(crop, Set.of("x", "y", "width", "height"));
        return new ScreenshotCaptureRequest(targets, new Region(integer(crop, "x"), integer(crop, "y"),
                integer(crop, "width"), integer(crop, "height")));
    }

    private static void requireKeys(JsonObject object, Set<String> keys) {
        if (object == null || !object.keySet().equals(keys)) {
            throw new IllegalArgumentException("Expected exactly these fields: " + keys);
        }
    }

    private static int integer(JsonObject object, String key) {
        var value = object.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("crop." + key + " must be an integer");
        }
        return value.getAsBigDecimal().intValueExact();
    }

    record Region(int x, int y, int width, int height) {
        Region {
            if (x < 0 || y < 0 || width < 1 || height < 1) {
                throw new IllegalArgumentException("crop needs nonnegative origin and positive extent");
            }
        }

        Region scaled(int nativeWidth, int nativeHeight, int targetWidth, int targetHeight) {
            if (nativeWidth < 1 || nativeHeight < 1 || targetWidth < 1 || targetHeight < 1
                    || (long) x + width > nativeWidth || (long) y + height > nativeHeight) {
                throw new IllegalArgumentException("crop is outside the native framebuffer");
            }
            // Round the start down and the end up so every pixel the crop touches is kept at any scale.
            int left = Math.toIntExact((long) x * targetWidth / nativeWidth);
            int bottom = Math.toIntExact((long) y * targetHeight / nativeHeight);
            int right = Math.toIntExact((((long) x + width) * targetWidth + nativeWidth - 1) / nativeWidth);
            int top = Math.toIntExact((((long) y + height) * targetHeight + nativeHeight - 1) / nativeHeight);
            return new Region(left, bottom, right - left, top - bottom);
        }

        long bytes(GpuFormat format, int layers) {
            if (layers != 1 || format.hasStencilAspect()) {
                throw new IllegalArgumentException("Only single-layer, non-stencil textures can be captured");
            }
            long result = Math.multiplyExact(Math.multiplyExact((long) width, height), format.blockSize());
            if (result > MAX_BYTES) throw new IllegalArgumentException("crop exceeds 16 MiB request budget");
            return result;
        }
    }

    /** A reservation holds until every callback is done, even if the graph closes first. */
    static final class Budget {
        private int requests;
        private long bytes;

        synchronized Reservation reserve(long requestedBytes) {
            if (requestedBytes < 0 || requestedBytes > MAX_BYTES || requests >= MAX_PENDING
                    || requestedBytes > MAX_PENDING_BYTES - bytes) return null;
            requests++;
            bytes += requestedBytes;
            return new Reservation(this, requestedBytes);
        }

        synchronized int requests() { return requests; }
        synchronized long bytes() { return bytes; }

        private synchronized void release(long releasedBytes) {
            requests--;
            bytes -= releasedBytes;
        }
    }

    static final class Reservation implements AutoCloseable {
        private final Budget budget;
        private final long bytes;
        private boolean closed;

        private Reservation(Budget budget, long bytes) { this.budget = budget; this.bytes = bytes; }

        @Override public synchronized void close() {
            if (!closed) { closed = true; budget.release(bytes); }
        }
    }
}

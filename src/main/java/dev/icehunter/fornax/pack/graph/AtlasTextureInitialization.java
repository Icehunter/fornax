package dev.icehunter.fornax.pack.graph;

import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.systems.CommandEncoder;
import dev.icehunter.fornax.util.GpuFatalException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/** Completes initial/replacement atlas uploads before raw compute; same-image animation is separate. */
final class AtlasTextureInitialization<T> {
    // Same five-second graphics-submit deadline as StorageTextureInitialization's allocation fence.
    private static final long FENCE_TIMEOUT_NANOS = 5_000_000_000L;
    private Map<String, T> prepared = Map.of();
    private boolean ready;

    static boolean requiresPreparation(String name) {
        return switch (name) {
            case "builtin.blockAtlas", "builtin.normalAtlas", "builtin.materialAtlas",
                    "builtin.blockAtlasPages", "builtin.materialAtlasPages" -> true;
            default -> false;
        };
    }

    void prepare(Iterable<String> inputs, Function<String, T> resolver, Supplier<CommandEncoder> encoderFactory) {
        ready = false;
        Map<String, T> next = new LinkedHashMap<>();
        for (String name : inputs) {
            if (requiresPreparation(name)) next.computeIfAbsent(name, key -> Objects.requireNonNull(resolver.apply(key)));
        }
        // Resolving every input first also records any lazy neutral uploads before this fence.
        boolean changed = next.entrySet().stream().anyMatch(entry -> prepared.get(entry.getKey()) != entry.getValue());
        if (changed) {
            CommandEncoder encoder = encoderFactory.get();
            // Fence first: a fence created after submit would name the next, still-recording batch.
            try (GpuFence fence = encoder.createFence()) {
                encoder.submit();
                if (!fence.awaitCompletion(FENCE_TIMEOUT_NANOS)) {
                    throw new GpuFatalException("atlas texture initialization fence timeout ("
                            + FENCE_TIMEOUT_NANOS + "ns)");
                }
            }
        }
        // Never admit the new identity set after a timeout or an exception at any completion stage.
        prepared = Map.copyOf(next);
        ready = true;
    }

    void requireDeclared(String passName, String name) {
        requirePrepared(passName, name, prepared.get(name));
    }

    void requirePrepared(String passName, String name, T resource) {
        if (requiresPreparation(name) && (!ready || resource == null || prepared.get(name) != resource)) {
            throw new GpuFatalException("compute pass '" + passName + "' reached unprepared atlas '" + name
                    + "'; materialize and complete its upload during graph prepare");
        }
    }

    void clear() { prepared = Map.of(); ready = false; }
}

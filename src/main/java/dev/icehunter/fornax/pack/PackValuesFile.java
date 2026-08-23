package dev.icehunter.fornax.pack;

import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.pack.option.PackOption;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Flat {@code KEY=value} per-pack option store. Unknown keys are dropped on load (pack drift tolerance). */
public final class PackValuesFile {
    private PackValuesFile() {}

    private static final AtomicBoolean LOGGED_NON_ATOMIC_FALLBACK = new AtomicBoolean(false);

    public static Map<String, String> load(Path file, Map<String, PackOption> options) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.exists(file)) return out;
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            FornaxMod.LOGGER.error("[Fornax] Failed to read pack values {}", file, e);
            return out;
        }
        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int eq = trimmed.indexOf('=');
            if (eq <= 0) continue;
            String key = trimmed.substring(0, eq).strip();
            String value = trimmed.substring(eq + 1).strip();
            if (!options.containsKey(key)) {
                FornaxMod.LOGGER.info("[Fornax] Dropping unknown option '{}' from {}", key, file.getFileName());
                continue;
            }
            out.put(key, value);
        }
        return out;
    }

    public static void save(Path file, Map<String, String> values) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : values.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
            try {
                Files.writeString(tmp, sb.toString());
                moveIntoPlace(tmp, file);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            FornaxMod.LOGGER.error("[Fornax] Failed to write pack values {}", file, e);
        }
    }

    private static void moveIntoPlace(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            if (LOGGED_NON_ATOMIC_FALLBACK.compareAndSet(false, true)) {
                FornaxMod.LOGGER.warn(
                        "[Fornax] Filesystem does not support atomic moves; pack value saves will fall back to "
                                + "non-atomic replace for {}", target, e);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

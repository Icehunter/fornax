package dev.icehunter.fornax.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.icehunter.fornax.FornaxMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Static singleton holding Fornax's current {@link FornaxSettings}, backed by a {@code fornax.json}
 * file in the Fabric config directory.
 */
public final class FornaxConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static FornaxSettings settings = new FornaxSettings();

    private FornaxConfig() {
    }

    public static FornaxSettings get() {
        return settings;
    }

    /**
     * Loads {@code fornax.json} from the Fabric config directory if it exists, parsing it into
     * {@link FornaxSettings} and installing it as the current settings. If the file doesn't exist,
     * the current (default) settings are kept and immediately written out via {@link #save()}. Any
     * IO or parse failure is logged rather than thrown, so a corrupt/unreadable config never crashes
     * mod init.
     */
    public static void load() {
        load(configPath());
    }

    /** Path-parameterized core of {@link #load()} -- the seam {@code FornaxConfigWiringTest} drives with a temp dir. */
    static void load(Path path) {
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                FornaxSettings loaded = GSON.fromJson(reader, FornaxSettings.class);
                if (loaded != null) {
                    int schemaVersionBeforeMigration = loaded.schemaVersion;
                    settings = FornaxSettings.migrate(loaded);
                    if (settings.schemaVersion != schemaVersionBeforeMigration) {
                        // A legacy file just got its one-time aaMethod migration -- persist it so the
                        // next load sees schemaVersion already current and skips migration entirely.
                        save(path);
                    }
                } else {
                    FornaxMod.LOGGER.warn("[Fornax] Config file {} was empty/invalid; using defaults", path);
                }
            } catch (IOException | RuntimeException e) {
                FornaxMod.LOGGER.error("[Fornax] Failed to load config from {}; using defaults", path, e);
            }
        } else {
            // Stamp the schema version BEFORE the very first write: an unstamped fresh file is
            // indistinguishable from a legacy (pre-aaMethod) one, so the next launch's migrate()
            // would re-derive aaMethod from ssaaPreset and silently clobber whatever the user
            // picked in their first session (see FornaxSettings.CURRENT_SCHEMA_VERSION).
            settings.schemaVersion = FornaxSettings.CURRENT_SCHEMA_VERSION;
            save(path);
        }
    }

    /**
     * Writes the current settings to {@code fornax.json} in the Fabric config directory,
     * pretty-printed, creating the config directory if needed. Public so a future settings screen
     * can call it on close.
     */
    public static void save() {
        save(configPath());
    }

    /** Path-parameterized core of {@link #save()} -- the seam {@code FornaxConfigWiringTest} drives with a temp dir. */
    static void save(Path path) {
        try {
            Path dir = path.getParent();
            if (dir != null && !Files.exists(dir)) {
                Files.createDirectories(dir);
            }

            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(settings, writer);
            }
        } catch (IOException e) {
            FornaxMod.LOGGER.error("[Fornax] Failed to save config to {}", path, e);
        }
    }

    /**
     * Test seam: installs {@code s} as the current settings directly, so a wiring test can start
     * each scenario from known defaults (or a simulated fresh JVM) regardless of what earlier tests
     * in the same JVM did to the shared static.
     */
    static void install(FornaxSettings s) {
        settings = s;
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("fornax.json");
    }
}

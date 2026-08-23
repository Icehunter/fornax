package dev.icehunter.fornax.screen;

import dev.icehunter.fornax.pack.PackDiscovery;
import dev.icehunter.fornax.pack.PackModel;
import dev.icehunter.fornax.pack.PackValuesFile;
import dev.icehunter.fornax.pack.option.OptionType;
import dev.icehunter.fornax.pack.option.PackOption;
import dev.icehunter.fornax.pack.option.PackOptionValues;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small glue shared by {@link ShaderPacksScreen}, {@link PackSettingsScreen}, and {@code
 * FornaxMod}'s own boot-time pack load: where a pack's per-pack values file lives, and converting
 * its flat string map to/from the typed maps {@code GraphRunner.rebuild}/{@code PackOptionsBuffer}
 * want. Keyed by {@code PackModel.meta().name()} (the pack.toml display name), not the discovered
 * folder/zip filename -- stable for zip packs, whose mounted root has no filename component of its
 * own. Public: the same activation flow now also runs from {@code FornaxMod}, in a
 * different package, at mod init.
 */
public final class PackSettingsSupport {
    private PackSettingsSupport() {}

    /** Public: {@code pack.PackSwitch} (a different package -- the {@code screen.ShaderPacksScreen
     * .applyChanges} pack-selection extraction) also needs the old pack's values-file path to save
     * its merged values before switching away from it, the same call this class's own callers in
     * {@code .screen} already make. */
    public static Path valuesPath(PackModel model) {
        return PackDiscovery.shaderpacksDir().resolve(model.meta().name() + ".txt");
    }

    /** Persisted values merged with each option's own default for anything never customized. */
    public static Map<String, String> mergedValues(PackModel model) {
        Map<String, String> merged = new LinkedHashMap<>(PackValuesFile.load(valuesPath(model), model.options()));
        for (PackOption option : model.options().values()) {
            merged.putIfAbsent(option.name(), option.defaultValue());
        }
        return merged;
    }

    public static Map<String, Integer> compileIntMap(PackModel model, Map<String, String> values) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (PackOption option : model.options().values()) {
            if (option.type() == OptionType.COMPILE) {
                out.put(option.name(), PackOptionValues.toCompileInt(option, values.get(option.name())));
            }
        }
        return out;
    }

    public static Map<String, Float> runtimeFloatMap(PackModel model, Map<String, String> values) {
        Map<String, Float> out = new LinkedHashMap<>();
        for (PackOption option : model.options().values()) {
            if (option.type() == OptionType.RUNTIME) {
                try {
                    out.put(option.name(), Float.parseFloat(values.get(option.name())));
                } catch (NumberFormatException ignored) {
                    // Non-numeric runtime value (shouldn't happen for v0.1's float-only runtime options).
                }
            }
        }
        return out;
    }
}

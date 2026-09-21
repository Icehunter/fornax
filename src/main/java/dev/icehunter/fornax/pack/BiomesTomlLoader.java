package dev.icehunter.fornax.pack;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlFormat;

import java.io.Reader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Reads the biome ID file. Needs no game, and turns down anything it does not expect. */
public final class BiomesTomlLoader {
    private BiomesTomlLoader() {}

    public static BiomesSpec load(Reader reader, String file) {
        Config.setInsertionOrderPreserved(true);
        Config root;
        try {
            root = TomlFormat.instance().createParser().parse(reader);
        } catch (RuntimeException error) {
            throw new FornaxPackError(file, "", "invalid TOML: " + error.getMessage());
        }
        TomlSupport.rejectUnknownKeys(root, Set.of("biomes"), file);
        if (!root.contains("biomes")) return BiomesSpec.empty();
        if (!(root.get("biomes") instanceof Config table)) {
            throw new FornaxPackError(file, "biomes", "expected a table");
        }
        Map<String, Integer> ids = new LinkedHashMap<>();
        for (Config.Entry entry : table.entrySet()) {
            String key = "biomes." + entry.getKey();
            Object value = entry.getValue();
            if (!(value instanceof Integer) && !(value instanceof Long)) {
                throw new FornaxPackError(file, key, "expected an integer biome ID");
            }
            long id = ((Number) value).longValue();
            if (id < 1 || id > BiomesSpec.MAX_ID) {
                throw new FornaxPackError(file, key, "biome ID must be in 1.." + BiomesSpec.MAX_ID);
            }
            ids.put(entry.getKey(), (int) id);
        }
        try {
            return new BiomesSpec(ids);
        } catch (IllegalArgumentException error) {
            throw new FornaxPackError(file, "biomes", error.getMessage());
        }
    }
}

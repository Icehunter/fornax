package dev.icehunter.fornax.pack;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Biome IDs the pack picks and keeps. 0 always means the pack gave this biome no ID. */
public record BiomesSpec(Map<String, Integer> ids) {
    // The shader reads the ID as a float. Whole numbers up to 2^24 fit a float exactly.
    public static final int MAX_ID = 1 << 24;
    private static final Pattern KEY = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
    private static final BiomesSpec EMPTY = new BiomesSpec(Map.of());

    public BiomesSpec {
        Map<String, Integer> copy = new LinkedHashMap<>();
        var assigned = new HashSet<Integer>();
        for (var entry : ids.entrySet()) {
            String key = entry.getKey();
            Integer id = entry.getValue();
            if (key == null || !KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("invalid namespaced biome key '" + key + "'");
            }
            if (id == null || id < 1 || id > MAX_ID) {
                throw new IllegalArgumentException("biome '" + key + "' ID must be an integer in 1.." + MAX_ID);
            }
            if (!assigned.add(id)) {
                throw new IllegalArgumentException("biome '" + key + "' reuses ID " + id);
            }
            copy.put(key, id);
        }
        ids = Collections.unmodifiableMap(copy);
    }

    public static BiomesSpec empty() { return EMPTY; }

    /** A biome the pack left out gets 0. Its heat and rain are still passed on. */
    public int id(String key) { return key == null ? 0 : ids.getOrDefault(key, 0); }
}

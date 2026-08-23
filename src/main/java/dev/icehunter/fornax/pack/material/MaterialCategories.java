package dev.icehunter.fornax.pack.material;

import dev.icehunter.fornax.pack.BlocksSpec;
import dev.icehunter.fornax.pack.CategorySpec;
import dev.icehunter.fornax.pack.FornaxPackError;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assigns each blocks.toml category a dense 1-based material ID in declaration order (index 0 is the
 * reserved "uncategorized" slot -- pure labPBR). Deterministic and registry-independent: IDs come
 * from the manifest alone, so they are stable across tag reloads and identical for the generated
 * shader constants and the Java-side blockstate lookup.
 */
public final class MaterialCategories {
    /** Dense IDs are packed into a u16 material channel; 1023 is the highest displayable ID. */
    public static final int MAX_CATEGORIES = 1023;

    private final List<CategorySpec> ordered;        // index i -> ID i+1
    private final Map<String, Integer> idByName;     // "polished_metal" -> 1

    private MaterialCategories(List<CategorySpec> ordered, Map<String, Integer> idByName) {
        this.ordered = ordered;
        this.idByName = idByName;
    }

    public static MaterialCategories from(BlocksSpec blocks) {
        List<CategorySpec> ordered = new ArrayList<>(blocks.categories().values());
        if (ordered.size() > MAX_CATEGORIES) {
            throw new FornaxPackError("blocks.toml", "categories",
                    "too many categories: " + ordered.size() + " (max " + MAX_CATEGORIES + ")");
        }
        Map<String, Integer> idByName = new LinkedHashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            idByName.put(ordered.get(i).name(), i + 1);
        }
        return new MaterialCategories(ordered, idByName);
    }

    /** Number of ID slots including the uncategorized slot 0 (i.e. categories + 1). */
    public int slotCount() { return ordered.size() + 1; }

    /** 1-based ID for a category name, or 0 if unknown. */
    public int idOf(String name) { return idByName.getOrDefault(name, 0); }

    /** Categories in ID order (index i is ID i+1). */
    public List<CategorySpec> ordered() { return ordered; }
}

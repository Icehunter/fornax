package dev.icehunter.fornax.pack;

import dev.icehunter.fornax.pack.option.PackOption;

import java.util.List;
import java.util.Map;

/**
 * Fatal load-time validation of the meta-option + yacl-pages constructs, run after the option table
 * is scanned (mirrors {@link ProfileValidator}'s placement, but throws {@link FornaxPackError}
 * instead of warning, since a meta naming a non-existent option/page is a broken pack, not drift).
 */
public final class MetaValidator {
    private MetaValidator() {}

    public static void validate(ScreensSpec screens, Map<String, PackOption> options) {
        // Every option a meta assigns must exist in the merged option table.
        for (Map.Entry<String, MetaSpec> e : screens.metas().entrySet()) {
            for (Map.Entry<String, Map<String, Object>> tier : e.getValue().assign().entrySet()) {
                for (String optionName : tier.getValue().keySet()) {
                    if (!options.containsKey(optionName)) {
                        throw new FornaxPackError("screens.toml",
                                "metas." + e.getKey() + ".assign." + tier.getKey(),
                                "assigns unknown option '" + optionName + "'");
                    }
                }
            }
        }
        // Every <meta:NAME> token referenced from any page must resolve to a declared meta.
        checkMetaRefs(screens.main().elements(), screens);
        for (Map.Entry<String, ScreenSpec> e : screens.screens().entrySet()) {
            checkMetaRefs(e.getValue().elements(), screens);
        }
        // Every yacl page id must be a declared [screens.X] page.
        for (String page : screens.yaclPages()) {
            if (!screens.screens().containsKey(page)) {
                throw new FornaxPackError("screens.toml", "yacl.pages",
                        "names unknown page '" + page + "' (no matching [screens." + page + "] table)");
            }
        }
    }

    private static void checkMetaRefs(List<String> elements, ScreensSpec screens) {
        for (String token : elements) {
            if (token.startsWith("<meta:") && token.endsWith(">")) {
                String id = token.substring("<meta:".length(), token.length() - 1);
                if (!screens.metas().containsKey(id)) {
                    throw new FornaxPackError("screens.toml", token, "references unknown meta '" + id + "'");
                }
            }
        }
    }
}

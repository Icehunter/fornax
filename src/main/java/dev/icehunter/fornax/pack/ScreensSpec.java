package dev.icehunter.fornax.pack;
import java.util.List;
import java.util.Map;
public record ScreensSpec(MainScreenSpec main, Map<String, ScreenSpec> screens,
                          Map<String, ProfileSpec> profiles, List<String> sliders,
                          Map<String, MetaSpec> metas, List<String> yaclPages,
                          Map<String, String> descriptions) {
    /** Back-compat for callers predating authored per-option descriptions. */
    public ScreensSpec(MainScreenSpec main, Map<String, ScreenSpec> screens,
                       Map<String, ProfileSpec> profiles, List<String> sliders,
                       Map<String, MetaSpec> metas, List<String> yaclPages) {
        this(main, screens, profiles, sliders, metas, yaclPages, Map.of());
    }

    /** Back-compat for callers/tests predating the meta + yacl-pages constructs. */
    public ScreensSpec(MainScreenSpec main, Map<String, ScreenSpec> screens,
                       Map<String, ProfileSpec> profiles, List<String> sliders) {
        this(main, screens, profiles, sliders, Map.of(), List.of(), Map.of());
    }
}

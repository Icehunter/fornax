package dev.icehunter.fornax.pack;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * One {@code [metas.NAME]} table: a per-axis meta-option that stages several internal option
 * assignments at once. {@code assign} maps a tier name (a member of {@code values}) to that tier's
 * option-name -> raw-TOML-literal assignment table. Parsed beside {@link ProfileSpec}; literals stay
 * raw {@code Object} (night-config's Boolean/Long/Double/String) and are interpreted by consumers
 * through {@code PackOptionValues}, exactly like {@link ProfileSpec#values()}.
 *
 * <p>{@code dependsOn} is an optional gating option name (e.g. {@code EMITTER_LIGHTS}) -- when
 * present, this meta's row should render disabled/greyed while that option is off, so a dormant
 * row (like {@code LIGHT_REACH} while {@code EMITTER_LIGHTS} is off) LOOKS dormant instead of a
 * placebo control. {@code null} means unconditional, the overwhelming common case -- see the
 * 4-argument constructor, which every pre-existing call site keeps using unchanged.
 */
public record MetaSpec(String label, String description, List<String> values,
                       Map<String, Map<String, Object>> assign, @Nullable String dependsOn) {

    public MetaSpec(String label, String description, List<String> values,
                    Map<String, Map<String, Object>> assign) {
        this(label, description, values, assign, null);
    }
}

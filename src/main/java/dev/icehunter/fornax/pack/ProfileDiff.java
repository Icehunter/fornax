package dev.icehunter.fornax.pack;

import dev.icehunter.fornax.pack.option.PackOption;
import dev.icehunter.fornax.pack.option.PackOptionValues;

import java.util.Map;

/**
 * Counts how many of a {@link ProfileSpec}'s declared option values differ from a pack's current
 * values -- the "+N changed" suffix the {@code <profile>} cycler shows next to each preset name.
 * Pure over already-loaded data, so it's unit-tested without any pack-loading or GPU machinery.
 */
public final class ProfileDiff {
    private ProfileDiff() {}

    /**
     * @param currentValues the pack's current option values (row-widget string form, keyed by option name)
     * @param options       the pack's merged option table, used to interpret each profile literal's type
     */
    public static int countChanged(ProfileSpec profile, Map<String, String> currentValues, Map<String, PackOption> options) {
        int changed = 0;
        for (Map.Entry<String, Object> entry : profile.values().entrySet()) {
            PackOption option = options.get(entry.getKey());
            if (option == null) {
                continue; // unknown option in profile -- drift tolerance, mirrors PackValuesFile.load
            }
            String have = currentValues.getOrDefault(option.name(), option.defaultValue());
            if (PackOptionValues.valuesDiffer(option, have, entry.getValue())) {
                changed++;
            }
        }
        return changed;
    }
}

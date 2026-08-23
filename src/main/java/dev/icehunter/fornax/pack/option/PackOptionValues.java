package dev.icehunter.fornax.pack.option;

import java.util.List;

/**
 * Pure conversions between a {@link PackOption}'s three on-the-wire value shapes -- the row-widget
 * string form ({@link #canonicalize}/{@link #toBooleanValue}), the compile-time integer form {@code
 * DefineRewriter}/{@code EnabledIfExpr} expect ({@link #toCompileInt}), and TOML profile literals
 * ({@link Boolean}/{@link Number}/{@link String}) -- shared by {@code ShaderPacksScreen} and {@code
 * PackSettingsScreen} UI and unit-tested independently of any Minecraft/GPU class.
 */
public final class PackOptionValues {
    private PackOptionValues() {}

    /**
     * A row renders as a slider only for a runtime option with a numeric range whose name is also
     * listed in {@code screens.toml}'s {@code sliders}; every other option (including any runtime
     * option a pack author didn't opt into slider display for) renders as a cycle button.
     */
    public static boolean rendersAsSlider(PackOption option, List<String> sliderNames) {
        return option.type() == OptionType.RUNTIME && option.range() != null && sliderNames.contains(option.name());
    }

    /** The row-widget string form of a boolean option's current value ("0"/"1" or "false"/"true"). */
    public static boolean toBooleanValue(String value) {
        return !value.equals("0") && !value.equalsIgnoreCase("false");
    }

    /** Converts a row-widget string value to the integer form {@code GraphRunner.rebuild}'s compileValues map wants. */
    public static int toCompileInt(PackOption option, String value) {
        if (option.isBoolean()) {
            return toBooleanValue(value) ? 1 : 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            // Name the offending option and value. A bare NumberFormatException here surfaces as
            // "Unexpected failure loading configured pack; falling back to vanilla Sodium" with a stack
            // trace that points at Integer.parseInt and says nothing about WHICH option is malformed --
            // a pack author then has to bisect their own shaders to find it (live-caught 2026-07-20, cost
            // a full launch cycle over a compile option declared as 0.35 instead of an integer).
            throw new IllegalArgumentException(
                    "Compile option '" + option.name() + "' has non-integer value \"" + value
                            + "\". Compile options must be whole numbers -- declare a fraction as an"
                            + " integer percent and divide in the shader (e.g. 35 with"
                            + " `#define X (float(X_PCT) / 100.0)`). Runtime options take real numbers.",
                    e);
        }
    }

    /** Converts a raw TOML profile literal ({@link Boolean}/{@link Number}/{@link String}) to the row-widget string form. */
    public static String canonicalize(PackOption option, Object rawTomlValue) {
        if (option.isBoolean()) {
            return toBooleanRaw(rawTomlValue) ? "1" : "0";
        }
        if (option.range() != null) {
            return String.valueOf(toDoubleRaw(rawTomlValue));
        }
        if (rawTomlValue instanceof String s) {
            return s;
        }
        if (rawTomlValue instanceof Number n) {
            return String.valueOf(n.intValue());
        }
        return String.valueOf(rawTomlValue);
    }

    /** Whether a profile's literal for {@code option} differs from the widget's current string value. */
    public static boolean valuesDiffer(PackOption option, String currentValue, Object profileRawValue) {
        String canon = canonicalize(option, profileRawValue);
        if (option.range() != null) {
            try {
                return Math.abs(Double.parseDouble(currentValue) - Double.parseDouble(canon)) > 1e-6;
            } catch (NumberFormatException e) {
                return !currentValue.equals(canon);
            }
        }
        return !currentValue.equals(canon);
    }

    private static boolean toBooleanRaw(Object v) {
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0;
        if (v instanceof String s) return s.equals("1") || s.equalsIgnoreCase("true");
        return false;
    }

    private static double toDoubleRaw(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        return Double.parseDouble(String.valueOf(v));
    }
}

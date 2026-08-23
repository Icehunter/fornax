package dev.icehunter.fornax.pack.option;

import org.jspecify.annotations.Nullable;
import java.util.List;
import java.util.Map;

public record PackOption(String name, OptionType type, @Nullable OptionRange range,
                         List<String> allowedValues, boolean isBoolean, boolean booleanDefaultOn,
                         String rawDefault, String label, Map<String, String> enumNames) {

    /** The value this option holds before any user/profile override: boolean form => "1"/"0". */
    public String defaultValue() {
        if (isBoolean) return booleanDefaultOn ? "1" : "0";
        return rawDefault;
    }
}

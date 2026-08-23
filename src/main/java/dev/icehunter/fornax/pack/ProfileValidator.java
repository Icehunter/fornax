package dev.icehunter.fornax.pack;

import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.pack.option.PackOption;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Load-time sanity checks over parsed screens/options that warn (never fail) on author drift. */
public final class ProfileValidator {
    private ProfileValidator() {}

    /** Every profile value naming an option absent from the scanned option table, as "Profile.KEY". */
    public static List<String> unknownProfileKeys(ScreensSpec screens, Map<String, PackOption> options) {
        List<String> unknown = new ArrayList<>();
        for (Map.Entry<String, ProfileSpec> profile : screens.profiles().entrySet()) {
            for (String key : profile.getValue().values().keySet()) {
                if (!options.containsKey(key)) {
                    unknown.add(profile.getKey() + "." + key);
                }
            }
        }
        return unknown;
    }

    /** Logs a WARN for every profile value naming an option absent from the scanned option table. */
    public static void warnUnknownProfileKeys(ScreensSpec screens, Map<String, PackOption> options) {
        for (String entry : unknownProfileKeys(screens, options)) {
            FornaxMod.LOGGER.warn("[Fornax] profile sets unknown option '{}' (ignored)", entry);
        }
    }
}

package dev.icehunter.fornax.pack;

import dev.icehunter.fornax.pack.option.OptionRange;
import dev.icehunter.fornax.pack.option.PackOption;

import java.util.List;
import java.util.Map;

/**
 * The resolved meaning of one {@code screens.toml} element token, independent of any Minecraft/GUI
 * class -- {@code PackSettingsScreen} switches on this to decide which vanilla widget to
 * build, but the token-classification rules themselves are unit-tested here in isolation.
 */
public sealed interface ScreenElement {
    /** A plain option name -- renders as a slider or cycle button depending on {@link
     * dev.icehunter.fornax.pack.option.PackOptionValues#rendersAsSlider}. {@code NAME|requires:OTHER}
     * greys the row out while the two-state option {@code OTHER} is off. */
    record Option(PackOption option, String requires) implements ScreenElement {
        /** The plain, ungated form. */
        public Option(PackOption option) {
            this(option, null);
        }
    }

    /** {@code [screenId]} -- a button opening the named nested screen. */
    record ScreenLink(String screenId, String title) implements ScreenElement {}

    /** The literal token {@code <profile>} -- a cycler over {@code screens.profiles} keys. */
    record ProfileCycler() implements ScreenElement {}

    /** The literal token {@code <empty>} -- a layout spacer, no option/action attached. */
    record Empty() implements ScreenElement {}

    /** {@code <meta:NAME>} -- a compound control staging {@code meta}'s assignments across its tiers. */
    record MetaRef(String metaId, MetaSpec meta) implements ScreenElement {}

    /** {@code <group:Title>} -- starts a named group; the rows that follow belong to it until the
     * next group header or the end of the page. YACL pages render it as an expander that starts
     * OPEN; {@code <group:Title|collapsed>} opts a busy section back into starting folded, and
     * {@code <group:Title|requires:NAME>} greys every row in the group out while the two-state
     * option {@code NAME} is off (modifiers combine in any order). The legacy screen ignores
     * group headers (rows still render, just ungrouped and ungated). */
    record GroupHeader(String title, boolean collapsed, String requires) implements ScreenElement {
        /** The default form: an expanded, ungated section. */
        public GroupHeader(String title) {
            this(title, false, null);
        }

        /** Collapse without a gate. */
        public GroupHeader(String title, boolean collapsed) {
            this(title, collapsed, null);
        }
    }

    /**
     * Classifies one element token from {@code MainScreenSpec#elements()}/{@code ScreenSpec#elements()}.
     *
     * @throws FornaxPackError if the token names an option not present in {@code options}, a screen
     *                         link {@code [id]} whose {@code id} isn't a key of {@code screens.screens()},
     *                         an unknown modifier, or a {@code requires:} target that is missing or
     *                         not a two-state option
     */
    static ScreenElement resolve(String token, ScreensSpec screens, Map<String, PackOption> options) {
        if (token.equals("<profile>")) {
            return new ProfileCycler();
        }
        if (token.equals("<empty>")) {
            return new Empty();
        }
        if (token.startsWith("<group:") && token.endsWith(">")) {
            String body = token.substring("<group:".length(), token.length() - 1);
            // Modifiers ride after pipes, in any order. Anything unrecognized is a typo that
            // would otherwise silently become part of the visible title.
            String[] parts = body.split("\\|");
            boolean collapsed = false;
            String requires = null;
            for (int i = 1; i < parts.length; i++) {
                String modifier = parts[i].trim();
                if (modifier.equals("collapsed")) {
                    collapsed = true;
                } else if (modifier.startsWith("requires:")) {
                    requires = requireTwoState(token, modifier, options);
                } else {
                    throw new FornaxPackError("screens.toml", token,
                            "unknown group modifier '" + modifier
                                    + "' (only 'collapsed' and 'requires:NAME' exist)");
                }
            }
            String title = parts[0].trim();
            if (title.isEmpty()) {
                throw new FornaxPackError("screens.toml", token, "group header has an empty title");
            }
            return new GroupHeader(title, collapsed, requires);
        }
        if (token.startsWith("<meta:") && token.endsWith(">")) {
            String id = token.substring("<meta:".length(), token.length() - 1);
            MetaSpec meta = screens.metas().get(id);
            if (meta == null) {
                throw new FornaxPackError("screens.toml", token, "references unknown meta '" + id + "'");
            }
            return new MetaRef(id, meta);
        }
        if (token.length() > 2 && token.startsWith("[") && token.endsWith("]")) {
            String id = token.substring(1, token.length() - 1);
            ScreenSpec spec = screens.screens().get(id);
            if (spec == null) {
                throw new FornaxPackError("screens.toml", token, "screen link references unknown screen '" + id + "'");
            }
            return new ScreenLink(id, spec.title());
        }
        // An option row, optionally gated: NAME|requires:OTHER.
        String name = token;
        String requires = null;
        int pipe = token.indexOf('|');
        if (pipe >= 0) {
            name = token.substring(0, pipe).trim();
            String modifier = token.substring(pipe + 1).trim();
            if (!modifier.startsWith("requires:")) {
                throw new FornaxPackError("screens.toml", token,
                        "unknown option modifier '" + modifier + "' (only 'requires:NAME' exists)");
            }
            requires = requireTwoState(token, modifier, options);
        }
        PackOption option = options.get(name);
        if (option == null) {
            throw new FornaxPackError("screens.toml", token, "references unknown option '" + name + "'");
        }
        return new Option(option, requires);
    }

    /** Validates a {@code requires:NAME} modifier: the target must exist and be two-state, or the
     * gate could never be satisfied and every gated row would be permanently dead. */
    private static String requireTwoState(String token, String modifier, Map<String, PackOption> options) {
        String target = modifier.substring("requires:".length()).trim();
        PackOption governor = options.get(target);
        if (governor == null) {
            throw new FornaxPackError("screens.toml", token,
                    "requires unknown option '" + target + "'");
        }
        if (!isTwoState(governor)) {
            throw new FornaxPackError("screens.toml", token,
                    "requires '" + target + "', which is not a two-state option");
        }
        return target;
    }

    /** The value-shape half of the screen module's toggle test (declaration-syntax agnostic, like
     * that one): exactly two states, zero and one. Duplicated here rather than imported because
     * the screen module depends on this package, not the reverse. */
    private static boolean isTwoState(PackOption option) {
        if (option.isBoolean()) {
            return true;
        }
        List<String> allowed = option.allowedValues();
        if (allowed.size() == 2 && allowed.contains("0") && allowed.contains("1")) {
            return true;
        }
        OptionRange range = option.range();
        return range != null && range.min() == 0.0 && range.max() == 1.0 && range.step() == 1.0;
    }
}

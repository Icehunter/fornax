package dev.icehunter.fornax.screen;

import dev.icehunter.fornax.pack.PackModel;
import dev.icehunter.fornax.pack.ScreenSpec;
import dev.isxander.yacl3.api.ButtonOption;
import dev.icehunter.fornax.FornaxMod;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Factory for the pack-agnostic YACL "manage" screen -- THE pack-settings entry point. Not a {@code
 * Screen} subclass itself (YACL owns construction/layout, exactly like {@link FornaxSettingsScreen}).
 * The screen title is the loaded pack's own display name.
 *
 * <p>v1 carries a single "Manage" category holding only the Shader Options bridge into the legacy
 * bespoke option pages. Import / Export / Defaults live as chrome -- {@code
 * mixin.yacl.CategoryTabMixin} injects them into YACL's own right-side button cluster (beside
 * search/Reset/Undo/Done), scoped to screens registered via {@link PackChromeActions}, which {@link
 * #create} does below before returning. Future feature rounds migrate a pack's own options off
 * the legacy screen category by category; each new category is a one-line insertion into
 * the {@link #create} category-supplier list, nothing structural.
 *
 * <p><b>Fresh-parent law (both directions):</b> every sub-screen this opens is handed a
 * FRESHLY-BUILT {@code PackManageScreen} from the ORIGINAL {@code parent}, never the YACL instance
 * currently open. The Shader Options bridge sets the bespoke screen's {@code exitScreen} to a fresh
 * manage screen, and the chrome-injected Defaults confirm's return target (Yes and No alike) is a
 * fresh manage screen -- so a rebuild triggered on either sub-screen can never be silently reverted
 * by a stale instance on return.
 */
public final class PackManageScreen {
    private PackManageScreen() {}

    public static Screen create(Screen parent, PackModel pack) {
        // One shared edit session across every migrated page's rows (staging + one latched apply).
        PackEditSession session = new PackEditSession(pack);

        // One native YACL category per `[yacl] pages` entry, all bound into the one shared session.
        //
        // There used to be a "Manage" category first, holding a single row that opened the legacy
        // PackSettingsScreen. Both are gone. A pack's settings are its `[yacl] pages` and nothing
        // else, so there is one place to look rather than two, and a row labelled "Shader Options..."
        // inside a screen already reached through "Shader Options..." is not a route anyone needs.
        List<Supplier<ConfigCategory>> categories = new ArrayList<>();
        for (String pageId : pack.screens().yaclPages()) {
            ScreenSpec page = pack.screens().screens().get(pageId);
            if (page == null) {
                continue; // validated fatal at load (MetaValidator); defensive
            }
            categories.add(() -> YaclPackRows.category(session, page, pack.screens()));
        }

        // A pack declaring no `[yacl] pages` has nothing to show. YACL cannot build a screen with
        // zero categories, and an empty one would be worse than not opening: return the caller's own
        // screen so the click is simply inert. FornaxPacksTab decides whether to offer the button at
        // all, so reaching here with nothing is already unusual.
        if (categories.isEmpty()) {
            FornaxMod.LOGGER.warn("[Fornax] Pack '{}' declares no [yacl] pages; it has no settings screen",
                    pack.meta().name());
            return parent;
        }

        YetAnotherConfigLib.Builder builder = YetAnotherConfigLib.createBuilder()
                .title(Component.literal(pack.meta().name()));
        categories.forEach(category -> builder.category(category.get()));
        // The Save/Done button's own commit point (latch law): at most one rebuild per Save. Every
        // OTHER normal way of leaving this screen (Cancel, Escape, any close) also settles on
        // session.apply() -- see mixin.yacl.YACLScreenCloseMixin, which is what actually enforces "at
        // most one rebuild" as a screen-wide invariant rather than a Save-button-only one: apply() is
        // idempotent on a clean session, so a stray extra call here is always free.
        builder.save(session::apply);
        Screen screen = builder.build().generateScreen(parent);
        // Chrome-injection scoping (mixin.yacl.CategoryTabMixin) AND apply-on-close scoping
        // (mixin.yacl.YACLScreenCloseMixin): registered before returning, since YACL builds its
        // CategoryTabs lazily in init(), never at construction -- see PackChromeActions' own doc for
        // why keying on this exact instance is always safe, and for why the ONE session built above is
        // threaded through here too (apply-on-close routes into it by name, not by re-deriving it).
        PackChromeActions.register(screen, pack, parent, session);
        return screen;
    }


    private static ButtonOption buildShaderOptionsButton(Screen parent, PackModel pack) {
        return ButtonOption.createBuilder()
                .name(Component.translatable("gui.fornax.manage.shader_options"))
                .text(Component.translatable("gui.fornax.manage.shader_options"))
                .description(OptionDescription.of(Component.translatable("gui.fornax.manage.shader_options.tooltip")))
                // Fresh-parent law both directions: the bespoke screen's exitScreen is a freshly-built
                // manage screen, never `screen`; on Done/Escape-at-root the stack lands back here.
                .action((screen, button) -> PackSettingsScreen.open(create(parent, pack), pack, "main"))
                .build();
    }
}

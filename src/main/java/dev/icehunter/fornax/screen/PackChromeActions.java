package dev.icehunter.fornax.screen;

import dev.icehunter.fornax.pack.PackModel;
import net.minecraft.client.gui.screens.Screen;

import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * Scoping registry letting {@code mixin.yacl.CategoryTabMixin}'s chrome injection find the {@link
 * PackModel} and original parent {@link Screen} for a YACL screen built by {@link PackManageScreen},
 * without threading either through YACL's own {@code CategoryTab} construction path (which the mixin
 * has no seam to extend -- YACL builds that screen with no knowledge fornax exists). {@code
 * mixin.yacl.YACLScreenCloseMixin} shares this same registry to reach the screen's {@link
 * PackEditSession} on the outer {@code YACLScreen} instance -- see {@link Context#applyOnClose()}/
 * {@link Context#discardPending()} for that seam.
 *
 * <p>{@link PackManageScreen#create} calls {@link #register} on its built screen BEFORE returning it
 * -- before that screen's {@code init()} (and therefore its {@code CategoryTab}s) ever runs, however
 * late that ends up being, since YACL builds tabs lazily in {@code init()} rather than at
 * construction. The registration is keyed on the exact {@code Screen} instance, so it's always in
 * place by the time the mixin's constructor injection looks it up.
 *
 * <p>A {@link WeakHashMap} keyed on the screen instance means an unregistered screen (e.g. the plain
 * Engine settings screen, {@code FornaxSettingsScreen}) is simply absent from the map -- the mixin's
 * {@link #lookup} returns empty and does nothing, leaving stock YACL chrome untouched there. An
 * abandoned {@code PackManageScreen} instance (closed, garbage collected) never leaks an entry here
 * either.
 */
public final class PackChromeActions {
    private PackChromeActions() {}

    private static final Map<Screen, Context> REGISTRY = new WeakHashMap<>();

    /**
     * The pack a chrome-injected {@code YACLScreen} belongs to, plus its fresh-parent-law parent, plus
     * the two commit actions {@code mixin.yacl.YACLScreenCloseMixin} routes YACL's own close/undo paths
     * into. {@code applyOnClose} and {@code discardPending} are bound directly to the screen's ONE
     * shared {@link PackEditSession} ({@link PackEditSession#apply()}/{@link PackEditSession#discard()}
     * respectively) at {@link #register} time -- exposed here as bare {@link Runnable}s, not the
     * package-private {@link PackEditSession} type itself, since the mixin lives in a different package
     * ({@code dev.icehunter.fornax.mixin.yacl}) and has no access to it.
     */
    public record Context(PackModel pack, Screen parent, Runnable applyOnClose, Runnable discardPending) {}

    static void register(Screen yaclScreen, PackModel pack, Screen parent, PackEditSession session) {
        REGISTRY.put(yaclScreen, new Context(pack, parent, session::apply, session::discard));
    }

    /** Called from {@code mixin.yacl.CategoryTabMixin} -- empty means "not our screen, do nothing". */
    public static Optional<Context> lookup(Screen yaclScreen) {
        return Optional.ofNullable(REGISTRY.get(yaclScreen));
    }
}

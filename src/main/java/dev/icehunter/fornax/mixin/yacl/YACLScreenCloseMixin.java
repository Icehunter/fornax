package dev.icehunter.fornax.mixin.yacl;

import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.screen.PackChromeActions;
import dev.isxander.yacl3.gui.YACLScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fix for "settings changes don't stick": routes every normal way of leaving a {@code
 * PackManageScreen}-built {@code YACLScreen} into {@code PackEditSession#apply()} (package-private,
 * hence {@code @code} not {@code @link} throughout this class) instead of only the explicit Save/Done
 * button, and leaves YACL's own "Undo" button as the
 * one remaining discard action. User repro: change a Quality-tab tier, leave the screen (by any path
 * other than the literal "Save"/"Done" button), and the value silently reverted -- the highest-
 * confidence mechanism was YACL's bottom-right button relabeling itself "Cancel" the instant any
 * option goes dirty, discarding on click since compile options never live-preview (nothing visibly
 * changes before Done, so a Cancel click is indistinguishable from a Done click at a glance).
 *
 * <p>Ground truth (CFR-decompiled {@code YACLScreen.java}, 3.9.5+26.2-fabric): {@code onClose()} --
 * whose only body is {@code GuiUtils.setScreen(this.parent)} -- is the ONE method every close path
 * funnels through: {@code finishOrSave()}'s clean-session branch calls it directly; {@code
 * cancelOrReset()}'s dirty branch ("Cancel", the button {@code saveFinishedButton}/{@code
 * cancelResetButton} swap to whenever {@code pendingChanges()} is true) calls {@code
 * Option::forgetPendingValue} for every option -- which only resets YACL's OWN internal
 * pending-value bookkeeping, never touches this mod's {@code PackEditSession#staged} map or the
 * live-preview GPU buffer, per the diagnosis -- and THEN calls {@code onClose()}; and the vanilla ESC
 * key path calls it too,
 * whenever {@code shouldCloseOnEsc()} allows the close. Injecting ONE apply at this single choke
 * point turns Done, Cancel, and a clean ESC all into "apply, then close" -- Cancel's label lied about
 * discarding all along, this makes the behavior match the label's OLD (bug-free) intent instead of
 * fixing the label.
 *
 * <p>{@code shouldCloseOnEsc()} still blocks ESC while {@code pendingChanges()} is true (flashing
 * "save before exit"). That guard existed to stop ESC from silently discarding an edit -- now that
 * closing always applies instead, the guard is pure friction, so this mixin also force-allows ESC to
 * close a scoped screen unconditionally, matching the "ESC is muscle-memory back-out" expectation the
 * diagnosis calls out.
 *
 * <p>{@code undo()} (the "Undo" button, always visible/enabled only while dirty) is the one action
 * left that must genuinely throw edits away: it already resets YACL's own pending-value bookkeeping,
 * but -- same gap as Cancel -- never touched this mod's session state before this mixin, which is
 * exactly the {@code PackEditSession#discard()}-never-called gap the diagnosis documents (mechanism
 * #2: a live-previewed runtime slider's GPU write orphaning instead of reverting). Injecting {@code
 * PackEditSession#discard()} here closes that gap at its one real call site.
 *
 * <p>Scoped via {@link PackChromeActions} exactly like {@link CategoryTabMixin}: {@code lookup}
 * returns empty for any {@code YACLScreen} {@link dev.icehunter.fornax.screen.PackManageScreen} did
 * not build (e.g. the plain Engine settings screen), so this mixin is a complete no-op there and
 * stock YACL behavior (Cancel discards, ESC blocked while dirty) is untouched. Same fail-soft law as
 * {@link CategoryTabMixin}: everything below the registry lookup runs behind one {@link Throwable}
 * catch per hook, logging at most once total, never crashing the screen.
 */
@Mixin(YACLScreen.class)
public abstract class YACLScreenCloseMixin {
    private static final AtomicBoolean WARNED = new AtomicBoolean(false);

    @Inject(method = "onClose", at = @At("HEAD"), remap = false)
    private void fornax$applyOnClose(CallbackInfo ci) {
        try {
            PackChromeActions.lookup((Screen) (Object) this).ifPresent(ctx -> ctx.applyOnClose().run());
        } catch (Throwable t) {
            fornax$warnOnce(t);
        }
    }

    @Inject(method = "undo", at = @At("HEAD"), remap = false)
    private void fornax$discardOnUndo(CallbackInfo ci) {
        try {
            PackChromeActions.lookup((Screen) (Object) this).ifPresent(ctx -> ctx.discardPending().run());
        } catch (Throwable t) {
            fornax$warnOnce(t);
        }
    }

    @Inject(method = "shouldCloseOnEsc", at = @At("HEAD"), cancellable = true, remap = false)
    private void fornax$alwaysAllowEscToClose(CallbackInfoReturnable<Boolean> cir) {
        try {
            if (PackChromeActions.lookup((Screen) (Object) this).isPresent()) {
                cir.setReturnValue(true);
            }
        } catch (Throwable t) {
            fornax$warnOnce(t);
        }
    }

    @Unique
    private static void fornax$warnOnce(Throwable t) {
        if (!WARNED.getAndSet(true)) {
            FornaxMod.LOGGER.warn("[Fornax] YACL close/undo apply hook unavailable", t);
        }
    }
}

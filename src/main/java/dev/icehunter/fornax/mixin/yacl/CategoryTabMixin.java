package dev.icehunter.fornax.mixin.yacl;

import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.screen.PackChromeActions;
import dev.icehunter.fornax.screen.PackManageScreen;
import dev.icehunter.fornax.screen.PackValuesActions;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.gui.OptionDescriptionWidget;
import dev.isxander.yacl3.gui.SearchFieldWidget;
import dev.isxander.yacl3.gui.YACLScreen;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Injects the Import.../Export.../Defaults... chrome buttons into YACL's own right-side bottom
 * cluster (search field / Reset / Undo / Done) on a {@code PackManageScreen}-built {@code
 * YACLScreen}, keeping these three actions out of the Manage tab's option list as {@code
 * ButtonOption}s.
 *
 * <p>Ground truth (CFR-decompiled from the real 3.9.5+26.2-fabric jar, {@code
 * dev.isxander.yacl3.gui.YACLScreen$CategoryTab}'s constructor): {@code saveFinishedButton}/{@code
 * cancelResetButton}/{@code undoButton} are laid out first, then {@code searchField} at {@code y =
 * undoButton.getY() - 22} spanning the same padded column width as those buttons, then {@code
 * optionList}, then {@code descriptionWidget} filling the pane above the search field -- ALL of
 * these positions are computed exactly ONCE, right here in the constructor ({@code doLayout} only
 * repositions {@code optionList} on resize, never the others). This mixin injects at the TAIL of
 * that same constructor, once, matching that one-time layout.
 *
 * <p>{@code descriptionWidget}'s bounds are the one exception to "computed once": they come from a
 * {@code Supplier<ScreenRectangle>} captured at construction and re-evaluated by {@code
 * extractWidgetRenderState} on EVERY frame (see {@link OptionDescriptionWidgetAccessor}'s own doc),
 * so shrinking its height to make room for the new button row requires replacing that supplier, not
 * a one-off {@code setHeight} call.
 *
 * <p>Scoped via {@link PackChromeActions}: {@link PackManageScreen#create} registers its built
 * screen there before returning it, so an unregistered {@code YACLScreen} (e.g. the plain Engine
 * settings screen, {@code FornaxSettingsScreen}) leaves this mixin a no-op -- {@code lookup} returns
 * empty and stock YACL chrome is untouched.
 *
 * <p><b>Fail-soft law:</b> everything below the outer {@code try} -- the registry lookup, every
 * shadowed-field read, the accessor cast, all layout math -- runs behind a single {@link Throwable}
 * catch that logs ONE warning total (a static flag, never per-tab, never per-frame) and leaves stock
 * chrome exactly as YACL built it. This must never crash the screen. The {@code @Shadow}/{@code
 * @Accessor} DECLARATIONS themselves are outside that net: if YACL ever renames one of these
 * members, mixin-apply fails hard at load, same as every other mixin in this mod.
 */
@Mixin(YACLScreen.CategoryTab.class)
public abstract class CategoryTabMixin {
    private static final AtomicBoolean WARNED = new AtomicBoolean(false);
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_GAP = 4;
    /** Row height (20) plus a 4px clearance gap to the search field below it. */
    private static final int DESCRIPTION_SHRINK = ROW_HEIGHT + ROW_GAP;

    @Shadow(remap = false)
    private SearchFieldWidget searchField;

    @Shadow(remap = false)
    private OptionDescriptionWidget descriptionWidget;

    @Unique
    private Button fornax$importButton;
    @Unique
    private Button fornax$exportButton;
    @Unique
    private Button fornax$defaultsButton;

    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void fornax$injectChrome(YACLScreen screen, ConfigCategory category, ScreenRectangle tabArea, CallbackInfo ci) {
        try {
            PackChromeActions.lookup(screen).ifPresent(ctx -> {
                int x0 = this.searchField.getX();
                int totalWidth = this.searchField.getWidth();
                int y = this.searchField.getY() - DESCRIPTION_SHRINK;
                int buttonWidth = (totalWidth - ROW_GAP * 2) / 3;
                int lastWidth = totalWidth - (buttonWidth + ROW_GAP) * 2;

                // Atomicity: build everything into locals and perform the description-pane
                // supplier swap BEFORE any field assignment -- if any step throws, no field is
                // set, visitChildren adds nothing, and stock chrome survives untouched (the
                // fail-soft law's all-or-nothing guarantee).
                Button importBtn = Button.builder(
                                Component.translatable("gui.fornax.manage.import"),
                                btn -> PackValuesActions.importSettings(ctx.pack()))
                        .pos(x0, y)
                        .size(buttonWidth, ROW_HEIGHT)
                        .tooltip(Tooltip.create(Component.translatable("gui.fornax.manage.import.tooltip")))
                        .build();

                Button exportBtn = Button.builder(
                                Component.translatable("gui.fornax.manage.export"),
                                btn -> PackValuesActions.export(ctx.pack()))
                        .pos(x0 + buttonWidth + ROW_GAP, y)
                        .size(buttonWidth, ROW_HEIGHT)
                        .tooltip(Tooltip.create(Component.translatable("gui.fornax.manage.export.tooltip")))
                        .build();

                Button defaultsBtn = Button.builder(
                                Component.translatable("gui.fornax.manage.reset"),
                                // Fresh-parent law: the confirm's return target (Yes and No alike) is
                                // a freshly-built manage screen from the ORIGINAL parent, never the
                                // currently-open YACL instance `screen`.
                                btn -> PackValuesActions.resetToDefaults(
                                        PackManageScreen.create(ctx.parent(), ctx.pack()), ctx.pack()))
                        .pos(x0 + (buttonWidth + ROW_GAP) * 2, y)
                        .size(lastWidth, ROW_HEIGHT)
                        .tooltip(Tooltip.create(Component.translatable("gui.fornax.manage.reset.tooltip")))
                        .build();

                OptionDescriptionWidgetAccessor accessor = (OptionDescriptionWidgetAccessor) this.descriptionWidget;
                Supplier<ScreenRectangle> original = accessor.fornax$getDimensions();
                accessor.fornax$setDimensions(() -> {
                    ScreenRectangle base = original.get();
                    return new ScreenRectangle(base.left(), base.top(), base.width(),
                            Math.max(0, base.height() - DESCRIPTION_SHRINK));
                });

                this.fornax$importButton = importBtn;
                this.fornax$exportButton = exportBtn;
                this.fornax$defaultsButton = defaultsBtn;
            });
        } catch (Throwable t) {
            fornax$warnOnce(t);
        }
    }

    @Inject(method = "visitChildren", at = @At("TAIL"), remap = false)
    private void fornax$visitChromeChildren(Consumer<AbstractWidget> consumer, CallbackInfo ci) {
        try {
            if (this.fornax$importButton != null) {
                consumer.accept(this.fornax$importButton);
            }
            if (this.fornax$exportButton != null) {
                consumer.accept(this.fornax$exportButton);
            }
            if (this.fornax$defaultsButton != null) {
                consumer.accept(this.fornax$defaultsButton);
            }
        } catch (Throwable t) {
            fornax$warnOnce(t);
        }
    }

    @Unique
    private static void fornax$warnOnce(Throwable t) {
        if (!WARNED.getAndSet(true)) {
            FornaxMod.LOGGER.warn("[Fornax] YACL chrome injection unavailable", t);
        }
    }
}

package dev.icehunter.fornax.screen;

import com.google.common.collect.ImmutableList;
import dev.icehunter.fornax.config.FornaxConfig;
import dev.icehunter.fornax.config.FornaxSettings;
import dev.icehunter.fornax.pack.DiscoveredPack;
import dev.icehunter.fornax.pack.PackDiscovery;
import dev.icehunter.fornax.pack.PackModel;
import dev.icehunter.fornax.pack.PackSwitch;
import dev.icehunter.fornax.pack.ShadersEnabledFlip;
import dev.icehunter.fornax.pack.graph.GraphRunner;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.CustomTabProvider;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.gui.YACLScreen;
import dev.isxander.yacl3.gui.tab.TabExt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The "Shader Packs" YACL category, rendered as a custom in-screen pack browser instead of YACL
 * options. Implements BOTH {@link ConfigCategory} (name + empty groups, so it slots into the normal
 * {@code YetAnotherConfigLib.Builder.category(...)}) AND {@link CustomTabProvider}: YACL's tab
 * dispatch checks {@code instanceof CustomTabProvider} before building a normal CategoryTab and lets
 * this class hand back its own vanilla {@link Tab} laid out in the tab-content rectangle (save/undo
 * buttons belong to CategoryTab, not the screen -- a custom tab owns its whole footer).
 */
public final class FornaxPacksTab implements ConfigCategory, CustomTabProvider {
    private final Screen parent;

    public FornaxPacksTab(Screen parent) {
        this.parent = parent;
    }

    @Override
    public Component name() {
        return Component.translatable("gui.fornax.category.shader_packs");
    }

    @Override
    public ImmutableList<OptionGroup> groups() {
        return ImmutableList.of();
    }

    @Override
    public Component tooltip() {
        return Component.empty();
    }

    @Override
    public Tab createTab(YACLScreen screen, ScreenRectangle tabArea) {
        FornaxSettings settings = FornaxConfig.get();
        PackListState state = new PackListState(settings.shadersEnabled, settings.activePack);
        return new PacksTab(this.parent, state);
    }

    /**
     * Three zones inside the tab rectangle, all reading {@link PackListState} live every frame so
     * Apply's post-apply {@link PackListState#refresh} converges the whole UI with no widget rebuild:
     * <ul>
     *   <li>Header: the master shaders toggle ({@link PackRow.Cycle}, STAGED) -- moved here from the
     *       Engine category; two widgets on one field is the dual-pending-value hazard the
     *       fresh-parent law exists for.</li>
     *   <li>Content: a scrollable {@link PackRowList} -- "None" plus one {@link PackRow.Select} per
     *       discovered pack; staged row highlighted, loaded pack marked "Active", and the
     *       staged==loaded (not None) row carries a separate square settings-cog button (see
     *       {@link PackRowList#addRowWithAccessory}) opening {@link PackManageScreen} for the
     *       LOADED pack (which bridges on to the option pages).</li>
     *   <li>Footer: Open Pack Folder / Pack Settings (same enablement as the cog button) / Apply
     *       (enabled only while {@link PackListState#isDirty()}).</li>
     * </ul>
     * Discovery handles from the row scan are closed after their names are read (display-only scan;
     * the pack actually activated is loaded through {@code PackSwitch}'s own separate discovery).
     */
    private static final class PacksTab implements TabExt {
        private static final int HEADER_HEIGHT = 28;
        private static final int FOOTER_HEIGHT = 30;
        private static final int ROW_HEIGHT = 22;
        private static final int BUTTON_GAP = 6;
        /** The per-row settings-cog button: a small square inset from {@link #ROW_HEIGHT} by 4px
         * (2px padding top/bottom), sitting {@link #SETTINGS_BUTTON_GAP} to the right of the pack
         * row it belongs to (see {@link PackRowList#addRowWithAccessory}). */
        private static final int SETTINGS_BUTTON_SIZE = ROW_HEIGHT - 4;
        private static final int SETTINGS_BUTTON_GAP = 2;
        private static final Identifier SETTINGS_COG_SPRITE = Identifier.fromNamespaceAndPath("fornax", "pack_settings_cog");

        private final Screen parent;
        private final PackListState state;
        private final PackRow shadersToggle;
        private final PackRowList list;
        private final PackButton openFolder;
        private final PackButton packSettings;
        private final PackButton apply;

        PacksTab(Screen parent, PackListState state) {
            this.parent = parent;
            this.state = state;

            this.shadersToggle = new PackRow.Cycle<>(
                    Component.translatable("gui.fornax.packs.header.label").getString(),
                    List.of(Boolean.TRUE, Boolean.FALSE),
                    state.stagedEnabled(),
                    v -> Component.translatable(v
                            ? "gui.fornax.packs.header.enabled"
                            : "gui.fornax.packs.header.disabled").getString(),
                    Component.translatable("gui.fornax.packs.header.tooltip").getString(),
                    state::stageEnabled,
                    "?")
                    .withValueSupplier(state::stagedEnabled);
            // Native-YACL-look row (see PackRow#useVanillaChrome) -- this header lives beside the
            // Engine tab's own YACL option rows, so it renders through the same vanilla button-sprite
            // chrome those rows use. The live value supplier + staging callback wired just above are
            // untouched; only the drawing path changes.
            this.shadersToggle.useVanillaChrome();

            this.list = new PackRowList(Minecraft.getInstance(), 100, 100, HEADER_HEIGHT, ROW_HEIGHT);
            buildRows();

            this.openFolder = new PackButton(0, 0, 100, 20,
                    Component.translatable("gui.fornax.packs.open_folder").getString(),
                    () -> Util.getPlatform().openUri(PackDiscovery.shaderpacksDir().toUri()))
                    .vanillaChrome();
            this.packSettings = new PackButton(0, 0, 100, 20,
                    Component.translatable("gui.fornax.packs.pack_settings").getString(),
                    this::openLoadedPackSettings)
                    .withActiveSupplier(this::packSettingsEnabled)
                    .withTooltip(Component.translatable("gui.fornax.packs.pack_settings.tooltip").getString())
                    .vanillaChrome();
            this.apply = new PackButton(0, 0, 100, 20,
                    Component.translatable("gui.fornax.packs.apply").getString(),
                    this::apply)
                    .withActiveSupplier(this.state::isDirty)
                    .withTooltip(Component.translatable("gui.fornax.packs.apply.tooltip").getString())
                    .vanillaChrome();
        }

        /** Builds the None row + one row per discovered pack; closes every discovered handle after
         * reading its name (display-only scan -- PackSwitch re-discovers to activate). */
        private void buildRows() {
            this.list.addRow(new PackRow.Select(
                    Component.translatable("gui.fornax.packs.none").getString(),
                    Component.translatable("gui.fornax.packs.none.tooltip").getString(),
                    () -> this.state.stagedPack().isEmpty(),
                    () -> this.state.livePack().isEmpty(),
                    () -> this.state.stagePack("")));

            List<DiscoveredPack> discovered = PackDiscovery.discover();
            for (DiscoveredPack pack : discovered) {
                String name = pack.name();
                PackRow.Select row = new PackRow.Select(name,
                        Component.translatable("gui.fornax.packs.row.tooltip").getString(),
                        () -> this.state.stagedPack().equals(name),
                        () -> this.state.livePack().equals(name),
                        () -> this.state.stagePack(name));
                // Settings-cog button: visible/clickable only when this row is the loaded pack
                // (staged == live == name, and a pack is actually loaded), since settings edit the
                // LOADED pack only. A dedicated PackButton, not drawn inside the row -- the row's
                // own width shrinks to make room for it (see addRowWithAccessory).
                PackButton settingsButton = new PackButton(0, 0, SETTINGS_BUTTON_SIZE, SETTINGS_BUTTON_SIZE,
                        Component.translatable("gui.fornax.packs.settings_icon.tooltip").getString(),
                        this::openLoadedPackSettings)
                        .withTooltip(Component.translatable("gui.fornax.packs.settings_icon.tooltip").getString())
                        .icon(SETTINGS_COG_SPRITE, 16)
                        .vanillaChrome();
                BooleanSupplier settingsVisible = () -> name.equals(this.state.stagedPack())
                        && name.equals(this.state.livePack())
                        && GraphRunner.currentPack() != null;
                this.list.addRowWithAccessory(row, settingsButton, SETTINGS_BUTTON_SIZE, SETTINGS_BUTTON_GAP, settingsVisible);
            }
            for (DiscoveredPack pack : discovered) {
                try {
                    pack.close();
                } catch (IOException e) {
                    // Best-effort cleanup -- display-only scan, no filesystem needs to stay open.
                }
            }
        }

        /** True only when the staged selection IS the currently loaded pack (not None). */
        private boolean packSettingsEnabled() {
            return !this.state.stagedPack().isEmpty()
                    && this.state.stagedPack().equals(this.state.livePack())
                    && GraphRunner.currentPack() != null;
        }

        /** Opens the LOADED pack's manage screen; fresh-parent law: its parent is a freshly-built
         * settings screen, so exiting the manage screen returns to a fresh settings screen. */
        private void openLoadedPackSettings() {
            PackModel current = GraphRunner.currentPack();
            if (current != null) {
                Minecraft.getInstance().gui.setScreen(
                        PackManageScreen.create(FornaxSettingsScreen.create(this.parent), current));
            }
        }

        /**
         * Runs the ordered apply plan, then reseeds the state from post-apply config so the header/
         * highlight/marker converge. A failing {@code PackSwitch.apply} that reverts activePack (and
         * forces shadersEnabled=false) is reflected by the refresh; a failing switch that also shows
         * an AlertScreen navigates to the fresh settings screen, which reseeds from live config
         * identically. Fresh-parent law: PackSwitch's alert returns to a freshly-built settings
         * screen, never this now-spent tab.
         */
        private void apply() {
            List<PackListState.Action> plan = this.state.applyPlan();
            // The alert-return target is only needed on the pack-switch path; a flip-only Apply
            // never shows an alert, so don't build a throwaway settings screen for it.
            Screen freshParent = plan.contains(PackListState.Action.PACK_SWITCH)
                    ? FornaxSettingsScreen.create(this.parent)
                    : null;
            for (PackListState.Action action : plan) {
                switch (action) {
                    case WRITE_ENABLED -> FornaxConfig.get().shadersEnabled = this.state.stagedEnabled();
                    case PACK_SWITCH -> PackSwitch.apply(this.state.stagedPack(), this.state.livePack(), freshParent);
                    case SHADERS_FLIP -> ShadersEnabledFlip.apply(this.state.stagedEnabled());
                }
            }
            FornaxSettings live = FornaxConfig.get();
            this.state.refresh(live.shadersEnabled, live.activePack);
        }

        @Override
        public Component getTabTitle() {
            return Component.translatable("gui.fornax.category.shader_packs");
        }

        @Override
        public @Nullable Tooltip getTooltip() {
            return null;
        }

        @Override
        public void visitChildren(Consumer<AbstractWidget> consumer) {
            consumer.accept(this.shadersToggle);
            consumer.accept(this.list);
            consumer.accept(this.openFolder);
            consumer.accept(this.packSettings);
            consumer.accept(this.apply);
        }

        @Override
        public void doLayout(ScreenRectangle area) {
            int contentWidth = Math.min(PackRowList.contentWidthFor(area.width()), area.width() - 16);
            int contentLeft = area.left() + (area.width() - contentWidth) / 2;

            this.shadersToggle.setPosition(contentLeft, area.top() + 4);
            this.shadersToggle.setWidth(contentWidth);
            this.shadersToggle.setHeight(20);

            int listTop = area.top() + HEADER_HEIGHT;
            int listHeight = area.height() - HEADER_HEIGHT - FOOTER_HEIGHT;
            this.list.updateSizeAndPosition(area.width(), listHeight, area.left(), listTop);

            int buttonWidth = (contentWidth - BUTTON_GAP * 2) / 3;
            int barY = area.top() + area.height() - FOOTER_HEIGHT + 5;
            this.openFolder.setPosition(contentLeft, barY);
            this.openFolder.setWidth(buttonWidth);
            this.packSettings.setPosition(contentLeft + buttonWidth + BUTTON_GAP, barY);
            this.packSettings.setWidth(buttonWidth);
            this.apply.setPosition(contentLeft + (buttonWidth + BUTTON_GAP) * 2, barY);
            this.apply.setWidth(contentWidth - (buttonWidth + BUTTON_GAP) * 2);
        }
    }
}

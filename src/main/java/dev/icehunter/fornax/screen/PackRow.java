package dev.icehunter.fornax.screen;

import dev.icehunter.fornax.FornaxMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Full-width, translucent settings rows: label on the left, value on the right, toggled/dragged in
 * place -- no opaque per-row button chrome (the only real buttons live in a screen's bottom bar).
 * Hosted by {@link PackRowList}. Every row STAGES its edits through a caller-supplied setter; nothing
 * here writes config, disk, or GPU state directly -- see {@link PackEditSession}.
 *
 * <p>Option rows may carry compact per-row AFFORDANCES at the right edge (see {@link
 * #addAffordance}): an undo glyph reverting the row's staged edit, and a reset glyph staging the
 * pack default. Both are ordinary staged edits -- they flow through the session and only persist on
 * Apply/Done, exactly like clicking the value itself.
 */
abstract class PackRow extends AbstractWidget {
    /** Formats a {@link Slider} row's float readout (was {@code OptionRow}'s, absorbed when that widget layer retired). */
    interface FloatFormatter {
        String format(float value);
    }

    /** Two-decimal display -- most 0-1(ish) strength/blend sliders. */
    static final FloatFormatter TWO_DECIMAL = v -> String.format("%.2f", v);

    /** Base translucent row fill; hover lightens it, matching the list-over-world settings look. */
    private static final int ROW_COLOR = 0x90000000;
    private static final int ROW_COLOR_HOVERED = 0x903A3A3A;
    private static final int LABEL_COLOR = 0xFFFFFFFF;
    private static final int VALUE_COLOR = 0xFFE0E0E0;
    /** Values differing from the PACK DEFAULT render yellow -- the convention every shaderpack UI
     * user already knows. (Staged-but-unapplied edits are signalled by the undo glyph, not color.) */
    private static final int VALUE_COLOR_NON_DEFAULT = 0xFFFFD867;
    private static final int AFFORDANCE_COLOR = 0xFF9A9A9A;
    private static final int AFFORDANCE_COLOR_HOVERED = 0xFFFFFFFF;
    private static final int PAD = 6;
    /** Width of one affordance glyph cell at the row's right edge. */
    private static final int AFFORDANCE_CELL = 13;

    /**
     * When set, overrides the row's own constructed-value dirtiness check -- screens rebuild their
     * rows on every init (back-navigation, resize), so "changed since the screen opened" must come
     * from the shared {@link PackEditSession}, not from a row instance's construction-time snapshot.
     */
    private BooleanSupplier dirtySupplier;

    /** When set, the value renders yellow while it differs from the pack-declared default. */
    private BooleanSupplier nonDefaultSupplier;

    /** Opt-in only (see {@link #useVanillaChrome}): draws the row background as the vanilla
     * button-sprite chrome ({@link VanillaChrome}) instead of the translucent fill, for the Shader
     * Packs tab's native-YACL-look rows. Default false leaves every existing caller (all of {@code
     * PackSettingsScreen}'s rows) byte-identical. */
    private boolean vanillaChrome;

    /** Affordance icons are DRAWN with fills, not font glyphs: the undo/reset arrows previously
     * rendered through the font chain, where a resource pack's glyph overrides colored them
     * orange/gold against the monochrome translucent look (live-caught). Primitive fills can't be
     * re-themed out from under us. */
    enum AffordanceIcon { UNDO, RESET_DEFAULT }

    private record Affordance(AffordanceIcon icon, String description, BooleanSupplier visible, Runnable action) {}

    /** Right-to-left from the row edge: the first added affordance occupies the outermost cell. */
    private final List<Affordance> affordances = new ArrayList<>(2);

    protected PackRow(Component label, String tooltip) {
        super(0, 0, 310, 20, label);
        if (tooltip != null && !tooltip.isEmpty()) {
            this.setTooltip(Tooltip.create(Component.literal(tooltip)));
        }
    }

    void dirtySupplier(BooleanSupplier supplier) {
        this.dirtySupplier = supplier;
    }

    void nonDefaultSupplier(BooleanSupplier supplier) {
        this.nonDefaultSupplier = supplier;
    }

    /** Opts this row into vanilla button-sprite chrome (see {@link #vanillaChrome}). Package-private
     * and one-way -- only {@code FornaxPacksTab} (the master toggle) and {@link Select} (every
     * pack-list row, {@code FornaxPacksTab}-exclusive) call this. */
    void useVanillaChrome() {
        this.vanillaChrome = true;
    }

    protected final int valueColor() {
        return this.nonDefaultSupplier != null && this.nonDefaultSupplier.getAsBoolean()
                ? VALUE_COLOR_NON_DEFAULT : VALUE_COLOR;
    }

    /**
     * Adds a compact glyph affordance at the row's right edge. Space is reserved for every
     * configured affordance regardless of visibility, so the value/track region never shifts as
     * edits come and go; the glyph itself only draws (and only accepts clicks) while {@code
     * visible} holds.
     */
    void addAffordance(AffordanceIcon icon, String description, BooleanSupplier visible, Runnable action) {
        this.affordances.add(new Affordance(icon, description, visible, action));
    }

    /** Right edge of the value/track region -- left of any reserved affordance cells. */
    protected final int contentRight() {
        return this.getX() + this.width - PAD - this.affordances.size() * AFFORDANCE_CELL;
    }

    private int affordanceCellLeft(int index) {
        return this.getX() + this.width - PAD - (index + 1) * AFFORDANCE_CELL;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        extractBackground(g);
        extractDecorations(g);
        Font font = Minecraft.getInstance().font;
        int textY = this.getY() + (this.height - 9) / 2 + 1;
        g.text(font, this.getMessage(), this.getX() + PAD, textY, LABEL_COLOR);
        extractValue(g, font, textY);

        for (int i = 0; i < this.affordances.size(); i++) {
            Affordance affordance = this.affordances.get(i);
            if (!affordance.visible().getAsBoolean()) {
                continue;
            }
            int cellLeft = affordanceCellLeft(i);
            boolean cellHovered = this.isHovered() && mouseX >= cellLeft && mouseX < cellLeft + AFFORDANCE_CELL;
            drawAffordanceIcon(g, affordance.icon(), cellLeft,
                    cellHovered ? AFFORDANCE_COLOR_HOVERED : AFFORDANCE_COLOR, cellHovered);
        }
    }

    /** The row's background: translucent fill by default (byte-identical to the pre-restyle look),
     * or -- opted in via {@link #useVanillaChrome} -- the same full-row vanilla button sprite YACL's
     * own option rows draw through. */
    private void extractBackground(GuiGraphicsExtractor g) {
        if (this.vanillaChrome) {
            VanillaChrome.drawButton(g, this.getX(), this.getY(), this.width, this.height, true, this.isHoveredOrFocused());
        } else {
            g.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + this.height, rowFillColor());
        }
    }

    /**
     * Sodium's own reset-arrow texture (10x10), referenced at runtime from the loaded Sodium jar --
     * never copied into ours. Sodium's ResetButton draws this same texture through the tinting blit
     * overload (with an ORANGE tint, which is exactly the accent that clashed here); rendering it
     * with our grey/white tint keeps the familiar icon shape inside the monochrome look. Sodium
     * ships no undo/back icon at all (assets/sodium/textures/gui holds only coffee_cup,
     * reset_button, tooltip_arrows), so UNDO always uses the drawn arrow.
     */
    private static final Identifier SODIUM_RESET_ICON = Identifier.fromNamespaceAndPath("sodium", "textures/gui/reset_button.png");

    private static Boolean sodiumResetIconPresent;

    private static boolean sodiumResetIconPresent() {
        if (sodiumResetIconPresent == null) {
            boolean present;
            try {
                present = Minecraft.getInstance().getResourceManager().getResource(SODIUM_RESET_ICON).isPresent();
            } catch (RuntimeException e) {
                present = false;
            }
            if (!present) {
                FornaxMod.LOGGER.info("[Fornax] Sodium reset icon texture not found; using the built-in drawn icon");
            }
            sodiumResetIconPresent = present;
        }
        return sodiumResetIconPresent;
    }

    /** Icons centered in their cell: dimmed light grey idle, white on hover. */
    private void drawAffordanceIcon(GuiGraphicsExtractor g, AffordanceIcon icon, int cellLeft, int color, boolean hovered) {
        int cy = this.getY() + this.height / 2;
        if (icon == AffordanceIcon.RESET_DEFAULT && sodiumResetIconPresent()) {
            g.blit(RenderPipelines.GUI_TEXTURED, SODIUM_RESET_ICON,
                    cellLeft + (AFFORDANCE_CELL - 10) / 2, cy - 5, 0.0f, 0.0f, 10, 10, 10, 10, color);
            return;
        }
        int x0 = cellLeft + (AFFORDANCE_CELL - 7) / 2;
        switch (icon) {
            case UNDO -> {
                // Left-pointing arrow: three-step head plus a shaft.
                g.fill(x0, cy, x0 + 1, cy + 1, color);
                g.fill(x0 + 1, cy - 1, x0 + 2, cy + 2, color);
                g.fill(x0 + 2, cy - 2, x0 + 3, cy + 3, color);
                g.fill(x0 + 3, cy, x0 + 7, cy + 1, color);
            }
            case RESET_DEFAULT -> {
                // Fallback when Sodium's texture is unavailable: square outline with a center dot.
                int y0 = cy - 3;
                g.fill(x0, y0, x0 + 7, y0 + 1, color);
                g.fill(x0, y0 + 6, x0 + 7, y0 + 7, color);
                g.fill(x0, y0, x0 + 1, y0 + 7, color);
                g.fill(x0 + 6, y0, x0 + 7, y0 + 7, color);
                g.fill(x0 + 3, y0 + 3, x0 + 4, y0 + 4, color);
            }
        }
    }

    /** The row's background fill; subclasses may override (e.g. a selected pack row reads darker). */
    protected int rowFillColor() {
        return this.isHoveredOrFocused() ? ROW_COLOR_HOVERED : ROW_COLOR;
    }

    /** Drawn between the background fill and the text -- selection borders and the like. */
    protected void extractDecorations(GuiGraphicsExtractor g) {
    }

    /** Draws the right-hand value region; default draws {@link #valueText()} right-aligned. */
    protected void extractValue(GuiGraphicsExtractor g, Font font, int textY) {
        String value = valueText();
        g.text(font, value, contentRight() - font.width(value), textY, valueColor());
    }

    protected String valueText() {
        return "";
    }

    /** Whether the row's staged value differs from its last-applied value. */
    protected final boolean isDirty() {
        return this.dirtySupplier != null ? this.dirtySupplier.getAsBoolean() : isLocallyDirty();
    }

    /** Fallback dirtiness for rows without a {@link #dirtySupplier}: construction-time comparison. */
    protected boolean isLocallyDirty() {
        return false;
    }

    @Override
    public final void onClick(MouseButtonEvent event, boolean doubled) {
        // Affordance cells intercept before any row behavior -- a click on the undo/reset glyph must
        // never also cycle the value or jump a slider.
        for (int i = 0; i < this.affordances.size(); i++) {
            int cellLeft = affordanceCellLeft(i);
            if (event.x() >= cellLeft && event.x() < cellLeft + AFFORDANCE_CELL) {
                Affordance affordance = this.affordances.get(i);
                if (affordance.visible().getAsBoolean()) {
                    affordance.action().run();
                }
                return;
            }
        }
        onRowClick(event, doubled);
    }

    /** The row's own click behavior (cycle/slide/open), after affordance-cell interception. */
    protected void onRowClick(MouseButtonEvent event, boolean doubled) {
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }

    // ---------------------------------------------------------------------------------------------

    /** Click-to-cycle row (booleans, enums, profiles): left click advances, right click goes back. */
    static final class Cycle<T> extends PackRow {
        private final List<T> values;
        private final Function<T, String> namer;
        private final Consumer<T> onStage;
        private final int initialIndex;
        private final boolean startedUnmatched;
        private final String unmatchedLabel;
        private int index;
        private boolean clicked;

        /** When set, the displayed value re-reads this every frame instead of the row's own
         * click-tracked index -- mirrors {@link PackButton#withActiveSupplier}: a row whose backing
         * model can change out from under the widget (Apply reseeding staged state, a pack-load
         * failure forcing a revert) stays truthful without a rebuild. Clicks still stage through
         * {@code onStage} as before; {@link #onRowClick} re-syncs {@code index} from this supplier
         * first, so cycling always advances from what's currently displayed, never a stale
         * click-count left over from before an external reseed. */
        private Supplier<T> valueSupplier;

        /**
         * {@code current} may be {@code null} or absent from {@code values} -- when so, the row starts
         * in an "unmatched" display state (shows {@code unmatchedLabel} instead of any real value)
         * until the first click, after which it behaves exactly like a normal cycle starting from
         * index 0. Existing callers passing a {@code current} that IS present in {@code values} see
         * zero behavior change (this is a strict, backward-compatible generalization).
         */
        Cycle(String label, List<T> values, @org.jspecify.annotations.Nullable T current, Function<T, String> namer,
              String tooltip, Consumer<T> onStage, String unmatchedLabel) {
            super(Component.literal(label), tooltip);
            this.values = List.copyOf(values);
            this.namer = namer;
            this.onStage = onStage;
            int found = current == null ? -1 : this.values.indexOf(current);
            this.startedUnmatched = found < 0;
            this.initialIndex = Math.max(0, found);
            this.index = this.initialIndex;
            this.unmatchedLabel = unmatchedLabel;
        }

        /** Fluent, mirrors {@link PackButton#withActiveSupplier}: opt a row into live-value display.
         * See the {@link #valueSupplier} field doc for why this is safe against the click handler. */
        Cycle<T> withValueSupplier(Supplier<T> supplier) {
            this.valueSupplier = supplier;
            return this;
        }

        @Override
        protected void onRowClick(MouseButtonEvent event, boolean doubled) {
            this.clicked = true;
            if (this.valueSupplier != null) {
                // Re-sync from the live model before advancing, so cycling always starts from what
                // is currently ON SCREEN -- not a click-count left stale by an external reseed
                // (Apply/refresh) that happened without going through this row's own click path.
                int current = this.values.indexOf(this.valueSupplier.get());
                if (current >= 0) {
                    this.index = current;
                }
            }
            int direction = event.button() == 1 ? -1 : 1;
            this.index = Math.floorMod(this.index + direction, this.values.size());
            this.onStage.accept(this.values.get(this.index));
        }

        @Override
        protected String valueText() {
            if (this.valueSupplier != null) {
                return this.namer.apply(this.valueSupplier.get());
            }
            if (this.startedUnmatched && !this.clicked) {
                return this.unmatchedLabel;
            }
            return this.namer.apply(this.values.get(this.index));
        }

        @Override
        protected boolean isLocallyDirty() {
            return this.index != this.initialIndex;
        }
    }

    // ---------------------------------------------------------------------------------------------

    /** Slider row: the right-hand zone is a drag track with a filled fraction bar + value readout. */
    static final class Slider extends PackRow {
        private static final int TRACK_COLOR = 0x60000000;
        private static final int FILL_COLOR = 0x905A9BD5;
        private static final int HANDLE_COLOR = 0xFFE8E8E8;

        private final float min;
        private final float max;
        private final float step;
        private final FloatFormatter formatter;
        private final Consumer<Float> onStage;
        private final float initialValue;
        private float value;
        /** Only drags that STARTED on the track adjust the value -- not ones begun on a glyph cell. */
        private boolean draggingTrack;

        Slider(String label, float min, float max, float step, float current,
               FloatFormatter formatter, String tooltip, Consumer<Float> onStage) {
            super(Component.literal(label), tooltip);
            this.min = min;
            this.max = max;
            this.step = step;
            this.formatter = formatter;
            this.onStage = onStage;
            this.initialValue = current;
            this.value = current;
        }

        private int trackLeft() {
            return this.getX() + this.width / 2;
        }

        private int trackRight() {
            return contentRight() - 2;
        }

        private void setFromMouse(double mouseX) {
            float fraction = (float) Mth.clamp((mouseX - trackLeft()) / Math.max(1, trackRight() - trackLeft()), 0.0, 1.0);
            float raw = this.min + fraction * (this.max - this.min);
            if (this.step > 0.0f) {
                raw = Math.round(raw / this.step) * this.step;
            }
            this.value = Mth.clamp(raw, this.min, this.max);
            this.onStage.accept(this.value);
        }

        /** Rows rebuild via the session on undo/reset; this keeps an in-place row's readout in sync. */
        void setDisplayValue(float newValue) {
            this.value = newValue;
        }

        @Override
        protected void onRowClick(MouseButtonEvent event, boolean doubled) {
            this.draggingTrack = true;
            setFromMouse(event.x());
        }

        @Override
        protected void onDrag(MouseButtonEvent event, double dragX, double dragY) {
            if (this.draggingTrack) {
                setFromMouse(event.x());
            }
        }

        @Override
        public void onRelease(MouseButtonEvent event) {
            this.draggingTrack = false;
        }

        @Override
        protected void extractValue(GuiGraphicsExtractor g, Font font, int textY) {
            int left = trackLeft();
            int right = trackRight();
            int trackTop = this.getY() + 4;
            int trackBottom = this.getY() + this.height - 4;
            float fraction = (this.value - this.min) / Math.max(1.0e-6f, this.max - this.min);
            int fillX = left + Math.round(fraction * (right - left));

            g.fill(left, trackTop, right, trackBottom, TRACK_COLOR);
            g.fill(left, trackTop, fillX, trackBottom, FILL_COLOR);
            g.fill(Math.max(left, fillX - 1), trackTop, Math.min(right, fillX + 1), trackBottom, HANDLE_COLOR);

            String text = this.formatter.format(this.value);
            g.text(font, text, right - 4 - font.width(text), textY, valueColor());
        }

        @Override
        protected boolean isLocallyDirty() {
            return this.value != this.initialValue;
        }
    }

    // ---------------------------------------------------------------------------------------------

    /** Navigation row ("Lighting >"): opens a nested screen; carries no value of its own. */
    static final class Link extends PackRow {
        private final Runnable onOpen;

        Link(String label, String tooltip, Runnable onOpen) {
            super(Component.literal(label), tooltip);
            this.onOpen = onOpen;
        }

        @Override
        protected void onRowClick(MouseButtonEvent event, boolean doubled) {
            this.onOpen.run();
        }

        @Override
        protected String valueText() {
            return ">";
        }
    }

    // ---------------------------------------------------------------------------------------------

    /** Selection row for the pack list: vanilla button-sprite chrome full-row (see {@link
     * #useVanillaChrome}, always on -- this class has no other caller, so opting in unconditionally
     * carries zero shared-look risk), with the staged selection marked by the same white/grey
     * outline vanilla's own {@code AbstractSelectionList#extractSelection} draws around a selection
     * list's selected entry (focused = white {@code -1}, unfocused = grey {@code -8355712}) instead
     * of a flat accent border. */
    static final class Select extends PackRow {
        /** Vanilla {@code AbstractSelectionList}'s own selection-outline colors ({@code -1} / {@code
         * -8355712} in ARGB int form), reproduced here rather than depending on that class -- our
         * "selection" isn't the list's own {@code setSelected} concept (staging flows through {@link
         * PackListState}, not list focus), so we draw the same idiom by hand. */
        private static final int SELECTION_OUTLINE_FOCUSED = 0xFFFFFFFF;
        private static final int SELECTION_OUTLINE = 0xFF808080;

        private final Runnable onSelect;
        private final BooleanSupplier isSelected;
        private final BooleanSupplier isApplied;

        Select(String label, String tooltip, BooleanSupplier isSelected,
               BooleanSupplier isApplied, Runnable onSelect) {
            super(Component.literal(label), tooltip);
            this.isSelected = isSelected;
            this.isApplied = isApplied;
            this.onSelect = onSelect;
            this.useVanillaChrome();
        }

        @Override
        protected void onRowClick(MouseButtonEvent event, boolean doubled) {
            this.onSelect.run();
        }

        @Override
        protected void extractDecorations(GuiGraphicsExtractor g) {
            if (!this.isSelected.getAsBoolean()) {
                return;
            }
            int color = this.isFocused() ? SELECTION_OUTLINE_FOCUSED : SELECTION_OUTLINE;
            int x0 = this.getX();
            int y0 = this.getY();
            int x1 = x0 + this.getWidth();
            int y1 = y0 + this.getHeight();
            g.fill(x0, y0, x1, y0 + 1, color);
            g.fill(x0, y1 - 1, x1, y1, color);
            g.fill(x0, y0, x0 + 1, y1, color);
            g.fill(x1 - 1, y0, x1, y1, color);
        }

        @Override
        protected String valueText() {
            boolean selected = this.isSelected.getAsBoolean();
            boolean applied = this.isApplied.getAsBoolean();
            if (selected) {
                return applied ? "Active" : "Selected";
            }
            return applied ? "Active" : "";
        }

        @Override
        protected boolean isLocallyDirty() {
            return this.isSelected.getAsBoolean() && !this.isApplied.getAsBoolean();
        }
    }
}

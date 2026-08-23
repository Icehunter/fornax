package dev.icehunter.fornax.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Scrolling host for {@link PackRow}s: translucent rows in a CENTERED content column (about
 * two-thirds of the screen, matching the familiar shaderpack-configure proportions -- not
 * edge-to-edge), no list chrome of its own (the vanilla list background/separator art is
 * suppressed; the rows carry their own translucent fills). Entries hold one full-width row or a
 * side-by-side PAIR of short rows (two-column layout for compact cycle options); the list
 * positions each widget every frame, mirroring vanilla {@code OptionsList}'s own
 * setPosition-then-extract entry pattern.
 */
final class PackRowList extends ContainerObjectSelectionList<PackRowList.Row> {
    /** Content column: about two-thirds of the screen, clamped to stay usable at both extremes. */
    static int contentWidthFor(int screenWidth) {
        return Math.max(300, Math.min((int) (screenWidth * 0.66), 520));
    }

    PackRowList(Minecraft minecraft, int width, int height, int y, int itemHeight) {
        super(minecraft, width, height, y, itemHeight);
    }

    void addRow(AbstractWidget row) {
        this.addEntry(new RowEntry(row, null));
    }

    /** Two compact rows sharing one line, each taking half the content column. */
    void addRowPair(AbstractWidget left, AbstractWidget right) {
        this.addEntry(new RowEntry(left, right));
    }

    /**
     * A row paired with a small square accessory button anchored to its right edge (the Shader
     * Packs tab's per-row settings-cog button). {@code accessoryVisible} is read live every frame
     * in {@link AccessoryEntry#extractContent} -- exactly the same discipline {@link PackRow}'s own
     * value/selection suppliers already use -- so the row and the accessory converge with the rest
     * of the tab on Apply's {@code PackListState#refresh} with no widget rebuild. When hidden, the
     * row occupies the entry's full width; when shown, the row's width shrinks by {@code
     * accessorySize + gap} so the two never overlap.
     */
    void addRowWithAccessory(AbstractWidget row, AbstractWidget accessory, int accessorySize, int gap,
                              BooleanSupplier accessoryVisible) {
        this.addEntry(new AccessoryEntry(row, accessory, accessorySize, gap, accessoryVisible));
    }

    @Override
    public int getRowWidth() {
        return Math.min(contentWidthFor(this.width), this.width - 16);
    }

    @Override
    protected void extractListBackground(GuiGraphicsExtractor g) {
        // Intentionally empty: the screen's own background (in-world blur / menu panorama) shows
        // through; each row draws its own translucent fill.
    }

    @Override
    protected void extractListSeparators(GuiGraphicsExtractor g) {
        // Intentionally empty -- same reasoning as extractListBackground.
    }

    /** Common entry base -- {@link ContainerObjectSelectionList} is generic over one recursive entry
     * type, so {@link RowEntry} (a plain row or side-by-side pair) and {@link AccessoryEntry} (a row
     * plus a trailing accessory button) both extend this shared, otherwise-empty class instead of
     * each binding the list's type parameter to itself. */
    abstract static class Row extends ContainerObjectSelectionList.Entry<Row> {
    }

    static final class RowEntry extends Row {
        private static final int PAIR_GAP = 4;

        private final AbstractWidget left;
        private final AbstractWidget right;

        RowEntry(AbstractWidget left, AbstractWidget right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovering, float partialTick) {
            int x = this.getContentX();
            int width = this.getContentWidth();
            int height = this.getContentHeight();
            if (this.right == null) {
                place(this.left, x, this.getContentY(), width, height);
            } else {
                int half = (width - PAIR_GAP) / 2;
                place(this.left, x, this.getContentY(), half, height);
                place(this.right, x + half + PAIR_GAP, this.getContentY(), width - half - PAIR_GAP, height);
            }
            this.left.extractRenderState(g, mouseX, mouseY, partialTick);
            if (this.right != null) {
                this.right.extractRenderState(g, mouseX, mouseY, partialTick);
            }
        }

        private static void place(AbstractWidget widget, int x, int y, int width, int height) {
            widget.setPosition(x, y);
            widget.setWidth(width);
            widget.setHeight(height);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return this.right == null ? List.of(this.left) : List.of(this.left, this.right);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return this.right == null ? List.of(this.left) : List.of(this.left, this.right);
        }
    }

    /** See {@link #addRowWithAccessory}. */
    static final class AccessoryEntry extends Row {
        private final AbstractWidget row;
        private final AbstractWidget accessory;
        private final int accessorySize;
        private final int gap;
        private final BooleanSupplier accessoryVisible;

        AccessoryEntry(AbstractWidget row, AbstractWidget accessory, int accessorySize, int gap,
                        BooleanSupplier accessoryVisible) {
            this.row = row;
            this.accessory = accessory;
            this.accessorySize = accessorySize;
            this.gap = gap;
            this.accessoryVisible = accessoryVisible;
            // Starts hidden; extractContent below sets the live value before the first render, but
            // an accessory that briefly renders/hit-tests at its stale (unset) bounds on the very
            // first frame is worse than starting closed.
            this.accessory.visible = false;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovering, float partialTick) {
            int x = this.getContentX();
            int width = this.getContentWidth();
            int height = this.getContentHeight();
            int y = this.getContentY();

            boolean showAccessory = this.accessoryVisible.getAsBoolean();
            this.accessory.visible = showAccessory;
            if (showAccessory) {
                int rowWidth = width - this.accessorySize - this.gap;
                place(this.row, x, y, rowWidth, height);
                place(this.accessory, x + rowWidth + this.gap, y + (height - this.accessorySize) / 2,
                        this.accessorySize, this.accessorySize);
            } else {
                place(this.row, x, y, width, height);
            }

            this.row.extractRenderState(g, mouseX, mouseY, partialTick);
            if (showAccessory) {
                this.accessory.extractRenderState(g, mouseX, mouseY, partialTick);
            }
        }

        private static void place(AbstractWidget widget, int x, int y, int width, int height) {
            widget.setPosition(x, y);
            widget.setWidth(width);
            widget.setHeight(height);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(this.row, this.accessory);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(this.row, this.accessory);
        }
    }
}

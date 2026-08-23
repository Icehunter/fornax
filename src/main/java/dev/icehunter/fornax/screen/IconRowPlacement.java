package dev.icehunter.fornax.screen;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;

/**
 * Finds vanilla's row of small square icon buttons so Fornax's own button can sit beside it.
 *
 * <p>Both the title screen and the pause screen carry such a row -- skin, language and accessibility
 * on one, bug report, feedback and the rest on the other -- and both are where a player already
 * looks for a control of this kind. Fornax's button used to sit in the top-right corner of each,
 * which is nowhere anybody checks.
 *
 * <p><b>The row is found, not written down.</b> Vanilla builds it with a centred layout whose
 * contents change between versions, so a hardcoded coordinate drifts off the row the moment that
 * set changes. Reading the buttons' own positions survives that, and survives another mod having
 * added its button first, since ours lands to the right of whatever is actually there.
 *
 * <p>Matched on SHAPE rather than on type: the row's buttons are the only {@value #BUTTON_SIZE}
 * pixel squares these screens carry, and the menu buttons are all wide. That names no vanilla class
 * which might be renamed, and assumes nothing about where on the screen the row sits.
 */
public final class IconRowPlacement {
    /** The square size vanilla uses for icon buttons, and the size Fornax's own button is built at. */
    public static final int BUTTON_SIZE = 20;
    /** Matches the spacing vanilla leaves between the buttons already in the row. */
    public static final int ROW_GAP = 4;

    private IconRowPlacement() {
    }

    /**
     * The position to place a {@value #BUTTON_SIZE}-pixel button immediately right of the row, or
     * null when the screen has no such row -- which is what happens if something has replaced the
     * screen wholesale. Callers fall back to their own corner in that case: a button somewhere odd
     * beats no button.
     *
     * @return {@code {x, y}}, or null
     */
    public static int @Nullable [] rightOfIconRow(Screen screen) {
        AbstractWidget rightmost = null;
        for (GuiEventListener child : screen.children()) {
            if (!(child instanceof AbstractWidget widget)) {
                continue;
            }
            if (widget.getWidth() != BUTTON_SIZE || widget.getHeight() != BUTTON_SIZE) {
                continue;
            }
            if (rightmost == null || widget.getX() > rightmost.getX()) {
                rightmost = widget;
            }
        }
        if (rightmost == null) {
            return null;
        }
        return new int[] { rightmost.getX() + rightmost.getWidth() + ROW_GAP, rightmost.getY() };
    }
}

package dev.icehunter.fornax.mixin.yacl;

import dev.isxander.yacl3.gui.OptionDescriptionWidget;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.function.Supplier;

/**
 * Exposes {@code OptionDescriptionWidget}'s private final {@code dimensions} supplier (CFR-decompiled
 * and confirmed against the real 3.9.5+26.2-fabric jar: {@code private final Supplier<ScreenRectangle>
 * dimensions;}) so {@link CategoryTabMixin} can wrap it to shrink the widget's rendered height when
 * the chrome buttons are injected above the search field.
 *
 * <p>{@code OptionDescriptionWidget#extractWidgetRenderState} re-derives the widget's live
 * x/y/width/height from {@code this.dimensions.get()} on EVERY frame, so a one-off external resize
 * (e.g. calling a plain {@code setHeight}) would be silently reverted the very next frame -- only
 * replacing the supplier itself sticks, hence an accessor rather than a one-time layout tweak.
 */
@Mixin(OptionDescriptionWidget.class)
public interface OptionDescriptionWidgetAccessor {
    @Accessor(value = "dimensions", remap = false)
    Supplier<ScreenRectangle> fornax$getDimensions();

    @Accessor(value = "dimensions", remap = false)
    @Mutable
    void fornax$setDimensions(Supplier<ScreenRectangle> dimensions);
}

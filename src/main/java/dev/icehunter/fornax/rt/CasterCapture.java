package dev.icehunter.fornax.rt;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;

/**
 * A provider whose geometry is the renderer's own uploaded terrain, handed over once per frame at
 * the one point it can be read: inside the shadow pass, after this frame's uploads are complete
 * and before anything relocates an arena.
 *
 * <p>The caster source travels by this type rather than inside {@link CelestialFill}, so the
 * coupling to a renderer-owned object stays visible at the call site instead of hiding in a field
 * the neutral request would have to carry for every provider. Whichever mesh tier a platform
 * installs implements this; {@code RayRouter.provider(CasterCapture.class)} finds it.
 */
public interface CasterCapture extends RayProvider {

    /** Called on the render thread each frame the shadow pass runs; cleared by {@link #beginFrame()}. */
    void captureCasters(RenderSectionManager manager);
}

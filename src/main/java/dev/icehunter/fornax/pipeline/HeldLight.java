package dev.icehunter.fornax.pipeline;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

/**
 * The light level of whatever the player is holding, per hand, 0..15.
 *
 * <p>Every shaderpack that lights the world from a held torch needs this, and vanilla surfaces it
 * nowhere a shader can reach -- the item is drawn by the hand renderer, and nothing tells the world
 * pass that a light source is attached to the camera. The established shader ABI for it is {@code
 * heldBlockLightValue}/{@code heldBlockLightValue2}, one uniform per hand, and a pack's held-light
 * path reads exactly those two names and does nothing at all without them.
 *
 * <p><b>Level only, deliberately.</b> This reports how bright the held item is and nothing else --
 * not its colour, not a falloff curve, not a position offset. Those are the pack's decisions, and
 * baking any of them here would make the engine dictate a look (see the "infrastructure, not style"
 * rule). A pack that wants warm torchlight tints it itself and picks its own distance falloff;
 * a different pack will want different ones, and both should be able to.
 *
 * <p><b>Independently implemented from vanilla's own API.</b> The ABI above fixes the shape of the
 * answer -- per-hand, 0..15 -- and nothing more; how the engine arrives at the number is its own
 * problem, and this solves it the shortest way vanilla allows. A {@link BlockItem}'s block already
 * knows its {@code getLightEmission()}, so asking it directly needs no mixin, no new interface and
 * no registry: the whole implementation is a lookup on data the game already maintains. The
 * tradeoff is that a non-block item cannot declare itself a light source -- a lava bucket reads as
 * dark, because vanilla does not model it as emissive -- which is a gap worth closing later with a
 * data-driven table in the pack's own {@code blocks.toml}, where a per-item emission list is the
 * pack's data to own rather than a hardcoded engine table.
 *
 * <p>Read live on the render thread, once per frame, from {@code GlobalUniformsWriteMixin} -- the
 * same shape as the wind clock, the eye-in-water flag and the sky probe, and for the same reason:
 * a value fed from a conditionally-invoked pass mixin goes stale on frames that pass does not run.
 * That failure has already been fixed three times in this codebase.
 */
public final class HeldLight {
    /** Vanilla's maximum block light level, for the 0..1 normalisation the uniform carries. */
    private static final float MAX_LIGHT = 15.0f;

    private HeldLight() {
    }

    /** Main-hand held light, 0..15. Zero when nothing is held or the item emits no light. */
    public static int mainHandLevel() {
        return levelOf(InteractionHand.MAIN_HAND);
    }

    /** Off-hand held light, 0..15. */
    public static int offHandLevel() {
        return levelOf(InteractionHand.OFF_HAND);
    }

    /** Main-hand held light normalized to 0..1, as the uniform carries it. */
    public static float mainHandNormalized() {
        return mainHandLevel() / MAX_LIGHT;
    }

    /** Off-hand held light normalized to 0..1. */
    public static float offHandNormalized() {
        return offHandLevel() / MAX_LIGHT;
    }

    /**
     * The light emission of the item in {@code hand}, or 0 when there is no player, no item, or the
     * item is not a placeable light source.
     *
     * <p>Only {@link BlockItem}s report emission, because only a block has a light level: the item
     * form of a torch is a {@code BlockItem} whose block emits 14, and asking the block's default
     * state is the same query the world lighting engine itself makes. A held item that is not a
     * block -- a glowing potion, a modded lantern-on-a-stick -- reports 0 here even if a pack would
     * want it lit; see the class note on Iris's provider interface for why that gap is left open
     * rather than closed by copying.
     */
    private static int levelOf(InteractionHand hand) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return 0;
        }
        LocalPlayer player = client.player;
        if (player == null) {
            return 0;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        if (stack.getItem() instanceof BlockItem blockItem) {
            return blockItem.getBlock().defaultBlockState().getLightEmission();
        }
        return 0;
    }
}

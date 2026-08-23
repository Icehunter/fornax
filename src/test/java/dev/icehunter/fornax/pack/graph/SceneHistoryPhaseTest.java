package dev.icehunter.fornax.pack.graph;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.icehunter.fornax.pipeline.SceneHistory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the ping-pong PHASE of the engine's end-of-frame sceneHistory copy -- the one ordering fact
 * a GPU-less test can and must own, because getting it wrong doesn't crash, it just serves stale
 * (or never-written) history. Per real frame, in order:
 *
 * <ol>
 *   <li>graph passes read {@code sceneHistory.history} (SSR trace, resolve),</li>
 *   <li>{@code GraphRunner.finish()} ends with {@code TargetRegistry.swapHistory()} -- MID-{@code
 *       renderLevel},</li>
 *   <li>the engine copy ({@code GameRendererMixin}, {@code renderLevel} RETURN) writes {@link
 *       SceneHistory#writeSlot}.</li>
 * </ol>
 *
 * Because the swap precedes the copy inside the same frame, and next frame's reads precede next
 * frame's swap, the copy must write the post-swap HISTORY slot for readers to see it exactly one
 * frame later. Writing the post-swap {@code current} slot instead delays every read by one
 * additional swap: two-frames-stale steady state, and a still-black history on frame 2 (this
 * test's exact failure mode against that bug). Lives in this package, not {@code pipeline},
 * because {@link TargetInstance}'s constructor and {@code swap()} are package-private here.
 */
class SceneHistoryPhaseTest {

    @Test
    void copyLandsInTheSlotTheNextFrameReads() {
        GpuTexture texA = fakeTexture("sceneHistory A");
        GpuTexture texB = fakeTexture("sceneHistory B");
        TargetInstance t = new TargetInstance(SceneHistory.TARGET, TargetFormat.RGBA8, 1, 1, true,
                texA, fakeView(texA), texB, fakeView(texB));

        // Frame 1: passes read history (initial clear -- black, fine); swap; engine copy.
        t.swap();
        GpuTexture frame1Write = SceneHistory.writeSlot(t);

        // Frame 2 reads history BEFORE its own swap -- it must see frame 1's write, not black.
        assertSame(t.historyTexture(), frame1Write,
                "frame 2's history read must return exactly what frame 1's engine copy wrote");

        // Frame 2: swap, copy. Steady state must hold the same one-frame latency...
        t.swap();
        GpuTexture frame2Write = SceneHistory.writeSlot(t);
        assertSame(t.historyTexture(), frame2Write,
                "frame 3's history read must return exactly what frame 2's engine copy wrote");

        // ...and genuinely alternate physical textures (ping-pong, not overwriting one slot).
        assertNotSame(frame1Write, frame2Write, "consecutive frames must write alternating slots");
    }

    @Test
    void reconstructReadsLastFramesColorNotTwoFramesStale() {
        GpuTexture texA = fakeTexture("sceneHistory A");
        GpuTexture texB = fakeTexture("sceneHistory B");
        TargetInstance t = new TargetInstance(SceneHistory.TARGET, TargetFormat.RGBA8, 1, 1, true,
                texA, fakeView(texA), texB, fakeView(texB));

        // Frame 1: swap, engine copy writes frame 1's final color.
        t.swap();
        GpuTexture frame1Write = SceneHistory.writeSlot(t);

        // Frame 2, POST-swap (renderLevel RETURN, where the reconstruct pass runs): the slot
        // holding frame 1's color is now `current`, not `history` -- the reconstruct read must
        // track the swap or it blends toward a frame the motion vectors don't describe.
        t.swap();
        assertSame(frame1Write, SceneHistory.reconstructReadSlot(t).texture(),
                "post-swap, the reconstruct pass must read the slot last frame's copy wrote");

        // The intuitive-but-wrong choice -- historyView(), the slot pack passes read PRE-swap --
        // is two copies old at this point in the frame: blending 0.9 toward it trails the camera
        // by a full frame of velocity on every move (the original reconstruct trailing bug).
        assertNotSame(frame1Write, t.historyTexture(),
                "post-swap, historyView() no longer holds last frame's color -- reading it there is the two-frame-stale bug");
    }

    @Test
    void writeSlotIsNeverTheSlotThisFramesPassesJustRead() {
        GpuTexture texA = fakeTexture("sceneHistory A");
        GpuTexture texB = fakeTexture("sceneHistory B");
        TargetInstance t = new TargetInstance(SceneHistory.TARGET, TargetFormat.RGBA8, 1, 1, true,
                texA, fakeView(texA), texB, fakeView(texB));

        // The buggy phase (writing post-swap `current`) is structurally identical to writing the
        // very slot this frame's passes just read: post-swap, that texture IS `current`. A write
        // there lands where next frame's readers won't look -- they read the OTHER slot -- so this
        // pins the bug's shape directly, independent of the latency framing in the test above.
        GpuTexture readThisFrame = t.historyTexture();
        t.swap();
        assertNotSame(readThisFrame, SceneHistory.writeSlot(t),
                "the engine copy must write the slot next frame reads, not the one this frame already read");
    }

    @Test
    void fullTwoFrameTimelineForAllThreeConsumers() {
        // The complete slot map, two consecutive frames, all three consumers -- SSR's pre-swap
        // read, the reconstruct's post-swap read AND write, plus the write slot the OFF/SSAA copy
        // shares. Every read must land on a slot that was written the previous frame (or was
        // freshly allocation-cleared on the very first frame); the reconstruct's read and write
        // must never alias (it samples one slot while rendering into the other).
        GpuTexture texA = fakeTexture("sceneHistory A");
        GpuTexture texB = fakeTexture("sceneHistory B");
        TargetInstance t = new TargetInstance(SceneHistory.TARGET, TargetFormat.RGBA8, 1, 1, true,
                texA, fakeView(texA), texB, fakeView(texB));

        // ---- Frame 1 ----
        // SSR (pre-swap) reads a slot that is allocation-cleared, never garbage (zero-fill law).
        GpuTexture frame1SsrRead = t.historyTexture();
        t.swap();
        // Reconstruct (post-swap): reads one slot, renders into the other -- disjoint textures.
        GpuTexture frame1ReconstructRead = SceneHistory.reconstructReadSlot(t).texture();
        GpuTexture frame1Write = SceneHistory.writeSlotView(t).texture();
        assertNotSame(frame1ReconstructRead, frame1Write,
                "the reconstruct must never sample the slot it is rendering into");
        assertSame(frame1Write, SceneHistory.writeSlot(t),
                "the reconstruct's render target and the OFF/SSAA copy's destination are the same slot");
        // Frame 1's two reads (SSR pre-swap, reconstruct post-swap) hit the SAME allocation-cleared
        // slot -- the swap moved it from `history` to `current` between them -- so the first frame
        // reads black history, never garbage (zero-fill law).
        assertSame(frame1SsrRead, frame1ReconstructRead,
                "frame 1's pre-swap SSR read and post-swap reconstruct read are the same cleared slot");

        // ---- Frame 2 ----
        // SSR (pre-swap) must read exactly what frame 1 wrote.
        assertSame(frame1Write, t.historyTexture(),
                "frame 2's SSR read must see frame 1's accumulation");
        t.swap();
        // Reconstruct (post-swap) must also read frame 1's accumulation -- and write the other slot.
        assertSame(frame1Write, SceneHistory.reconstructReadSlot(t).texture(),
                "frame 2's reconstruct read must see frame 1's accumulation");
        GpuTexture frame2Write = SceneHistory.writeSlotView(t).texture();
        assertNotSame(frame1Write, frame2Write, "consecutive frames must write alternating slots");
        assertSame(frame1ReconstructRead, frame2Write,
                "frame 2 overwrites the slot frame 1 read -- no third slot exists, nothing is ever read unwritten");
    }

    private static GpuTexture fakeTexture(String label) {
        return new GpuTexture(0, label, GpuFormat.RGBA8_UNORM, 1, 1, 1, 1) {
            @Override
            public void close() {
            }

            @Override
            public boolean isClosed() {
                return false;
            }
        };
    }

    private static GpuTextureView fakeView(GpuTexture texture) {
        return new GpuTextureView(texture, 0, 1) {
            @Override
            public void close() {
            }

            @Override
            public boolean isClosed() {
                return false;
            }
        };
    }
}

package dev.icehunter.fornax.atlas;

import java.util.List;

/** Vanilla-compatible frame clock for one animated LabPBR sidecar. */
final class LabPbrAnimationState {
    record Frame(int index, int time) {
        Frame {
            if (index < 0 || time <= 0) {
                throw new IllegalArgumentException("animation frames need a non-negative index and positive time");
            }
        }
    }

    record Sample(int currentFrameIndex, int nextFrameIndex, float blend, boolean upload) {
    }

    private final List<Frame> frames;
    private final boolean interpolate;
    private int frame;
    private int subFrame;

    LabPbrAnimationState(List<Frame> frames, boolean interpolate) {
        if (frames.isEmpty()) {
            throw new IllegalArgumentException("an animation needs at least one frame");
        }
        this.frames = List.copyOf(frames);
        this.interpolate = interpolate;
    }

    int initialFrameIndex() {
        return this.frames.getFirst().index();
    }

    Sample tick() {
        this.subFrame++;
        Frame current = this.frames.get(this.frame);
        boolean changed = false;
        if (this.subFrame >= current.time()) {
            int previousIndex = current.index();
            this.frame = (this.frame + 1) % this.frames.size();
            this.subFrame = 0;
            current = this.frames.get(this.frame);
            changed = previousIndex != current.index();
        }

        Frame next = this.frames.get((this.frame + 1) % this.frames.size());
        float blend = this.interpolate ? (float) this.subFrame / current.time() : 0.0f;
        boolean needsInterpolation = this.interpolate && current.index() != next.index();
        return new Sample(current.index(), next.index(), blend, changed || needsInterpolation);
    }
}

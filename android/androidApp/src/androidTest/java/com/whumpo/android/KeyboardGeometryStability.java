package com.whumpo.android;

/** Geometry evidence from distinct actual draw frames, independent of callback availability. */
public final class KeyboardGeometryStability {
    public static final class Frame {
        public final long timeNanos;
        public final float composerBottom;
        public final int keyboardTop;
        public final int imeBottom;
        public final int windowHeight;
        public final boolean visible;
        public final boolean animating;
        public final int animationGeneration;

        public Frame(long timeNanos, float composerBottom, int keyboardTop, int imeBottom,
                     int windowHeight, boolean visible, boolean animating) {
            this(timeNanos, composerBottom, keyboardTop, imeBottom, windowHeight, visible, animating, 0);
        }

        public Frame(long timeNanos, float composerBottom, int keyboardTop, int imeBottom,
                     int windowHeight, boolean visible, boolean animating, int animationGeneration) {
            this.animationGeneration = animationGeneration;
            this.timeNanos = timeNanos;
            this.composerBottom = composerBottom;
            this.keyboardTop = keyboardTop;
            this.imeBottom = imeBottom;
            this.windowHeight = windowHeight;
            this.visible = visible;
            this.animating = animating;
        }

        private boolean sameGeometry(Frame other) {
            return composerBottom == other.composerBottom && keyboardTop == other.keyboardTop
                    && imeBottom == other.imeBottom && windowHeight == other.windowHeight
                    && animationGeneration == other.animationGeneration;
        }
    }

    private long lastTimeNanos = -1;
    private Frame previous;
    private int stableFrames;

    public boolean observe(Frame frame) {
        // Polling the same render again is not independent evidence.
        if (frame.timeNanos <= lastTimeNanos) return false;
        lastTimeNanos = frame.timeNanos;
        if (frame.animating || !frame.visible || frame.imeBottom <= 0
                || frame.windowHeight <= 0 || frame.composerBottom <= 0 || !Float.isFinite(frame.composerBottom)) {
            reset();
            return false;
        }
        stableFrames = previous != null && frame.sameGeometry(previous) ? stableFrames + 1 : 1;
        previous = frame;
        // Stability is deliberately independent of the overlap assertion: a stable
        // covered composer must finish sampling and FAIL the original assertion.
        return stableFrames >= 3;
    }

    /** Discard stale geometry without allowing an already seen frame to count again. */
    public void reset() {
        previous = null;
        stableFrames = 0;
    }

    public int getStableFrames() {
        return stableFrames;
    }
}

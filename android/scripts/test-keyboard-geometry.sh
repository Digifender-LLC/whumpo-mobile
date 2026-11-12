#!/usr/bin/env bash
# Pure Java state-machine regressions; never invokes Gradle, Android or a device.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
fixture="$(mktemp -d)"
trap 'rm -rf "$fixture"' EXIT
cat >"$fixture/GeometryRegression.java" <<'JAVA'
import com.whumpo.android.KeyboardGeometryStability;
import com.whumpo.android.KeyboardGeometryStability.Frame;

public class GeometryRegression {
    static Frame frame(long time, float composer, int top, boolean animating) {
        return new Frame(time, composer, top, 900, top + 900, true, animating);
    }
    static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        KeyboardGeometryStability state = new KeyboardGeometryStability();
        require(!state.observe(frame(1, 1200, 1500, false)), "one frame insufficient");
        for (int i = 0; i < 20; i++) {
            require(!state.observe(frame(1, 1200, 1500, false)), "same frame must not count");
        }
        require(!state.observe(frame(2, 1200, 1500, false)), "two frames insufficient");
        require(!state.observe(frame(3, 1200, 1500, true)), "active animation resets stability");
        require(!state.observe(frame(4, 1200, 1500, false)), "restart after animation");
        require(!state.observe(frame(5, 1201, 1500, false)), "composer movement resets");
        require(!state.observe(frame(6, 1201, 1499, false)), "keyboard movement resets");
        require(!state.observe(frame(7, 1201, 1499, false)), "second stable frame");
        require(state.observe(frame(8, 1201, 1499, false)), "three drawn frames settle without callbacks");
        require(!state.observe(frame(8, 1201, 1499, false)), "duplicate remains duplicate even after settled");
        require(!state.observe(new Frame(9, 1201, 1499, 900, 2399, true, false, 1)),
                "callback generation change resets even when no active frame was sampled");
        state = new KeyboardGeometryStability();
        require(!state.observe(frame(1, 1800, 1500, false)), "covered first frame");
        require(!state.observe(frame(2, 1800, 1500, false)), "covered second frame");
        require(state.observe(frame(3, 1800, 1500, false)), "covered geometry still settles");
        require(!(1800 <= 1500 + 1), "original overlap assertion would fail, not wait until it passes");
        require(!state.observe(new Frame(4, 1200, 1500, 0, 2400, false, false)), "hidden IME resets");
        require(!state.observe(frame(5, 1200, 1500, false)), "one new frame after hide");
        require(!state.observe(frame(6, Float.NaN, 1500, false)), "invalid geometry resets");
        require(!state.observe(frame(7, 1200, 1500, false)), "reset after invalid frame");
        require(!state.observe(frame(8, 1200, 1500, false)), "second frame after invalid");
        require(state.observe(frame(9, 1200, 1500, false)), "recovered distinct frames");
        for (float invalid : new float[]{0f, -1f, -100f}) {
            state = new KeyboardGeometryStability();
            require(!state.observe(frame(1, 1200, 1500, false)), "positive initial frame");
            require(!state.observe(frame(2, 1200, 1500, false)), "positive second frame");
            for (long time = 3; time <= 6; time++) {
                require(!state.observe(frame(time, invalid, 1500, false)), "zero/negative bounds never settle");
                require(state.getStableFrames() == 0, "zero/negative geometry clears prior evidence");
            }
            require(!state.observe(frame(7, 1200, 1500, false)), "first real frame after invalid bounds");
            require(!state.observe(frame(8, 1200, 1500, false)), "second real frame after invalid bounds");
            require(state.observe(frame(9, 1200, 1500, false)), "third real frame after invalid bounds");
            state.reset();
            require(!state.observe(frame(9, 1200, 1500, false)), "reset cannot reuse the previous draw");
            require(!state.observe(frame(10, 1200, 1500, false)), "first fresh draw after stale node reset");
            require(!state.observe(frame(11, 1200, 1500, false)), "second fresh draw after stale node reset");
            require(state.observe(frame(12, 1200, 1500, false)), "three new draws required after stale node reset");
        }
        System.out.println("Keyboard geometry: distinct frames, movement, animation, absence and overlap regressions passed");
    }
}
JAVA
javac --release 17 -d "$fixture/classes" "$root/android/androidApp/src/androidTest/java/com/whumpo/android/KeyboardGeometryStability.java" "$fixture/GeometryRegression.java"
java -cp "$fixture/classes" GeometryRegression

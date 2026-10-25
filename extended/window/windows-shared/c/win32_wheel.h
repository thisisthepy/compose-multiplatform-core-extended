#ifndef DXC_WIN32_WHEEL_H
#define DXC_WIN32_WHEEL_H

// How a turn of the wheel becomes the scroll the scene reads.
//
// Kept apart from the window because it is arithmetic on three numbers, so the MSVC test
// can check the direction without a window, and a direction that is wrong shows up there
// rather than on a reader's screen.
//
// Win32 and Compose disagree on the sign of the vertical axis. WM_MOUSEWHEEL reports a
// positive delta when the wheel turns away from the reader, which asks the content to
// move down so that what is above comes into view: a scroll up. Compose desktop reads a
// positive scrollDelta.y as a scroll down, the convention of AWT's preciseWheelRotation.
// So the vertical delta is negated. WM_MOUSEHWHEEL reports a positive delta for a tilt to
// the right, and Compose reads a positive x as a scroll to the right, so it is not.
//
// One notch is WHEEL_DELTA (120). A precise touchpad sends smaller steps, so the
// division is in floating point and a step of 30 is a quarter notch, not zero.

#define DXC_WHEEL_DELTA 120.0f

/**
 * The scroll for one WM_MOUSEWHEEL (horizontal == 0) or WM_MOUSEHWHEEL (horizontal != 0)
 * carrying wheel_delta, at lines per notch, written to x and y.
 */
static inline void dxc_wheel_to_scroll(int wheel_delta, int horizontal, float lines,
                                       float *x, float *y) {
    float notches = (float)wheel_delta / DXC_WHEEL_DELTA;
    if (horizontal) {
        *x = notches * lines;
        *y = 0.0f;
    } else {
        *x = 0.0f;
        *y = -notches * lines;
    }
}

#endif

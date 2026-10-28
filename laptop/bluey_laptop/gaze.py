"""Where the phone's eyes should look. Port of `face(in:now:)` in CursorOverlay.swift for
follow mode: the phone sits just below the bottom edge of a screen, and the eyes follow
your mouse. Coordinates are top-left origin, y down (Qt's and the Mac overlay's).

The Mac only watches its main display, and with the phone off-center its eyes stop at
the near half-screen. Here the eyes follow the mouse to the far edge of the desktop on
each side, across every monitor, wherever the phone sits (even between two monitors).
With one monitor and the phone centered this is exactly the Mac's formula."""
import math

from .protocol import face


class GazeTracker:
    def __init__(self, phone_position=0.5):
        # 0 = phone under the left edge of its screen, 1 = under the right edge.
        self.phone_position = phone_position
        self._mouse = None
        self._last_move = 0.0

    def face(self, mouse_x, mouse_y, screen, desktop=None, now=0.0, mood=None, reach=None):
        """`screen` is the (x, y, width, height) the phone sits under: one monitor, or the span of
        two when it stands between them. `desktop` is the bounding box of all monitors (defaults
        to `screen`). `reach` is the smallest sideways glance distance, half a monitor by default.
        `mood` None means automatic: listening, drowsy after 5 s of a still mouse, napping after 15 s."""
        if self._mouse is None or math.hypot(mouse_x - self._mouse[0], mouse_y - self._mouse[1]) > 1.5:
            self._last_move = now
        self._mouse = (mouse_x, mouse_y)

        x, y, width, height = screen
        dx, dy, dwidth, dheight = desktop or screen
        phone_x, phone_y = x + width * self.phone_position, y + height + height * 0.18

        # The Mac's reach is half a screen each way and 1.1 screens up. Here each side reaches
        # the desktop's far edge instead, so there's no dead zone; never less than the Mac's.
        reach = width * 0.5 if reach is None else reach
        reach_left = max(reach, phone_x - dx)
        reach_right = max(reach, (dx + dwidth) - phone_x)
        reach_up = height * 1.1 + max(0, y - dy)
        offset = mouse_x - phone_x
        gaze_x = max(-1.0, min(1.0, offset / (reach_left if offset < 0 else reach_right)))
        gaze_y = -max(0.15, min(1.0, (phone_y - mouse_y) / reach_up))

        if mood is None:
            idle = now - self._last_move
            mood = "resting" if idle > 15 else "sleepy" if idle > 5 else "listening"
        return face(gaze_x, gaze_y, mood)

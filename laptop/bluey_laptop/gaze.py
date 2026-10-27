"""Where the phone's eyes should look. Port of `face(in:now:)` in CursorOverlay.swift for
follow mode: the phone sits just below the bottom edge of the screen, and the eyes follow
your mouse. Coordinates are top-left origin, y down (Qt's and the Mac overlay's)."""
import math

from .protocol import face


class GazeTracker:
    def __init__(self, phone_position=0.5):
        # 0 = phone under the left edge of the screen, 1 = under the right edge.
        self.phone_position = phone_position
        self._mouse = None
        self._last_move = 0.0

    def face(self, mouse_x, mouse_y, width, height, now, mood=None):
        """`mood` None means automatic: listening, drowsy after 5 s of a still mouse, napping after 15 s."""
        if self._mouse is None or math.hypot(mouse_x - self._mouse[0], mouse_y - self._mouse[1]) > 1.5:
            self._last_move = now
        self._mouse = (mouse_x, mouse_y)

        phone_x, phone_y = width * self.phone_position, height + height * 0.18
        gaze_x = max(-1.0, min(1.0, (mouse_x - phone_x) / (width * 0.5)))
        gaze_y = -max(0.15, min(1.0, (phone_y - mouse_y) / (height * 1.1)))

        if mood is None:
            idle = now - self._last_move
            mood = "resting" if idle > 15 else "sleepy" if idle > 5 else "listening"
        return face(gaze_x, gaze_y, mood)

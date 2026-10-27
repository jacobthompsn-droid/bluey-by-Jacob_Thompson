import pytest

from bluey_laptop.gaze import GazeTracker

W, H = 1920, 1200


def test_eyes_follow_the_mouse():
    tracker = GazeTracker()
    middle = tracker.face(W / 2, H / 2, W, H, now=0)
    assert middle["gazeX"] == 0 and -1 <= middle["gazeY"] <= -0.15
    left = tracker.face(0, H / 2, W, H, now=0)
    right = tracker.face(W, H / 2, W, H, now=0)
    assert left["gazeX"] == -1 and right["gazeX"] == 1
    # The phone sits 0.18 screen-heights below the bottom edge (same as the Mac).
    assert tracker.face(W / 2, H, W, H, now=0)["gazeY"] == pytest.approx(-0.18 / 1.1)
    assert tracker.face(W / 2, 0, W, H, now=0)["gazeY"] == -1


def test_phone_position_shifts_the_gaze():
    tracker = GazeTracker(phone_position=0)
    assert tracker.face(W / 2, H / 2, W, H, now=0)["gazeX"] == 1


def test_drowsy_then_napping_when_the_mouse_is_still():
    tracker = GazeTracker()
    assert tracker.face(100, 100, W, H, now=0)["mood"] == "listening"
    assert tracker.face(100.5, 100, W, H, now=6)["mood"] == "sleepy"   # sub-pixel jitter isn't movement
    assert tracker.face(100, 100, W, H, now=16)["mood"] == "resting"
    assert tracker.face(300, 100, W, H, now=17)["mood"] == "listening"
    assert tracker.face(300, 100, W, H, now=40, mood="happy")["mood"] == "happy"

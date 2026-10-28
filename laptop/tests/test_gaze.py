import pytest

from bluey_laptop.gaze import GazeTracker

W, H = 1920, 1200
ONE = (0, 0, W, H)


def gaze(tracker, x, y, screen=ONE, desktop=None):
    face = tracker.face(x, y, screen, desktop, now=0)
    return face["gazeX"], face["gazeY"]


def test_eyes_follow_the_mouse():
    tracker = GazeTracker()
    x, y = gaze(tracker, W / 2, H / 2)
    assert x == 0 and -1 <= y <= -0.15
    assert gaze(tracker, 0, H / 2)[0] == -1
    assert gaze(tracker, W, H / 2)[0] == 1
    # The phone sits 0.18 screen-heights below the bottom edge (same as the Mac).
    assert gaze(tracker, W / 2, H)[1] == pytest.approx(-0.18 / 1.1)
    assert gaze(tracker, W / 2, 0)[1] == -1


@pytest.mark.parametrize("position", [0, 0.25, 0.5, 0.8, 1])
def test_one_monitor_matches_the_mac_formula(position):
    tracker = GazeTracker(phone_position=position)
    for mx in (0, 300, W / 2, 1500, W):
        for my in (0, 400, H):
            mac_x = max(-1, min(1, (mx - W * position) / (W * 0.5)))
            mac_y = -max(0.15, min(1, (H * 1.18 - my) / (H * 1.1)))
            assert gaze(tracker, mx, my) == pytest.approx((mac_x, mac_y))


def test_second_monitor_to_the_right_keeps_following():
    tracker = GazeTracker()
    laptop, desktop = (0, 0, W, H), (0, 0, 2 * W, H)
    xs = [gaze(tracker, mx, H / 2, laptop, desktop)[0] for mx in range(0, 2 * W + 1, 240)]
    assert xs == sorted(xs) and len(set(xs)) == len(xs)  # keeps moving all the way across
    assert xs[0] == -1 and xs[-1] == 1
    assert 0 < gaze(tracker, W, H / 2, laptop, desktop)[0] < 1  # own right edge isn't the limit


def test_phone_under_the_right_hand_monitor():
    tracker = GazeTracker()
    # Main display at 0..1920, the phone's monitor to its right (Windows coordinates).
    right, desktop = (W, 0, W, H), (0, 0, 2 * W, H)
    assert gaze(tracker, W * 1.5, H / 2, right, desktop)[0] == 0
    assert gaze(tracker, 0, H / 2, right, desktop)[0] == -1
    assert -1 < gaze(tracker, W, H / 2, right, desktop)[0] < 0


def test_monitor_to_the_left_with_negative_coordinates_and_one_above():
    tracker = GazeTracker()
    laptop = (0, 0, W, H)
    left = (-2560, -240, 2560, 1440)
    desktop = (-2560, -240, 2560 + W, 1440)
    assert gaze(tracker, -2560, 0, laptop, desktop)[0] == -1
    assert -1 < gaze(tracker, -1280, 0, laptop, desktop)[0] < -0.5
    # The taller monitor reaches higher, so its top is a full upward look and below it isn't.
    assert gaze(tracker, -1280, -240, laptop, desktop)[1] == -1
    assert -1 < gaze(tracker, -1280, 0, laptop, desktop)[1] < -0.15


def test_phone_position_shifts_the_gaze():
    assert gaze(GazeTracker(phone_position=0), W / 2, H / 2)[0] == 1


def test_drowsy_then_napping_when_the_mouse_is_still():
    tracker = GazeTracker()
    assert tracker.face(100, 100, ONE, now=0)["mood"] == "listening"
    assert tracker.face(100.5, 100, ONE, now=6)["mood"] == "sleepy"   # sub-pixel jitter isn't movement
    assert tracker.face(100, 100, ONE, now=16)["mood"] == "resting"
    assert tracker.face(300, 100, ONE, now=17)["mood"] == "listening"
    assert tracker.face(300, 100, ONE, now=40, mood="happy")["mood"] == "happy"

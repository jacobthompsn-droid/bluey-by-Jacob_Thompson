"""What the laptop and the phone agree on. Mirrors Shared/GooglyLink.swift so the
Android app, the iPhone app, the Mac app and this app all speak the same packets."""

# Over USB, `adb reverse tcp:47474 tcp:47474` makes the phone's localhost:47474 reach this port.
DEFAULT_PORT = 47474

# Mood raw values, exactly as the Swift enum spells them.
MOODS = ("listening", "resting", "thinking", "talking", "pointing", "happy", "sleepy")

# Packet keys from Swift's `Packet`. Missing keys mean "not set".
PACKET_KEYS = ("face", "hello", "volume", "command", "audio", "speech", "callID", "tool", "text", "image")

# Same cap as the Swift LineConnection: one line (message) may not exceed this.
MAX_LINE = 16 * 1024 * 1024


def face(gaze_x=0.0, gaze_y=0.0, mood="listening", talk=0.0):
    """A FaceState. x: -1 left … 1 right. y: -1 up … 1 down."""
    if mood not in MOODS:
        raise ValueError(f"unknown mood {mood!r}")
    return {"gazeX": float(gaze_x), "gazeY": float(gaze_y), "mood": mood, "talk": float(talk)}


def packet(**fields):
    """A Packet with only the fields that are set."""
    unknown = set(fields) - set(PACKET_KEYS)
    if unknown:
        raise ValueError(f"unknown packet fields {sorted(unknown)}")
    return {k: v for k, v in fields.items() if v is not None}

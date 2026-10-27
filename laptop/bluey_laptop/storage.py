"""The laptop's pairing secret, in a private per-user file (never in this repo).

Windows: %APPDATA%\\Bluey\\pairing.json. macOS: ~/Library/Application Support/Bluey.
Linux: $XDG_CONFIG_HOME/bluey. Treat it like a password: anyone with it can pair.
"""
import json
import os
import socket
import sys
from pathlib import Path

from .secure import PairingRecord


def config_dir():
    if sys.platform == "win32":
        base = Path(os.environ.get("APPDATA") or Path.home() / "AppData" / "Roaming")
        return base / "Bluey"
    if sys.platform == "darwin":
        return Path.home() / "Library" / "Application Support" / "Bluey"
    return Path(os.environ.get("XDG_CONFIG_HOME") or Path.home() / ".config") / "bluey"


def laptop_name():
    return socket.gethostname() or "Laptop"


def _path():
    return config_dir() / "pairing.json"


def load():
    try:
        return PairingRecord.from_json(json.loads(_path().read_text(encoding="utf-8")))
    except (OSError, ValueError):
        return None


def save(record):
    folder = config_dir()
    folder.mkdir(parents=True, exist_ok=True)
    if sys.platform != "win32":
        folder.chmod(0o700)
    path = _path()
    # Create with private permissions before writing the secret (POSIX); Windows profile folders are per-user.
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as file:
        json.dump(record.to_json(), file)


def load_or_create(port):
    record = load()
    if record is None or record.port != port:
        record = PairingRecord.generate(laptop_name(), port) if record is None \
            else PairingRecord(service=record.service, secret=record.secret, port=port)
        save(record)
    return record


def reset(port):
    """A fresh secret: phones paired with the old code stop connecting."""
    record = PairingRecord.generate(laptop_name(), port)
    save(record)
    return record

"""USB link: `adb reverse` makes the phone's localhost:<port> reach this laptop's localhost:<port>,
so the phone and laptop talk over the cable with no Wi-Fi involved. The mapping drops when the
phone is unplugged, so the app re-runs this every few seconds while no phone is connected."""
import os
import shutil
import subprocess
import sys
from pathlib import Path


def find_adb():
    found = shutil.which("adb")
    if found:
        return found
    candidates = []
    for variable in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(variable):
            candidates.append(Path(os.environ[variable]) / "platform-tools")
    if sys.platform == "win32":
        candidates.append(Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk" / "platform-tools")
    elif sys.platform == "darwin":
        candidates.append(Path.home() / "Library" / "Android" / "sdk" / "platform-tools")
    else:
        candidates.append(Path.home() / "Android" / "Sdk" / "platform-tools")
    for folder in candidates:
        for name in ("adb.exe", "adb"):
            if (folder / name).is_file():
                return str(folder / name)
    return None


def reverse(port, adb=None):
    """Returns (ok, message)."""
    adb = adb or find_adb()
    if not adb:
        return False, "adb not found. Install Android Studio (it includes adb) or Android platform-tools."
    flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)  # no console flash on Windows
    try:
        result = subprocess.run([adb, "reverse", f"tcp:{port}", f"tcp:{port}"], capture_output=True,
                                text=True, timeout=8, creationflags=flags)
    except (OSError, subprocess.TimeoutExpired) as error:
        return False, f"adb didn't run: {error}"
    if result.returncode == 0:
        return True, f"USB link ready (phone port {port} → laptop)."
    message = (result.stderr or result.stdout).strip().splitlines()
    detail = message[-1] if message else f"exit code {result.returncode}"
    if "no devices" in detail or "not found" in detail:
        return False, "No phone on USB. Plug it in and allow USB debugging on the phone."
    if "unauthorized" in detail:
        return False, "Phone says unauthorized: unlock it and tap Allow on the USB debugging prompt."
    return False, f"adb reverse failed: {detail}"

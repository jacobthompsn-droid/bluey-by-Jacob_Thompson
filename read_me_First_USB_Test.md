# First test: Bluey on an Android phone + Windows laptop, over USB

**What this test proves:** the phone and laptop pair securely, talk over the USB cable, and the blueberry's eyes on the phone **follow your laptop mouse**. That's the base everything else (focus nudges, voice, pointing) will sit on. There's no AI in this test, so no OpenAI key or cost.

**If anything goes wrong:** this whole change only *adds* two new folders (`android/` and `laptop/`) plus this guide. The Mac and iPhone apps are untouched. To undo it, revert the commit on `main`.

---

## What changed, and why (the "second round" findings)

Going USB-only removed most of the hard parts from the porting plan:

| Problem from the plan | What USB does to it |
|---|---|
| Finding the laptop on Wi-Fi (Bonjour/mDNS) | **Gone.** `adb reverse` makes the phone's `localhost:47474` *be* the laptop's `localhost:47474`. The phone always connects to `127.0.0.1`. |
| Campus Wi-Fi blocking devices | **Gone.** No Wi-Fi involved. |
| Windows Firewall prompt | **Gone.** The laptop only listens on `127.0.0.1`, which the firewall doesn't block. |
| Android 17 local-network permission | **Avoided.** It's localhost, and the app targets Android 15 (API 35). |

What still had to be built from scratch (none of the Swift could be reused):

| New file | What it is | Ported from |
|---|---|---|
| `laptop/bluey_laptop/secure.py` | Pairing code + encrypted channel (HMAC proof, HKDF keys, AES-GCM, in-order sequence numbers) | `Shared/SecurePairing.swift` |
| `laptop/bluey_laptop/link.py` | The laptop's server: challenge → proof → encrypted packets; drops bad or silent peers after 8 s | `Shared/GooglyLink.swift`, `Mac/…/PhoneServer.swift` |
| `laptop/bluey_laptop/gaze.py` | Mouse position → where the eyes look, **across all your monitors**; drowsy after 5 s still, napping after 15 s | `CursorOverlay.swift` `face(in:now:)` (the Mac only watches its main display) |
| `laptop/bluey_laptop/usb.py` | Finds `adb` and runs `adb reverse tcp:47474 tcp:47474`, re-trying every 5 s until the phone connects | new |
| `laptop/bluey_laptop/app.py` | The window: QR code, status, mood picker, which monitor the phone sits under + where along it (remembered) | `AppDelegate.swift` menu, `PairingPanel.swift` |
| `laptop/bluey_laptop/fake_phone.py` | Pretends to be the phone, to test the laptop side alone | new |
| `android/…/link/Secure.kt`, `Protocol.kt` | The same pairing/encryption/packets in Kotlin | `SecurePairing.swift`, `GooglyLink.swift` |
| `android/…/link/LaptopLink.kt` | Connects to `127.0.0.1:47474`, handshakes, reconnects every 2 s, explains what's wrong | `iOS/Sources/MacLink.swift` |
| `android/…/face/FaceAnimator.kt` | Springs, blinks, eye darts, hops, moods: a line-by-line port | `iOS/Sources/FaceView.swift` (`FaceAnimator`) |
| `android/…/face/FaceDrawing.kt` | Draws the blueberry with Compose, same 844×390 design units | `FaceView.swift` (`draw`), `BlobShape.swift` |
| `android/…/MainActivity.kt` | Full-screen sideways face, status line, **Pair** button (Google's QR scanner, so no camera permission needed) | `GooglyApp.swift`, `QRPairingView.swift` |

**Compatibility:** the packets and encryption are byte-for-byte the same as the Swift apps. The QR code gains an optional `port` field, which the Swift decoder ignores.

---

## What I tested in the cloud, and what I couldn't

| Check | Result |
|---|---|
| Laptop tests (`laptop/tests`): encryption, pinned test vectors, tamper/replay/out-of-order rejection, the full handshake over a real socket, wrong code refused, silent/garbage peers dropped, gaze maths (one monitor identical to the Mac; two monitors side by side, left/right, above, negative coordinates), drowsy/napping timing | ✅ 30 passed |
| Laptop window with **two simulated monitors**: picker lists both, eyes sweep smoothly across both, choice remembered after restart | ✅ |
| Phone Kotlin tests (`android/app/src/test`) run on a plain JVM: **same vectors as Python, byte for byte**, opening a line the laptop sealed, pairing-code parsing (including iPhone-era codes), animator moods and settling | ✅ 7 passed |
| **Live end-to-end:** the real Kotlin `LaptopLink` connected to the real Python server, paired, received hello and a stream of faces, and sent packets back | ✅ |
| Phone status messages for: laptop app not running / USB tunnel up but app not answering / wrong pairing code | ✅ each shows the right message |
| Laptop window renders; its QR decodes (OpenCV) to the exact saved pairing code | ✅ |
| **Building the Android app (APK)** and the Compose drawing code | ❌ **Not possible here.** The cloud environment blocks `dl.google.com`, where the Android SDK lives. The drawing and screen code was reviewed against the Compose APIs but **never compiled**. Your first Android Studio build is its first compile. |
| Windows-specific bits (`adb` on Windows, DPI scaling, the window on a real desktop) | ❌ Tested on Linux only |

---

## Setup (one time)

### Laptop (Windows)
1. Install **Python 3.10 or newer** from python.org. Tick **"Add python.exe to PATH"**.
2. Install **Android Studio** (developer.android.com/studio). It includes `adb`, which the laptop app finds automatically.
3. Open **PowerShell** in this repo's `laptop` folder and run:
   ```powershell
   py -m venv .venv
   .venv\Scripts\Activate.ps1
   pip install -r requirements.txt
   ```
   If PowerShell refuses to run `Activate.ps1`, run `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned` once, then try again.

### Phone (Samsung / any Android 8+)
1. **Settings → About phone → Software information → tap "Build number" 7 times** (enter your PIN). This unlocks Developer options.
2. **Settings → Developer options → turn on "USB debugging".**
3. Plug the phone into the laptop with a **data** USB cable. On the phone, tap **Allow** on "Allow USB debugging?" (tick "Always allow from this computer").

### Build the phone app
1. In Android Studio: **File → Open →** this repo's **`android`** folder.
2. Wait for **Gradle sync** to finish. The first time, it downloads a lot; accept any prompts to install the Android SDK / platform 35.
   - If it offers to **upgrade the Android Gradle Plugin**, choosing *not now* is fine for this test.
3. Pick your phone in the device dropdown at the top and press **Run ▶**. Bluey opens on the phone, sideways.

---

## Run the test

1. **Laptop:** in the `laptop` folder (with `.venv` active): `python -m bluey_laptop`
   A window opens with a QR code. Its **USB** line should say *"USB link ready"* (it runs `adb reverse` for you).
2. **Phone:** tap **Pair** (top right) and scan the QR code on the laptop screen.
3. Stand the phone sideways under the laptop screen.

### Checklist: what you should see

| # | Do this | Expect |
|---|---|---|
| 1 | Pair | Phone status (top left): *"Connected to <your laptop name>"*. Laptop window: *"Phone: connected to Samsung …"* |
| 2 | Move the mouse left / right / up | The eyes follow it smoothly. The numbers at the bottom of the laptop window change. |
| 3 | Set **Phone sits under** to the monitor the phone is below, and the slider to where along it | The eyes look "up at" the mouse from the right spot |
| 3b | **Two monitors:** sweep the mouse from the far edge of one monitor to the far edge of the other | The eyes keep moving the whole way across, with no stuck spot. Movement on the monitor farther from the phone looks a little smaller; that's by design. |
| 4 | Leave the mouse still for 5 s, then 15 s | Droopy sleepy eyes, then closed eyes with floating z's. Move the mouse and he wakes up. |
| 5 | Pick each mood in the **Mood** menu | Happy (squinty smile), Thinking (dots, eyes up-right), Talking, Pointing (lids), Resting, Sleepy |
| 6 | Unplug the USB cable for 10 s, then plug it back in | Phone says what's wrong and its eyes wander. It reconnects by itself within ~5 s of replugging. |
| 7 | Close the laptop app, then reopen it | Phone shows *"The USB link is up, but the laptop app didn't answer"*, then reconnects |
| 8 | Laptop: **New pairing code** | Phone drops and says the code was refused. Re-pair and it works again. |

Tell me the result of each row. A photo or screen recording of row 2 would be perfect.

### No phone handy? Test the laptop side alone
With the laptop app running, in a second PowerShell (`.venv` active): `python -m bluey_laptop.fake_phone`. It pairs and prints the face values as you move the mouse.

### Run the automated tests yourself
- Laptop: `pip install pytest` then `python -m pytest` in the `laptop` folder.
- Phone logic: in Android Studio, right-click `app/src/test` → **Run tests** (or `gradlew test` in `android`).

---

## Troubleshooting

| You see | Do this |
|---|---|
| Laptop: *"adb not found"* | Install Android Studio, or add `%LOCALAPPDATA%\Android\Sdk\platform-tools` to PATH, then press **Set up USB now** |
| Laptop: *"No phone on USB"* | Use a data cable (some are charge-only), turn on USB debugging, and unlock the phone |
| Laptop: *"unauthorized"* | Unlock the phone and tap **Allow** on the USB debugging prompt |
| Phone: *"Nothing on port 47474…"* | `adb reverse` hasn't run yet. Check the laptop's USB line, or press **Set up USB now** |
| Phone: *"…laptop refused this pairing code"* | Tap **Re-pair** and scan the current QR code |
| Laptop: *"Couldn't listen on port 47474"* | Another copy is running. Close it, or start with `python -m bluey_laptop --port 47475` and pair again |
| Android Studio build errors | Copy the **first** red error from the Build window and send it to me. This is the first time the Android code has been compiled, so a small fix may be needed. |
| Eyes point the wrong way | Check **Phone sits under** names the right monitor (Monitor 1 is the leftmost), then move the slider. If they still look off, tell me your Windows display scaling for each monitor (Settings → Display → Scale). |

---

## Next steps after this passes
1. Focus mode on the laptop: screenshot + Windows OCR + the same OpenAI focus check as the Mac, with nudge bubbles (porting plan phase 3).
2. The pointing cursor overlay on Windows (phase 4).
3. Hold-to-talk voice on the phone (phase 5).

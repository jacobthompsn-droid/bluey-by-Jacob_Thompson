# What this repo is: research notes and a startup read

These notes come from reading the code, README, build config, tests and git history of this repository (43 commits, 28 Sep to 25 Oct 2026). Every claim below was checked against the source unless it's marked as an opinion or an open question.

---

## 1. The short version

**Bluey is an AI desk companion: a cartoon blueberry that lives on your iPhone and watches over your Mac.**

You stand your iPhone sideways under your Mac monitor. A blueberry face with googly eyes appears on it. The eyes follow your mouse. Hold the phone and tell it what you're working on, for example *"I'm making a Codex video for 30 minutes,"* and a focus session starts. While it runs, the Mac takes a screenshot every 30 seconds. An AI model judges whether you're **on task**, **off task** or **uncertain**. If you keep drifting, Bluey flies its own blue cursor to the distracting thing on screen and shows a short, cheeky British speech bubble with a chirp. Example: *"Oi, back to the mission."* It doesn't scold you, block websites or close apps.

Bluey can also:
- answer questions about anything on your screen by pointing at it
- run web research and show a report card with sources
- control your computer (click, type, scroll, open apps and sites) when you ask
- hand coding jobs to OpenAI's Codex and report back when they finish
- record and transcribe all-day meeting notes with speaker labels

In one line: **a focus coach and screen-aware assistant with a personality, spread across your phone and your Mac.**

---

## 2. Where it came from

| Thing | What the repo shows |
|---|---|
| Original name | **"Googly Eyes"**. The first commit is *"Googly Eyes milestone 1: phone face and hand-driven Mac cursor"*. Internal names are still `GooglyMac`, `GooglyEyes` and `_googly._tcp`. |
| Renamed to Bluey | 4 Oct 2026, commit `cc8cf4f` |
| Commit author | Every one of the 43 commits is by **Riley Brown** (`riley@notanumber.com` and `rb@visionairy.co`). The iPhone app was *"signed with Riley's team"*. |
| Company / bundle ID | `co.visionairy.googly.*`, so it looks like it came out of **Visionairy** |
| License | **The Unlicense**, i.e. public domain. *"Do whatever you want with it."* |
| This copy | Lives at `jacobthompsn-droid/bluey-by-Jacob_Thompson`. None of the commits are by Jacob yet. |

**What this means:** the code was written by Riley Brown and released into the public domain. Legally, you can use it, change it, and build a commercial product on it without asking permission or giving credit. Crediting the original author is still the decent thing to do.

### How it evolved (from git history)

1. **28 Sep: "Googly Eyes."** A phone face plus a Mac cursor. Then the "brain": push-to-talk, screen reading, AI answers and an ElevenLabs voice. Later the same day it moved to OpenAI Realtime voice, gained a personality editor, sleepy and napping moods, smooth cursor flights, and **computer control**.
2. **2 Oct: polish.** Speech bubbles and chirps replaced the spoken voice. It gained web research report cards, a sessions and transcripts screen, and permission handling.
3. **4 Oct.** Renamed to **Bluey**. Added all-day meeting notes.
4. **22 Oct: the pivot to a *focus companion*.** Screenshot classification, gentle nudges, synced timers, secure QR pairing, Developer Mode and tests. This is now the app's main job: the voice model's instructions say *"Your main job is being a gentle focus friend."*

---

## 3. How it works (technical)

### Architecture

```
 iPhone (SwiftUI, iOS 17+)                       Mac (menu bar app, macOS 14+)
 ┌─────────────────────────┐   local Wi-Fi,     ┌──────────────────────────────────┐
 │ Blueberry face + eyes   │   Bonjour +        │ Screen capture + OCR             │
 │ Hold-to-talk mic        │◄─ AES-GCM link ──►│ Focus timer (source of truth)     │
 │ Focus controls/timeline │   (QR-paired)      │ Blue cursor overlay + bubbles    │
 │ Session history         │                    │ Computer control, Codex, research│
 └───────────┬─────────────┘                    └───────────────┬──────────────────┘
             │ short-lived token (10 min)                      │ long-lived API key
             ▼                                                 ▼
                       OpenAI APIs (Realtime, Decisions, Responses, Transcribe)
```

- **Language:** 100% Swift, about 8,700 lines. Mac code is built with SwiftPM, iPhone code with XcodeGen and Xcode.
- **The Mac is in charge.** It owns the clock, the screenshot schedule and cancellation. The phone sends commands and shows what the Mac reports.
- **The phone never gets your real OpenAI key.** The Mac mints a Realtime token that expires after 10 minutes and passes that on (`RealtimeHost.mintToken`).

### AI models used (as named in the code)

| Job | API / model |
|---|---|
| Voice conversation and tool calls | OpenAI **Realtime** API. Text-only output (bubbles), push-to-talk input, `gpt-4o-mini-transcribe` for transcripts |
| "Am I on task?" check | OpenAI **Decisions** API (`POST /v1/decisions`), model `gpt-6-luna`, a three-way choice: `on_task` / `off_task` / `uncertain`, with probabilities |
| Nudge wording and what to point at | **Responses** API, `gpt-5.5`, structured JSON output, `store: false` |
| Web research | Responses API with the web-search tool, `gpt-5.5` |
| Meeting notes | `gpt-4o-transcribe-diarize`, falling back to `gpt-transcribe` |
| Coding jobs | Local `codex app-server` (JSON-RPC), signed in with your ChatGPT account |

### The nudge logic (`Shared/FocusSession.swift`, `Mac/Sources/GooglyMac/FocusCompanion.swift`)

This is the most carefully built part of the app:
- Only **consecutive** off-task results with P(off_task) ≥ 0.5 count toward a nudge. Any on-task, uncertain, failed, stale or missing check resets the count.
- The more confident the model is, the sooner it nudges. At P ≥ 0.95 it waits **120 s**. At P = 0.5 it waits **240 s**.
- After a nudge there's a **120 s cooldown**.
- Before nudging, it takes a fresh screenshot. It only points at a line of text if the AI quotes that line exactly, and the line is still in the same place when re-checked. Otherwise it shows the bubble without pointing.
- If any step fails, the fallback is: *"Are you sure you're on task right now?"*
- Monitoring stops when you pause, end, lock or sleep the Mac, background the phone, or lose the connection. It never resumes on its own.

### Privacy and security choices

- Screenshots stay in memory and are never saved to disk by Bluey. History keeps the task, timings and scores, but no images or screen text.
- Screen text is treated as **untrusted data, never instructions**, which defends against prompt injection. The computer-control rules also refuse to type passwords or payment details.
- Pairing uses a QR code that carries a 256-bit secret, then a challenge/HMAC handshake, HKDF keys and AES-GCM encryption with replay protection.
- The README admits that OpenAI may still keep data according to your API account settings.

### Quality

- 22 deterministic tests that make no real API calls. They cover timing, resets, cooldowns, pause/resume/expiry, disconnects and encrypted pairing.
- Opt-in live probes that use **synthetic** screenshots.
- Per the README, these were verified on a real Mac and an iPhone 16 Pro. Still untested: real-world off-task accuracy, chirp loudness and pointing at targets.

---

## 4. Why it's interesting as a startup idea

### The problem
Knowledge workers, students and creators lose hours to drifting: one tab becomes twenty minutes of scrolling. Existing tools fall into two groups:
- **Blockers** (Freedom, Cold Turkey, Screen Time). These are blunt, easy to switch off, and can't tell research on Reddit from doom-scrolling Reddit.
- **Timers and body-doubling** (Pomodoro apps, Focusmate, study-with-me streams). They don't know what you're actually doing.

### What's new here
1. **It understands context.** It judges your screen against *your stated task*. Twitter can be on-task if you're researching a post.
2. **It's a character, not a cop.** A cute blueberry with a dry British wit nudges you gently. That's emotionally different from a block screen, closer to a desk pet or a body double.
3. **It's a physical presence.** A phone propped under your monitor makes it feel like a companion on your desk. That's an ADHD-friendly "body doubling" angle.
4. **It's more than a focus app.** The same companion explains what's on screen, researches, operates your computer and runs Codex jobs. Focus is the hook. "AI desk buddy" is the platform.
5. **It's privacy-aware by design.** That matters a lot for a product that literally watches your screen.

### Who might pay
- Students (exam prep and assignments). Bluey's playful tone fits them.
- People with ADHD looking for body-doubling and accountability tools
- Remote workers, freelancers and indie developers
- Content creators. The README's own example task is "making a Codex video".

### Possible business models (opinion)
- **Subscription** ($5–15/month) that bundles the AI costs, so users don't need their own OpenAI key. Right now each user must paste in an API key, which is the biggest barrier to mainstream use.
- **Freemium:** a free timer and face, with paid AI screen checks, research and meeting notes.
- **Character and skin packs:** other mascots, voices and personalities. A personality editor already exists.
- **Education or team licences:** opt-in, privacy-first accountability for study groups or bootcamps.

---

## 5. Risks and open questions (be honest about these)

| Risk | Why it matters |
|---|---|
| **The name "Bluey"** | *Bluey* is a very famous children's TV show and brand (Ludo Studio / BBC Studios). Commercialising under that name is likely a trademark problem. **Rename before launching.** |
| **Screen-watching is sensitive** | Sending a screenshot to OpenAI every 30 seconds is a hard sell for workplaces and privacy-conscious users. You'd need a clear privacy policy and maybe on-device models. |
| **Running costs** | One vision check every 30 s works out to about 120 checks an hour per user, plus nudge and voice calls. Pricing has to cover that. Test real costs per hour. |
| **Accuracy is unproven** | The README says its thresholds are "conservative initial product thresholds, not measured accuracy guarantees." False nudges would annoy users quickly. |
| **Bring-your-own-key setup** | Users need an OpenAI account, a key, Screen Recording permission and QR pairing. That's fine for developers, not for everyone else. |
| **Apple-only, and needs both devices** | Mac + iPhone only. No Windows, Android or web version. |
| **Computer control and Codex "full access"** | Powerful, but risky to ship to consumers. The Codex controller runs with *"full access and no approvals"*. Those features should be off by default or removed in a consumer version. |
| **Code ownership / credit** | The code is public domain, so you're free to build on it. Still, all the commits are Riley Brown's, so be transparent about where the code came from if you present this as your startup. |
| **Platform dependency** | It's built entirely on OpenAI APIs, including newer ones like Decisions. Price or API changes hit the product directly. |

---

## 6. Suggested next steps, if this became a startup

1. **Pick a new name and character** (keep the blueberry idea, drop the trademark risk).
2. **Get it running yourself.** See README "Build and run": `./scripts/build-mac.sh` on the Mac, then the iPhone app through Xcode with your own development team in `project.yml`.
3. **Measure accuracy and cost** in real sessions: how often nudges are right or wrong, and how many dollars an hour it costs.
4. **Remove setup friction:** a hosted backend so users don't need their own API key, plus simpler onboarding.
5. **Talk to 10–20 target users** (students, people with ADHD, creators) before adding features.
6. **Decide the wedge:** focus companion first. Keep research, computer control and Codex as later "power" features.

---

## 7. Repo map (for reference)

| Path | What's there |
|---|---|
| `Shared/` | Shared by both apps: focus session and nudge policy (`FocusSession.swift`), encrypted pairing (`SecurePairing.swift`, `GooglyLink.swift`), colours, fonts |
| `Mac/Sources/GooglyMac/` | Menu bar app: `FocusCompanion` (timer and checks), `FocusAPI` (classifier and nudges), `RealtimeHost` (voice tools and personality), `CursorOverlay` (the blue cursor), `ScreenReader` (screenshot and OCR), `ComputerControl`, `CodexController`, `WebResearch`, `MeetingNotes`, `PhoneServer` |
| `iOS/Sources/` | Phone app: `FaceView` and `BlobShape` (the blueberry), `LiveVoice` and `ChunkRecorder` (mic), `FocusViews` (timeline), `QRPairingView`, `SessionStore`, `BlueyAppView` |
| `Tests/` | 22 deterministic tests for focus logic, lifecycle and secure transport |
| `docs/previews/` | Screenshots and GIFs of the face, expressions and cursor trails |
| `scripts/build-mac.sh` | Builds and signs `build/Bluey.app` |
| `project.yml` | XcodeGen spec for the iPhone app |

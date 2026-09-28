# fexdroid

**Linux PC games on an Android phone, running on the phone itself.** No streaming, no PC, no root.

fexdroid is an Android app that starts the Linux version of **Steam**, and games from it, on an ARM64 phone. x86 code is translated by [FEX-Emu](https://github.com/FEX-Emu/FEX); graphics go through [Mesa Turnip](https://docs.mesa3d.org/drivers/freedreno.html), the open Vulkan driver for Adreno. The game it is built and measured with is **Dota 2**.

[Română](README.ro.md)

> **Alpha, for testers.** It works on the phones below, with the limits described on this page. Expect rough edges, and please send reports: they are what moves the project.

## What works today

| | State |
|---|---|
| Steam client | Installs itself from Valve on first start; sign-in, library, store, downloads |
| Installing games | From Steam, on the phone, over Wi-Fi |
| Dota 2 | Starts from Steam, online; hero demo, watching live matches |
| Input | Touch, USB/Bluetooth mouse and keyboard |
| Sound | Yes |
| Updates | From inside the app |
| Languages | English, Romanian |

Not there yet: on-screen game controls and gamepads, a match played from start to finish on every tested phone, Samsung DeX.

## Tested phones

| Phone | Chip | Android | Result |
|---|---|---|---|
| Realme GT 5G | Snapdragon 888, Adreno 660, 12 GB | 14 | Dota 2 from Steam. 36–44 frames/s in a ten-hero test scene, 13–15 in a late-game match on a hot phone |
| Galaxy S26 Ultra | Snapdragon 8 Elite Gen 5, Adreno 840 | 16 | Steam and Dota 2 run; 25–32 frames/s in a match, measured with an earlier build |

Numbers are from the phones themselves, not estimates. How they were measured is in [NOTES.md](NOTES.md) (in Romanian).

## What your phone needs

- A **Snapdragon** chip with an **Adreno 6xx, 7xx or 8xx** (Snapdragon 845 or newer). Other graphics processors fall back to drawing on the CPU, which is far too slow for 3D games.
- **Android 12 or newer** (tested on 14 and 16), 64-bit, memory pages of 4 KB (the app checks).
- **12 GB of RAM or more** for Dota 2. The game holds 5 to 8 GB under the emulator.
- **Free space**: about 5 GB for Steam, and what the game needs on top (Dota 2: about 70 GB).

The app's first screen shows a verdict for your phone.

## Install

1. Download **`fexdroid-legacy.apk`** from the [latest release](https://github.com/cobrabm12/fexdroid/releases/tag/apk-latest) and install it. Android asks you to allow installing from your browser.
2. Open the app. On **Home › Status**, press **Download and install** for the Steam libraries (about 250 MB).
3. Press **Start Steam**. The first start downloads the Steam client from Valve and takes a few minutes. Sign in with your own account.
4. In Steam, install the game: **Store › Dota 2 › Play Game › Install**. Use Wi-Fi and keep the phone on its charger.
5. Press **Play**.

Later versions install from inside the app.

## Known limits

- **Heat decides the frame rate.** Phones lower their processor's speed when hot; a Snapdragon 888 drops to about 40% of it. Play off the charger if you can, and take the case off.
- **Memory.** On a 12 GB phone, several matches in one session can exhaust memory: the game slows to a crawl and Android closes it. Close other apps, and restart fexdroid between matches.
- **The first “Watch in-game”** of a session often ends with “Overflow error”, because the map loads slower than the server waits. The second try works.
- **Performance settings** are in Settings › Performance. “Fast memory access for Dota 2 and CS2” gave 13% more frames in our tests; it is experimental and off by default.

## Reporting a problem

In the app: **Home › Send report**, or **Send the log** on the screen shown when a game has closed. The report has the device, the app's log, the end of Steam's logs and a few self-tests. It has no passwords, but Steam's logs can name your account: read it before you post it in public. Open an [issue](https://github.com/cobrabm12/fexdroid/issues) and paste it there, with what you did and what happened.

## Steam, Valve and anti-cheat

- The app contains **no files from Valve**. Steam and the games are downloaded from Valve by Steam itself, with your account.
- fexdroid does **not modify** Steam or the games, and does nothing to or around VAC or any other protection.
- fexdroid is not affiliated with Valve, FEX-Emu or Mesa. Steam and Dota 2 are trademarks of Valve Corporation.
- We know of no account that had a problem from playing this way, but nobody outside Valve can promise what their systems decide. You play at your own risk.

## How it works

```
the game (x86-64 Linux)  →  FEX-Emu translates to ARM64
        ↓ Vulkan calls cross to native code
Mesa Turnip (ARM64)      →  Adreno, through the kernel's KGSL driver
        ↓ picture
Xvfb (virtual X11 screen) →  the app shows it and sends touch, mouse and keys back
```

The app carries a small Debian userland built for Android's restrictions: a patched glibc (System V shared memory and semaphores in user space, system calls Android forbids), FEX with local patches, Mesa with local patches. Everything is in [`patches/`](patches) and built by [`scripts/`](scripts).

## Building

You need Linux, the Android SDK and NDK, and Docker. Start with `scripts/check-host.sh`, then `scripts/build-payload.sh` and `./gradlew :app:assembleLegacyDebug`. The details and the reasons behind each decision are in [PLAN.md](PLAN.md) and [NOTES.md](NOTES.md) (both in Romanian; the code, comments and commits are in English).

## Licence

The project's own code is under the [MIT licence](LICENSE). The components it builds on keep theirs: see [LICENSES.md](LICENSES.md).

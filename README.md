# FEXDroid

FEXDroid is an experimental Android app for running Linux x86_64 programs locally on an ARM64 phone. It uses FEX-Emu for x86 translation and Mesa Turnip for Vulkan. The long-term target is the Linux version of Dota 2, without game streaming or Wine/Proton.

## Current status

On a Realme GT (Snapdragon 888, Android 14, 4 KB pages), the project has run x86_64 programs through FEX, shown Vulkan output, started the Steam client, and reached the Dota 2 main menu with a direct launch. The direct launch reports a lost Steam connection. Steam login, online play, and an actual match have **not** been verified on the phone. The latest SysV semaphore changes passed PC/qemu tests and still need phone testing.

This is a research prototype, not a ready-to-install Dota 2 release. Device support and performance are limited and may change as the project develops.

## Repository contents

- `app/`: Android app, UI, native display/audio/input bridges.
- `scripts/`: build, deployment, and device testing helpers.
- `patches/`: changes needed for FEX, glibc, and Mesa on Android.
- `tools/` and `tests/`: support programs and focused checks.
- `PLAN.md` and `NOTES.md`: architecture, setup commands, experiments, and known limits.

Start with `PLAN.md` and `scripts/check-host.sh` before attempting a build. The build helpers require a configured Android/Linux toolchain and produce large generated files that are excluded from Git.

Steam, Dota 2, game data, account credentials, and built root filesystems are not included. Users must obtain Valve software through their own accounts. See `LICENSES.md` for third-party components and `LICENSE` for the project's own code.

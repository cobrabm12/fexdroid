# fexdroid — PLAN

Aplicație Android care rulează programe Linux x86_64 (țintă finală: Dota 2 nativ Linux/Vulkan)
prin FEX-Emu + Mesa Turnip, fără Wine/Proton.

```
Steam Linux (x86/x86_64) ─► FEXInterpreter (JIT x86→ARM64)
                               │ thunks (libvulkan, libX11, libasound…)
                               ▼
                 biblioteci ARM64 glibc native ─► Mesa Turnip (KGSL) ─► /dev/kgsl-3d0 (Adreno 840)
                               │
                 server de afișare ─► SurfaceView în aplicația Android (Kotlin/Compose + NDK)
```

Status legendă: ⬜ neînceput · 🟨 în lucru · ✅ verificat pe telefon (cu output) · ⛔ blocat

---

## Faza 0 — Recunoaștere  🟨 (PC ✅ `scripts/check-host.sh` trece complet; telefon în așteptare)

### 0.1 PC de dezvoltare (verificat 2026-09-25) ✅
| Componentă | Stare |
|---|---|
| OS | CachyOS, kernel 7.2.3, 16 threaduri, 31 GiB RAM, 2.3 TB liberi |
| adb | ✅ 1.0.41 (platform-tools r36.0.2), reguli udev prezente, user în `plugdev` |
| NDK | ✅ r29 (29.0.14206865) în `/opt/android-ndk` |
| Android SDK | ✅ SDK nou, al utilizatorului, în `~/Android/Sdk`: cmdline-tools 22.0, platform-tools 37.0.1, platforms;android-36, build-tools 36.1.0, ndk 29.0.14206865. (`/opt/android-sdk` e vechi și rămâne neatins; proiectul folosește `~/Android/Sdk` prin `local.properties`.) |
| Java | ✅ OpenJDK 17 implicit (AGP 8.x/9.x cere 17+); există și 21+ |
| Gradle / Kotlin | ⚠️ lipsesc global — folosim Gradle wrapper din proiect (OK) |
| CMake / Ninja | ✅ 4.4.3 / 1.13.2 (NDK aduce și el CMake propriu dacă e nevoie) |
| Clang / LLD | ✅ 22.1.8 cu target aarch64 (cross-compile posibil cu sysroot) |
| aarch64-linux-gnu-gcc | nu e necesar (D5: cross cu clang + sysroot) |
| meson + mako | ✅ instalate 2026-09-25 |
| qemu-user-static + binfmt | ✅ instalate 2026-09-25; containerele arm64 rulează |
| Docker / Podman | ✅ 29.7 / 6.1 |
| patchelf, squashfs-tools, erofs-utils, ccache | ✅ instalate 2026-09-25 (debootstrap rulează în container) |

De instalat (cu acordul tău, e `pacman`/`sdkmanager` pe sistem):
```
sudo pacman -S --needed meson python-mako patchelf squashfs-tools erofs-utils ccache
# arm64 binfmt: scripts/ensure-binfmt.sh (vezi NOTES N-008)
# SDK (făcut, fără sudo, în ~/Android/Sdk):
sdkmanager "platforms;android-36" "build-tools;36.1.0" "cmdline-tools;latest"
```

### 0.2 Telefon ⛔ (telefonul nu e disponibil acum)
Rulez `scripts/phone-recon.sh` când e conectat; salvează în `docs/recon/`. Ce verificăm și de ce:

| Ce | De ce contează |
|---|---|
| `getconf PAGESIZE` | **Critic.** FEX suportă doar gazde cu pagini de 4 KB. Pe 16 KB, FEX nu pornește (vezi NOTES N-001). |
| model GPU (`/sys/class/kgsl/kgsl-3d0/gpu_model`) | Așteptat Adreno 840 (Gen 8, a8xx) pe Snapdragon 8 Elite Gen 5 |
| `/dev/kgsl-3d0` accesibil din app | Turnip pe KGSL are nevoie de el; SELinux trebuie să-l permită pentru `untrusted_app` |
| Features CPU (`/proc/cpuinfo`) | FEX folosește LSE/atomics, RCPC, FlagM, SVE etc. dacă există |
| versiune Android / SDK, kernel | seccomp, restricții exec, phantom process killer |
| SELinux, seccomp, SysV IPC, userns | vezi constrângerile de mai jos |
| thermal zones, frecvențe/clustere CPU | Faza 6 (afinitate, throttling) |

Așteptări (de confirmat pe telefon): SoC Snapdragon 8 Elite Gen 5 "for Galaxy", GPU Adreno 840,
One UI 8.x. Pagină de 4 KB e probabilă, dar **nu am găsit nicio sursă care s-o confirme**.

---

## Decizii (aprobate 2026-09-25: toate recomandările)

### D1. Cum lansăm procesele glibc în sandbox-ul Android
Problemele de rezolvat indiferent de variantă: (a) `execve` din directorul de date e interzis
pentru `targetSdk ≥ 29`; (b) seccomp-ul aplicațiilor omoară procesul cu SIGSYS la syscall-uri
pe care glibc/FEX le folosesc (`rseq`, `set_robust_list`, `faccessat2`, `shmget`…);
(c) căile absolute (`/lib/ld-linux-aarch64.so.1`, `/usr`, `/etc`) nu există.

| Variantă | + | − |
|---|---|---|
| **A. proot** (ptrace) | Rezolvă toate trei problemele dintr-o lovitură: traduce căi, poate emula/rescrie syscall-uri, lucrat de ani în Termux. Faza 1 e gata rapid. | Fiecare syscall trece prin ptrace → overhead mare pe jocuri (futex, ioctl-uri Vulkan, mmap de la JIT-ul FEX). GPL-2 (ok ca binar separat, vezi LICENSES). Încă un strat de depanat sub FEX. |
| **B. rootfs glibc cu căi patch-uite (stil Winlator)** | Zero overhead la runtime. Interpretor + RPATH rescrise cu `patchelf` spre `/data/data/<pkg>/files/rootfs`. Glibc patch-uit (SIGSYS→ENOSYS, SysV shm peste memfd/ashmem). | Trebuie să construim și să întreținem un glibc patch-uit. Programele care execută căi absolute hardcodate (Steam o face des: `/bin/sh`, `/usr/bin/…`) au nevoie de redirectare. Mai mult lucru inițial. |
| **C. loader propriu** (`ld.so` lansat din `nativeLibraryDir` + LD_PRELOAD shim care interceptează `execve`/`open` și un handler SIGSYS) | Performanță ca B, fără glibc patch-uit complet; înțelegi fiecare piesă. | Cel mai mult cod propriu; un shim LD_PRELOAD nu prinde syscall-urile directe (dar FEX le trece prin propriul handler, deci putem intercepta acolo pentru codul x86). |

**Recomandarea mea:** **B+C combinat în trepte** — Faza 1 cu `ld-linux-aarch64.so.1` pus în
`nativeLibraryDir` (ca `libld.so`) care încarcă binarele din rootfs (fără `execve` din date),
plus un mic preload care redirecționează `execve` și prinde SIGSYS. Păstrăm proot doar ca
unealtă de depanare, nu în calea jocului. Motiv: la Dota 2 overhead-ul ptrace ar fi cel mai
mare cost evitabil din tot lanțul.

### D2. targetSdk / exec
| Variantă | + | − |
|---|---|---|
| **targetSdk 28** (Winlator, Termux) | `execve` din date funcționează; simplu | Nu poate intra pe Play; Android afișează avertisment; risc ca versiuni viitoare să blocheze instalarea |
| **targetSdk 36 + nativeLibraryDir + redirect exec** | Modern, „corect” | Mai multă muncă (legat de D1-C) |

Recomandare: **targetSdk 36** cu soluția din D1; aplicația e pentru uz personal, dar nu vrem să
depindem de un comportament vechi pe care Google îl poate închide oricând.

### D3. Afișare
Turnip pe KGSL nu are DRI3/dma-buf pentru X11 ca pe un desktop, deci prezentarea Vulkan din
WSI-ul X11 cade pe calea software (copiere prin `xcb_put_image`/MIT-SHM).

| Variantă | + | − |
|---|---|---|
| **A. Xvfb (real, din rootfs ARM64) + punte framebuffer→SurfaceView** | Protocol X11 complet (Steam/CEF are nevoie de multe extensii). Punte simplă: citim framebuffer-ul Xvfb (fișier mmap) și îl copiem în `ANativeWindow`. | O copie de CPU pe cadru + latență; Xvfb nu știe de input Android (injectăm prin XTEST). |
| **B. server X11 propriu minimal** (ca Winlator, dar scris de noi) | Control total, poate prezenta direct în `AHardwareBuffer` | Foarte mult de implementat ca Steam/CEF să meargă; LGPL dacă ne inspirăm prea mult din codul Winlator |
| **C. compositor Wayland minimal** (wlroots headless sau propriu) + XWayland | Protocol modern, buffere partajate mai ușor | XWayland tot e nevoie pentru Steam; mai multe piese; thunk-ul WaylandClient e mai puțin testat |

Recomandare: **A pentru Fazele 3–5** (cea mai simplă variantă care sigur funcționează), apoi în
Faza 6 un **layer Vulkan propriu** care prezintă swapchain-ul jocului direct într-un
`AHardwareBuffer` al SurfaceView-ului, ocolind copierea X11 doar pentru joc.

### D4. Sursa rootfs-urilor
- ARM64 glibc: **Debian 13 (trixie) arm64** construit cu `debootstrap`/`mmdebstrap` într-un
  container (după ce activăm binfmt), sau Arch Linux ARM (tarball gata făcut).
  Recomand Debian: glibc stabil, multiarch, aceleași pachete ca rootfs-ul x86.
- x86_64 pentru FEX: **Debian/Ubuntu amd64 + i386 multiarch** (Steam are încă părți pe 32 de
  biți), construit tot cu `build-rootfs.sh`; alternativ imaginile oficiale FEX (Ubuntu, erofs/squashfs).

### D5. Cum compilăm pentru aarch64 glibc
| Variantă | + | − |
|---|---|---|
| **container arm64 sub qemu-user** | Build „nativ”, zero probleme de cross | Lent (FEX + Mesa sub qemu: ore) |
| **cross cu clang + sysroot din rootfs-ul ARM64** | Rapid, reproductibil, folosește clang 22 existent | Configurare CMake/meson cross-file |

Recomandare: **cross cu clang + sysroot**, cu containerul arm64 ca rezervă.

### D6. Licența aplicației
Vezi LICENSES.md. Trebuie aleasă înainte de primul cod: propun **MIT** pentru codul nostru
(compatibil cu FEX/Mesa); componentele GPL/LGPL rămân binare separate cu sursele indicate.

---

## Riscuri majore (în ordinea gravității)
1. **Pagini de 16 KB** pe telefon → FEX nu merge deloc. Primul lucru verificat.
2. **Turnip pe Adreno 840 + KGSL**: suport upstream din Mesa 26.0, dar comunitatea încă aplică
   patch-uri (branch `turnip/gen8`); build-urile publice A8xx sunt marcate „compile-verified only”.
3. **steamwebhelper (CEF) sub FEX**: fragil; soluții cunoscute `-cef-disable-gpu`, biblioteci
   runtime scoase. Alternativă de rezervă: pornire cu UI minim.
4. **Seccomp Samsung** poate diferi de AOSP; de testat fiecare syscall problematic.
5. **Phantom process killer** (Android 12+) omoară procese copil în exces — Steam pornește
   multe. Se dezactivează din opțiunile de dezvoltator / `adb shell settings`.
6. **Termic**: sesiuni lungi de Dota → throttling; Faza 6.

Semnal bun: Canonical livrează Steam arm64 ca snap cu FEX, iar Dota 2 a fost raportat
funcționând acolo (pe hardware ARM Linux cu driver normal, nu Android).

---

## Faze

### Stare 2026-09-27 (Realme GT, firmware oficial, fără root)
| Fază | Stare | Dovadă |
|---|---|---|
| 0 | ✅ recunoaștere pe Realme GT (SD888) | `docs/recon/`, N-021 |
| 1 | ✅ pe telefon | N-021 |
| 2 | ✅ x86_64 dinamic prin FEX pe telefon; static încă crapă | N-021 |
| 3 | ✅ `vkcube` arm64 și x86_64 (FEX + thunk) pe ecran, Turnip Adreno 660 | N-021 |
| 4 | ✅ input XTEST (touch/tastatură verificate cu xev) și audio PulseAudio→AAudio (arm64 + x86 prin FEX); BT real netestat | N-022 |
| 5 | ✅ clientul Steam se instalează singur din aplicație, login, bibliotecă, magazin, instalare de jocuri; Big Picture netestat | N-028, N-029 |
| 6 | 🟨 **Dota 2 pornit din Steam ajunge la meniul principal, online** (~17 cadre/s în meniu); meci + performanță urmează | N-024, N-030 |

### Testare pe telefon (când e conectat)
```
./gradlew assembleLegacyDebug assembleModernDebug
adb install -r app/build/outputs/apk/legacy/debug/app-legacy-debug.apk
adb install -r app/build/outputs/apk/modern/debug/app-modern-debug.apk
# recunoaștere (ambele variante), raport în /sdcard/Android/data/<pkg>/files/recon.txt
adb shell am start -n ro.cobrabm.fexdroid/.MainActivity --ez autorun true
adb shell am start -n ro.cobrabm.fexdroid.modern/ro.cobrabm.fexdroid.MainActivity --ez autorun true
adb pull /sdcard/Android/data/ro.cobrabm.fexdroid/files/recon.txt docs/recon/recon-legacy.txt
adb pull /sdcard/Android/data/ro.cobrabm.fexdroid.modern/files/recon.txt docs/recon/recon-modern.txt
# faza 1 / 2 (doar varianta legacy), log în .../files/linux.txt și în logcat
adb shell am start -n ro.cobrabm.fexdroid/.MainActivity --es action phase1
adb shell am start -n ro.cobrabm.fexdroid/.MainActivity --es action fex-static
adb shell am start -n ro.cobrabm.fexdroid/.MainActivity --es action vulkaninfo
# faza 3 pe ecran: X11 + vkcube arm64, apoi x86 prin FEX
adb shell am start -n ro.cobrabm.fexdroid/.MainActivity --es action vkcube
adb shell am start -n ro.cobrabm.fexdroid/.MainActivity --es action fex-vkcube
adb logcat -s fexdroid-linux fexdroid-recon fexdroid-display
```
Fără PC, aplicația are butoane pentru toate pașii și „Trimite raportul” (Share).

### Pipeline de build
```
scripts/build-rootfs.sh     # Debian arm64 rootfs + sysroot, rootfs x86_64 (Docker)
scripts/build-glibc.sh      # glibc patch-uit (N-009)
scripts/build-fex.sh        # FEX cross-compilat
scripts/build-mesa.sh       # Turnip KGSL
scripts/build-payload.sh    # asamblează build/payload (assets + jniLibs pentru APK)
scripts/test-payload-qemu.sh
./gradlew assembleLegacyDebug
```

### Faza 1 — Shell-ul aplicației ⬜
1. Proiect Gradle: Kotlin + Compose, modul NDK/CMake, `minSdk 31`, `targetSdk` după D2, ABI `arm64-v8a`.
2. `build-rootfs.sh`: rootfs ARM64 minim (busybox/dash + glibc + coreutils) → `rootfs-arm64.tar.zst`.
3. Extragere la prima rulare în `filesDir/rootfs` (JNI + libzstd/libarchive), cu verificare checksum.
4. Lansator nativ (după D1) care rulează `/bin/sh -c "uname -a; ls /"` în rootfs; stdout/stderr în UI.
5. Test: `adb install`, `adb logcat -s fexdroid`, captură output în NOTES.

### Faza 2 — FEX ⬜
1. `build-fex.sh`: FEX-2609 (fixat pe tag), cross clang, `BUILD_THUNKS=ON`, instalat în rootfs-ul ARM64.
2. Rootfs x86_64 + configurare FEX (`Config.json`, `RootFS`, `ThunkHostLibs`).
3. „hello world” x86_64 static (compilat pe PC) → prin FEX pe telefon.
4. „hello world” dinamic (glibc x86_64 din rootfs-ul x86).
5. Documentat: SIGSYS-uri întâlnite, cache de cod FEX.

### Faza 3 — Grafică ⬜
1. `build-mesa.sh`: Turnip, `-Dvulkan-drivers=freedreno -Dfreedreno-kmds=kgsl -Dplatforms=x11`,
   glibc ARM64; Mesa fixat pe tag (≥ 26.2) + patch-urile a8xx necesare, documentate.
2. `vulkaninfo` ARM64 nativ pe telefon.
3. Server de afișare (D3) + punte spre SurfaceView.
4. `vkcube` ARM64 nativ pe ecran, apoi `vkcube` x86_64 prin FEX cu thunk Vulkan.

### Faza 4 — Input și audio ⬜
1. Tastatură/mouse Bluetooth (inclusiv DeX): `onGenericMotionEvent`, pointer capture pentru
   mouse relativ, mapare keycode Android → X11 keysym, injectare XTEST.
2. Touch → mouse (tap = click, drag, two-finger = click dreapta/scroll).
3. Audio: PulseAudio în rootfs cu un sink care scrie într-un socket/ring buffer citit de o
   componentă nativă AAudio (sau thunk `libasound`). Test: `paplay`/`speaker-test`.

### Faza 5 — Steam ⬜
Utilizatorul descarcă și instalează singur Steam (bootstrapper-ul Valve) și se loghează.
Nu includem fișiere Valve, nu atingem VAC/protecții.
Investigăm: componente i386, steam-runtime (pressure-vessel nu merge fără userns → rulăm fără
container), `steamwebhelper` (flag-uri CEF, `-cef-disable-gpu`), modul Big Picture/„-gamepadui”
ca UI alternativ.

### Faza 6 — Dota 2 ⬜
Lansare, apoi profilare: cache de cod FEX (JIT disk cache din FEX-2609), afinitate pe
nucleele mari, monitorizare temperatură/frecvențe, setări grafice, layer de prezentare directă (D3).

---

## Jurnal de decizii
| Data | Decizie | Motiv |
|---|---|---|
| 2026-09-25 | Nume proiect `fexdroid`, în `~/Projects/fexdroid` | provizoriu, se poate schimba |
| 2026-09-25 | D1: loader `ld.so` din `nativeLibraryDir` + shim (exec redirect, SIGSYS); proot doar pentru depanare | fără overhead ptrace în calea jocului |
| 2026-09-25 | D2: targetSdk 36 | nu depindem de comportament vechi |
| 2026-09-25 | D3: Xvfb + punte framebuffer→SurfaceView; mai târziu layer Vulkan cu prezentare directă | cea mai simplă variantă sigură pentru Steam/CEF |
| 2026-09-25 | D4: rootfs Debian 13 (arm64; amd64 + i386), construit cu debootstrap într-un container Docker | reproductibil, fără debootstrap pe host |
| 2026-09-25 | D5: cross-compile cu clang 22 + sysroot din rootfs-ul ARM64; container arm64 ca rezervă | rapid |
| 2026-09-25 | D6: licență MIT pentru codul propriu | compatibilă cu FEX/Mesa |
| 2026-09-25 | D1 rafinat: glibc patch-uit (varianta B) este obligatoriu, nu opțional | seccomp omoară glibc standard la fiecare fir nou (N-009) |
| 2026-09-25 | D2 redeschis: APK în două variante (targetSdk 28 și 36); decidem după recunoaștere | W^X blochează și mmap(PROT_EXEC) pe fișiere din app (N-010) |
| 2026-09-25 | Pachet canonic `ro.cobrabm.fexdroid` = varianta legacy | calea rootfs-ului e compilată în glibc |
| 2026-09-25 | Apelurile blocate de Android devin ENOSYS generic (glibc + FEX), nu caz cu caz | N-018 |
| 2026-09-25 | Telefon de test suplimentar: Snapdragon 888, Android 14/15 | N-019 |

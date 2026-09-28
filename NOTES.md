# fexdroid — NOTES

Probleme întâlnite / cunoscute și soluțiile alese. Fiecare intrare: ID, simptom, cauză, soluție,
status (📚 cunoscut din documentație · 🧪 reprodus pe telefon · ✅ rezolvat și verificat).

## N-001 · Pagini de memorie 16 KB vs 4 KB  📚
- FEX suportă doar gazde AArch64 cu pagini de 4 KB; pe 16 KB eșuează
  (ex. „jemalloc: Unsupported system page size”). Asahi ocolește asta cu un microVM de 4 KB, nerealist pe Android.
- Android 15+ permite kernel de 16 KB, dar e alegerea producătorului.
- Verificare: `adb shell getconf PAGESIZE` (4096 = OK, 16384 = blocaj).
- Surse: https://github.com/FEX-Emu/FEX/issues/3496 · https://source.android.com/docs/core/architecture/16kb-page-size/16kb

## N-002 · Seccomp pentru aplicații  📚
- Politica AOSP pentru `untrusted_app` trimite SIGSYS la syscall-uri pe care glibc modern le
  folosește: `rseq`, `set_robust_list`, `faccessat2`, SysV IPC (`shmget`/`semget`/`msgget`),
  `io_uring_*`, `futex_waitv`, `name_to_handle_at`, landlock.
- Permise: `clone3`, `memfd_create`, `pidfd_open`, `userfaultfd`.
- Soluții de evaluat: handler SIGSYS care întoarce `-ENOSYS`; `GLIBC_TUNABLES=glibc.pthread.rseq=0`;
  pentru codul x86 le filtrăm în handler-ul de syscall al FEX.
- Samsung poate avea o politică diferită de AOSP `main` → de testat pe telefon.
- Sursă: https://android.googlesource.com/platform/bionic/+/refs/heads/main/libc/SECCOMP_ALLOWLIST_COMMON.TXT

## N-003 · Fără SysV IPC  📚
- `shmget`/`shmat` lipsesc (seccomp + kernel). X11 MIT-SHM, PulseAudio și Steam le folosesc.
- Rezolvat în N-017 (glibc patch 0002 + `fxshmd`). Opțiuni evaluate: emulare peste `memfd_create` + un server local (ca `android_sysvshm` din Winlator sau
  libandroid-shmem din Termux), sau dezactivăm MIT-SHM unde se poate.

## N-004 · execve din directorul de date (W^X)  📚
- Cu `targetSdk ≥ 29` nu se poate executa un fișier din `filesDir`.
- Opțiuni: `targetSdk 28`; binare în `nativeLibraryDir` ca `lib*.so` (`useLegacyPackaging=true`);
  loader (`ld.so`) din `nativeLibraryDir` care încarcă restul prin `mmap`; redirect de `execve`.
- Sursă: https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission

## N-005 · Phantom process killer  📚
- Android 12+ omoară procesele copil ale aplicațiilor peste o limită (32 total).
- Steam pornește multe procese. Soluție de test:
  `adb shell settings put global settings_enable_monitor_phantom_procs false` (Android 14+),
  sau opțiunea „Disable child process restrictions” din Developer options.

## N-006 · Turnip pe Adreno 840 (a8xx) peste KGSL  📚
- Suport Gen 8 în Mesa 26.0; ID-ul A840 e în `main`. Comunitatea încă folosește branch-ul
  `turnip/gen8` + patch-uri; build-urile publice a8xx sunt „compile-verified only”.
- Plan: încercăm întâi Mesa upstream (tag fix), apoi aplicăm patch-uri documentate unul câte unul.
- Surse: https://www.phoronix.com/news/Mesa-26.0-Adreno-Gen-8-Graphics · https://github.com/The412Banner/Banners-Turnip

## N-007 · Mediu PC  🧪
- binfmt aarch64 nu e înregistrat (qemu-user fără `qemu-user-static-binfmt`).
- Android SDK fără platforme și cu `sdkmanager` vechi (tools 26.1.1).
- Dispozitivul adb wireless găsit prin mDNS la [LAN address] **nu e telefonul** (confirmat de utilizator); deconectat.

## N-008 · qemu-user-static din pacman dă 404  ✅
- Baza locală pacman era mai veche decât mirror-ele, deci `pacman -S qemu-user-static` a cerut o versiune ștearsă de pe mirror.
- Soluție fără update de sistem: înregistrăm binfmt arm64 cu `docker run --privileged --rm tonistiigi/binfmt --install arm64`.
  Se pierde la repornire, așa că `scripts/ensure-binfmt.sh` îl reînregistrează când e nevoie.
- Verificat: `docker run --rm --platform linux/arm64 debian:trixie-slim uname -m` → `aarch64`.

## N-009 · glibc patch-uit pentru seccomp-ul Android  ✅ (verificat static și sub qemu; 🧪 pe telefon în așteptare)
- Politica AOSP (`bionic/libc/SECCOMP_*.TXT`, `main`) nu conține `set_robust_list`, `rseq`, `faccessat2`, `shm*`/`sem*`/`msg*`.
  glibc 2.41 standard apelează `set_robust_list` și `rseq` la pornire, la fiecare `pthread_create` și în copilul lui `fork()`.
  Firele noi pornesc cu toate semnalele blocate, deci un handler SIGSYS nu poate salva situația (kernelul forțează SIG_DFL).
- Soluție: `patches/glibc/0001-android-seccomp-avoid-trapped-syscalls.patch` (generat de `scripts/lib/make-glibc-patch.py`):
  fără `set_robust_list` (mutex-urile robuste rămân funcționale în userspace, doar curățarea de kernel la moartea firului dispare),
  fără `rseq`, `faccessat` direct în loc de `faccessat2`.
- Căile hardcodate mutate în rootfs: `_PATH_BSHELL`, `/etc/*` pentru nss_files și resolver, `/tmp`, `/dev/shm` (SHMDIR).
  glibc e configurat cu `--prefix=$FXD_ROOT/usr`, deci și `ld.so.cache`, gconv, locale sunt în rootfs.
- Verificare statică (scanner `mov x8,#N` + `svc #0` pe `libc.so.6`/`ld.so`): Debian are `set_robust_list` ×2 și `rseq` ×1;
  versiunea noastră nu mai are `set_robust_list` și nici `faccessat2`; `rseq` rămâne doar pe o cale de cod inaccesibilă.
- Test pe PC (qemu, container gol la calea reală): `sh -c "uname -a; ls /"` rulează, iar toate bibliotecile se rezolvă din rootfs.

## N-010 · W^X blochează și mmap(PROT_EXEC), nu doar execve  📚 → decizie D2 redeschisă
- Pentru `targetSdk ≥ 29`, SELinux interzice `execute` pe `app_data_file`: nici `execve`, nici maparea executabilă a unui
  fișier din `filesDir`. Asta înseamnă că `ld.so` nu poate încărca `libc.so.6` din rootfs (și nici FEX/Mesa).
- Cu `targetSdk 28` (domeniul `untrusted_app_27`) ambele sunt permise; așa funcționează Termux și Winlator.
- Aplicația are două variante (`legacy` = 28, `modern` = 36). Ecranul de recunoaștere măsoară exact aceste operații
  pe telefon; decidem pe baza rezultatului. Glibc și rootfs-ul sunt construite pentru pachetul `legacy`.

## N-011 · Limitele testării pe PC  ✅
- qemu-user nu aplică seccomp-ul Android și nici SELinux; testele pe PC prind doar greșeli de căi, interpretor și biblioteci.
- FEX nu pornește sub qemu-user pe x86: „Couldn't allocate page after SBRK”. Emularea `brk` din qemu refuză maparea
  `MAP_FIXED_NOREPLACE` de după heap, iar spațiul de adrese e de 47 de biți. FEX se testează doar pe telefon.
- `strace` nu merge sub qemu (fără ptrace).

## N-012 · ld.so.cache din Debian are căi fără prefix  ✅
- Cache-ul și `ld.so.conf` din Debian numesc `/lib/aarch64-linux-gnu`. Pe PC testul părea să meargă doar pentru că
  containerul Debian avea propriile biblioteci în `/lib`. Pe Android acel director nu există.
- Soluție în `build-payload.sh`: `ld.so.conf` rescris cu căile rootfs-ului și cache regenerat cu `ldconfig`-ul nostru,
  rulat sub qemu la calea reală. Testele rulează acum într-o imagine Docker goală (`fexdroid-empty:arm64`).

## N-013 · Politica FEX despre cod scris de AI  📚
- `AGENTS.md` din FEX: „AI must not be used to generate code for contributions to this project.”
- Noi doar compilăm FEX. Orice patch local (`patches/fex/`) rămâne în proiectul nostru și nu se trimite upstream.

## N-014 · (corectat) Mesa 26.2.3 cunoaște Adreno 840  ✅
- Afirmația inițială („26.2.3 nu are A840”) era greșită: căutarea mea a tăiat rezultatele prea devreme.
  26.2.3 conține `GPUId(chip_id=0xffff44050A31, name="Adreno (TM) 840")`. Detalii și patch-uri în N-015.

## N-015 · Turnip pentru Adreno 840 (a8xx gen2) peste KGSL  📚 (build verificat pe PC, netestat pe telefon)
- **Constatare principală:** Mesa **26.2.3** (tarball-ul pe care îl folosim) **are deja** intrarea pentru A840:
  `GPUId(chip_id=0xffff44050A31, name="Adreno (TM) 840")` în `src/freedreno/common/freedreno_devices.py`
  (upstream commit `6e359817` „freedreno/common: Add A840 and X2-85”, Rob Clark / Qualcomm, MR !38450, 2025-12-08).
  Afirmația anterioară („26.2.3 are doar 810/829/830”, N-014) era greșită — N-014 e depășit de această intrare. Pe `main` (2026-09-25) intrarea A840 e
  identică; diferențele față de 26.2.3 în tabela a8xx sunt doar `830v1` (`0xffff44050001`) și un chip-id
  suplimentar pentru X2-85 — nimic pentru A840.
- **Cum se potrivește chip-id-ul pe KGSL** (`freedreno_dev_info.c: dev_id_compare`): KGSL raportează un
  `chip_id` pe 32 de biți (`struct kgsl_devinfo.chip_id`, `KGSL_PROP_DEVICE_INFO`), fără fuse-id. Intrarea
  `0xffff44050A31` are wildcard de fuse-id (biții 47..32 = 0xffff), deci se potrivește **exact** cu
  `0x44050A31` de la kernel (regula „c”), dar **nu** cu alt patch-id (byte-ul de jos e 0x31, nu 0xff).
  Dovadă că telefonul raportează chiar `0x44050A31`: un utilizator de Galaxy S26 Ultra cu driverul
  proprietar Qualcomm (prin vulkan-wrapper-android, care tot pe KGSL merge) a postat
  `Device Name: Wrapper(Adreno (TM) 840)`, `Device ID: 0x44050A31`
  (https://github.com/sabamdarif/termux-desktop/issues/268). Turnip raportează același `deviceID = chip_id`
  (`tu_device.cc`), deci este identificatorul KGSL. Notă: kernelul upstream DRM/msm folosește pentru A840
  `ADRENO_CHIP_IDS(0x44050a01)` (patch-id diferit) — irelevant pentru noi, aplicația vede doar `/dev/kgsl-3d0`.
- **Decizia:** rămânem pe **Mesa 26.2.3 (tarball, sha256 pinned)** + patch-uri mici în `patches/mesa/`,
  nu pe un commit din `main`. Motiv: `main` a divergat mult față de 26.2 în freedreno (redenumire quirks în
  `QCTDD*`, A710/A720, reg_size din tabel etc.) fără niciun câștig pentru A840, iar un release e mai ușor de
  reprodus și de raportat upstream. Patch-urile (toate MIT, aplicate în ordine de `scripts/build-mesa.sh`,
  sursa e re-extrasă din tarball la fiecare rulare):
  - `0001-freedreno-a840-kgsl-patchid-wildcard.patch` — **local (fexdroid)**: adaugă
    `GPUId(chip_id=0xffff44050Aff, name="Adreno (TM) 840 (unknown patchid)")` ca fallback cu wildcard de
    patch-id (regula „d”), pentru SKU-ul cu 2 slice-uri / alt patch-level. Aceleași proprietăți ca intrarea upstream;
    numele diferit apare în `VkPhysicalDeviceProperties.deviceName`, ca să vedem când a intrat pe fallback.
  - `0002` upstream `c96b2e4c` „tu/kgsl: Fix double-close on load failure” și `0003` upstream `35f59101`
    „tu/kgsl: Don't leak the device/dma_fd on UBWC init failure” (MR !44152, 2026-09-03) — fixuri pe calea de
    eroare din `tu_knl_kgsl_load` (exact calea pe care o vom depana pe telefon).
  - `0004` upstream `118ec7f4` „tu: Workaround 32B rollover bug in GPU firmware” (MR !43970, 2026-08-25):
    firmware-ul (toate GPU-urile cunoscute) reia greșit IB-urile care traversează o graniță de 4 GB după
    preempție; pe KGSL preempția e mereu activă, iar workaround-ul alocă IB-urile cu `KGSL_MEMFLAGS_FORCE_32BIT`.
    Se aplică curat pe 26.2.3 (`util_vma_heap.nospan_shift` există).
- **Patch-urile comunității, verificate la sursă** (github whitebelyash/mesa-unified branch `turnip/gen8`,
  The412Banner/Banners-Turnip branch `A8xx`, `patches/tu8_kgsl_26.patch`): **niciunul nu e necesar pentru A840**.
  - „UBWC_5 / UBWC_6 pe KGSL” (Rob Clark, WIP) — **deja în 26.2.3** (`tu_knl_kgsl.cc`, `case KGSL_UBWC_5_0/6_0`
    → `bank_swizzle_levels=0x6`, `FDL_MACROTILE_8_CHANNEL`).
  - „freedreno/gmem: do not apply gmem cache offset” (`offset -= 0x78000` pe chip ≥ 8) — **deja scos upstream** în 26.2.3.
  - „increase shared mem size” (`cs_shared_mem_size` 32→64 KB) — schimbă **toate** GPU-urile, nu e justificat
    pentru A840 (upstream ține 32 KB pentru toate a8xx); nu-l luăm.
  - „HACK: u_gralloc: always use ubwc detection path” — doar pentru platforma Android/gralloc; noi construim
    `-Dplatforms=x11` pe glibc, `u_gralloc` nu intră în build.
  - Restul (A810/A825/A829 configs, `disable_gmem`, `nocb` forțat, DECK_EMU) vizează alte GPU-uri sau sunt hack-uri.
    Build-urile Banner „A8xx” sunt descrise ca „A810/A825/A829/A830”, nu A840.
- **Riscuri KGSL specifice a8xx, din cod** (de verificat pe telefon, `TU_DEBUG=startup`):
  1. Dacă KGSL raportează alt `chip_id` decât `0x44050A31`/`0x44050Axx` → `VK_ERROR_INCOMPATIBLE_DRIVER`
     „device (chip_id = …) is unsupported” (`tu_device.cc`); mesajul afișează id-ul raportat, îl adăugăm atunci în patch-ul 0001.
  2. `num_slices=3`, `num_ccu=6`, `tile_align_w=96` sunt fixe în tabel; SKU-ul A840 cu 2 slice-uri (există,
     conform seriei DRM/msm „Add support for Adreno 840”) ar avea `num_ccu` greșit → artefacte/GPU hang în gmem.
     KGSL nu expune numărul de slice-uri prin uAPI.
  3. UBWC: `KGSL_PROP_UBWC_MODE` trebuie să întoarcă ≤ 6 (A840 = UBWC v6), altfel „unknown UBWC version”;
     `KGSL_PROP_HIGHEST_BANK_BIT` e obligatoriu (`goto fail` dacă lipsește). Samsung poate avea un KGSL cu
     valori/proprietăți diferite de AOSP-Qualcomm.
  4. GMEM: `gmem_size` vine de la kernel (`info.gmem_sizebytes`, 18 MB la A840, forțabil cu `TU_GMEM`);
     `fd6_calc_gmem_cache_offsets` (chip == 8) scade din el cache-urile CCU/VPC per CCU pentru sysmem și gmem.
  5. Concurrent binning e activ implicit pe a7xx/a8xx (`allow_concurrent_binning`); comunitatea îl dezactivează
     pentru jocuri PC. Dacă apar hang-uri: `TU_DEBUG=nocb`.
  6. Sparse/`has_set_iova` depind de `KGSL_MEMFLAGS_VBO` / `USE_CPU_MAP` (testate la runtime); `/dev/dma_heap/system`
     sau `/dev/ion` lipsă → doar un warning (fără `VK_KHR_external_memory_fd`).
- **Build (2026-09-25, `scripts/build-mesa.sh`, ~35 s cu ccache):** `--wrap-mode=nofallback` (nu se mai
  descarcă/compilează `libarchive`/`libxml2`, care erau doar pentru uneltele de decode), `cpu = 'aarch64'` în
  cross-file (înainte `armv8.2-a` producea `freedreno_icd.armv8.2-a.json` cu `library_path` relativ, pentru că
  `vk_icd_gen.py` scrie calea absolută doar când numele are exact 3 segmente). Rezultat:
  `freedreno_icd.aarch64.json` → `"library_path": "/data/data/ro.cobrabm.fexdroid/files/rootfs/usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so"`;
  tabela generată `build/mesa/obj/src/freedreno/common/freedreno_devices.h` conține
  `{ {0, 0xffff44050a31}, "Adreno (TM) 840", &__info30 }` și fallback-ul `0xffff44050aff`; `strings` pe `.so`
  găsește „Adreno (TM) 840”. **Nimic din acestea nu dovedește funcționarea pe telefon.**
- Surse: https://gitlab.freedesktop.org/mesa/mesa/-/commit/6e359817 ·
  https://gitlab.freedesktop.org/mesa/mesa/-/merge_requests/44152 · https://gitlab.freedesktop.org/mesa/mesa/-/merge_requests/43970 ·
  https://github.com/whitebelyash/mesa-unified/commits/turnip/gen8 · https://github.com/The412Banner/Banners-Turnip/tree/A8xx ·
  public DRM/msm mailing-list discussion (DRM/msm A840: `0x44050a01`, 18 MB GMEM, 2 sau 3 slice-uri) ·
  https://github.com/sabamdarif/termux-desktop/issues/268 (S26 Ultra: deviceID `0x44050A31`)

## N-016 · Thunks FEX (Vulkan/X11) în layout-ul nostru  ✅ (build + dlopen sub qemu; netestat pe telefon)
- **Ce sunt.** Un thunk înlocuiește o bibliotecă x86_64 din guest (ex. `libvulkan.so.1`) cu un
  stub x86_64 (`libvulkan-guest.so`) care sare, prin opcode-ul FEX `OP_THUNK`, într-o bibliotecă
  ARM64 (`libvulkan-host.so`) ce încarcă cu `dlopen("libvulkan.so.1")` biblioteca **nativă** din
  rootfs-ul ARM64. Astfel Vulkan-ul jocului ajunge direct în loader-ul ARM64 → Turnip, fără JIT.
- **X11 nu e thunk-uit în FEX-2609** (`ThunkLibs/libX11` conține doar un placeholder pentru GL).
  Guest-ul rulează `libX11`/`libxcb` x86_64 native (emulate). Thunk-ul Vulkan gestionează WSI-ul:
  `X11Manager` (ThunkLibs/include/common/X11Manager.h) deschide pe partea host **o a doua**
  conexiune X (`XOpenDisplay`/`xcb_connect` cu același `DISPLAY`) și trimite driver-ului
  ID-urile de fereastră ale guest-ului. Deci host-ul are nevoie doar de `libX11.so.6` + `libxcb.so.1`
  ARM64 (deja în `RUNTIME_PKGS`); `libxshmfence`/`libdrm` nu sunt necesare pentru asta
  (`libxshmfence` nici nu mai e în CMakeLists-ul FEX; `libdrm` e construit dar dezactivat).
- **Trei toolchain-uri** (`scripts/build-fex.sh`, patch local `patches/fex/0001-*.patch`,
  regenerabil cu `scripts/lib/make-fex-patch.py`; **nu se trimite upstream**, vezi N-013):
  1. `thunkgen` — rulează pe PC, linkează `libclang-cpp` 22 de pe host; build nativ separat din
     `scripts/cmake/thunkgen/CMakeLists.txt`, dat lui FEX prin `THUNKGEN_EXE` (patch-ul îl
     importă ca target, ca să nu-l compileze cross). **Capcană:** thunkgen-ul parsează interfețele
     host cu ABI-ul mașinii pe care rulează; pe x86 `char` e signed, pe aarch64 unsigned → erori
     `guest_layout<signed char*>`. Soluție: `THUNKGEN_EXTRA_FLAGS="--target=aarch64-linux-gnu
     --sysroot=$SYSROOT"` (pasul guest-layout își adaugă singur `--target=x86_64-linux-gnu` după).
  2. host thunks — ARM64, același cross clang + sysroot arm64; `pkg-config` întors spre sysroot
     (`PKG_CONFIG_LIBDIR`/`PKG_CONFIG_SYSROOT_DIR`); `libasound2-dev` arm64 e descărcat cu
     `apt-get download` într-un container și suprapus peste sysroot (nu e în `build-rootfs.sh`).
  3. guest thunks — x86_64, clang + `scripts/cmake/toolchain-x86_64-guest.cmake` peste sysroot-ul
     dev amd64 din **`scripts/build-x86-sysroot.sh`** (`build/rootfs/sysroot-x86_64`, doar la build).
  Opțiuni adăugate de patch: `THUNKS_ENABLE_GL=OFF` (nu avem libGL pe telefon; Dota e Vulkan),
  `THUNKS_ENABLE_32BIT_GUEST=OFF` (fără sysroot i386 până la Faza 5). `BUILD_STEAM_SUPPORT` rămâne
  OFF: adaugă doar căi pressure-vessel/`STEAM_COMPAT_*` la overlay-uri, irelevante fără container.
- **Layout pe telefon** (toate căile absolute sunt băgate în FEX la build, prin `CMAKE_INSTALL_PREFIX`):
  - `$FXD_ROOT/usr/lib/fex-emu/HostThunks/lib{vulkan,drm,asound,wayland-client,cuda}-host.so` (ARM64,
    fără RUNPATH; NEEDED doar libc/libstdc++; bibliotecile reale se rezolvă din `ld.so.cache`-ul rootfs-ului)
  - `$FXD_ROOT/usr/share/fex-emu/GuestThunks/lib{vulkan,drm,asound,wayland-client,cuda,VDSO}-guest.so`
    (x86_64; `libVDSO-guest.so` e mapat mereu, restul doar dacă sunt activate)
  - `$FXD_ROOT/usr/share/fex-emu/ThunksDB.json` (upstream) — descrie ce căi din guest sunt
    suprapuse: `@PREFIX_LIB@/libvulkan.so.1` devine `/usr/lib/x86_64-linux-gnu/libvulkan.so.1`
    (FEX detectează multiarch în RootFS). Suprapunerea se face în `FileManager` la `open`, deci
    fișierul original din rootfs-ul x86 nu e atins și nici nu trebuie să existe.
  - `$FXD_ROOT/usr/share/fex-emu/Config.json` (generat de `build-fex.sh`; = „global config”,
    `GLOBAL_DATA_DIRECTORY`): `{"Config":{"RootFS":…,"ThunkHostLibs":…,"ThunkGuestLibs":…},
    "ThunksDB":{"Vulkan":1,"drm":0,"asound":0,"WaylandClient":0}}`. Per utilizator se suprascrie în
    `$FEX_APP_CONFIG_LOCATION/Config.json` (= `~/.fex-emu/Config.json`, setat de `LinuxEnv.kt`),
    sau per aplicație în `AppConfig/<nume>.json`; `FEX_THUNKCONFIG=<fișier.json>` e a treia cale.
    Numele din `ThunksDB` sunt cheile din `ThunksDB.json` (`Vulkan`, `drm`, `asound`, `WaylandClient`).
- **Verificat pe PC** (`scripts/test-payload-qemu.sh`, rootfs montat la căile de pe telefon):
  `opt/fexdroid-tests/dlopen-thunks` (tests/arm64/dlopen-thunks.c, compilat de `build-payload.sh`)
  face `dlopen` pe fiecare `lib*-host.so` și apelează `fexthunks_exports_lib<nume>()` — exact ce
  face FEX în `ThunkHandler_impl::LoadLib`; asta rulează `fexldr_init_*`, care deschide biblioteca
  reală după SONAME și rezolvă toate simbolurile. Rezultat: `ok` pentru vulkan, drm, asound,
  wayland-client (deci `libvulkan.so.1`, `libX11.so.6`, `libxcb.so.1`, `libasound.so.2` ARM64 se
  găsesc prin `ld.so.cache`). FEX în sine nu poate rula sub qemu-user (N-011: emularea `brk` din qemu), deci
  tranziția guest→host și overlay-ul rămân **neverificate până la telefon**.
- **Riscuri deschise.**
  1. **ICD Turnip pe partea host.** Loader-ul `libvulkan.so.1` ARM64 caută ICD-uri în
     `/usr/share/vulkan/icd.d`, `/etc/vulkan/icd.d`, `$XDG_*` — căi absolute care nu există pe
     Android. Trebuie `VK_ICD_FILENAMES=$FXD_ROOT/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json`
     (sau `VK_DRIVER_FILES`) în environment-ul procesului FEX (`LinuxEnv.environment()`), iar JSON-ul
     ICD trebuie să indice calea absolută a `libvulkan_freedreno.so` din rootfs. Thunk-urile rulează
     în același proces, deci văd environment-ul host-ului (nu e nevoie de `HostEnv`).
  2. **Rootfs-ul x86_64 e încă minimal** (`X86_PKGS=(libc6 libstdc++6 coreutils)`): guest-ul are
     nevoie de `libx11-6`, `libxcb1`, `libvulkan1` (pentru `ld.so.cache`/dependențe) x86_64 — de
     adăugat în `build-rootfs.sh` (nu am modificat acel script). `libvulkan-guest.so` face
     `dlopen("libX11.so.6")` în constructor și, dacă lipsește, callback-urile X (XSync,
     XGetVisualInfo) rămân NULL → crash la prima suprafață X11.
  3. Thunk-ul Vulkan face `to_guest`/`to_host` layout pentru toate structurile din header-ele
     FEX (`External/Vulkan-Headers`); extensii mai noi decât acestea trec „opac”. De verificat
     versiunea `libvulkan1` din rootfs (1.4.309) față de ce cere Dota/Source 2.
  4. `asound` e construit dar dezactivat: pe Android nu există dispozitive ALSA; audio-ul rămâne
     pe planul PulseAudio (Faza 4) sau un plugin ALSA→AAudio propriu. `drm` idem (Turnip pe KGSL
     nu folosește libdrm).
  5. Cache-ul JIT/`FEXServer`: thunk-urile nu-l afectează, dar `libVDSO-guest.so` e mapat la
     capătul VA (47 biți) — dacă telefonul are VA de 39 biți FEX ajustează singur (`GetHostVABits`).

## N-017 · SysV shared memory în userspace (memfd + `fxshmd`), msg → ENOSYS (sem: vezi N-025)  ✅ (sub qemu; 🧪 pe telefon în așteptare)
- **Simptom:** seccomp-ul pentru `untrusted_app` trimite SIGSYS (proces omorât) la
  `shmget/shmat/shmdt/shmctl`, `semget/semop/semtimedop/semctl`, `msgget/msgsnd/msgrcv/msgctl`
  (aarch64 186–197). X11 MIT-SHM (Xvfb ↔ clienți), PulseAudio și Steam folosesc SysV shm.
- **Soluție:** `patches/glibc/0002-android-sysvipc-userspace.patch` (generat de
  `scripts/lib/make-glibc-sysvipc-patch.py`, sursele în `patches/glibc/fxshm/`) + daemonul
  `tools/fxshmd/fxshmd.c` (MIT, scris de la zero; Winlator/Termux doar ca idei). Protocolul e într-un
  singur header, `tools/fxshmd/fxshm-proto.h`, copiat în patch de generator.
  - Fiecare segment = un **memfd** deținut de `fxshmd` (nu fișiere pe flash, nu `$ROOT/dev/shm`).
  - glibc vorbește cu daemonul pe un socket `SOCK_SEQPACKET` la `$FXD_ROOT/tmp/.fxshm/sock`
    (override: `FXD_SHM_SOCKET`); `shmat` primește un dup al memfd-ului prin `SCM_RIGHTS` și îl
    mapează singur (`MAP_SHARED`, `SHM_RDONLY` → `PROT_READ`, `shmaddr` cu `MAP_FIXED_NOREPLACE`,
    `SHM_RND`); glibc ține local tabela addr → id pentru `shmdt`.
  - **De ce daemon și nu un registru pe disc:** un fd nu poate fi obținut din alt proces fără
    un proces viu care să-l dețină (`/proc/<pid>/fd` depinde de creator, care poate ieși — X
    client-ul moare, serverul păstrează segmentul). Daemonul dă semantică Linux: segmentul
    supraviețuiește creatorului, `key → id` între procese neînrudite, `shm_nattch` real.
  - **nattch și IPC_RMID:** fiecare proces ține o singură conexiune (CLOEXEC) cât trăiește;
    daemonul numără atașările per conexiune, deci exit/exec/crash eliberează automat
    (hangup). `IPC_RMID` eliberează cheia imediat (un `shmget` nou cu aceeași cheie creează alt
    segment), segmentul e distrus la ultimul detach; `shmat` după RMID merge (ca pe Linux).
  - **fork:** handler `__register_atfork` în libc: copilul închide conexiunea moștenită, deschide
    una nouă și trimite `INHERIT` pentru atașările copiate, înainte ca părintele să poată detașa.
    `_Fork`/clone brut: detectat prin `getpid()` la următorul apel shm (reconectare lazy).
  - **Pornire la cerere:** la primul `connect` eșuat (ENOENT/ECONNREFUSED) glibc face
    `clone(CLONE_VM|CLONE_VFORK|SIGCHLD)` + `execve($FXD_LDSO $FXD_ROOT/usr/libexec/fxshmd)`
    (override: `FXD_SHMD`), exact ca `posix_spawn`. Daemonul ia `flock` pe `.fxshm/lock`, înlocuiește
    un socket vechi, face `bind+listen`, apoi se detașează (`fork`, intermediarul iese 0 doar după
    `listen` → spawner-ul se poate conecta imediat; retry 200 × 10 ms). Dacă un alt daemon are
    lock-ul, iese 0. Aplicația e liberă să-l pornească și ea la începutul sesiunii (recomandat pe
    telefon, vezi riscuri).
  - `IPC_STAT/IPC_SET/IPC_RMID/SHM_LOCK/SHM_UNLOCK/IPC_INFO` implementate; `SHM_STAT/SHM_INFO` → EINVAL.
    Permisiuni: un singur uid, se verifică doar biții owner (0400/0200).
  - **sem/msg:** `semget/semtimedop(semop)/semctl/msgget/msgsnd/msgrcv/msgctl` întorc -1/ENOSYS
    fără syscall. PulseAudio nu folosește semafoare SysV (memblocks peste `memfd`/`shm_open` +
    `/dev/shm`… la noi `$ROOT/dev/shm`), Steam/CEF folosesc shm și futex-uri; dacă vreun program
    cere `semget`, îl vedem ca ENOSYS în log, nu ca SIGSYS — atunci se poate adăuga un `SEM` op în
    același daemon (`futex` pe o pagină din memfd), dar nu e necesar acum.
- **Compromisuri:** un proces în plus (contează la phantom process killer; `--idle-exit N`
  există dar e oprit implicit pentru a evita un race la închidere); dacă daemonul moare, segmentele
  „dispar” ca la un reboot (clienții primesc EIDRM și se reconectează la unul nou); un handler
  SIGCHLD al aplicației vede un copil (intermediarul) — tratat (ECHILD = ok). Copiii mor cu daemonul
  doar dacă îi omoară Android; el nu ține fd-urile părintelui (le închide la pornire).
- **Verificat pe PC** (`scripts/build-glibc.sh` → `scripts/build-payload.sh` →
  `scripts/test-payload-qemu.sh "$FXD_ROOT/opt/fexdroid-tests/shmtest all"`, container gol, qemu-user):
  `tests/sysvshm/shmtest.c` — proces A creează + scrie, B (exec separat) atașează după id și citește,
  fork moștenește (nattch 2 → 1 după exit), RMID cu semantica Linux, exec/exit eliberează atașările,
  `shmaddr`/`SHM_RND`, `semget/msgget/semop/semctl/msgctl` → ENOSYS: **toate cele 39 de verificări trec**,
  inclusiv pornirea la cerere a daemonului. `scripts/scan-syscalls.sh libc.so.6` (llvm-objdump,
  `mov x8,#N; svc #0`): niciun 186–197 în libc-ul nou (vechiul avea `shmdt` 197 la un site).
  Rămâne un site cu `rseq` (293) în `start_thread` (unregister, mort la runtime pentru că
  `do_rseq=false` din 0001) — de urmărit în N-009.
- **Neverificat pe telefon:** SELinux pentru socket unix în `files/` (Termux face la fel, ar trebui
  să meargă), `execve` al loader-ului din `files/` la pornirea la cerere (cu D2/N-010, dacă exec-ul
  din date e blocat, setăm `FXD_SHMD` spre un launcher din `nativeLibraryDir` sau aplicația
  pornește daemonul), `MAP_FIXED_NOREPLACE` pe kernelul Samsung (fallback: verificăm adresa).
  Pentru **oaspeții x86 sub FEX**: FEX trece `shmget/shmctl` ca syscall brut (`Passthrough.cpp`) —
  trebuie redirecționate spre wrapperele glibc (`::shmget`) în `build-fex.sh`/`patches/fex` (agentul FEX).

## N-018 · Strat generic împotriva SIGSYS: glibc `syscall()`, macro-urile glibc și FEX  ✅ (PC/qemu; 🧪 telefon în așteptare)
- Problema: pe lângă glibc-ul nostru, FEX trimite direct la kernel multe apeluri ale programelor x86
  (`set_robust_list` la fiecare fir nou din glibc-ul x86, `faccessat2`, `shm*`, `sem*`, `futex_waitv`, `accept`, ...),
  iar CEF/Chromium folosește intens `syscall()` direct. Oricare dintre ele, blocat de Android, omoară procesul.
- Tabel generat: `scripts/lib/gen-android-seccomp.py` citește politica AOSP (`third_party/aosp-bionic-seccomp/`,
  `main` din 2026-09-25) și produce lista numerelor arm64 blocate (101). Un număr e blocat doar dacă niciun nume
  al lui nu e permis (ex. 84 = `sync_file_range`/`sync_file_range2`, 79 = `newfstatat`).
- glibc patch 0003 (`scripts/lib/make-glibc-syscall-patch.py`):
  - `syscall()` e acum în C: numerele blocate întorc `ENOSYS`; `shm*` merg la fxshmd, `accept` → `accept4`,
    `set_robust_list` → succes fără kernel.
  - `INTERNAL_SYSCALL_RAW` (C) și `DO_CALL` (asamblare, macro `FXD_SVC`) înlocuiesc la compilare orice `svc`
    cu număr blocat prin `-ENOSYS`. Scanner: 0 apeluri blocate rămase în `libc.so.6`, `ld.so`, `libm.so.6`.
- FEX patch 0002 (`scripts/lib/make-fex-android-patch.py`, local, nu upstream):
  - fiecare `SyscallPassthroughN` verifică tabelul la compilare și întoarce `-ENOSYS` în loc de `svc`;
  - `shmget`/`shmctl` prin glibc (fxshmd), `accept` → `accept4`, `set/get_robust_list` x86-64 ținute în FEX
    (cum face deja FEX pentru 32 de biți);
  - `openat2` e emulat în userspace (`AndroidOpenat2.h`): calea se parcurge componentă cu componentă sub rootfs,
    iar `..` și symlink-urile absolute nu pot ieși din el (semantica `RESOLVE_IN_ROOT`).
  - Scanner: 0 `svc` blocate în `FEX`, `FEXServer`, `libFEXCore.so`.
- Rootfs x86: 33 de symlink-uri absolute rescrise ca relative (`scripts/lib/relativize-symlinks.py`), altfel
  unele căutări FEX fără `RESOLVE_IN_ROOT` ar ajunge în `/system` sau `/etc` ale Android-ului.
- `scripts/build-fex.sh` oprește acum build-ul dacă un patch nu se aplică (înainte îl sărea în tăcere).
- Test pe PC: `opt/fexdroid-tests/seccomp-wrap` → 10/10 ok; `shmtest all` → ok; thunk Vulkan se încarcă;
  încărcătorul Vulkan găsește și încarcă Turnip de la calea din rootfs.
- Limită: pe PC nimic nu e blocat, deci testul verifică doar logica de rutare. Pe telefon, același test arată
  dacă procesul supraviețuiește.

## N-019 · Compatibilitate Snapdragon 888 (Adreno 660)  📚
- Turnip are `FD660` cu chip id `0x060600ff` (acceptă orice revizie), deci merge și pe KGSL.
- CPU ARMv8.2 (Cortex-X1/A78/A55) = exact nivelul pentru care compilăm FEX (`TUNE_ARCH=armv8.2-a`).
- Telefoanele cu SD888 actualizate la Android 14/15 păstrează kernel cu pagini de 4 KB. `minSdk 31` e suficient.
- Ecranul de recunoaștere confirmă pe dispozitiv (pagină, VA, seccomp, KGSL).

## N-020 · Afișare: Xvfb + fxpath + punte fxshmd → SurfaceView  🟨 (PC/qemu ✅, telefon 🧪)
- Xvfb (Debian) are căi fixe: `/tmp/.X0-lock`, `/tmp/.X11-unix`, `/usr/bin/xkbcomp`, `/usr/share/X11/xkb`, `/var/lib/xkb`.
  `-nolock` e permis doar pentru root.
- `tools/fxpath/fxpath.c`: shim `LD_PRELOAD` care mută `/tmp`, `/usr`, `/etc`, `/var`, `/bin`, `/lib`, `/opt`, `/run`, ...
  în rootfs (open/stat/mkdir/unlink/rename/exec/bind/connect). `/dev`, `/proc`, `/sys`, `/data` rămân neatinse.
  `FXPATH_DEBUG=1` afișează fiecare redirecționare.
- Copilul lui Xvfb face `setgid(getgid()); setuid(getuid())` înainte de `xkbcomp`. Aceste apeluri sunt blocate de
  Android, iar `ENOSYS` îl făcea să iasă. Familia `setuid` răspunde acum cu succes (no-op: aplicația nu e root și
  apelul nu ajunge la kernel), în glibc și în FEX.
- Xvfb îl nu poate crea `/tmp/.X11-unix` (cere root), dar ascultă pe socketul abstract `@/tmp/.X11-unix/X0`,
  pe care libxcb îl încearcă primul. Pentru clienții x86 prin FEX, socketul abstract trece direct.
- Framebuffer-ul `-shmem` e un segment SysV în memfd (fxshmd). Puntea din aplicație (`cpp/display_bridge.c`)
  vorbește protocolul fxshmd, mapează segmentul și copiază cadrele (BGRX → RGBX) în `ANativeWindow` (de la N-031: doar cadrele noi, căutate de 60 de ori pe secundă).
- Vulkan pe X11: Turnip pe KGSL nu are DRI3 spre Xvfb, deci clienții rulează cu `MESA_VK_WSI_DEBUG=sw`
  (prezentare prin CPU, MIT-SHM). E mai lent, dar e cea mai simplă cale; optimizarea e layer-ul direct din D3.
- Test pe PC: Xvfb pornește cu shim-ul, `xwd-peek` atașează segmentul prin fxshmd și citește 1280x720, 32 bpp,
  iar dimensiunea calculată de punte se potrivește exact cu segmentul.

## N-021 · Primele rulări pe telefon real: Realme GT (SD888, Adreno 660, Android 14, kernel 5.4)  🧪✅
Rezultate (recon în `docs/recon/`): pagini 4 KB, VA 39 biți, `untrusted_app_27` (targetSdk 28): exec și mmap(PROT_EXEC)
din `filesDir` permise, deci D2 = legacy funcționează. KGSL `chip_id=0x06060001` → Turnip `FD660`.

Probleme găsite pe dispozitiv și rezolvate:
1. **`clone3`, `close_range`, `epoll_pwait2` blocate pe Android 14** (AOSP `main` le permite). Tabelul seccomp e acum
   intersecția politicilor Android 12–16 + `main` (`third_party/aosp-bionic-seccomp/<release>/`). `clone3.S` din glibc
   folosește `FXD_SVC`, iar glibc cade automat pe `clone`.
2. **`accept` prin calea de anulare** (`__internal_syscall_cancel`) ocolea verificarea de la compilare → FEXServer murea
   cu SIGSYS și FEX nu primea RootFS-ul. Verificare adăugată la rulare în `nptl/cancellation.c` (accept → accept4).
3. **Aplicația crăpa la instalare dublă** (două fire ștergeau rootfs-ul simultan). Instalarea e acum sub un lock global.
4. **Hard link-uri interzise de SELinux** → lock-ul lui Xvfb eșua. `fxpath` emulează `link()` cu `O_EXCL` + copiere.
5. **`vkcube` x86 prin FEX sărea la o adresă aleatoare** în wrapper-ul thunk pentru `vkCmdPipelineBarrier` (primul apel
   cu >6 argumente). Cauza: invoker-ul guest primește adresa funcției native în `r11`, iar protecția de stivă a
   clang-ului din Arch (activă implicit) încărca canary-ul în `r11`. Soluție: `-fno-stack-protector` în
   `scripts/cmake/toolchain-x86_64-guest.cmake` (upstream îl pune doar pentru 32 de biți). Găsit cu `gdb` pe telefon
   (`scripts/build-debug-tools.sh`).

Verificat pe telefon: Faza 1 (`sh`, `uname`), Faza 2 (hello x86_64 dinamic prin FEX), Faza 3 (`vulkaninfo` arm64 și x86,
`vkcube` arm64 și **x86_64 prin FEX + thunk Vulkan**, afișate prin Xvfb → SurfaceView).

Deschis:
- **hello x86_64 static** crapă (SIGSEGV la `0x4ce3c0`, imediat după BSS, înainte de primul apel de sistem al programului).
  Probabil legat de kernelul 5.4 (FEX avertizează „requires kernel 5.15”). Programele țintă sunt dinamice.
- FEX afișează „Failed to remap /proc/pid/cmdline” (`PR_SET_MM` → EPERM): inofensiv.
- Unelte adb: `scripts/adb-run.sh '<cmd>'` (rulează în sandbox-ul aplicației), `scripts/adb-push-rootfs.sh` (actualizări
  rapide fără APK nou).

## N-022 · Faza 4: input (XTEST) și audio (PulseAudio → AAudio)  ✅ pe Realme GT
- **Input:** `cpp/x11_input.c` e un client X11 minimal (fără libX11): handshake pe socketul abstract
  `@/tmp/.X11-unix/X0`, `QueryExtension("XTEST")`, apoi `XTestFakeInput`. `InputSurfaceView` trimite:
  tastatură fizică → scancode evdev + 8 (keymap-ul evdev din Xvfb); tastatură virtuală → tabel Android → evdev;
  mouse (BT/USB/DeX) → poziție absolută, butoane 1/2/3/8/9, scroll 4–7; captură de mouse → mișcare relativă
  (Ctrl+Alt eliberează); touch → un deget = click stânga cu drag, două degete = click dreapta.
  Verificat cu `xev` pe telefon: tap → ButtonPress 1, `a` → keycode 38, Enter → keycode 36.
- **Audio:** PulseAudio din rootfs cu `module-pipe-sink` (s16le, 48 kHz, stereo) într-un FIFO creat de aplicație
  (`Os.mkfifo` merge în `filesDir`), citit de `cpp/audio_bridge.c` și scris în AAudio (low latency, usage GAME).
  Modulele PulseAudio se încarcă prin `dlopen` de la o cale fixă, de aceea `--dl-search-path`. Clienții folosesc
  `PULSE_SERVER=unix:$ROOT/tmp/pulse/native`, inclusiv cei x86 prin FEX. Verificat: ton de 3 s din `pacat` ARM64
  și din `pacat` x86_64 prin FEX, ambele „3 s redate” prin AAudio.
- Avertismente PulseAudio inofensive: fără cookie, fără D-Bus, nu se poate re-executa.
- `FLAG_KEEP_SCREEN_ON` în activitate: ecranul nu se stinge cât aplicația e vizibilă (Realme blochează
  `stay_on_while_plugged_in` din adb).
- Netestat încă: tastatură/mouse Bluetooth reale (au nevoie de utilizator), DeX (doar pe Samsung).

## N-023 · Faza 5: Steam fără containere (fără user namespaces)  🟨
- Kernelul Android nu are user namespaces (`unshare(CLONE_NEWUSER)` → EINVAL), deci bubblewrap/pressure-vessel nu merg.
  Steam le folosește pentru steamwebhelper (runtime „steamrt3c”) și pentru jocuri (Steam Linux Runtime „sniper”).
- Testat nativ pe PC într-un container Docker (fără userns, ca pe Android), cu rootfs-ul Steam al telefonului și o copie
  a clientului Steam fără configurare/conturi:
  1. `steam-runtime-check-requirements` refuză („Steam now requires user namespaces”), dar sare peste verificare dacă
     rulează „sub pressure-vessel”: marcajul `/run/pressure-vessel` în rootfs-ul x86 (vizibil doar programelor x86).
  2. `steamwebhelper.sh` pornește `${STEAM_RUNTIME_STEAMRT}/_v2-entry-point -- <cmd>`: cu variabila spre
     `usr/lib/fexdroid/steam`, shim-ul nostru execută direct comanda (fără container).
  3. Lipseau `libibus-1.0-5`, `libxtst6`, `libvdpau1` (găsite cu `ldd` în afara containerului).
  4. Renderer-ul Chromium crăpa cu `/dev/shm` mic → pe telefon `/dev/shm` e un director din rootfs.
  Rezultat: **ecranul de login Steam cu cod QR** apare fără userns.
- Jocuri: Steam le pornește prin `SteamLinuxRuntime_sniper/_v2-entry-point`. Scriptul nostru îl înlocuiește (originalul
  rămâne ca `.valve`). `dota.sh` cere `/etc/os-release` = sniper, deci shim-ul setează `FEX_ROOTFS` spre arborele
  sniper deja asamblat (`var/tmp-*`); FEX recitește variabila la fiecare `execve`.
- Rootfs x86 „Steam” (`scripts/build-rootfs-steam.sh`, amd64 + i386, `steam-libs`): ~170 MB zst, instalat separat
  (prea mare pentru APK). Arhivele se fac cu `--hard-dereference`: SELinux interzice hard link-urile, iar `tar` din
  toybox se oprește la primul.
- Copiere de pe PC (instalarea proprie a utilizatorului, nu inclusă în APK): `scripts/adb-copy-steam-game.sh 570` (Dota 2,
  ~70 GB, loturi de 4 GB reluabile). Două fluxuri `adb exec-in` simultane au blocat serverul adb → doar pe rând.
- Login: doar de utilizator, cu QR din aplicația Steam (nu copiem token-uri, nu creăm conturi).

## N-024 · Dota 2 pornește pe telefon (Realme GT, SD888)  ✅
Dota 2 ajunge la meniul principal, randat de Turnip pe Adreno 660 prin FEX + thunk Vulkan, afișat prin Xvfb →
SurfaceView. Pornit direct, fără clientul Steam („Lost connection to Steam”). Pași rezolvați:
- **Rootfs de joc:** arborele `var/tmp-*` al lui Steam nu e portabil (337 de symlink-uri spre `/run/host` = glibc/drivere
  ale PC-ului). Folosim imaginea publică Valve `steamrt/sniper/platform` (`scripts/build-game-rootfs.sh`) ca
  `$FXD_FILES/sniper-rootfs`; shim-ul `_v2-entry-point` și `fexdroid-dota.sh` setează `FEX_ROOTFS` spre el.
- **glibc mai nou în sniper:** thunk-urile guest FEX sunt compilate pe Debian 13 (cer GLIBC_2.38, GLIBCXX_3.4.32), iar
  sniper are glibc 2.31 → „Unable to initialize Vulkan”. Ca pressure-vessel (bibliotecile gazdei mai noi câștigă),
  `scripts/lib/sniper-overrides.py` pune glibc 2.41/libstdc++/libgcc din rootfs-ul Steam în sniper.
- **zenity:** înlocuit cu un stub care scrie dialogurile în jurnal (`tools/steam/zenity-log`); așa am văzut eroarea Vulkan.
- **Fără Steam:** cu `~/.steam` prezent, `steamclient.so` poate bloca jocul așteptând IPC-ul Steam. Pornirea directă
  folosește un HOME separat (`files/home/direct`).
- **FEX:** `mkdir`/`creat`/`unlink` și `open(O_CREAT)` sub părinți existenți doar în RootFS (`/tmp` pe Android) se
  creează în RootFS (patch 0002); PATH-ul pentru guest conține doar prefixe standard.
Încărcare: ~4–5 minute la prima pornire (JIT FEX), ~130% CPU, 2–4 GB RAM.
Următorii pași: login Steam (QR, date mobile/WiFi) ca jocul să fie online; performanță (cache de cod FEX, afinitate).

## N-025 · SysV semaphores în userspace (memfd + futex, prin `fxshmd`)  ✅ (PC/qemu; 🧪 telefon în așteptare)
- **Simptom (telefon):** clientul Steam (i386 sub FEX, plus părți x86-64) dă „semaphore creation failed” /
  „Thread synchronization object is unuseable” și rămâne blocat: `semget/semop/semtimedop/semctl` întorceau ENOSYS
  (N-017, N-018). Pe PC, strace-ul nativ arată: `semget(KEY, 1, IPC_CREAT|IPC_EXCL|0600)` (uneori EEXIST, apoi fără
  EXCL), mii de `semtimedop(id, [{0,-1,IPC_NOWAIT}], 1, NULL)` → EAGAIN (polling), `{0,±1,SEM_UNDO}` ca mutex,
  `semctl(id, 0, IPC_64|SETVAL, v)`; procese diferite folosesc același set prin cheie.
- **Design** (glibc 0002 + `fxshmd`; layout comun în `tools/fxshmd/fxsem-layout.h`, copiat în patch de generator):
  - Fiecare set = un **memfd** creat de `fxshmd` (op-uri noi `SEM_GET`/`SEM_OPEN`/`SEM_RMID`/`SEM_UNDO` pe aceeași
    conexiune per proces; cheile și id-urile semafoarelor au spațiu separat de shm, ca în kernel). Clientul
    (`patches/glibc/fxshm/fxsem-client.c`) primește fd-ul prin `SCM_RIGHTS` și îl mapează `MAP_SHARED`; mapările sunt
    ținute într-un cache id → mapare (cu refcount, eliberate după RMID).
  - Starea stă doar în mapare: header (nsems, key, perms, otime/ctime, `lock`, `seq`, `waiters`, `removed`) +
    `{val, pid, ncnt, zcnt}` per semafor + tabelul SEM_UNDO (64 de sloturi pid × nsems).
  - `semop/semtimedop` rulează **fără daemon**: toate operațiile se aplică atomic sub un lock process-shared
    (CAS pe cuvântul `lock`, FUTEX_WAIT/WAKE non-private), cu rollback complet dacă una nu poate continua. Așteptarea
    = `FUTEX_WAIT_BITSET` pe `seq` cu deadline absolut CLOCK_MONOTONIC; orice schimbare incrementează `seq` și face
    `FUTEX_WAKE` doar dacă există sleeperi. IPC_NOWAIT/timeout → EAGAIN, semnal → EINTR, RMID → EIDRM pentru sleeperi
    și EINVAL pentru apeluri noi (ca Linux). Polling-ul Steam (IPC_NOWAIT) costă un lock + `getpid`, fără socket.
  - Lock-ul ține pid-ul deținătorului: dacă procesul moare în secțiunea critică (scurtă, fără syscall-uri), un waiter
    îl preia după 100 ms când `kill(pid, 0)` dă ESRCH. Nu avem robust futex (`set_robust_list` e blocat, N-009).
  - **SEM_UNDO:** semadj per proces e în mapare, actualizat atomic cu operația (limite SEMAEM → ERANGE). La primul
    SEM_UNDO procesul își înregistrează pid-ul pe conexiunea cu daemonul; la hangup (exit, crash, SIGKILL, sau exec
    fără reînregistrare) `fxshmd` aplică ajustările ca `exit_sem` din kernel (clamp 0..32767, trezește sleeperii).
    SETVAL/SETALL șterg ajustările, ca în Linux.
  - `semctl`: IPC_STAT/IPC_SET/IPC_RMID/GETVAL/SETVAL/GETALL/SETALL/GETPID/GETNCNT/GETZCNT/IPC_INFO/SEM_INFO;
    `IPC_64` din cmd e ignorat; SEM_STAT/SEM_STAT_ANY → EINVAL. Limite: SEMMSL 32000, SEMOPM 500, SEMVMX 32767.
    Permisiuni: un singur uid, doar biții owner.
  - msg* rămân ENOSYS.
- **Rutare:** glibc 0003: `syscall()` trimite `__NR_semget/semop/semtimedop/semctl` blocate la implementarea de mai sus
  (ABI-ul kernel păstrat: argumentul 4 din semctl e valoarea union semun). FEX 0002: `semget` (comun), `semop`/
  `semtimedop` x86-64 și `semtimedop_time64` i386 erau `SyscallPassthroughN` → acum `::semget`/`::semop`/`::semtimedop`.
  `semctl` x86-64 și multiplexorul i386 `ipc()` (folosit de glibc-ul Debian i386 din rootfs-ul Steam, syscall 117)
  apelau deja `::syscall(SYSCALL_DEF(sem*))` → ajung în wrapper. Reparat și un bug FEX la `semctl` i386 direct
  (syscall 394, glibc construit pentru kernel ≥ 5.1): union semun e trimis prin valoare, nu pointer, iar `IPC_64` nu
  era mascat (patch local, nu upstream, N-013).
- **Teste** (`tests/sysvsem/semtest.c`, 49 de verificări; `semtest all` sub qemu, container gol, prin
  `scripts/test-payload-qemu.sh`): set cu cheie partajat de procese neînrudite (posix_spawn), IPC_CREAT|IPC_EXCL →
  EEXIST, -1 IPC_NOWAIT pe 0 → EAGAIN, 2000 × polling ca Steam, semop blocant trezit de +1 din alt proces (GETNCNT = 1
  cât doarme), timeout 300 ms → EAGAIN după 300 ms, SETVAL/GETVAL/SETALL/GETALL/IPC_STAT, multi-op all-or-nothing,
  EFBIG/ERANGE, wait-for-zero (GETZCNT) trezit de SETVAL 0, RMID trezește sleeperul cu EIDRM, SEM_UNDO la exit și
  la SIGKILL, SETVAL șterge semadj, mutex SEM_UNDO între 4 procese × 400 iterații (contor exact 1600), calea
  `syscall(SYS_semget/semctl/semtimedop/semop)`. **Toate 49 trec**, de 3 ori la rând în același container. Aceleași
  surse compilate x86-64 și i386 și rulate **nativ pe kernelul PC-ului**: toate trec în afară de `msgget` (pe PC
  există cozi de mesaje) → testele reflectă semantica Linux reală. Regresii: `shmtest all` 36/36 (verificările sem
  → ENOSYS scoase), `seccomp-wrap` all ok (semget/semctl rutate, msgget → ENOSYS). `scripts/scan-syscalls.sh`:
  niciun număr blocat în `libc.so.6` (doar futex 98 × 78 și kill 129 × 5 noi), `ld.so`, `fxshmd`, `FEX`,
  `FEXServer`, `libFEXCore.so`.
- **Neverificat:** FEX nu pornește sub qemu (N-011), deci calea x86 → FEX → glibc e verificată doar la compilare și
  cu scannerul. Pe telefon: `FEX /opt/fexdroid-tests/semtest all` (x86-64 static, syscall-uri directe) și
  `FEX /opt/fexdroid-tests/semtest-i386 all` (i386 static, semget/semctl/semtimedop_time64 directe; calea `ipc()`
  e acoperită de Steam însuși). `/opt/fexdroid-tests/semtest all` (arm64) arată dacă seccomp-ul real e ocolit.
- **Limite / riscuri:** semadj se aplică și la exec dacă imaginea nouă nu reînregistrează (Linux îl păstrează);
  un proces omorât în timp ce doarme lasă `ncnt/zcnt/waiters` incrementate (doar GETNCNT greșit + FUTEX_WAKE-uri în
  plus); cu handler SA_RESTART, `futex` e repornit, deci semop nu întoarce EINTR (Linux ar întoarce); dacă daemonul
  moare, seturile deja mapate continuă între procesele care le au, dar cheile se pierd (id-urile noi pornesc de la un
  offset aleator ca să nu se confunde); un proces oprit (SIGSTOP) cu lock-ul luat blochează setul (și daemonul la
  RMID/undo pe acel set); max 64 de procese cu SEM_UNDO nenul simultan pe un set (altfel ENOSPC).

## N-026 · Oprire curată, jurnal fără cost pe CPU, compatibilitate pe mai multe telefoane  🟨 (compilat în CI; 🧪 telefon în așteptare)
- **Procese orfane:** `Process.destroy()` oprea doar `sh`-ul pornit direct; FEX, Steam, `steamwebhelper` și jocul
  rămâneau în viață după „Oprește”. `ProcessTree.kt` citește `/proc/*/stat` (același uid), trimite SIGTERM întregului
  arbore, iar după 2 s SIGKILL la ce a rămas. Sunt prinse și procesele deja re-parentate la init care rulează din
  `files/` (în afară de `fxshmd`, care e partajat și repornește la cerere). Oprirea rulează pe un fir separat, iar o
  pornire nouă așteaptă terminarea ei (Xvfb `:0`, FIFO-ul audio).
- **Jurnal:** `GameSession.append` reconstruia un șir de 40 KB și declanșa o recompunere Compose la fiecare linie
  (mii de linii de la FEX/Steam). Acum liniile stau într-un buffer circular, iar UI-ul primește textul cel mult o dată la 250 ms.
- **`GameService`** (foreground service, `specialUse`): Android nu mai omoară aplicația, și odată cu ea jocul, când
  utilizatorul trece în altă aplicație. Notificarea readuce jocul pe ecran.
- **Compatibilitate:** `minSdk` 31 → 28, cu verificări de API: culorile dinamice doar pe 12+, `WindowInsetsControllerCompat`,
  `Build.SOC_*` doar pe 12+. `DeviceCheck.kt` afișează pe ecranul Acasă (și în raportul de recunoaștere) un verdict per
  cerință: arm64, pagini 4 KB, ARMv8.2 (LSE + FP16, cerut de `TUNE_ARCH=armv8.2-a`), GPU Adreno 6xx/7xx/8xx cu
  `/dev/kgsl-3d0` (Mali, Xclipse și PowerVR nu au driver Turnip), kernel, varianta legacy/modern, RAM, spațiu,
  limita de procese copil. Butonul „Trimite” exportă raportul, ca testerii să-l poată trimite.
- **Profiluri FEX + cache de cod** (`FexConfig.kt`, Setări › Performanță): aplicația scrie `~/.fex-emu/Config.json`
  (stratul utilizator, peste cel global din `build-fex.sh`) la fiecare pornire. Opțiunile și valorile implicite sunt
  verificate în FEX-2609 `Config.json.in`:
  - **Compatibil:** TSO complet, inclusiv vectori și memcpy;
  - **Echilibrat:** valorile implicite FEX;
  - **Rapid:** `TSOEnabled=0`, `HalfBarrierTSOEnabled=0`, `X87ReducedPrecision=1`.

  `DiskCache=1` (implicit oprit în FEX) păstrează codul tradus în `FEX_APP_CACHE_LOCATION=~/.cache/fex-emu/`. Același
  director e folosit și de Steam și de pornirea directă (HOME diferit). Fișierele cache au lock (`FOZFile::Open`), deci
  merg și cu mai multe procese simultan. De măsurat pe telefon: a doua pornire a Dota 2 față de ~4–5 min acum.
- **Manifest:** `appCategory="game"` + `isGame`, ca modurile de joc ale producătorilor să se aplice.
- **CI:** `.github/workflows/android-apk.yml` compilează ambele variante la fiecare push. APK-urile din CI **nu conțin
  payload-ul** (rootfs/FEX/Mesa se construiesc local cu Docker), deci servesc la verificarea compatibilității, nu la jocuri.

## N-027 · Telefoane fără Adreno: Vulkan pe CPU (lavapipe)  🟨 (build în CI; 🧪 telefon în așteptare)
- Primul tester fără Snapdragon: Xiaomi 23090RA98G „zircon” (Redmi Note 13 Pro+, Dimensity 7200, Mali-G610),
  Android 16, kernel 5.15, pagini de 4 KB, ARMv8.2 cu LSE2. FEX poate rula, dar nu există `/dev/kgsl-3d0`, deci
  nici Turnip. Driverul Mali al Android-ului e bionic și nu se poate încărca într-un proces glibc fără un wrapper.
  PanVK cere kernel DRM (panthor), nu kbase.
- **Soluția:** `mesa-vulkan-drivers` (Debian arm64) în rootfs-ul arm64 aduce lavapipe (`libvulkan_lvp.so`, cu LLVM).
  `build-rootfs.sh` păstrează doar lavapipe din acel pachet. Celelalte drivere Vulkan Debian (intel, radeon, panfrost…)
  țintesc GPU-uri DRM de desktop, inaccesibile unei aplicații. `build-payload.sh` rescrie `library_path` din
  manifestele Debian spre rootfs.
- `LinuxEnv.vulkanIcd()`: cu `/dev/kgsl-3d0` → Turnip, altfel → lavapipe. Thunk-ul Vulkan FEX folosește același
  loader, deci jocurile x86 ajung la lavapipe fără alte schimbări. `DeviceCheck` raportează acum GPU-ul non-Adreno
  ca INFO („grafică pe procesor”), nu ca eroare.
- **Așteptări:** interfața Steam (CEF rulează oricum cu `-cef-disable-gpu`) și jocurile 2D/ușoare merg. Jocurile 3D
  mari (Dota 2) vor fi foarte lente. Pasul următor pentru Mali ar fi un wrapper spre driverul vendor
  (ideea vulkan-wrapper-android).
- Cost: ~150 MB în plus în rootfs-ul arm64 (LLVM).

## N-028 · Clientul Steam pornește pe telefon: login, bibliotecă, magazin  ✅ (Realme GT, 2026-09-26)
Verificat pe telefon: ecranul de login Steam, login cu parolă (tastat prin `adb shell input`, trece prin
XTEST), apoi biblioteca și magazinul. Steam a găsit Dota 2 în `files/steamlib` și a pornit validarea.
Blocajele, în ordinea în care au apărut:
- **Semafoare x86:** `semtest` x86-64 și i386 trec prin FEX („all ok”). Sunt compilate dinamic, pentru că
  binarele statice x86 crapă sub FEX pe kernelul 5.4.
- **`DiskCache=1` (FEX-2609)** face ca `steam-runtime/setup.sh` să crape cu segfault. E oprit implicit, cu o
  cheie nouă de preferință (`fex_disk_cache_v2`). În Setări e marcat experimental.
- **GLX:** vgui (`glXChooseVisual`) cere GLX. `build-rootfs.sh` păstrează doar `swrast_dri.so` (libgallium).
  `XSession` dă `LIBGL_DRIVERS_PATH` numai procesului Xvfb, pentru că `ld.so` deschide căile `/usr` direct,
  fără fxpath. Layerele Vulkan implicite Mesa (device_select, overlay) sunt scoase.
- **DNS:** Android nu are `/etc/resolv.conf`. `LinuxEnv.writeIdentityFiles` scrie `resolv.conf` și `hosts` și
  în rootfs-urile x86 (DNS din `ConnectivityManager`, fără IPv6 cu scope, maxim 3).
- **`/proc` gol în rootfs:** FEX deschide `<rootfs>/proc` când există, iar exporturile Docker/OCI îl au ca
  director gol. `openat(fd_proc, "self/task")` dădea ENOENT și zygote-ul Chromium murea (`thread_helpers.cc`).
  Aplicația șterge `proc`/`sys` goale; scripturile de build nu le mai pun.
- **libpci:** `/proc/bus/pci` există, dar SELinux interzice citirea, iar handler-ul implicit libpci face
  `exit(1)`, deci procesul GPU al CEF murea la fiecare pornire. Patch FEX: `/proc/bus/pci` apare ca ENOENT, iar
  `/sys/bus/pci` e redirecționat spre un arbore gol (`usr/share/fex-emu/fexdroid-empty-pci`), adică o mașină
  fără PCI.
- **Socket AF_UNIX în `/tmp`:** `bind("/tmp/steam_chrome_shmem_…")` dădea ENOENT, pentru că FEX trimite căile
  socket-urilor neschimbate. Patch FEX: pentru `bind`/`connect` (x86-64 și socketcall i386), o cale al cărei
  părinte există doar în RootFS e rescrisă spre RootFS (`FileManager::RootFSSocketAddr`).
- **Client Steam incomplet:** copierea de dimineață se oprise la jumătatea unui fișier (`steamui/library.js`
  lipsea, iar `-noverifyfiles` ascundea asta). `adb-copy-steam-client.sh` e acum incremental și verifică
  dimensiunile. Listarea pe telefon folosește `find -print0 | xargs`, pentru că `find -exec {} +` din toybox
  depășea ARG_MAX.
- **403 pe websocket-ul UI** (`logs/transport_client.txt`): clientul verifică cine e la celălalt capăt cu
  `lsof -P -F upnR -i TCP@127.0.0.1:<port>`. Aplicațiile Android nu pot citi `/proc/net/tcp` și nici folosi
  `NETLINK_SOCK_DIAG` (testat: EACCES). Soluția păstrează verificarea reală, nu o ocolește. FEX notează
  socket-urile TCP ale oaspeților la `connect`/`bind`/`accept` (`patches/fex/src/AndroidTcpRegistry.h`), iar
  `tools/lsof/fxlsof.c` răspunde din acest registru. Raportează o intrare doar cât timp `/proc/<pid>/fd` al acelui
  proces încă ține socket-ul. Formatul de ieșire e identic cu lsof -F (verificat pe PC).
- Unelte de depanare: `files/strace-steam.txt` (opțiuni strace pentru tot arborele Steam),
  `files/steam-launch-options.txt` (de ex. `-cef-enable-debugging` → DevTools pe portul 8080, cu
  `adb forward`) și `scripts/lib/cdp.py` (client DevTools fără dependențe).
- **Baterie:** sub sarcină, portul USB al PC-ului nu ține pasul; telefonul s-a oprit la 0% în timpul validării
  Dota. Pentru sesiuni lungi e nevoie de încărcător de priză (adb prin Wi-Fi).

## N-029 · Drumul „doar APK”: Steam se instalează singur, mediul jocului se construiește pe telefon  🟨 (2026-09-27)
Scop: cineva care are doar APK-ul (fără PC, fără root) să ajungă la joc. Testat pe Realme GT, firmware oficial, fără root.
- ✅ **Verificat pe telefon:** bibliotecile Steam x86 se descarcă din aplicație (release-ul `steam-rootfs`); Steam își
  descarcă singur clientul (2,3 GB, ~3 min pe Wi-Fi) și ajunge la login; login; instalarea Dota 2 din magazin
  (71 GB, 110–200 Mbps). Pe clientul proaspăt, Dota nu apărea în Library până la instalarea din Store › Play Game.
- Reparat pe drum: `tar`-ul x86 din bootstrap rula cu PATH-ul arm64 și nu găsea `xz`; `-noverifyfiles` la prima
  pornire sărea și descărcarea clientului („Verification skipped”, apoi `steamui.so` lipsă, exit 0). Acum se dă doar
  când clientul e instalat.
- ✅ **`tools/fxwmfit`:** fără manager de ferestre, Steam (1280×800) era tăiat jos pe ecranul virtual de 1280×720.
  Utilitarul potrivește ferestrele top-level la ecran; nu ia redirect, nu atinge meniurile (override-redirect).
- ✅ **Verificat pe telefon** (APK instalat peste, sesiune nouă):
  - „Potrivește la ecranul telefonului”: lățimea ecranului virtual din forma ecranului real (4:3…21:9), citită de la
    ecranul pe care e activitatea (pentru ecrane externe/DeX). Realme GT: 1600×720.
  - `GameRootfs.kt`: mediul jocurilor (`files/sniper-rootfs`) construit din ce descarcă Steam. Depozitul are
    `sniper_platform_*/files` + `usr-mtree.txt.gz`: mtree-ul descrie /usr, inclusiv symlink-urile (1538), fișierele
    goale (nestocate în depozit) și fișierele cu nume incomode (`contents=./xx/yyyyyy-1.bin`). Peste el se pun
    bibliotecile de bază din rootfs-ul Debian x86 (portul lui `sniper-overrides.py`). Validat pe PC în Python pe
    platforma 3.0.20260805: toate sursele există, în afara fișierelor de mărime 0.
  - Construirea pornește la începutul sesiunii (platformă nouă) sau la cererea entry point-ului
    (`files/game-rootfs.request`), când jocul e pornit în aceeași sesiune în care s-a descărcat runtime-ul.
  - `fexdroid-steam.sh` reînlocuiește entry point-ul containerului la fiecare 5 s, pentru că Steam instalează
    runtime-urile în timp ce rulează.
  - Atingere: tap = clic, glisare = rotiță (derulare), apăsare lungă = tragere, două degete = clic dreapta.
  - Mesaje clare la descărcare fără internet / fără spațiu (🟨 compilat, netestat pe telefon).
  Măsurat: mediul jocurilor se construiește în 5–14 s (8203 fișiere, 1538 legături, 31 de biblioteci înlocuite).
- ✅ `fxwmfit` maximizează ferestrele care ocupă deja ≥60% din lățime și ≥80% din înălțime: pe 1600×720 Steam
  (1280×800 cerut) rămânea cu o bandă neagră în dreapta.
- De făcut: Big Picture (`-gamepadui`) ca interfață pentru jucători; test pe Samsung DeX (S26 Ultra + monitor);
  `steamsysinfo` nu poate crea instanța Vulkan (-9), de investigat; dialogul „Processing Vulkan shaders” trebuie
  sărit cu Skip (de oprit implicit shader pre-caching); butonul de meniu al aplicației stă peste butoanele
  ferestrei Steam.

## N-030 · Dota 2 pornit din Steam, online, pe telefon  ✅ (Realme GT, 2026-09-27)
Steam › Library › Dota 2 › Play ajunge la meniul principal, logat (prieteni, chat, magazin). Doar cu APK-ul:
Steam pornește în ~100 s, Dota ajunge la meniu în ~2,5 min de la Play. ~190% CPU, 3,5–4 GB RAM, baterie 42 °C.
Eroarea de pe drum: „FATAL: It appears <joc> was not launched within the Steam for Linux sniper runtime
environment”. `dota.sh` verifică `/etc/os-release` (VERSION_CODENAME=sniper). Două cauze:
- **`/etc` în mediul jocurilor:** runtime-ul își ține `/etc` sub `/usr` (`files/etc` în depozit), iar în container e
  `/etc`. Cu un symlink `etc -> usr/etc`, legătura relativă `os-release -> ../usr/lib/os-release` ducea la
  `usr/usr/lib/os-release`. Acum intrările `etc/*` din mtree se scriu în `<root>/etc`, director real
  (`GameRootfs.kt`, FORMAT=2, reconstruire automată).
- **FEXServer hotăra RootFS-ul:** clientul FEX cere calea RootFS de la FEXServer-ul sesiunii
  (`RequestRootFSPath`) și o pune peste `FEX_ROOTFS` din mediu, deci jocul rula tot în rootfs-ul lui Steam. Un al
  doilea FEXServer nu e o soluție: `$HOME/.fex-emu` are prioritate față de `FEX_APP_DATA_LOCATION`, deci ajunge la
  același `Server.lock`. Patch 0002 (`FEXServerClient.cpp`): dacă RootFS-ul configurat e un director existent, nu se
  mai întreabă serverul. Serverul e folosit în continuare pentru rootfs-uri imagine (squashfs/erofs), pe care nu
  le folosim. FEX se re-execută din `/proc/self/exe`, deci un FEX înlocuit se vede abia după repornirea sesiunii.
**Cadre pe secundă:** contorul din meniu număra copierile spre ecran (mereu 30). Puntea numără acum cadrele al
căror conținut s-a schimbat (sumă pe tot cadrul, în bucla de copiere) și scrie în logcat la 10 s
(`fexdroid-display: last 300 frames copied: N had new content`). Meniul principal Dota, 1600×720: **16–18 cadre/s**.
Limita de sus a măsurătorii e rata de copiere (Setări › cadre/s).
Următorii pași: un meci cu boți; de unde vine limita (CPU prin FEX sau prezentarea prin Xvfb, D3).
Unde se duce timpul (meniu, o singură măsurătoare): firul principal al jocului ~54% dintr-un nucleu, 7 fire
`GlobPool` la 8–19%, `VKRenderThread` 4%, Xvfb 4,6%; nucleele la 1,0–1,6 GHz; RAM 11 GB plin, 5,4 GB în swap.
Nu e un nucleu blocat la 100%, deci limita nu e doar CPU-ul prin FEX: de măsurat prezentarea și memoria.
**Oprirea sesiunii închidea aplicația:** firele care citesc ieșirea proceselor primeau
`InterruptedIOException: read interrupted` când procesul era oprit. `Process.forEachOutputLine` tratează asta ca
sfârșit de ieșire. ✅ Verificat: după „Oprește” procesele dispar, aplicația rămâne deschisă.
**Big Picture** ✅: comutatorul din Setări pornește Steam cu `-gamepadui`; pe 1600×720 ocupă tot ecranul, merge la
atingere (tap pe joc › Play › OK la sfatul cu tastatura › Skip la shadere). Dota pornit de aici: 19 cadre/s în meniu.
E pornit implicit, dar `-gamepadui` se dă doar după prima autentificare (există `Steam/userdata/<id>`), fiindcă
descărcarea clientului și loginul sunt verificate doar în interfața clasică. Unele nume de prieteni cu caractere
speciale apar ca pătrățele (lipsesc fonturi în rootfs-ul x86).

## N-031 · Performanță: unde se duce timpul unui cadru  🟨 (Realme GT, 2026-09-27)
Măsurat cu `strace -T` pe firul `VKRenderThread` (strace din rootfs, pornit cu `run-as`), `top -H`, `gpubusy`.
- **În joc e mai bine decât în meniu.** Demo Hero (hartă reală), 1600×720, setările alese de joc: **25–29 cadre/s**.
  Meniul principal (trei eroi 3D): 17–22. Pagina Heroes (2D): peste 30.
- **Limita e GPU-ul, nu procesorul.** În fiecare cadru firul de randare stă într-un singur
  `IOCTL_KGSL_DEVICE_WAITTIMESTAMP_CTXTID`: ~45 ms în meniu, ~20 ms în joc, ~13 ms pe pagina 2D. Firele jocului
  stau la 10–13% dintr-un nucleu (cel principal ~47% în meniu). Xvfb 5–7%.
- **Drumul de afișare nu e frâna:** `vkcube` arm64 la 1600×720, prin același drum (`MESA_VK_WSI_DEBUG=sw`, MIT-SHM,
  Xvfb), face 125–134 cadre/s, cu așteptări după GPU de 0,1 ms.
- **Telefonul se sufocă termic:** carcasa 41–42 °C, nucleele 56–75 °C; nucleele mari sunt limitate la
  1,3–1,55 GHz (din 2,42), cel rapid la 1,3–1,55 GHz (din 2,84). Frecvența GPU nu se poate citi fără root.
  RAM 11 GB plin, ~2000 de pagini/s scrise în swap cu Steam + Dota pornite.
- **Fără efect măsurabil (Dota):** `mat_viewportscale` 0.5 (meniul nu ține cont de el), `TU_DEBUG=sysmem`,
  `MESA_VK_WSI_PRESENT_MODE=relaxed` (jocul avea deja vsync oprit). Ipoteza „fereastra nu are focus, deci
  `engine_no_focus_sleep`” s-a dovedit falsă: SDL își ia singur focusul (`XGetInputFocus` = fereastra Dota).
- **Procesor și GPU lucrează pe rând, nu în paralel.** Cu prezentare software, `wsi_common_queue_present` așteaptă
  fence-ul cadrului în firul aplicației. Am mutat așteptarea pe firul de prezentare al backend-ului X11
  (`docs/experiments/mesa-wsi-x11-sw-wait-on-present-thread.patch`, neaplicat la build). Rezultat: `vkcube` merge,
  dar la Dota câștigul e neclar, pentru că motorul Source 2 așteaptă și el GPU-ul după fiecare present (aceeași
  așteptare de 20 ms, doar că în alt loc): meniu 17 → 21 cadre/s, joc 28 → 24, măsurate în sesiuni diferite, cu
  telefonul la temperaturi diferite. Fără o comparație A/B curată nu intră în build.
- **Ce a intrat:** `MESA_VK_WSI_PRESENT_MODE=relaxed` (un cadru întârziat nu mai așteaptă următorul tact de 60 Hz al
  lui Xvfb; limita rămâne 60); puntea caută o imagine nouă de 60 de ori pe secundă și copiază doar imaginile noi
  (înainte: 30 de copieri pe secundă, mereu, deci maximum 30 de cadre afișate); `files/session-env.txt`
  (linii `CHEIE=valoare`) pentru experimente fără APK nou.
- **De făcut:** A/B curat pentru patch-ul Mesa; setări grafice recomandate pentru telefon; mai puțină memorie
  (Steam ține ~2 GB cu Big Picture); prezentare directă pe GPU (D3); test pe un telefon mai rece/mai nou.
  Atenție la automatizări prin `adb shell input tap`: după ce jocul se închide, Steam poate fi pe alt ecran
  (o secvență oarbă a deschis dialogul de instalare pentru alt joc; anulat, nimic instalat).
- **„Processing Vulkan shaders” (shader pre-caching al lui Steam)** lăsat să ruleze 3,5 minute: a rămas la 0%.
  `fossilize_replay` pornește un proces principal și 6 lucrători, fiecare ~80% dintr-un nucleu și ~420 MB. La 3:26
  de la pornire lucrătorii aveau doar 4–10 s vechime și existau deja 31 de fișiere `replay_cache.*.N.foz`
  (500 KB în total), deci lucrătorii sunt reporniți des; cauza nu e cunoscută. Pe telefon e de sărit (Skip) sau
  de oprit din Steam › Settings › Downloads. Shaderele compilate în timpul jocului rămân oricum în cache-ul Mesa
  (`shadercache/570/mesa_shader_cache_sf`).

## N-032 · Teste la distanță: raport din aplicație, actualizare din aplicație  🟨 (2026-09-27)
Un tester cu Galaxy S26 Ultra (SM-S948B, SM8850, Adreno 840, Android 16, kernel 6.12) nu are PC cu adb.
- **Raport** („Trimite” pe Acasă, „Trimite jurnalul” pe ecranul de eroare și în jurnal): versiune + commit, telefon,
  jurnalul sesiunii, ultimele rânduri din jurnalele lui Steam, șase teste automate (sh arm64, FEX hello, semafoare
  SysV x86_64 și i386, vulkaninfo arm64 și prin FEX), verificările de compatibilitate. Se scrie și în
  `<external files>/report.txt`. Partea utilă e la început: un raport a ajuns tăiat pe drum.
- **`steam.sh` întoarce 0 orice ar păți Steam** (ultima comandă e testul de repornire), iar de la
  „Steam runtime environment up-to-date!” își mută ieșirea în `logs/console-linux.txt` (srt-logger). De aceea
  aplicația arăta „Programul s-a încheiat normal” și un jurnal fără nimic util. `STEAM_RUNTIME_LOGGER=0` ține
  ieșirea în jurnalul aplicației.
- **Testele „FEX static/dinamic” dădeau „command not found”** pe orice telefon cu bibliotecile Steam instalate:
  programele de test sunt doar în arborele x86 de bază, iar FEX rula în arborele Steam.
- **Actualizare din aplicație** (`Updater.kt`): release-ul `apk-latest` are lângă APK `fexdroid-legacy.json`
  (commit, dată, mărime, SHA-256). Alt commit decât al aplicației = versiune nouă. APK-ul se descarcă direct
  într-o sesiune `PackageInstaller`, se verifică SHA-256, Android cere confirmarea. `files/update-url.txt` schimbă
  adresa (teste cu fișiere locale).
- **Cheia de semnare:** prima actualizare reală a eșuat pe două telefoane cu
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. `full-apk.yml` păstra cheia de debug cu `actions/cache`, dar calea
  `~/.android/debug.keystore` nu există pe runner („Path Validation Error … no cache is being saved”), deci fiecare
  build avea altă cheie. Acum build-urile publicate sunt semnate cu cheia proiectului (`CN=fexdroid`, SHA-256
  `2735ed06…37eca6`), din secretele repo-ului `FEXDROID_KEYSTORE_B64` și `FEXDROID_KEYSTORE_PASSWORD`; copia e pe
  PC-ul de dezvoltare în `~/.config/fexdroid/` (în afara repo-ului; pierderea ei = toți testerii reinstalează).
  Build-urile locale fără `FEXDROID_KEYSTORE` rămân pe cheia de debug a PC-ului.
- **S26 Ultra, ce știm:** FEX hello și `vulkaninfo` prin FEX merg, Turnip recunoaște Adreno 840; Steam se închide
  imediat după pornire, `vkcube` x86 nu apare. Într-un raport testul de semafoare x86_64 a avut 5 eșecuri
  (`semtimedop` întors după 35 ms cu EIDRM), cel i386 a trecut: posibil două rapoarte pornite deodată (acum
  exclus), de reverificat. Cauza opririi lui Steam: necunoscută până la un raport complet.
- Simularea procesorului pe PC (qemu-user, `-cpu max`: SVE, SME, PAC, BTI) nu a fost concludentă: FEX hello a
  crăpat diferit de la o rulare la alta, iar un program i386 a rămas blocat minute întregi. Pe telefonul real FEX
  hello merge, deci comportamentul ține de qemu.

## N-033 · Galaxy S26 Ultra: Steam nu poate crea `/tmp/dumps`  ✅ (2026-09-27; reparat în FEX, confirmat de tester pe S26 Ultra)
Raportul de pe S26 Ultra (Android 16): `/tmp/dumps: insufficient permissions - delete and recreate`, apoi
`/tmp/dumps: failed to create, skipping` pentru toate cele zece nume (`dumps` … `dumps09`) și
`FATAL: Steam cannot run. Please delete some /tmp/dumps* directories or change their ownership to the local user.`
Steam folosește doar un director de dump-uri creat de el; pe Realme GT aceeași verificare pică, dar refacerea reușește.
- **Cauza în patch-ul nostru FEX:** `GetRootFSCreatePath` trimitea crearea în RootFS doar când părintele **lipsea**
  pe telefon (`stat` eșuat). Dacă telefonul are un `/tmp` al lui, în care aplicațiile nu pot scrie, `mkdir` ajungea
  acolo și era refuzat. Că S26 are un `/tmp` pe host nu e încă văzut direct (raportul următor listează directoarele
  host); e singura cale din cod pe care `mkdir /tmp/dumps04` poate eșua, iar pe Android utilizatorii Termux
  raportează „Permission denied” la `/tmp`.
- **Reprodus pe Realme GT cu `/etc`** (există pe orice Android, legătură spre `/system/etc`, doar citire): din
  guest, `mkdir /etc/x` → „Read-only file system”. După reparație merg `mkdir`, `touch`, `rmdir`, `rm -r` în `/etc`
  și `/tmp`, create în RootFS.
- **Reparația:** părintele contează ca „al host-ului” doar dacă putem crea în el (`access(W_OK | X_OK)`); altfel,
  dacă RootFS-ul are directorul, se creează acolo. `unlinkat` trecea direct la kernel (doar `unlink`/`rmdir`
  foloseau `FileManager::Unlinkat`), deci `rm` pe un fișier creat în RootFS pica: acum trece și el prin aceeași regulă.
- Capcană la testare: într-un guest pornit cu PATH-ul arm64 al aplicației, `ls`, `mkdir` etc. sunt binarele arm64
  native (FEX le rulează nativ), care văd căile telefonului, nu RootFS-ul. Testele folosesc `PATH=/usr/bin:/bin`.
- `fexdroid-steam.sh` șterge la pornire `steam_chrome_shmem_uid*` rămase în `/tmp` (unul per pornire).
- Rămâne de văzut pe S26: `vkcube` x86 nu apare (posibil aceeași cauză, prin socketul X din `/tmp`).
- **Confirmat pe S26 Ultra (relatat de tester, 2026-09-27 ~20:00):** cu build-ul `e5d979c`, apoi cu `8afd73e`
  (reinstalat, cheia stabilă), Steam pornește, se autentifică și instalează Dota 2. Primul telefon cu Adreno 840 și
  Android 16 pe care rulează clientul Steam. Nemăsurat încă: pornirea jocului, cadre pe secundă, `vkcube` x86.

## N-034 · Jocul fără focus se frânează singur: de la 13 la 26 de cadre/s  ✅ (Realme GT, 2026-09-27)
Aceeași scenă (Dota 2, Demo Hero, 1600×720) dădea de la o pornire la alta fie ~13, fie ~26 de cadre/s. Șapte
porniri: patru lente (11–15), trei rapide (25–27), fără legătură cu reglajele încercate.
- **Cauza:** nu există manager de ferestre, deci nimeni nu dă focusul ferestrei care se deschide. În pornirile
  rapide `XGetInputFocus` întoarce fereastra „Dota 2”; în cele lente întoarce fereastra rădăcină (0x21f). Fără
  focus motorul Source 2 se frânează (la noi fără `sleep` vizibil în `strace`: firul principal rămâne la ~78%).
- **Dovada:** într-o pornire lentă (14,1 cadre/s) am dat focusul ferestrei jocului, fără să repornesc nimic:
  25,5–26,8 cadre/s, stabil.
- **Reparația:** `fxwmfit` dă focusul ferestrei normale de deasupra (o dată pe secundă și la fiecare fereastră nouă),
  dacă nu îl are deja ea sau o subfereastră a ei; scrie și `_NET_ACTIVE_WINDOW`. `fxwmfit --focus-info` doar citește.
- **Am anunțat greșit un câștig:** prima pornire cu `TU_DEBUG=sysmem` a fost una rapidă (25–26) după una lentă (13–14)
  și am pus diferența pe seama reglajului. Repetată, a dat 11–14. Un reglaj se judecă pe mai multe porniri.
- **Ce limitează după reparație:** firul principal al jocului stă la 96–97% dintr-un nucleu, iar nucleele mari merg
  la 0,85–1,3 GHz din 2,4–2,8 GHz (limitare termică; carcasa 52 °C). GPU ocupat 55%. Deci procesorul, prin
  emulator, pe un telefon încins.
- **Fără TSO (profilul „Rapid”) Dota rămâne blocat** la ecranul de încărcare, cu firele în așteptare (o încercare).
- **Detectarea de controllere la fiecare al doilea cadru:** SDL3 din joc încarcă `libudev.so.1`, citește
  `/proc/self/mountinfo` (34 KB pe Android), cere un socket `NETLINK_KOBJECT_UEVENT`, primește EACCES, descarcă
  biblioteca și o ia de la capăt: ~300 de apeluri de sistem de fiecare dată, ~13 pe secundă. Nu o opresc
  `SDL_JOYSTICK_DISABLE_UDEV=1`, `SDL_HIDAPI_UDEV=0` și nici `/run/host/container-manager` în mediul jocului.
  De făcut: un socket uevent de formă în FEX, ca inițializarea să reușească o dată. Costul nu e măsurat.
- `DisableL2Cache=0` + `DynamicL1Cache=0` (FEX): o singură pornire, lentă; neconcludent.
- **Cum rulează Valve Half-Life: Alyx pe Steam Frame:** nu prin emulator. Actualizarea din 14 septembrie 2026 a adus
  un build nativ ARM64, cu randare foveată și reproiecție. Jocurile x86 trec prin Proton + FEX, cu 10–20% cost
  declarat; la jocurile Windows FEX poate folosi „volatile metadata” din fișierele PE ca să emuleze ordinea
  memoriei x86 (TSO) doar unde trebuie. Dota 2 nu are build ARM64, iar build-ul lui de Linux nu are acele metadate.
- Raportul din aplicație are acum un instantaneu de performanță (cadre, procese, fire, GPU, frecvențe,
  temperaturi, memorie, focus) și ultimele rânduri din `content_log.txt`. `_v2-entry-point` citește
  `files/session-env.txt` la fiecare program pornit, deci un reglaj se poate schimba fără repornirea lui Steam.

## N-035 · Optimizări măsurate: ce ajută și ce nu  🟨 (Realme GT, 2026-09-27/28)
Scena de măsură: Dota 2 › Demo Hero, 1600×720, setările alese de joc. Cadrele vin din puntea de afișare
(`fexdroid-display`), trei ferestre de câte 10 s. **Temperatura contează cel mai mult:** aceeași versiune dă
31 de cadre/s cu nucleele mari limitate la 2,0–2,15 GHz și 26 cu ele la 1,3 GHz, deci se compară doar porniri cu
aceleași limite (`scaling_max_freq`), alternate.

| Schimbare | Rezultat | Stare |
|---|---|---|
| Focus pe fereastra jocului (N-034) | 13–14 → 26–27 cadre/s în pornirile afectate | în build |
| Mesa: așteptarea fence-ului pe firul de prezentare (patch 0005) | 30,8 / 30,4 față de 28,3 / 28,0 (+8%) | în build |
| FEX: socket uevent de formă (udev) | încercări udev 55 → 0 în 3 s; firul principal 74% → 64% | în build |
| `fxwmfit`: ferestrele acoperite de un joc pe tot ecranul sunt ascunse | `steamwebhelper` dispare dintre procesele ocupate (era 30–40% dintr-un nucleu) | în build |
| Puntea de afișare citește un rând din opt | aplicația 23% → 11% dintr-un nucleu | în build |
| `-nojoy` | același efect ca socketul uevent; inutil cu el | nefolosit |
| `DisableL2Cache=0`, `DynamicL1Cache=0` (FEX) | 28,6 față de 29,8 la aceleași limite: nimic | respins |
| Setări video minime | 28,6 față de 26,3 (+9%) în scena goală | la alegerea jucătorului |
| Randare la 70% + FSR | același număr de cadre | respins |
| `TU_DEBUG=sysmem` | nimic (N-034) | respins |
| TSO oprit | jocul rămâne blocat la încărcare | respins |

- **Unde e limita acum:** firul de randare așteaptă GPU-ul ~22 ms pe cadru (`IOCTL_KGSL_DEVICE_WAITTIMESTAMP_CTXTID`),
  GPU ocupat 40–55%, firul principal 64–66%. Niciunul nu e saturat: se așteaptă unul pe altul, iar guvernorul ține
  frecvențele jos. Ultimele trei schimbări din tabel scad căldura, nu cresc direct cadrele.
- **`fxwmfit` și ferestrele ascunse:** se ascund doar ferestrele altui client decât cel de deasupra (id-urile X au
  baza clientului peste 21 de biți) și se arată din nou când fereastra jocului dispare. `FXD_KEEP_COVERED_WINDOWS=1`
  oprește comportamentul.
- **Sunet:** fluxul AAudio era în mod „low latency”, cu tampon de 2–4 ms: orice întârziere a firului care citește
  FIFO-ul era o pauză. Acum ține 80 ms, iar FIFO-ul e redus de la 64 KB (340 ms, mereu plin) la 16 KB, deci sunetul
  nu întârzie mai mult ca înainte. Puntea numără pauzele de ieșire și „găurile” (tăceri de 3–150 ms între sunete,
  puse de PulseAudio când programul întârzie). `PULSE_LATENCY_MSEC=60` pentru programele sesiunii.
  În configurația contului de test `snd_mixahead` e 0.001 (1 ms), adusă din cloud de pe PC: candidat pentru sunetul
  sacadat din meniu, de verificat cu `files/launch-options-570.txt`.
- **Opțiuni de lansare pe joc:** `_v2-entry-point` adaugă la comanda jocului conținutul din
  `files/launch-options-<app id>.txt`, ca „Launch Options” din Steam.
- `map_enable_background_maps` nu mai există în Dota (scos de Valve), deci fundalul meniului nu se poate opri
  din linia de comandă.
- În timpul măsurătorilor Steam a instalat o actualizare Dota de 2,2 GB (descărcare 4 min, apoi „Validating”,
  ~10 min): jocul nu se poate porni până nu termină.
- **Toate la un loc** (build-ul publicat): Demo Hero 31,8–33,3 cadre/s cu nucleele mari limitate la 1,3 GHz, față
  de 26,3 la aceleași limite înainte (+23%) și 13–14 în pornirile fără focus. Meniul principal: 22 (era 17–18).
  Sunet: 600 s redate, o pauză de ieșire, 11 găuri (în timpul încărcărilor); 90 s de meniu fără nicio gaură nouă,
  deși `snd_mixahead` e tot 0.001. Nu am o măsurătoare „înainte” pentru sunet: contoarele sunt noi.

## N-036 · Marginea ecranului și „Watch” în Dota  🟨 (Realme GT, 2026-09-28)
- **Margine:** imaginea jocului umplea tot ecranul telefonului. Colțurile rotunjite tăiau ce desenează Dota în colțuri
  (datele de rețea „FPS / PING” din dreapta sus), atingerile de pe marginea ecranului se pierdeau (butoanele din
  stânga sus ale jocului), iar butonul de meniu al aplicației stătea peste colțul din dreapta sus. Acum imaginea
  stă la o margine de ecran (Setări › Ecran › Margine: fără, 3%, 5%, 8% din latura scurtă; implicit 3%), iar
  lățimea ecranului virtual se calculează din spațiul rămas (Realme GT: 1656×720 la 3%). Butonul de meniu e la
  mijlocul marginii din dreapta, mai mic, și se poate trage în sus sau în jos. ✅ văzut pe telefon în Steam și în
  meniul Dota; atingerile de pe margine nu sunt verificate cu degetul (adb nu trece prin filtrul de margine).
- **„Watch in-game” → „Could not watch this game at this time”:** reprodus. Din `console.log` (`-condebug` prin
  `files/launch-options-570.txt`): clientul trimite `k_EMsgGCWatchGame`, coordonatorul răspunde `result 0` (în
  așteptare) și după 60 s `result 3` (indisponibil). În tot acest timp jocul nu încearcă nicio conexiune spre un
  server de joc (`strace -e trace=network`: niciun `sendto` cu adresă). Rețeaua de relee e în regulă la pornire:
  „Ping measurement completed in 3.4s. Relays: 25 valid”, `avail=OK config=OK anyrelay=OK`. Refuzul vine deci de la
  serverul Valve; dacă ține de clientul nostru sau de serviciul lor în acel moment nu se poate spune fără aceeași
  încercare pe un PC, cu același cont.
- **„Watch” e la Valve:** aceeași eroare apare și pe PC, cu același cont (verificat de utilizator). Urmărirea
  meciului unui prieten merge.
- **Meci real (spectator, 10 eroi), Realme GT încins:** 12,3–13,5 cadre/s. Firul principal al jocului stă la
  94–95% dintr-un nucleu (pe cpu7, nucleul X1), iar nucleele mari sunt limitate la 1,30–1,32 GHz din 2,42–2,84 GHz
  (carcasa 52 °C); 7 fire `GlobPool` la ~29% fiecare, GPU ocupat 45%. Fără erori de pagină pe firul principal
  (swap 166 pagini/s în tot sistemul, jocul are 3,5 GB în swap). Apelurile de sistem ale firului principal sunt
  neglijabile. Deci limita e viteza unui singur nucleu frânat termic, prin emulator; Demo Hero (32–33 cadre/s) nu
  e reprezentativ pentru un meci.
- `taskset` pe procesele jocului din `run-as` e refuzat (Permission denied), deci afinitatea se poate încerca doar
  din aplicație. Planificatorul ține oricum firul principal pe cpu7.
- **GT Mode (Realme) nu ridică limitele pe un telefon deja încins:** cu `gt_mode_state_setting=1`, după ore de
  rulat pe încărcător (carcasă 45–47 °C, baterie 43–44 °C, nuclee 65–72 °C), limitele au rămas 1,61 / 1,21 / 1,42 GHz
  și au coborât pe moment la 0,60 / 1,08 / 1,08 GHz. Meciul: 12–14 cadre/s, cu căderi la 5. De măsurat de la rece.

## N-037 · A treia rundă de optimizări: profil Dota, GPU la frecvență maximă, 540p  🟨 (Realme GT, 2026-09-28)
Scena de măsură nouă: Demo Hero cu **zece eroi care se bat** (cinci inamici, patru aliați puși cu butoanele
`+ENEMY`/`+ALLY`), mai aproape de un meci decât un erou singur. Cadrele: `fexdroid-display`, ferestre de 10 s.
Porniri alternate, comparate doar la aceleași limite de frecvență.

| Schimbare | Fără | Cu | Limite (MHz, mic/mare/X1) |
|---|---|---|---|
| Profil de performanță Dota | 29,5 | 36,9–40,0 | 1804 / 2419 / 2841 (telefon rece) |
| Profil de performanță Dota | 23,8 | 34,2 | 1804 / 1555–1996 / 1555–2150 |
| GPU la frecvență maximă (`TU_KGSL_PWR_CONSTRAINT=max`) | 25,6 · 25,7 | 30,5 · 29,8 | 1612 / 1209 / 1420 fără; 595 / 1075 / 1075 cu |
| `TU_KGSL_PWR_CONSTRAINT=70` | 25,6 | 25,6 | fără efect pe kernelul 5.4 |
| 540p față de 720p (cu profil și GPU max) | 30,2 | 31,1 | 1612 / 1209 / 1420 |
| `r_low_latency 0` | 25,2–25,7 | 25,3–25,8 | nimic |
| AVX ascuns jocului (`HostFeatures: disableavx`) | 25,9 | 25,7 | nimic |

- **Profilul Dota** (`DotaProfile.kt`, Setări › Performanță, oprit implicit): scrie în
  `userdata/<cont>/570/local/cfg/video.txt` valorile cele mai ieftine (fără umbre, particule minime, fără treceri
  de lumină suplimentare, texturi mai mici, `useadvanced 1`). Fișierul e per mașină, nu e în Steam Cloud. Valorile
  jucătorului rămân în `video.txt.fexdroid-orig` și revin când profilul e oprit. GPU ocupat 44–54% în loc de 70–80%.
- **GPU la frecvență maximă** (patch Mesa 0006, Setări › Performanță, pornit implicit): Turnip cere kernelului
  `KGSL_PROP_PWR_CONSTRAINT` = maxim pe contextul lui, aceeași interfață pe care o folosește driverul Qualcomm.
  Guvernorul urmărea încărcarea GPU-ului, care la noi stătea la 35–50% (cadrele se așteaptă unul pe altul), deci
  ținea frecvența jos. Cu constrângerea, firul de randare nu mai așteaptă deloc după GPU (`strace`: 0 așteptări
  lungi, față de ~22 ms pe cadru), GPU ocupat 13–18%. Prețul: telefon mai cald, limitele procesorului coboară
  (595 / 1075 / 1075 MHz), dar câștigul net rămâne +17%. Dacă kernelul refuză contextul cu acest flag, Turnip îl
  creează fără.
- **După aceste două schimbări limita e doar firul principal al jocului**: 88% dintr-un nucleu, restul firelor
  îl așteaptă. De aici încolo contează viteza unui nucleu prin emulator și temperatura.
- **540p** e în Setări, dar nu aduce cadre în plus când GPU-ul nu mai e limita; poate ajuta doar la căldură.
- **Răcirea contează mai mult decât orice reglaj:** cu telefonul răcit (utilizatorul l-a ținut în ușa frigiderului;
  carcasa 37–38 °C) nucleele mergeau la frecvența maximă și scena dădea 37–40 de cadre/s cu profilul, 44–48 cu un
  singur erou. Încins (carcasa 44–48 °C): 25–31.
- Nereușit: opțiunile din FEX care ar reduce costul emulării (fără TSO) blochează jocul (N-034).
- **Build-ul final, verificat:** Steam, Dota, scena cu zece eroi la 1656×720 cu profil și GPU la maxim, telefon
  încins (limite 595 / 1075 / 1075 MHz, carcasa 46 °C): 30,9–32,6 cadre/s. În aceeași stare, înaintea acestei runde:
  23,3–25,7. Sunet: 360 s, nicio pauză de ieșire.

## N-038 · Bara de jocuri realme, meniurile Steam, descărcarea de la fiecare pornire  🟨 (Realme GT, 2026-09-28)
- **Marginea stângă nu se putea atinge** (nici cu mouse-ul): realme pune peste aplicațiile marcate ca joc o
  fereastră de sistem, `GamesFloatBar` (`dumpsys input`: `frame=[576,-71][1080,72]` în coordonate portret,
  `TRUSTED_OVERLAY`), adică în landscape fâșia x 0–72 px, y 0–504 px. Butonul de ieșire din demo/meci al Dota era
  sub ea (centrul la ~62 px), cheița de setări imediat lângă (~106 px). „Margini laterale late” ține 28 dp
  (84 px) liberi în stânga și în dreapta; implicit pornit pe realme, OPPO și OnePlus. Realme GT: 1584×720, săgeata
  ajunge la ~114 px. Văzut în capturi; atingerea cu degetul o confirmă utilizatorul.
- **Meniurile Steam nu se mai deschideau** de la N-034: meniul e o fereastră override-redirect a aceluiași client,
  care primește focusul și se închide când îl pierde; `fxwmfit` îl dădea înapoi ferestrei principale în cel mult o
  secundă. Acum focusul nu se mai ia de la o fereastră vizibilă a aceluiași client. Verificat: meniul „Steam” se
  deschide.
- **FSR** în Dota e disponibil doar cu Anti-Aliasing pornit (tooltip-ul jocului). Testul din N-034 („nu crapă pe
  Adreno 660”) a fost făcut cu Anti-Aliasing oprit, deci FSR nu rula: concluzia de acolo nu e valabilă. Profilul
  de performanță nu mai oprește Anti-Aliasing.
- **„Updating / Validating” la fiecare pornire a lui Steam:** nu sunt fișierele jocului. `content_log.txt`:
  `AppID 570 update started : download 0/2222610096 … stage 0/2947264340`, apoi `starting commit from
  ".../steamapps/shadercache/570/downloads/"`: e cache-ul de shadere precompilate (Shader Pre-caching), ~2,2 GB,
  descărcat din nou la fiecare pornire (21:29, 23:58, 06:55, 07:30 UTC). Între pornirile jocului din aceeași
  sesiune Steam nu se descarcă nimic. La noi cache-ul nici nu folosește: procesarea lui (`fossilize_replay`)
  rămâne la 0% (N-031). De ce îl consideră Steam mereu învechit nu e lămurit (driverul Mesa a fost schimbat de
  mai multe ori în noaptea aceea, dar ultima descărcare a fost cu același driver).
  Oprit din Steam › Settings › Downloads, Steam scrie în `config/config.vdf`, sub `ShaderCacheManager`,
  `"DisableShaderCache" "1"` și șterge cache-ul (2,9 GB → 30 MB). `fexdroid-steam.sh` pune cheia o singură dată
  (marker `files/.shader-precache-default`), la instalări noi într-un `config.vdf` minimal creat înainte de prima
  pornire (🟨 netestat pe o instalare nouă). ✅ Verificat pe Realme: următoarea pornire a lui Steam nu mai
  descarcă nimic.
- **Mouse:** butoanele X urmează acum starea butoanelor din fiecare eveniment (`syncButtons`), oricare dintre
  DOWN/UP și BUTTON_PRESS/BUTTON_RELEASE ajunge la view; o apăsare fără buton numit înseamnă primul buton.
  Evenimentele de rotiță și de buton pe care ierarhia Compose nu le dă view-ului le predă
  `MainActivity.dispatchGenericMotionEvent`. ✅ cu `adb shell input mouse tap/swipe`: clic și tragerea barei de
  derulare din Steam. Înainte, `input mouse tap` nu producea niciun clic. Rotița unui mouse real: de confirmat de
  utilizator (adb nu o poate simula).

## N-039 · Unde se pierde timpul în emulare: fire pe nuclee, x87, măsurători cu profilerul  ✅/🧪 (Realme GT, 2026-09-28)
Cerința: performanță din emulare și din driver, nu din setările jocului. Întâi măsurat, apoi schimbat.
Unelte noi: `scripts/fex-thread-stats.py` (procesor pe fir + contoarele FEX, `FEX_PROFILESTATS=1`),
`simpleperf` din NDK pornit din shell cu `--app ro.cobrabm.fexdroid -t <tid>` (cere
`setprop security.perf_harden 0`, pus la loc după), `strace -T -p <tid>` pe câte un fir.

- **Meci real privit (Watch), telefon încins pe încărcător** (baterie 46 °C, limite 595/1075 MHz): 15–21 cadre/s.
  Firul principal 68–79% dintr-un nucleu (deci nu e ocupat tot timpul), 7 fire `GlobPool` a câte ~22%, memoria
  liberă ~600 MB, jocul 3,0 GB în RAM + 4,2 GB în swap (5,6 GB memorie anonimă, 0,9 GB KGSL, 0,3 GB cod JIT).
  `kswapd`, `kshrink_slabd` și `hybridswapd` consumă și ele procesor. „Un cadru la 5–10 secunde”, cum a văzut
  utilizatorul, nu s-a reprodus: cel mai probabil memoria plină după o sesiune lungă (🟨 nedovedit).
- **„Disconnected from Server: Overflow error”** la primul Watch: serverul închide conexiunea
  (`NETWORK_DISCONNECT_OVERFLOW`, `SIGNONSTATE_SPAWN`) la 29 s după `ProcessServerInfo`, cât timp clientul încă
  încarcă harta (firul principal 83–88%). A doua încercare reușește fiindcă resursele sunt deja încărcate. Nu e o
  eroare de rețea; se rezolvă doar prin încărcare mai rapidă.
- **Firul principal așteaptă 7–9 ms în fiecare cadru** un `eventfd` semnalat de firele `GlobPool`: o parte din
  lucrul cadrului rulează pe firele ajutătoare, iar kernelul le punea și pe nucleele mici (A55, limitate la
  595 MHz). Tot kernelul muta firul principal între nuclee, uneori pe unul mic.
- ✅ **Așezarea firelor (`ThreadTuner`, Setări › Performanță, pornit implicit):** firul cel mai ocupat al
  procesului cel mai ocupat primește singur nucleul cel mai rapid, celelalte fire ale jocului nucleele mari
  rămase, restul sesiunii (Steam, Xvfb, sunet, aplicația) nucleele mici. Aplicația are voie să facă asta pentru
  procesele sesiunii (același utilizator, același domeniu SELinux); prin `run-as` nu se poate. Scena cu zece eroi,
  aceleași limite (1075/1075/595 MHz), moduri alternate la 30 s, două sesiuni:

  | așezare | cadre/s |
  |---|---|
  | fără | 31,0 · 31,1 |
  | doar firul principal pe nucleul rapid (`pin`) | 33,0 |
  | **fir principal pe 7, jocul pe 4–6, restul pe 0–3 (`big`)** | **37,8 · 37,2 · 36,0** |
  | ca `big`, dar firele jocului și pe nucleul 7 | 36,2 |
  | ca `big`, dar restul sesiunii pe orice nucleu | 36,4 |
  | firele jocului și pe nucleele mici | 33,4 |
  | fir principal pe 6–7, jocul pe 4–5 | 31,6 |

  `files/tuner.txt` alege modul în timpul sesiunii: `off`, `pin`, `big`, `masks A B C` (hexazecimal).
  Pe telefoane fără nuclee mici (Snapdragon 8 Elite) „restul sesiunii” rămâne pe toate nucleele în afară de cel
  rezervat. 🟨 Netestat pe alt telefon.
- ✅ **x87 pe 64 de biți în profilul „Echilibrat”** (`X87ReducedPrecision`), ca în configurația pe care FEX o
  livrează pentru Steam (`Source/Steam/ConfigTemplate.json`). Cu 80 de biți fiecare operație x87 e un apel în
  software: Dota, fir principal, 45–54 de mii pe secundă în meci. Dota pornește și rulează cu opțiunea (deci
  blocarea profilului „Rapid” din N-034 vine de la TSO). Câștigul separat nu a fost măsurat.
- 🧪 **Profilul firului principal** (10 s, 6790 de eșantioane): 87,5% cod de joc tradus (JIT), 8,0% kernel, 3,6%
  FEX (dispecer, syscall-uri, compilare), 0,34% Turnip. Profilul e plat: adresa cea mai fierbinte are 0,65%.
  Contoare hardware: 0,97 instrucțiuni pe ciclu (puțin pentru un Cortex-X1), 32% din cicluri blocate în frontend
  și 39% în backend, 22 de ratări de cache de instrucțiuni la mia de instrucțiuni, 15 de cache L2 de date, 11 de
  TLB de date, 3,6% salturi prezise greșit. Codul tradus e mare și împrăștiat (310 MB), datele sunt pe pagini de
  4 KB. Paginile mari ar ajuta, dar kernelul le are oprite (`transparent_hugepage/enabled` = `never`, se schimbă
  doar cu root).
- 🧪 **Prezentarea imaginii:** Mesa trimite fiecare cadru cu `PutImage` prin socketul X (4,6 MB pe cadru, `writev`),
  nu prin memorie partajată: MIT-SHM e folosit doar când serverul are și DRI3, iar Xvfb nu are. Patru copii pe
  cadru (socket, Xvfb, framebuffer, suprafața Android). Firul de prezentare 12–35% dintr-un nucleu, Xvfb ~6%,
  puntea ~5%: puțin față de cele ~300% ale jocului, deci nu limitează cadrele acum, dar încălzește. Rămâne
  pentru prezentarea directă (D3).
- 🧪 Imaginea de swapchain e 1600×720 pe un ecran virtual de 1584×720 (rezoluția salvată de joc înainte de
  marginile laterale).
- Patch FEX 0003: harta pentru profiler se scrie în directorul din `FEX_PERFMAP_DIR` (Android nu are `/tmp`);
  pentru `FEX_LIBRARYJITNAMING=1`. 🟨 Construit, încă nefolosit la o măsurătoare.
- **Căldura rămâne factorul cel mai mare:** toate măsurătorile de mai sus sunt cu nucleele mari la 1075 MHz din
  2841/2419. Telefonul era pe încărcător; bateria a ajuns la 50 °C și testele au fost oprite.

## N-040 · Clic dreapta, ecran negru la pornire, numărul versiunii  ✅ (Realme GT, 2026-09-28)
- **Clic dreapta deschidea meniul aplicației:** Android transformă al doilea buton al mouse-ului în tasta Înapoi
  (`KEYCODE_BACK` cu sursa mouse). `MainActivity.dispatchKeyEvent` o dă înapoi sesiunii ca butonul 3.
  ✅ `adb shell input mouse keyevent 4` deschide meniul contextual din Steam. 🟨 Mouse real: de confirmat.
- **Ecran negru deși Steam rula:** la pornire au existat pentru o jumătate de secundă două `SurfaceView`; la
  dispariția celui de-al doilea, puntea de afișare (legată de primul) era oprită. Acum puntea rămâne pe o
  suprafață care există și se mută doar când aceea dispare.
- **Versiune:** `0.3.<număr de commit-uri>-alpha`, `versionCode` = numărul de commit-uri (crește la fiecare
  versiune publicată; Android refuză un număr mai mic). Workflow-urile descarcă tot istoricul (`fetch-depth: 0`),
  iar build-ul se oprește dacă istoricul e incomplet. `fexdroid-legacy.json` are câmpul `version`, afișat în
  cartela de actualizare.

## N-041 · Emularea ordinii memoriei (TSO) oprită pe bibliotecile jocului  ✅ scenă demo / 🟨 meci (Realme GT, 2026-09-28)
- **De ce:** 87,5% din timpul firului principal e cod de joc tradus (N-039), iar fiecare acces la memorie din el
  e tradus cu instrucțiuni ordonate (`LDAPR`/`STLR`) ca să păstreze ordinea memoriei x86. Cu `TSOEnabled=0`
  peste tot, Dota se blochează la încărcare (N-034).
- **Profil pe biblioteci** (`FEX_LIBRARYJITNAMING=1`, `scripts/perf-by-library.py`), scena cu zece eroi. Firul
  principal: `libclient` 26,0%, `libserver` 11,9% (doar în demo, unde rulează și serverul), `libtier0` 8,3%,
  kernel 7,5%, `libengine2` 6,9%, `libpanorama` 6,7%, `librendersystemvulkan` 6,2%, FEX 3,4%,
  `libmaterialsystem2` 3,2%, `libnetworksystem` 3,1%, `libc` 3,1%, `libscenesystem` 3,1%. Tot procesul:
  `librendersystemvulkan` 14,8%, `libclient` 11,0%, kernel 11,0%, `libpanorama` 7,8%, `libmaterialsystem2` 7,7%,
  `libtier0` 7,5%, Turnip 7,4%, `libparticles` 6,7%.
- **FEX are deja mecanismul**, `ExtendedVolatileMetadata` („Disable TSO for a full module: just provide the
  module name”), dar în FEX-2609 nu avea efect: `Core.cpp` punea excepția doar pe blocurile care conțin o
  instrucțiune marcată ca având nevoie de TSO, iar un modul dat doar cu numele nu are niciuna. Patch local 0004.
  Verificat cu `scripts/fex-tso-check.py` (numără instrucțiunile din codul tradus): `sleep` cu `libc.so.6` în
  listă → în codul din libc 0 `LDAPR`/`STLR`, în `ld-linux` 8403.
- **Capcană la testare:** un proces pornit de Steam rulează pe binarul FEX al lui Steam (FEX se re-execută din
  `/proc/self/exe`), deci un FEX nou copiat pe telefon e folosit abia după repornirea sesiunii.
- **Lista** (`FexConfig.SOURCE2_WITHOUT_TSO`, 18 biblioteci): client, server, engine2, panorama,
  rendersystemvulkan, materialsystem2, scenesystem, particles, animationsystem, vphysics2, worldrenderer,
  meshsystem, soundsystem, resourcesystem, panorama_text_pango, schemasystem, vscript, v8. Rămân cu TSO:
  `libtier0` (primitivele de fire), sistemul de fișiere, rețeaua, clientul Steam, SDL, libc, libstdc++.
- **Rezultat, scena cu zece eroi**, sesiuni alternate, ferestre de 10 s împerecheate după limita de frecvență:

  | limită nucleu rapid / mic | cu TSO peste tot | fără TSO în cele 18 |
  |---|---|---|
  | 1420 / 1612 MHz | 38,7 (7 ferestre) | 43,8 (5 ferestre) |
  | 1075 / 595 MHz | 36,0–37,8 (sesiuni anterioare), 37,5 | 42,4 (3 ferestre), 43,1 |

  Adică +13…15%. Dota încarcă, pornește scena și un meci privit.
- **Setare** „Memorie rapidă pentru Dota 2 și CS2 (experimental)”, **oprită implicit**: codul care se bazează pe
  ordinea memoriei x86 fără operații atomice poate greși rar; testat doar câteva minute. Pe Realme e pornită.
- `am start --es action steam` cu aplicația deschisă, după oprirea unei sesiuni pornite tot așa: aplicația
  deschidea „Avansat” și pornea Steam în ecranul de test (1280×720). Reparat (`onNewIntent`, `startSession`).

## N-042 · Meci real: memoria se termină  🧪 (Realme GT 12 GB, 2026-09-28)
- **Scena demo nu e reprezentativă.** Meci privit, minutul 39, scor 51–55, telefon la 46–47 °C (limite
  1075/595 MHz), cu așezarea firelor și fără TSO în cele 18 biblioteci: 13–15 cadre/s, cu căderi la 5–8.
  `console.log`: 224 × „Excessive frame time” (cadre de peste 100 ms), „Slamming client tick to server tick”,
  iar după 100 s serverul închide conexiunea: `NETWORK_DISCONNECT_OVERFLOW` (clientul nu ține pasul).
- **Reprodus „un cadru la 5–10 secunde”** (raportat de utilizator): la al patrulea meci încărcat în aceeași
  sesiune, 0–1 cadre în 10 s. `MemAvailable` 17 MB, `MemFree` 26 MB, swap liber 1,8 din 7,5 GB; jocul 4,9 GB în
  RAM + 3,4 GB în swap = **8,4 GB**. Firul principal al jocului 10–15% (stă în erori de pagină), iar procesorul
  e luat de `kswapd0`, `kshrink_slabd`, zece fire `kverityd`, `lmkd` și de aplicațiile pe care Android le omoară
  și le repornește (`lowmemorykiller: Kill … oom_score_adj 0`). La un minut după, Android a închis toată sesiunea.
- **Memoria jocului pe etape:** meniul principal 4,5 GB; după primul meci privit 7,2 GB; după al patrulea 8,4 GB.
  La meniu: 3,55 GB memorie anonimă fără nume (din care 1,84 GB în patru zone de peste 512 MB și 0,74 GB în 798
  de zone de 1–8 MB), KGSL 0,36 GB, biblioteci 0,23 GB, cod JIT 0,22 GB, alocatorul FEX 0,18 GB.
- **Primul „Watch in-game” dă mereu „Overflow error”:** serverul închide la ~30 s după `ProcessServerInfo`, cât
  timp clientul încarcă harta. A doua încercare reușește (resursele sunt deja în memorie).
- **De făcut:** de aflat ce sunt zonele mari (alocatorul jocului, memoria gazdei Mesa sau FEX) și de ce cresc
  între meciuri; o măsurătoare repetabilă pe un replay; un avertisment în aplicație când memoria liberă scade.
  🟨 Pe un telefon de 12 GB un meci lung nu e încă de încredere.

## N-043 · Aplicația în două limbi, texte pentru public  ✅ (Realme GT, 2026-09-28)
- Textele aplicației sunt în resurse Android: `res/values/strings.xml` (engleză, implicit) și
  `res/values-ro/strings.xml` (română), 190 de texte. O limbă nouă = o copie tradusă a fișierului.
- `str(R.string.nume, argumente)` (`Strings.kt`) merge și în codul fără `Context` (fire de lucru, erori).
  Setări › Aspect › Limbă: Automat (limba telefonului) / English / Română; se schimbă pe loc.
- **Rapoartele și jurnalul sesiunii sunt mereu în engleză** (`Strings.inEnglish`), ca să le poată citi oricine
  le primește. Ecranele din „Avansat” sunt doar în engleză (unelte de dezvoltator).
- Texte aduse la zi cu ocazia asta: instalarea se face toată pe telefon (nu mai apar scripturile de PC),
  memoria (Dota ocupă 5–8 GB; avertisment sub 16 GB), Adreno 840 verificat, Samsung nu mai e „netestat”.
- `App` (clasă `Application`) inițializează textele și setările înaintea activității, a serviciului și a
  receptorului de actualizare.
- README în engleză (`README.md`) și română (`README.ro.md`); pagina versiunii are acum numărul versiunii,
  ce să instalezi și suma SHA-256.
- ✅ Văzut pe telefon: Acasă și Setări în engleză (telefonul e pe en-GB), apoi în română după alegerea din
  Setări, fără repornire.

## N-044 · Memoria video, replay ca test repetabil, profil într-un meci real  ✅/🧪 (Realme GT, 2026-09-28)
- **Test repetabil pe un meci real:** replay-ul unui meci privit (`replays/<id>.dem`, butonul „Download Replay”
  de pe ecranul de final), pornit direct cu opțiunea de lansare `+playdemo replays/<id>.dem`
  (`files/launch-options-570.txt`), apoi cursorul de timp tras mereu în același loc (minutul 22, scor 28–17).
  Scena demo cu zece eroi cere de două ori mai puțin de la firul principal decât un meci.
- **Memoria video era partea nevăzută.** `/proc/meminfo` `GPUTotalUsed`: 3,5–3,7 GB cu Dota într-un meci, 0,17 GB
  fără joc. În `smaps` se văd doar zonele KGSL mapate în proces (0,3–1,3 GB); texturile stau în alocări KGSL
  fără mapare. Pe telefon e aceeași memorie ca restul, deci jocul ocupa de fapt 5,6 + 3 ≈ 8,6 GB.
- **Cauza:** Turnip anunță ca memorie video 75% din memoria sistemului (`os_gpu_heap_size_calculate`), adică
  8,6 GB pe un telefon de 12 GB, iar Source 2 își dimensionează texturile după ea. Opțiunea Mesa
  `heap_memory_percent` se poate da și ca variabilă de mediu.

  Măsurat în același replay, de la pornirea jocului (minute de la pornire → `GPUTotalUsed`):

  | anunțat | 2½ min | 5 min | 8 min | 11 min | 16 min | rezultat |
  |---|---|---|---|---|---|---|
  | 75% = 8,6 GB | 2,78 | 3,24 | 3,63 | 3,82 | 4,29 și crește | merge |
  | 40% = 4,6 GB | 2,81 | 3,24 | 3,71 | 4,06 | 4,05–4,11, se oprește | merge |
  | 20% = 2,4 GB | — | — | — | — | — | `VK_ERROR_OUT_OF_DEVICE_MEMORY` la încărcarea meciului |

  Din raportul jocului la eroare: texturi 393 MB (bugetul de streaming 412 MB, ~17% din cât e anunțat),
  vertex buffers 172 MB, **26 de zone de transfer („Staging”) mapate permanent, 872 MB**, restul rezerve ale
  alocatorului (VMA).
- **Greșeala mea:** am anunțat întâi o economie de 0,7 GB (2,9 față de 3,6 GB), comparând o citire de la 3 minute
  cu una de la 15. La același minut diferența e de 0,0–0,1 GB, iar la platou de 0,2–0,3 GB. Memoria video crește
  un sfert de oră după pornirea meciului, deci se compară doar citiri de la același minut.
- 🟨 **Setare „Mai puțină memorie video pentru jocuri”, oprită implicit** (0.3.85 a avut-o pornită):
  `heap_memory_percent` = 40% din memoria telefonului, cel puțin 4 GB (`MemoryWatch.videoMemoryShare`).
  Câștigul e mic, iar jocul a ajuns la 0,5 GB de plafon; un joc care îl depășește se închide.
- ✅ **Avertisment de memorie** (`MemoryWatch`): când `MemAvailable` e sub 500 MB sau swap-ul liber sub 600 MB de
  trei ori la rând, ecranul de joc arată un mesaj, iar jurnalul notează valorile. Raportul are linia
  „memory watch”. 🟨 Pragurile vin din două sesiuni pe un singur telefon.
- **Memoria obișnuită a jocului** (4,4 GB la meniu, 4,5–4,9 GB în meci) e date reale: în patru zone de câte
  1 GB am găsit texte de interfață, nume de resurse, shadere; paginile prezente sunt pline de zerouri în
  proporție de 0–6%. FEX adaugă ~0,5 GB (cod tradus 0,2, alocator 0,2, tabele 0,1).
- **Interfața Steam înghețată în timpul jocului** (`SIGSTOP` pe `steamwebhelper`): 19,4 față de 18,2 cadre/s,
  în marja de zgomot; Steam nu a repornit procesele în 90 s. Nu e folosit.
- 🧪 **Profil pe biblioteci în meci** (replay, fir principal, 11152 de eșantioane): `libclient` 46,2%,
  `libparticles` 10,0%, `libpanorama` 6,5%, `libtier0` 6,3%, `libanimationsystem` 5,1%, kernel 4,7%,
  `libengine2` 3,2%, `librendersystemvulkan` 2,8%, `libc` 2,5%, FEX 2,4%, Turnip 0,3%. Pe blocuri
  (`FEX_BLOCKJITNAMING=1`, `build/measure/byblock.py`): primele 10 blocuri 12,5%, primele 100 37%, primele
  1000 75%. Cele mai fierbinți funcții din `libclient` sunt cod C++ obișnuit cu `lock cmpxchg` / `lock xadd`;
  nicio instrucțiune anume nu iese în evidență.
- 🧪 **TSO oprit și în `libtier0`, `libc`, `libstdc++`, `libm`:** jocul pornește și rulează, 21,7 față de 21,4
  cadre/s la aceleași limite, adică nimic măsurabil. Lista rămâne la 18.
- **Frecvența nucleului rapid** stă la limita termică (1305 din 1305 MHz în 15 din 20 de citiri), deci guvernorul
  nu ține nimic în rezervă; limita o pune temperatura. Nucleele mici rămân la 1804 MHz (maximul lor) și când
  cele mari sunt limitate la 1305.
- **Replay, minutul 22, cu toate setările din aplicație:** 25–28 cadre/s cu telefonul răcit (limite
  2150/1804 MHz), 20–23 când e cald (1305/1804 MHz).
- De 185 de ori pe secundă firul principal face `access("/dev/random")` + `getrandom` + `getpid` + `getuid`
  (generare de numere aleatoare): ~1000 de apeluri de sistem pe secundă, 1–2% din fir. Lăsat așa.
- 🧪 **Memoria video crește cu timpul, nu cu scena.** Cu replay-ul pe pauză (imagine fixă) crește la fel:
  2,79 GB la 3½ minute de la pornirea jocului, apoi +350, +280, +190, +130, +100, +80, +70 MB pe minut, până la
  4,00 GB la 10½ minute. Creșterea care se stinge arată o umplere treptată (streaming de texturi), nu o scurgere
  de memorie în driver sau în prezentare, care ar crește constant.
- 🧪 **`setting.mem_level` și `setting.gpu_mem_level`** din `video.txt` (jocul le pune pe 2, maxim, după cât
  anunță driverul): puse pe 0 rămân așa, dar nu schimbă nimic măsurabil: memorie video 3,91 GB la 10 minute
  (3,95–4,00 cu 2), memorie obișnuită 5,98 GB (6,23 cu 2, în alt moment al meciului). Se scriu cu jocul oprit;
  la închidere jocul își rescrie fișierul. Lăsate pe 2.
- **Concluzie pentru telefoane de 12 GB:** Dota ocupă ~6 GB de memorie obișnuită și ~4 GB de memorie video după
  un sfert de oră de meci, lângă ~1,7 GB Steam și Android. Încape doar prin swap comprimat (zram: 1,8 GB fizici
  pentru 6,8 GB mutați), iar swap-ul liber stă la ~1 GB. Nicio setare încercată nu schimbă asta cu mai mult de
  0,3 GB. Avertismentul de memorie și repornirea între meciuri rămân soluția.
- 🧪 Alte încercări în același replay, nepăstrate: `-threads 4` (3 fire ajutătoare în loc de 7): 21,2 față de
  21,0 cadre/s, dar meciul se încarcă în 180 s în loc de 65–95; firele ajutătoare și pe nucleele mici: 22,3
  față de 23,9; fără constrângerea de frecvență a GPU-ului: 15,2 față de 19–23 (constrângerea rămâne pornită).
- **Replay cinci minute la rând** (de la minutul 22, 720p, toate setările): 21,6 cadre/s în medie, 21,0 în a doua
  jumătate; bateria de la 41,9 la 45,7 °C; limitele nucleului rapid 1305–1420 MHz.
- O invitație de party primită în timpul testelor acoperă meniul și replay-ul cu o fereastră care nu dispare
  singură minute întregi. Scripturile de test verifică imaginea înainte de apăsări și s-au oprit; invitația nu
  a fost nici acceptată, nici refuzată (a expirat, apoi fereastra a fost închisă cu „Dismiss”).

## N-045 · Taste pe ecran, joc prin atingere  ✅ (Realme GT, 2026-09-28)

- **Ce este:** două coloane de taste, în stânga și în dreapta imaginii (`OnScreenKeys.kt`), pornite din
  Setări › Joc prin atingere sau din meniul din joc. Aranjamentul e un rând de nume despărțite prin spații,
  implicit pentru Dota 2: stânga `Esc Q W E R D F`, dreapta `Mouse Swipe A S Alt Ctrl` (plus butonul de meniu,
  care altfel ar sta peste taste). `Shift`, `Ctrl`, `Alt` rămân apăsate până la a doua atingere.
- **Atingerea pe imagine:** tasta `Mouse` alege butonul apăsat de o atingere (stânga/dreapta); atingerea cu două
  degete apasă celălalt buton. Tasta `Swipe` alege între rotiță și tragere cu butonul din mijloc, care în Dota
  mută camera. Apăsarea lungă urmată de mișcare rămâne tragere cu butonul stâng (selecție).
- **Imaginea se îngustează** cu 56 dp pe fiecare parte cât timp tastele sunt pornite: pe Realme GT 1344x720 în
  loc de 1584x720. Rezoluția se alege la pornirea sesiunii: tastele pornite din meniu în timpul jocului
  stau peste marginile imaginii, transparente, până la următoarea pornire (verificat în Steam).
- 🧪 **Compose nu poate împărți degetele între taste și imagine.** Cu un deget pe o tastă desenată în Compose,
  al doilea deget pus pe imagine (un `AndroidView`) nu ajunge deloc la ea: vederea primește `ACTION_POINTER_DOWN`
  fără un `ACTION_DOWN` înainte, iar `ViewGroup` îl oprește. Verificat pe telefon: cursorul nu s-a mișcat.
  Rezolvare: `MainActivity.dispatchTouchEvent` trece fiecare eveniment prin `OnScreenKeys.filter`, care scoate
  degetele de pe taste și reface evenimentul pentru restul ferestrei, ca și cum celelalte degete ar fi singure.
  Tastele din Compose sunt doar desenate.
- **Test cu mai multe degete fără mâini:** `adb shell input` știe un singur deget, iar `sendevent` e refuzat
  de SELinux pe Realme. `TouchScript.kt` redă în fereastra aplicației un șir de pași:
  `am start --activity-single-top -n ro.cobrabm.fexdroid/.MainActivity --es touches "d0:168,1000 w400 d1:900,620 w80 u1 w400 u0"`
  (`d` deget jos, `m` mutat, `u` ridicat, `w` așteptare în ms). Fără `--activity-single-top` intenția nu ajunge
  la o activitate deja deschisă.
- **Verificat pe telefon, în Steam:** tastele Q, W, E scriu în căutare; Ctrl apoi A selectează textul; cu tasta
  F ținută, o atingere pe imagine cu alt deget e un clic în locul atins; atingerea cu două degete apasă
  celălalt buton.
- **Verificat în Dota 2 (demo de erou, 1344x720):** atingere cu butonul drept: eroul merge în locul atins;
  glisare cu „cameră”: harta se mută după deget; tasta W apoi atingere cu două degete: vraja (Ice Vortex) a
  fost lansată în locul atins, mana a scăzut de la 1587 la 1511.
- **Neverificat:** cu degete adevărate (toate apăsările au fost redate prin adb și `TouchScript`); un meci
  jucat așa; alt telefon. Camera din Dota se mută și când cursorul ajunge la marginea imaginii, deci o
  atingere lângă margine o pornește: se oprește din setările jocului (Options › Camera › Edge Pan).
- Meniul din joc a devenit mai înalt decât ecranul odată cu rândul nou și acum derulează.

## N-046 · Runda de performanță din 28 septembrie seara: ce limitează și ce nu  🧪 (Realme GT, 2026-09-28)
Cerința: mai multe cadre la aceeași rezoluție, din emulare și driver. Telefonul pe USB la PC (încarcă puțin:
bateria a scăzut de la 100% la 43% în două ore și jumătate de teste cu pauze).

- **Măsurătoare care nu depinde de temperatură** (`build/measure/cyc.sh`): replay-ul la momentul obișnuit, cu
  jocul ținut la 15 cadre/s (`+fps_max 15`; cele 30 de tick-uri pe secundă ale meciului sunt și ele fixe), apoi
  40 s de contoare (`simpleperf stat --per-thread`). Aceeași muncă, deci instrucțiunile și ciclurile se pot
  compara între rulări. Trei rulări cu aceleași setări: firul principal 0,90 / 0,98 G instrucțiuni/s, tot jocul
  2,57 / 2,33, adică ±10% de la o rulare la alta (locul din replay diferă cu câteva secunde). Diferențe sub
  10% nu se văd cu ea.
- ⚠ **`+fps_max` din linia de comandă rămâne salvat** în `machine_convars.vcfg`: jocul a pornit apoi limitat
  la 15 cadre/s și fără opțiune. Pus la loc pe 120 de mână. Scripturile de test trebuie să dea mereu valoarea.
- **Replay pe pauză: 49–55 cadre/s; același loc, în redare: 23–25**, la aceleași limite de frecvență
  (1555 MHz). Deci randarea și drumul de afișare nu limitează; jumătate din timpul firului principal e simularea
  meciului (30 de tick-uri pe secundă, oricâte cadre ar fi).
- **Firul principal nu e ocupat tot timpul:** 75–78% dintr-un nucleu în redare, 65% pe pauză. Restul așteaptă
  în `epoll_pwait` firele ajutătoare (28% din timp pe pauză, 10% în redare) și le trezește cu câte un `futex`
  (25 de treziri pe cadru).
- **Firele ajutătoare fac muncă adevărată**, nu așteaptă în buclă: `libparticles` 29%, `libclient` 17%,
  `libtier0` 9,5%, `libanimationsystem` 7%, `libscenesystem` 6%. `ThreadSpin` (buclă cu `rdtsc` + `pause`)
  e blocul cel mai fierbinte, dar are doar 3,9%.
- **Codul tradus al blocurilor fierbinți e aproape unu la unu** cu cel x86 (citit din memoria jocului,
  `build/measure/dis.sh`): o încărcare x86 devine un `ldr`, un `test` un `subs`, un salt un `b`. Fiecare bloc
  începe cu două instrucțiuni care țin minte blocul curent (pentru semnale); `ret` folosește stiva de perechi
  apel/întoarcere pe care FEX-2609 o are deja. Nu am găsit nimic de câștigat din configurare.
- 🧪 **AVX ascuns jocului** (`FEX_HOSTFEATURES=disableavx`): tot jocul 2,81 G instrucțiuni/s față de 2,33 și
  2,57, firul principal 1,05 față de 0,98 și 0,90. Nu e mai bine, probabil mai rău. AVX rămâne.
- 🧪 **Așezarea firelor**, pe replay-ul pus pe pauză, ferestre alternate de 20 s: firul de prezentare pe
  nucleele mici 55,0 față de 53,9 cadre/s (în zgomot, iar măsurătoarea se apropie de plafonul ei de 60); firele
  de randare pe nucleele mici 41,2 (mai rău); firele jocului și pe nucleul firului principal 51,9 (mai rău).
  Rămâne „big”. `files/tuner.txt` primește acum și reguli după numele firului (`masks 80 70 0f WSI:0f`,
  al treilea câmp e prioritatea), doar pentru măsurători.
- **GPU-ul e ocupat 26–30%** la frecvența maximă, în redare. Nu limitează.
- **Cine mai consumă procesor în timpul meciului** (telefon încins, nuclee mici la 595 MHz): jocul 236%,
  `hybridswapd` 32%, aplicația (puntea de imagine) 32%, Xvfb 19,5%, Steam 16%, sunetul 37% în total
  (`audioserver` 15,6%, serviciul audio 12,3%, PulseAudio 9,3%), `surfaceflinger` 13%. Procentele sunt mari
  fiindcă nucleele mici merg la o treime din frecvență; ca putere înseamnă puțin.
- **Căldura:** la 46–47 °C pe baterie limitele ajung la 1075 / 1075 / 595 MHz (nucleul rapid, cele mari, cele
  mici), din 2841 / 2419 / 1804. De la rece la încins trec 10–15 minute de joc.
- ✅ **Memoria lui Steam:** 2,9 GB cu interfața clasică deschisă pe Magazin (pagina Magazinului singură ține
  0,4 GB), 2,4 GB deschisă pe Bibliotecă, 2,1 GB în Big Picture. Aplicația pornește acum interfața clasică
  direct pe Bibliotecă (`steam://nav/games`); Big Picture rămâne cum era. `-silent` nu are efect la noi.
- **Concluzie:** cadrele sunt limitate de firul principal al jocului, care rulează cod tradus fără puncte
  fierbinți, pe un nucleu pe care telefonul îl încetinește la mai puțin de jumătate când se încinge. Din
  setările FEX, din așezarea firelor și din driver nu mai e nimic de câteva procente de luat pe telefonul
  ăsta. Ce ar mai aduce cadre: mai puțină căldură (răcire, fără încărcare în timpul jocului) și schimbări în
  generatorul de cod al lui FEX, care nu sunt de o seară.
- Unelte: `scripts/perf-by-block.py` (profil pe blocuri și pe biblioteci din `FEX_BLOCKJITNAMING=1`); locale,
  în `build/measure/`: `cyc.sh`, `threads.sh`, `dis.sh`, `procmem.py`.

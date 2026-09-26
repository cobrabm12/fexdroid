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
  vorbește protocolul fxshmd, mapează segmentul și copiază cadrele (BGRX → RGBX) în `ANativeWindow`, la 30 fps.
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

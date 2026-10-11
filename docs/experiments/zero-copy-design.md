# Cadrele jocului pe ecran fără copii prin procesor (zero-copy): analiză și plan

Scris 2026-10-11, pe `main` = `b0cad6c` (Mesa 26.2.3 cu patch-urile 0001–0008). Doar cercetare: nimic
din acest document nu e încă testat pe telefon. Ideea vine din DroidDeck (`ZERO_COPY_SPIKE.md`,
`ahb_swapchain.c`, `sc_layer.c`, GPL-3.0); a fost citită ca referință, nimic nu e copiat. fexdroid rămâne MIT.

## 1. Unde se duce timpul unui cadru azi

Swapchain-ul X11 rulează cu prezentare software (`MESA_VK_WSI_DEBUG=sw`). Codul Mesa e cel nativ arm64:
jocul x86 cheamă Vulkan prin thunk-ul FEX, deci WSI-ul, memcpy-urile și socketurile rulează pe arm64,
fără traducere.

Drumul unui cadru de 1600×720 (4,6 MB în BGRX), cu „Cadre direct de la joc” pornit (patch `0007`):

| # | Pas | Unde | Cine lucrează | Dovada |
|---|---|---|---|---|
| 1 | Jocul desenează în imaginea swapchain-ului (tiled, UBWC) | GPU | – | – |
| 2 | Imaginea se copiază într-un buffer liniar, vizibil procesorului (`vkCmdCopyImageToBuffer`) | GPU | ~0,2–0,5 ms GPU, 9 MB trafic | `wsi_cpu_image_needs_buffer_blit()` întoarce mereu `true` pentru Turnip (nu are `wants_linear`), deci `WSI_SWAPCHAIN_BUFFER_BLIT`; memoria e aleasă de `wsi_select_host_memory_type()` cu `HOST_CACHED` (`wsi_common.c:3075`) |
| 3 | Firul de prezentare așteaptă blit-ul (fence) | CPU (așteptare) | – | patch `0005` mută așteptarea pe firul de prezentare |
| 4 | `memcpy` din buffer în locul liber din `memfd` + desenarea cursorului | CPU, firul de prezentare Mesa | 4,6 MB/cadru | `fxpresent_frame()`, patch `0007` |
| 5 | Aplicația copiază locul publicat în `ANativeWindow`, cu verificări și schimbarea ordinii culorilor pe fiecare pixel | CPU, firul punții | 4,6 MB citiți + 4,6 MB scriși în memorie gralloc | `display_bridge.c:154–172`; N-051: aplicația urcă de la 20% la **55% dintr-un nucleu** |
| 6 | SurfaceFlinger compune stratul | DPU/GPU | – | neschimbat |

Pe drumul vechi (prin Xvfb, implicit azi) se adaugă `PutImage` prin socketul X, copia din Xvfb și o a
treia copie spre ecran (N-048: patru copii pe cadru).

**Ce s-ar câștiga în Dota.** Limita măsurată e firul principal al jocului (N-034: 88–96% dintr-un nucleu)
plus frânarea termică. Copiile de mai sus rulează pe alte fire, deci nu stau direct în calea firului
principal. Câștigul vine indirect: mai puțină căldură și lățime de bandă pentru memorie (aproximativ 0,5–1
nucleu ocupat cu copii la 25 cadre/s), deci frecvențe mai mari pentru firul principal. Estimare: **0–10%
cadre/s în Dota**, cu certitudine doar pentru procesorul punții (55% → aproape 0) și o latență mai mică
cu un cadru. Prima măsurătoare cu cadre directe (N-051) a dat 21,7 vs 24,1 cadre/s *în defavoarea*
drumului direct, din cauza buclei pe pixel de la pasul 5: drumul de azi nu e încă un punct de comparație
bun. Interfața Steam (CEF fără GPU, desenată prin `PutImage`) **nu** câștigă nimic din acest proiect.

## 2. Ce știe deja Turnip compilat pentru glibc pe KGSL

Verificat în sursa noastră (`build/mesa/mesa-26.2.3`):
- **Import dma-buf:** `kgsl_bo_init_dmabuf()` (`tu_knl_kgsl.cc:393`) face `IOCTL_KGSL_GPUOBJ_IMPORT` cu
  `KGSL_USER_MEM_TYPE_DMABUF`, apoi `IOCTL_KGSL_GPUOBJ_INFO` pentru adresa GPU. Expus ca
  `VK_EXT_external_memory_dma_buf` (`tu_device.cc:329`).
- **Layout explicit:** `VK_EXT_image_drm_format_modifier` e activ (`tu_device.cc:340`), deci putem crea o
  imagine cu pitch-ul exact al buffer-ului gralloc (`DRM_FORMAT_MOD_LINEAR`, apoi poate
  `DRM_FORMAT_MOD_QCOM_COMPRESSED` pentru UBWC).
- **Fence spre Android:** `VK_KHR_external_semaphore_fd` și `VK_KHR_external_fence_fd` sunt active
  (`tu_device.cc:226, 230`); `timestamp_to_fd()` (`tu_knl_kgsl.cc:764`) face un `sync_file` adevărat cu
  `KGSL_TIMESTAMP_EVENT_FENCE`. Exact ce așteaptă `ASurfaceTransaction_setBuffer` ca fence de achiziție.
- **AHB direct, nu:** varianta pentru Linux nu poate chema `AHardwareBuffer_*` (ramura `DETECT_OS_ANDROID`
  din `tu_device.cc:3714` dă `VK_ERROR_FEATURE_NOT_PRESENT`). Primim deci doar fd-ul dma-buf și layout-ul.
- **Cum arată un buffer gralloc Qualcomm:** `u_gralloc_fallback.c:151–155` din aceeași sursă: în
  `native_handle`, după fd-uri, primul int e magia `'gmsm'`, al doilea are bitul UBWC `0x08000000`.
  `data[0]` e fd-ul dma-buf al imaginii.

Concluzie: **nu lipsește nimic în driver.** Lipsesc un cârlig în WSI și partea din aplicație.

## 3. Variante

### A. Blit pe GPU într-un AHardwareBuffer al aplicației (recomandată)
Swapchain-ul jocului rămâne neschimbat (imagini tiled/UBWC, rapide pentru GPU). În pasul 2, în loc de
`vkCmdCopyImageToBuffer` într-un buffer al procesorului, WSI-ul face `vkCmdBlitImage` într-o imagine
importată dintr-un AHB al aplicației, trimite fd-ul fence-ului și numărul locului, iar aplicația pune AHB-ul
pe ecran cu `ASurfaceControl`.
1. **Aplicația** alocă 3 AHB-uri `R8G8B8X8_UNORM` la mărimea jocului, cu
   `GPU_COLOR_OUTPUT | GPU_SAMPLED_IMAGE | COMPOSER_OVERLAY`. La început și `CPU_READ_RARELY`, ca gralloc să
   aleagă un layout liniar (pitch din `AHardwareBuffer_describe().stride`).
2. **Fd-ul dma-buf:** NDK-ul nu expune `AHardwareBuffer_getNativeHandle`. Aplicația face
   `AHardwareBuffer_sendHandleToUnixSocket` pe un `socketpair` propriu și citește mesajul: fd-urile vin ca
   `SCM_RIGHTS`, iar datele sunt `GraphicBuffer::flatten` (magia `'GB01'`, mărimi, `numFds`/`numInts`, apoi
   int-urile din `native_handle`). Din ele ia `fds[0]` și cele două int-uri QTI. Spre Mesa trimite un
   protocol propriu, simplu: `fxpresent` v2 = `{slot, width, height, pitch, modifier, size}` + fd. Parsarea
   formatului Android rămâne astfel în aplicație (bionic), nu în glibc.
3. **Mesa (patch nou `0009`, în `wsi_common_x11.c` sw):** dacă aplicația răspunde cu v2, WSI-ul importă cele
   3 fd-uri ca `VkDeviceMemory` (`VkImportMemoryFdInfoKHR`, tip `DMA_BUF`) cu imagini
   `VK_IMAGE_TILING_DRM_FORMAT_MODIFIER_EXT` și layout explicit. La prezentare face blit-ul (cu schimbarea
   BGRA → RGBA gratis, eventual și scalarea la ecran), cere un `sync_file` pentru submit și trimite
   `{slot, frames}` + fd-ul fence. Nu mai așteaptă GPU-ul pe procesor. Fără v2: drumul `memfd` de azi.
4. **Aplicația (`display_bridge.c` v2):** un `ASurfaceControl` copil al SurfaceView-ului
   (`ASurfaceControl_createFromWindow`, API 29). La fiecare cadru `ASurfaceTransaction_setBuffer(sc, ahb,
   fence)` + `apply`. Un loc se eliberează doar după ce SurfaceFlinger l-a terminat
   (`ASurfaceTransaction_setOnComplete` + `ASurfaceTransactionStats_getPreviousReleaseFenceFd`), iar Mesa
   așteaptă fence-ul de eliberare înainte să refolosească locul. Pe API 28 (minSdk-ul nostru) rămâne v1.
5. **Cursorul:** nu se mai desenează în cadru. Devine un strat separat, mic (AHB 64×64 cu imaginea din
   XFixes, mutat cu `ASurfaceTransaction_setPosition`), trimis doar când se schimbă imaginea sau poziția.

Rămâne o singură copie, pe GPU (~0,3 ms), iar procesorul iese complet din drum.

### B. Swapchain-ul jocului direct în AHB (varianta DroidDeck)
Imaginile swapchain-ului sunt chiar AHB-uri (UBWC, alese de gralloc), iar SurfaceFlinger le ia fără nicio
copie. Câștigă în plus și blit-ul de 0,3 ms, dar: layout-ul UBWC al Turnip trebuie să fie identic cu cel al
gralloc (DroidDeck o face, dar cu Turnip-ul lor), mărimea swapchain-ului e impusă de joc, cursorul și
ferestrele X peste joc trebuie compuse altfel, iar o greșeală de layout se vede ca imagine stricată.
**Pas următor după A**, nu primul.

### C. Fără schimbări în Mesa: puntea copiază mai repede
Bucla pe pixel din `display_bridge.c` → copiere pe rânduri cu NEON, swizzle-ul mutat în Mesa (blit-ul GPU poate
scrie direct în RGBX). Câștig sigur pentru procesorul punții (55% → ~10–15%), o zi de lucru, fără risc. E
de făcut oricum, ca drum de rezervă pentru telefoanele pe care A nu merge.

## 4. Plan cu puncte de verificare pe telefon

| Pas | Ce | Verificare pe Realme | Efort |
|---|---|---|---|
| 0 | Varianta C (copiere pe rânduri, swizzle în blit) | `fxctl`: CPU-ul punții, A/B direct vs X, 3+3 | 1 zi |
| 1 | **Spike:** aplicația alocă un AHB, extrage fd-ul (§3.A.2), îl dă unui program arm64 din rootfs (`fxahbtest`) care îl importă în Turnip, îl umple cu roșu (`vkCmdClearColorImage`), trimite fence-ul; aplicația îl arată cu `ASurfaceControl` | `adb exec-out screencap`: ecran roșu, fără erori în logcat | 1–2 zile |
| 2 | La fel, fără `CPU_READ_RARELY` (UBWC): import cu `DRM_FORMAT_MOD_QCOM_COMPRESSED` | aplicația desenează un model cunoscut cu GLES, Turnip îl citește și îl compară | 1–2 zile (opțional) |
| 3 | Protocolul `fxpresent` v2 + patch Mesa `0009` (blit în AHB, `sync_file`, așteptarea eliberării) | `vkcube` x86 prin FEX: 3 porniri, imagine corectă, fără scurgeri de fd | 2–3 zile |
| 4 | `display_bridge.c` v2 cu `ASurfaceControl`, eliberarea locurilor, căderea pe v1 | Dota: imagine, cursor, ieșire din joc, schimbarea rezoluției | 2 zile |
| 5 | Cursorul ca strat separat | Dota și Steam în Big Picture | 1 zi |
| 6 | Măsurătoare: X / direct v1 / v2, cu `tools/fxctl` (3+3 ABBA, telefon la priză) | cadre/s, CPU-ul punții, temperatura | 0,5 zile |

Total: **~1,5–2 săptămâni** pentru A cu layout liniar; UBWC încă 2–4 zile.

## 5. Riscuri
- **Formatul `GraphicBuffer::flatten`** poate diferi între versiuni de Android sau producători (S26 cu
  Android 16, HyperOS pe Xiaomi). Verificăm magia, lungimile și `numFds`, iar la orice nepotrivire aplicația
  rămâne pe v1.
- **Gralloc poate ignora `CPU_READ_RARELY`** și alege totuși UBWC: citim bitul QTI și refuzăm importul
  liniar dacă e setat.
- **Fence-uri:** dacă `KGSL_TIMESTAMP_EVENT_FENCE` eșuează (`timestamp_to_fd` întoarce -1), trimitem -1 și
  așteptăm pe procesor, ca azi.
- **SELinux:** fd-urile circulă între procese ale aceleiași aplicații (același domeniu `untrusted_app`), deci
  nu ar trebui să fie refuzate. Primul lucru verificat în spike.
- **Mali (Xiaomi):** fără Turnip, deci fără v2. Rămâne drumul de azi.
- **Câștigul în Dota poate fi mic** (§1). Măsurat cu A/B, nu presupus.

## 6. Recomandare
Întâi C (o zi, câștig sigur pentru procesor), apoi spike-ul 1 (1–2 zile), care spune dacă importul, fence-ul
și `ASurfaceControl` merg pe Realme. Abia după spike decidem dacă merită restul lui A. B se ia în calcul doar
dacă A merge și măsurătoarea arată că blit-ul GPU contează.

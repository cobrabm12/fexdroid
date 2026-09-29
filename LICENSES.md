# LICENSES

Componentele pe care le folosim sau le luăm în calcul. Nicio componentă nu e integrată încă.
Orice conflict îl semnalez înainte de integrare.

| Componentă | Licență | Cum o folosim | Obligații / riscuri |
|---|---|---|---|
| Cod propriu fexdroid | MIT (decis 2026-09-25) | — | — |
| FEX-Emu | MIT | binar în rootfs ARM64, compilat de noi | păstrat textul licenței |
| Mesa (Turnip) | MIT (în mare parte; unele fișiere BSD/Khronos) | `libvulkan_freedreno.so` | păstrat textul licenței |
| Patch-uri Mesa (`patches/mesa/`) | MIT | 0002–0004 = backport-uri neschimbate din Mesa `main` (commit-uri `c96b2e4c`, `35f59101`, `118ec7f4`); 0001 și 0005–0007 = patch-uri proprii fexdroid | aceeași licență ca Mesa; niciun patch din repo-urile comunității (mesa-unified / Banners-Turnip) nu a fost preluat (vezi NOTES N-015) |
| glibc | LGPL-2.1+ | în rootfs, posibil patch-uit | oferim sursa + patch-urile noastre |
| Debian (rootfs) | mix (GPL/LGPL/MIT/…) | redistribuim pachete binare | trebuie oferite sursele pachetelor (sau link către snapshot.debian.org cu versiunile exacte) |
| PulseAudio | LGPL-2.1+ | în rootfs | idem glibc |
| Xvfb / X.Org | MIT/X11 | în rootfs | păstrat textul licenței |
| proot (dacă îl folosim) | GPL-2.0 | binar separat, doar unealtă de depanare | separat = agregare; dacă îl legăm în aplicație, aplicația ar deveni GPL-2 |
| zstd / libarchive | BSD / BSD-2 | extragere rootfs (JNI) | păstrat textul licenței |
| Winlator | LGPL-2.1 (după GitHub API) | **doar referință de arhitectură** | nu copiem cod; dacă am copia, acele fișiere rămân LGPL |
| GameNative | GPL-3.0 | **doar referință** | nu copiem cod (GPL-3 ar contamina aplicația) |
| AOSP bionic seccomp lists (`third_party/aosp-bionic-seccomp`) | Apache-2.0 | doar date de intrare pentru generatorul tabelului | păstrăm sursa și licența |
| Steam / Dota 2 | proprietar Valve | **NU se include** | utilizatorul le instalează singur |

## Conflicte semnalate până acum
Niciunul, cu condiția ca proot și orice cod GPL să rămână binare separate și să nu copiem cod din GameNative.

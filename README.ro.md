# fexdroid

**Jocuri Linux pentru PC pe un telefon Android, rulate chiar pe telefon.** Fără streaming, fără PC, fără root.

fexdroid este o aplicație Android care pornește versiunea de Linux a lui **Steam**, și jocuri din el, pe un telefon ARM64. Codul x86 este tradus de [FEX-Emu](https://github.com/FEX-Emu/FEX); grafica trece prin [Mesa Turnip](https://docs.mesa3d.org/drivers/freedreno.html), driverul Vulkan deschis pentru Adreno. Jocul cu care e construită și măsurată este **Dota 2**.

[English](README.md)

> **Alfa, pentru testeri.** Merge pe telefoanele de mai jos, cu limitele descrise pe pagina asta. Vor fi asperități; trimite rapoarte, ele duc proiectul mai departe.

## Ce merge azi

| | Stare |
|---|---|
| Clientul Steam | Se instalează singur de la Valve la prima pornire; autentificare, bibliotecă, magazin, descărcări |
| Instalarea jocurilor | Din Steam, pe telefon, prin Wi-Fi |
| Dota 2 | Pornește din Steam, online; demo de erou, meciuri live și replay-uri |
| Control | Atingere, mouse și tastatură pe USB/Bluetooth |
| Sunet | Da |
| Actualizări | Din aplicație |
| Limbi | Engleză, română |

Încă nu: controale de joc pe ecran și gamepad, un meci jucat de la cap la coadă pe fiecare telefon testat, Samsung DeX.

## Telefoane testate

| Telefon | Cip | Android | Rezultat |
|---|---|---|---|
| Realme GT 5G | Snapdragon 888, Adreno 660, 12 GB | 14 | Dota 2 din Steam. Replay-ul unui meci real, minutul 22: 25–28 cadre/s cu telefonul rece, 20–23 când e cald. Scena de test cu zece eroi: 36–44 |
| Galaxy S26 Ultra | Snapdragon 8 Elite Gen 5, Adreno 840 | 16 | Steam și Dota 2 rulează; 25–32 cadre/s în meci, măsurat cu o versiune mai veche |

Cifrele sunt de pe telefoane, nu estimări. Cum au fost măsurate scrie în [NOTES.md](NOTES.md).

## Ce trebuie să aibă telefonul

- Cip **Snapdragon** cu **Adreno 6xx, 7xx sau 8xx** (Snapdragon 845 sau mai nou). Cu alte plăci grafice desenul se face pe procesor, mult prea lent pentru jocuri 3D.
- **Android 12 sau mai nou** (testat pe 14 și 16), pe 64 de biți, cu pagini de memorie de 4 KB (aplicația verifică).
- **12 GB de RAM sau mai mult** pentru Dota 2. Sub emulator jocul ocupă 5–6 GB, plus cam 3 GB de memorie video, care pe telefon e aceeași memorie.
- **Spațiu liber**: cam 5 GB pentru Steam, plus cât cere jocul (Dota 2: cam 70 GB).

Primul ecran al aplicației arată un verdict pentru telefonul tău.

## Instalare

1. Descarcă **`fexdroid-legacy.apk`** din [ultima versiune](https://github.com/cobrabm12/fexdroid/releases/tag/apk-latest) și instalează-l. Android îți cere să permiți instalarea din browser.
2. Deschide aplicația. La **Acasă › Stare**, apasă **Descarcă și instalează** la bibliotecile Steam (~250 MB).
3. Apasă **Pornește Steam**. Prima pornire descarcă clientul Steam de la Valve și durează câteva minute. Autentifică-te cu contul tău.
4. În Steam, instalează jocul: **Store › Dota 2 › Play Game › Install**. Folosește Wi-Fi și ține telefonul la încărcat.
5. Apasă **Play**.

Versiunile următoare se instalează din aplicație.

## Limite cunoscute

- **Căldura hotărăște numărul de cadre.** Telefoanele își reduc viteza procesorului când se încing; un Snapdragon 888 coboară la cam 40% din ea. Joacă de pe baterie dacă poți și scoate husa.
- **Memoria.** Pe un telefon de 12 GB, mai multe meciuri într-o sesiune pot epuiza memoria: jocul abia se mai mișcă, apoi Android îl închide. Aplicația te avertizează înainte. Închide celelalte aplicații și repornește fexdroid între meciuri.
- **Primul „Watch in-game”** dintr-o sesiune se termină des cu „Overflow error”, fiindcă harta se încarcă mai încet decât așteaptă serverul. A doua încercare merge.
- **Setările de performanță** sunt în Setări › Performanță. „Memorie rapidă pentru Dota 2 și CS2” a dat 13% mai multe cadre în testele noastre; e experimentală și oprită implicit.

## Cum raportezi o problemă

Din aplicație: **Acasă › Trimite raportul**, sau **Trimite jurnalul** pe ecranul care apare când un joc s-a închis. Raportul conține telefonul, jurnalul aplicației, sfârșitul jurnalelor Steam și câteva teste. Nu conține parole, dar jurnalele Steam pot conține numele contului: citește-l înainte să îl postezi public. Deschide un [issue](https://github.com/cobrabm12/fexdroid/issues) și lipește-l acolo, cu ce ai făcut și ce s-a întâmplat.

## Steam, Valve și anti-cheat

- Aplicația **nu conține fișiere Valve**. Steam și jocurile sunt descărcate de la Valve de Steam însuși, cu contul tău.
- fexdroid **nu modifică** Steam sau jocurile și nu face nimic cu VAC sau cu altă protecție, nici pe lângă ele.
- fexdroid nu are legătură cu Valve, FEX-Emu sau Mesa. Steam și Dota 2 sunt mărci ale Valve Corporation.
- Nu știm de niciun cont care să fi avut probleme jucând așa, dar nimeni din afara Valve nu poate promite ce hotărăsc sistemele lor. Joci pe răspunderea ta.

## Cum funcționează

```
jocul (Linux x86-64)     →  FEX-Emu traduce în ARM64
        ↓ apelurile Vulkan trec în cod nativ
Mesa Turnip (ARM64)      →  Adreno, prin driverul KGSL din kernel
        ↓ imagine
Xvfb (ecran X11 virtual) →  aplicația o afișează și trimite înapoi atingeri, mouse și taste
```

Aplicația conține un mic sistem Debian construit pentru restricțiile Android: glibc modificat (memorie partajată și semafoare System V în spațiul utilizator, apeluri de sistem pe care Android le interzice), FEX și Mesa cu modificări locale. Totul e în [`patches/`](patches) și se construiește cu [`scripts/`](scripts).

## Construire

Ai nevoie de Linux, Android SDK și NDK și Docker. Începe cu `scripts/check-host.sh`, apoi `scripts/build-payload.sh` și `./gradlew :app:assembleLegacyDebug`. Detaliile și motivele fiecărei decizii sunt în [PLAN.md](PLAN.md) și [NOTES.md](NOTES.md).

## Licență

Codul propriu al proiectului este sub [licența MIT](LICENSE). Componentele pe care se bazează își păstrează licențele: vezi [LICENSES.md](LICENSES.md).

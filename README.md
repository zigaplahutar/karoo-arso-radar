# karoo-arso-radar

Razširitev za Hammerhead Karoo (Karoo 2 in Karoo 3), ki na podatkovni strani prikaže
**zadnjo radarsko sliko padavin nad Slovenijo** z ARSO in jo med vožnjo samodejno osvežuje.

Vir podatkov: [ARSO – meteo.si](https://meteo.arso.gov.si/met/sl/weather/observ/radar/),
slika `si0-rm-anim.gif`. ARSO v svojem FAQ izrecno dovoljuje uporabo objavljenih radarskih
slik komurkoli in za kakršenkoli namen, tudi komercialen, ob navedbi vira.

## Kaj naredi

- **Podatkovno polje »Radar padavin«** (grafično) – daš ga na svojo podatkovno stran,
  najbolje kot edino polje na strani, da je slika čim večja.
- **Samostojen zaslon** – aplikacija v glavnem meniju Karoo pokaže isto sliko čez cel zaslon.
- Pod sliko so štirje gumbi:

  | Gumb | Kaj naredi |
  | --- | --- |
  | `−` `+` | povečava na sredino slike (1x, 1.5x, 2x, 3x) |
  | `▶` | predvaja animacijo zadnjih 90 minut; med predvajanjem se spremeni v `■` |
  | `↻` | prenese novo sliko z ARSO |

- **Slika se ne osvežuje sama.** Prenese se enkrat ob prvem prikazu, potem samo na `↻`.
  Ko podatkovne strani ne gledaš, se ne dogaja nič in baterija miruje.
- Zadnja uspešna slika ostane na zaslonu tudi, ko povezave ni.

## Kako je narejeno (štiri stvari, ki so bile zoprne)

1. **Zadnja slika animacije.** ARSO objavlja GIF animacijo zadnjih 90 minut. Android sam iz
   animiranega GIF-a dekodira samo *prvo* sličico (torej 90 minut staro), zato je uporabljen
   samostojni GIF dekoder iz knjižnice Glide, ki se prevrti do zadnje sličice
   (`GifFrames.kt`).
2. **Prenos prek Karoo.** Karoo HTTP API (`OnHttpResponse.MakeHttpRequest`) je edina pot, ki
   deluje tudi, ko internet priteka prek Companion aplikacije na telefonu, ima pa **trdo
   omejitev 100 kB na telo odgovora**. GIF je večji, zato ga `RadarDownloader.kt` pobira po
   90 kB kosih z zaglavjem `Range`. Če strežnik zahtev `Range` ne bi podpiral, se koda
   samodejno vrne na navadno HTTP povezavo (deluje, ko je Karoo na WiFi).
   Popolnost prenosa se preveri po vsebini (glava `GIF8` + zaključni bajt `0x3B`).
3. **Velikost slike čez procesno mejo.** Podatkovno polje se izriše v procesu Karoo OS prek
   `RemoteViews`, zato se bitmap pred pošiljanjem pomanjša na velikost polja
   (`ViewConfig.viewSize`) in je v formatu RGB_565.

4. **Gumbi v podatkovnem polju.** Polje se izriše v procesu Karoo OS, zato pritiska ne
   moremo prestreči neposredno. Vsak gumb dobi `PendingIntent`, ki v našem procesu sproži
   `RadarCommandReceiver` (`setOnClickPendingIntent`). V predogledu profila so gumbi
   namenoma neaktivni.

## Nastavljeno vnaprej

URL-ji so že nastavljeni na `github.com/zigaplahutar/karoo-arso-radar`
(v `app/manifest.json` in v `MANIFEST_URL` v `app/src/main/AndroidManifest.xml`).
Če repozitorij poimenuješ drugače, popravi obe mesti.

## Build

Na voljo sta dve poti. Za lastno uporabo popolnoma zadostuje prva.

### A) Samo prek GitHuba, brez Android Studia (priporoceno)

APK zgradi GitHub Actions na svojem strezniku. Ne rabis ne Android SDK ne
`local.properties`. Za javne repozitorije je Actions brezplacen.
Podroben potek je spodaj v poglavju **GitHub: korak za korakom**.

### B) Lokalno v Android Studiu

Potrebujes JDK 17 in Android Studio.

1. Naredi Personal access token **(classic)** z obsegom `read:packages`
   (<https://github.com/settings/tokens>). Fine-grained tokeni z Maven registrom
   GitHub Packages ne delujejo.
2. V korenu projekta naredi `local.properties` (te datoteke ni v zipu in ne sme
   iti na GitHub - je v `.gitignore`):

   ```properties
   sdk.dir=/Users/tvojeime/Library/Android/sdk
   gpr.user=zigaplahutar
   gpr.key=ghp_xxxxxxxxxxxxxxxxx
   ```

3. Ce v projektu se ni Gradle wrapperja:

   ```bash
   gradle wrapper --gradle-version 8.11.1
   ```

4. Prevedi:

   ```bash
   ./gradlew assembleRelease
   # rezultat: app/build/outputs/apk/release/app-release.apk
   ```

> Release build je podpisan z **debug kljucem**. To je v redu za lastno uporabo, ampak:
> podpis mora ostati enak, sicer posodobitev ni mogoca brez odstranitve aplikacije.
> Za objavo si naredi pravi keystore (`keytool -genkey -v -keystore release.jks ...`)
> in ga vpisi v `app/build.gradle.kts`.
>
> Pozor pri poti A: debug kljuc se v CI generira ob vsakem teku posebej, zato bodo
> zaporedni APK-ji razlicno podpisani. Dokler testiras, to pomeni odstrani + namesti
> znova. Ko bos hotel prave posodobitve, dodaj keystore v repozitorij kot secret.

## Namestitev na Karoo (sideload)

**Karoo 3:** APK objavi nekam, kjer je dosegljiv prek povezave (npr. GitHub Release).
Na telefonu **dolgo pritisni povezavo do APK → Deli → Hammerhead Companion**.
Na Karoo se pojavi zaslon z informacijami o aplikaciji → **Install**.

**Karoo 2 / prek kabla:** `adb install -r app-release.apk`.

Po namestitvi:

1. Odpri aplikacijo **ARSO Radar** iz glavnega menija (enkrat je treba, da se razširitev
   registrira).
2. **Profili → izberi profil → uredi strani → dodaj stran → dodaj podatkovno polje →
   ARSO Radar → Radar padavin.**
3. Priporočilo: na tej strani naj bo to edino polje, da je slika čim večja.

Med vožnjo rabi Karoo internet: WiFi ali Companion aplikacija na telefonu z mobilnimi podatki.

## Nastavitve, ki jih boš morda hotel spremeniti

Vse je v kodi, brez UI:

- `RadarRepository.FRAME_DELAY_MS` / `LAST_FRAME_DELAY_MS` – hitrost animacije.
- `RadarRepository.ZOOM_LEVELS` – stopnje povečave.
- `RadarDataType.CROP_*` – obrez robov slike (privzeto 0, torej cela slika).
- `RadarDataType.IMAGE_HEIGHT_RATIO` – koliko višine polja dobi slika (ostalo gumbi).
  Če se ti na majhnem polju zdi, da je preveč okolice, obreži npr. `CROP_BOTTOM = 0.1f`.
- `RadarDownloader.RADAR_URL` – če bi ARSO kdaj spremenil pot do slike.

## GitHub: korak za korakom

Vse spodaj gre prek brskalnika. Predpostavka: repozitorij bo
`github.com/zigaplahutar/karoo-arso-radar`.

### 1. Token za karoo-ext

karoo-ext je na GitHub Packages in zahteva prijavo, tudi ker je paket javen.

1. <https://github.com/settings/tokens> (v levem meniju cisto na dnu:
   Developer settings → Personal access tokens → Tokens (classic))
2. **Generate new token (classic)**, ime npr. `karoo-ext read`
3. Obkljukaj samo **`read:packages`**
4. Kopiraj niz `ghp_...` (prikaze se enkrat samkrat)

### 2. Naredi repozitorij

<https://github.com/new> → ime `karoo-arso-radar` → **Public** →
brez README, .gitignore in licence.

### 3. Naloži datoteke

Na strani praznega repozitorija klikni **uploading an existing file** in povleci
vanj **vsebino** odpakirane mape (ne mape same).

Nato **preveri, ali se v repozitoriju vidi mapa `.github`**. Brskalniki skrite mape
pogosto tiho izpustijo, brez nje pa ne bo nobenega builda. Ce je ni:

1. **Add file → Create new file**
2. Za ime datoteke vpisi `.github/workflows/build.yml`
   (poti ni treba delati posebej, nastane sama, ko vpises posevnice)
3. Prilepi vsebino `build.yml` iz zipa in **Commit changes**

### 4. Dodaj token kot secret

Settings → **Secrets and variables** → **Actions** → **New repository secret**

- Name: `GPR_KEY`
- Secret: token `ghp_...` iz koraka 1

(Ime se ne sme zaceti z `GITHUB_` - to GitHub rezervira zase.)

### 5. Zazeni build

Zavihek **Actions** → workflow **build** → **Run workflow**.
Ce si secret dodal po prvem nalaganju datotek, prvi samodejni tek verjetno ni uspel;
tega pozeni znova.

Ko se tek konca zeleno, so spodaj pod **Artifacts** datoteke
`karoo-arso-radar` (APK, manifest.json, icon.png).

### 6. Naredi release

Sele release naredi povezave v `manifest.json` veljavne.

**Releases** (desno na glavni strani repozitorija) → **Create a new release** →
**Choose a tag** → vpisi `v1.0` → **Create new tag on publish** → **Publish release**.

Objava taga sprozi workflow, ki v release pripne APK, manifest.json in icon.png.
Po tem delujeta:

- <https://github.com/zigaplahutar/karoo-arso-radar/releases/latest/download/karoo-arso-radar.apk>
- <https://github.com/zigaplahutar/karoo-arso-radar/releases/latest/download/manifest.json>

Prvo povezavo dolgo pritisni na telefonu → Deli → Hammerhead Companion → Install na Karoo.
Drugo pozneje vpises v developer dashboard, ce bos sel v uradno Extension Library.

### 7. Vsaka naslednja verzija

1. V `app/build.gradle.kts` dvigni `versionCode` (1 → 2) in `versionName` ("1.0" → "1.1")
2. Enako popravi `latestVersion` in `latestVersionCode` v `app/manifest.json`
   (datoteki lahko urejas kar v brskalniku: odpri datoteko → svincnik → Commit changes)
3. Naredi nov release s tagom `v1.1`

Karoo bere `manifest.json` iz `releases/latest`, zato posodobitev pokaze sam.

## Objava v uradni Extension Library (neobvezno)

1. Naredi developer račun na <https://dashboard.hammerhead.io>.
2. Odpri <https://dashboard.hammerhead.io/developer/signup>, da se ti odklenejo
   developer nastavitve, in izpolni kontaktne podatke (na tej podlagi se podpiše
   SDK licenčna pogodba s SRAM).
3. V nastavitvah dodaj novo razširitev in vpiši URL do svojega `manifest.json`.
   Aplikacija se takoj pojavi v Extension Library na vseh Karoo napravah, ki so prijavljene
   v tvoj developer račun – to je uradna pot za testiranje.
4. Ko si zadovoljen, oddaj vlogo za odobritev. SRAM odgovarja tipično več tednov,
   vloga ostane v stanju *pending*, dokler ni pogodba podpisana z obeh strani.
5. Posodobitve: dvigni `versionCode`/`versionName`, objavi nov APK in posodobi
   `manifest.json`; uporabniki dobijo poziv za posodobitev v Extension Library.

## Licence in zasluge

- Radarske slike: **ARSO / meteo.si** (vir mora biti naveden – v aplikaciji piše na zaslonu).
- `karoo-ext`: Apache 2.0, SRAM LLC.
- GIF dekoder: Glide, BSD/MIT/Apache 2.0, Google/bumptech.

# karoo-arso-radar

Razširitev za Hammerhead Karoo (Karoo 2 in Karoo 3), ki na podatkovni strani prikaže
**zadnjo radarsko sliko padavin nad Slovenijo** z ARSO in jo med vožnjo samodejno osvežuje.

Vir podatkov: [ARSO – meteo.si](https://meteo.arso.gov.si/met/sl/weather/observ/radar/),
slika `si0-rm-anim.gif`. ARSO v svojem FAQ izrecno dovoljuje uporabo objavljenih radarskih
slik komurkoli in za kakršenkoli namen, tudi komercialen, ob navedbi vira.

## Kaj naredi

- **Podatkovno polje »Radar padavin«** (grafično) – daš ga na svojo podatkovno stran,
  najbolje kot edino polje na strani, da je slika čim večja.
- **Rdeč krogec kaže tvojo lokacijo** na radarski sliki.
- **Samostojen zaslon** – aplikacija v glavnem meniju Karoo pokaže isto sliko čez cel
  zaslon; tam sliko premikaš s prstom, dvojni tap približa, dva prsta ščipata.
- Pod sliko so štirje gumbi:

  | Gumb | Kaj naredi |
  | --- | --- |
  | `−` `+` | povečava (1x, 2x, 4x, 8x); izrez se reže **okoli tebe**, ne okoli sredine slike |
  | `▶` | predvaja animacijo zadnjih 90 minut; med predvajanjem se spremeni v `■` |
  | `↻` | prenese novo sliko z ARSO |

  Pri 8x je izrez približno 50 km širok. Če lokacije še ni (brez GPS fiksa), se reže
  sredina slike in v kotu piše `brez GPS`.

- **Slika se ne osvežuje sama.** Prenese se enkrat ob prvem prikazu, potem samo na `↻`.
  Ko podatkovne strani ne gledaš, se ne dogaja nič in baterija miruje.
- Zadnja uspešna slika ostane na zaslonu tudi, ko povezave ni.


## Namestitev na Karoo (sideload)

**Karoo 3:** 
<https://github.com/zigaplahutar/karoo-arso-radar/releases/latest/download/karoo-arso-radar.apk>
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


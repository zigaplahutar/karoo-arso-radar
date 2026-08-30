# karoo-arso-radar

Razširitev za Hammerhead Karoo (Karoo 2 in Karoo 3), ki na podatkovni strani prikaže
**zadnjo radarsko sliko padavin nad Slovenijo** z ARSO in jo med vožnjo samodejno osvežuje.
<img width="1000" alt="IMG_2223" src="https://github.com/user-attachments/assets/aa24535e-91cb-4c0c-863b-ebe6580b8941" />


Vir podatkov: [ARSO – meteo.si](https://meteo.arso.gov.si/met/sl/weather/observ/radar/),
slika `si0-rm-anim.gif`. 

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
  Ko podatkovne strani ne gledaš, se ne dogaja nič in ne porablja baterije.
- Zadnja uspešna slika ostane na zaslonu tudi, ko povezave ni.


## Namestitev na Karoo (sideload)
Povezavo dolgo pritisni na telefonu → Deli → Hammerhead Companion → Install na Karoo.
- <https://github.com/zigaplahutar/karoo-arso-radar/releases/latest/download/karoo-arso-radar.apk>
Na Karoo se pojavi zaslon z informacijami o aplikaciji → **Install**.

**Karoo 2 / prek kabla:** `adb install -r app-release.apk`.

Po namestitvi:

1. Odpri aplikacijo **ARSO Radar** iz glavnega menija (enkrat je treba, da se razširitev
   registrira).
2. **Profili → izberi profil → uredi strani → dodaj stran → dodaj podatkovno polje →
   ARSO Radar → Radar padavin.**
3. Priporočilo: na tej strani naj bo to edino polje, da je slika čim večja.

Med vožnjo rabi Karoo internet: WiFi ali Companion aplikacija na telefonu z mobilnimi podatki.






# karoo-arso-radar

Razširitev za Hammerhead Karoo (Karoo 2 in Karoo 3), ki na podatkovni strani prikaže
**zadnjo radarsko sliko padavin nad Slovenijo** z ARSO in jo med vožnjo samodejno osvežuje.

Vir podatkov: [ARSO – meteo.si](https://meteo.arso.gov.si/met/sl/weather/observ/radar/),
slika `si0-rm-anim.gif`. ARSO v svojem FAQ izrecno dovoljuje uporabo objavljenih radarskih
slik komurkoli in za kakršenkoli namen, tudi komercialen, ob navedbi vira.

## Kaj naredi

- **Podatkovno polje »Radar padavin«** (grafično) – daš ga na svojo podatkovno stran,
  najbolje kot edino polje na strani, da je slika čim večja.
- **Samostojen zaslon** – aplikacija v glavnem meniju Karoo pokaže isto sliko čez cel zaslon,
  z gumbom za osvežitev.
- Osvežitev na ~2 minuti (ARSO objavi novo sliko na 5 minut).
  Zadnja uspešna slika ostane na zaslonu tudi, ko povezave ni.



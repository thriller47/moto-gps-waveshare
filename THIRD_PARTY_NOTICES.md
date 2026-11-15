# Moto Chat GPS — attribuzioni e licenze di terzi

**Il testo di [LICENSE.md](LICENSE.md) (PolyForm Noncommercial 1.0.0) riguarda i diritti concessi sul codice originale soggetto a tale licenza; non sostituisce e non modifica le licenze indipendenti dei componenti e dei dati di terzi.** I sorgenti storici provengono dal progetto [mx3353672833-debug/moto-gps-waveshare](https://github.com/mx3353672833-debug/moto-gps-waveshare); preservare i crediti degli autori e la cronologia Git.

| Componente | Impiego | Licenza / fonte |
| --- | --- | --- |
| **Valhalla** | Routing offline e tool Mjolnir per grafi V5 | MIT — [valhalla/valhalla](https://github.com/valhalla/valhalla) |
| **Photon** | Ricerca di destinazioni; dump JSONL per SQLite offline v2 | Apache License 2.0 — [komoot/photon](https://github.com/komoot/photon) |
| **OpenStreetMap** | MotoMap, grafi Valhalla, dati Photon, fixture e derivati | ODbL 1.0, **© OpenStreetMap contributors** — [attribuzione](https://www.openstreetmap.org/copyright), [licenza](https://opendatacommons.org/licenses/odbl/1-0/) |
| **LVGL** | UI grafica; submodule `third_party/lvgl` | MIT; `third_party/lvgl/LICENCE.txt`, [LICENSES/LVGL-MIT.txt](LICENSES/LVGL-MIT.txt) |
| **Waveshare** | BSP/CO5300 e parti board-specific | Licenze/componenti del fornitore; per la derivazione Apache-2.0 vedi [LICENSES/Waveshare-Apache-2.0.txt](LICENSES/Waveshare-Apache-2.0.txt) e intestazioni sorgente |
| **Montserrat** | Font fornito con LVGL | SIL Open Font License 1.1 — [LICENSES/Montserrat-OFL.txt](LICENSES/Montserrat-OFL.txt) |
| **Source Han Sans SC** | Font subset usato dalla UI | SIL Open Font License 1.1 — [LICENSES/SourceHanSansSC-OFL.txt](LICENSES/SourceHanSansSC-OFL.txt) |
| **ESP-IDF, NimBLE, ESP Component Registry** | Firmware e librerie runtime | Le rispettive licenze distribuite con i pacchetti; versioni ESP-IDF in `platforms/esp32/dependencies.lock` |
| **Emscripten / SDL** | Runtime browser e simulatore | Licenze dei rispettivi componenti; consultare le distribuzioni utilizzate |

I dati OSM derivati non diventano PolyForm: continuano a sottostare a ODbL. Attribuzione relativa al fixture di Jinan: [shared/offline_map/ATTRIBUTION.txt](shared/offline_map/ATTRIBUTION.txt). I dati di routing/geocoding ricostruiti dall'utente non sono inclusi in Git e vanno distribuiti solo rispettando i diritti dei dati di origine.

**Altri componenti storici:** se riutilizzati da codice o cartelle originarie, controllare le dipendenze realmente collegate e le loro licenze (incluse quelle di eventuali componenti frontend, build e servizi). La pulizia di vecchie guide non autorizza a eliminare avvisi da librerie, risorse o sorgenti di terzi.

**Hardware:** `hardware/` conserva soltanto l'archivio utente `MotoNavFusion_v015.zip`, con lo script per il supporto meccanico Fusion 360. I vecchi schemi/datasheet/PCB/CAD pubblicati nelle revisioni precedenti sono recuperabili dalla cronologia Git, ma non fanno più parte dell'albero corrente. I diritti sui materiali dei fornitori e sulle revisioni storiche rimangono in capo ai rispettivi titolari: la cancellazione dai file correnti non ne modifica le licenze. Lo script Fusion allegato va trattato come prototipo e non come parte collaudata per la sicurezza in marcia.

Moto Chat GPS è un progetto indipendente e non è un prodotto ufficiale né approvato da OpenStreetMap Foundation, Valhalla, Photon, Waveshare o dai fornitori citati.

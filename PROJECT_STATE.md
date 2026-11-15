# Moto Chat GPS — stato operativo del progetto

## STATO ATTUALE — RIPARTIRE DA QUI

**Aggiornato:** 15 novembre 2026. **Repository:** [thriller47/moto-gps-waveshare](https://github.com/thriller47/moto-gps-waveshare). **Branch di integrazione e predefinito:** `valhalla` (non `main`, eliminato). **Ultima integrazione:** fast-forward del ramo `test/lvgl-single-road-renderer-20261113` su `valhalla` il 14/11, 26 commit, fino a `902de169`; successive rettifiche documentali su `valhalla`. Il vecchio branch di test è ancora presente, **non eliminarlo prima della verifica del clipping**.

**Funziona e risulta collaudato dall'utente:** navigazione con MotoMap v4 e Valhalla offline, rendering precedente LVGL/predittore su strada, ricerca Photon **offline v2** (Courmayeur, Via Roma Torino, indirizzo con civico e accento) e nuovo grafo **Valhalla Italia V5** installato e testato su Android. Non trattare queste conferme come una nuova misura automatica.

**Ancora DA COLLAUDARE:** il clipping LVGL conservativo per **singoli segmenti delle strade grigie** introdotto il 14/11, già integrato in `valhalla` per errore di sequenza. Il test su strada previsto per la sera del 14/11 è **saltato per maltempo**; l'utente intende riprovarci il 15/11. La precedente prova positiva riguardava il renderer unitario e il predittore, **NON il nuovo clipping**. **Decisione esplicita:** lasciare tutto com'è in `valhalla` fino alla prova; non fare rollback, non cambiare BLE/zoom/rendering, non eliminare il ramo test.

**Misure V4/V5 riportate dall'utente:** il grafo Italia V4 aveva 1.591 tile, 11.457.028 nodi e 26.093.538 archi direzionali; V5 ha 1.580 tile, 8.757.819 nodi e 18.799.618 archi. Entrambi Valhalla 3.9.1, **0 header non verificati**. File `tar.zst` da **886M a 661M** (`ls -lh`, arrotondato; circa −25,4%). I TAR non compressi passano da **2,3G a 1,7G** (`ls -lh`, arrotondato). V4 rimane un backup su PC fino a ulteriori prove.

**Misure Photon v2 riportate dall'utente:** **8.362.869 luoghi importati**, 15.909 record scartati, database finale **1.074,0 MiB** contro 2.016,8 MiB della v1 (circa −46,7%). Il vecchio formato v1 non è supportato dall'ultima app: in caso di indice assente/incompatibile o errore SQL usa Photon online; una ricerca offline valida senza risultati non provoca fallback. Corretto il riferimento erroneo a `p.city` con `c.name` nell'SQL di Android; verificato in test native contro database v2. Non rigenerare il database già collaudato senza necessità.

**Ultime CI automatiche verificabili:** [43615504345](https://github.com/thriller47/moto-gps-waveshare/actions/runs/43615504345) Android/native SUCCESS, [43616826002](https://github.com/thriller47/moto-gps-waveshare/actions/runs/43616826002) native SUCCESS con test dello script V5; precedenti build ESP32 erano riuscite nel ciclo LVGL. Queste sono verifiche di commit sperimentali **precedenti al fast-forward**, non una nuova CI completa post-merge su `valhalla`. Nessuna prova hardware del nuovo clipping ancora effettuata.

**Prossima azione unica:** registrare il giro di prova del clipping ESP32 con **log seriale** e (facoltativamente) diagnostica Android: valutare `LVGL_ROADS` (`seg`, `seg_skip`, `dirty`, `tiles`), `display perf`, FPS, sfarfallio, integrità della rotta verde e ricalcoli. Solo dopo decidere se tenere definitivamente il clipping o correggerlo. Le istruzioni operative attuali sono in [docs/MANUALE_OPERATIVO.md](docs/MANUALE_OPERATIVO.md).

## Pulizia repository e documentazione — 15 novembre 2026

**Scope:** pulizia della documentazione del progetto originario cinese/inglese non più utilizzata: vecchi README, tutorial iOS/gateway, piani software superati, duplicati bilingui e proposta completa di hardware non attuale (PDF/DOCX/asset e relativo generatore); rimozione dei banner pubblicitari ereditati. Sostituiti README principale, guida ai contributi, avvisi terze parti e indici `hardware/` e `platforms/esp32/` con versioni italiane coerenti con Android+ESP32, Photon v2, MotoMap v4 e Valhalla V5. **Intatti:** `LICENSE.md`, `LICENSES/`, `.gitmodules`, submodule `third_party/lvgl`, `shared/offline_map/ATTRIBUTION.txt`, sorgenti, risorse della scheda, CAD/KiCad e file di produzione hardware, script di build e i due documenti operativi aggiornati. Il materiale eliminato rimane recuperabile nella cronologia Git; non ricreare guide obsolete. La licenza principale non sostituisce MIT, Apache-2.0, OFL o ODbL per componenti/dati di terzi.

**Verifiche:** censimento completo del tree GitHub e verifica dei riferimenti principali; diff finale e assenza di modifiche a codice/SDK da controllare. Intervento esclusivamente documentale/asset pubblicitari, nessuna nuova prova hardware o build richiesta. **Il test su strada del clipping LVGL resta P0**, rinviato per maltempo e non ancora confermato. Il vecchio ramo di test rimane finché il clipping non è collaudato.

## Architettura attuale e artefatti indipendenti

| Componente | Stato operativo | Artefatto/importazione | Principale limite |
| --- | --- | --- | --- |
| Android | App Kotlin, navigazione e BLE, GPS autorevole per routing/progresso; Photon v2 offline e Valhalla offline | `platforms/android`, APK | Fallback online richiede rete funzionante |
| Valhalla | Motore offline 3.9.1; profili stradali, Touring moto, curve, tappe/ricalcoli; fallback Valhalla online | `valhalla_tiles.tar.zst` → TAR interno Android | V5 filtra soli percorsi pedonali/ciclabili esclusivi; percorso reale da verificare su aree diversificate |
| MotoMap | **v4** da OSM, node ID e `junction=roundabout/circular`, contesto stradale grigio, copertura z14 | `italy-v4.motomap.zip` | V3 non supportata; OpenFreeMap solo corridoio esplicito fuori copertura |
| Photon | **v2 SQLite**, FTS4 contentless, dizionari `cities` e `regions`, supporto civici/diacritici | `photon-italy-v2.sqlite` importato via app | V1 incompatibile; Photon online in assenza di indice valido |
| ESP32 | Waveshare ESP32-S3-Touch-AMOLED-1.75C, display 466×466, BLE NimBLE, LVGL | firmware ESP-IDF 5.5.6 | Clipping per segmento già integrato, prova su strada ancora pendente |
| Simulatore | Replay registrazioni GPS JSONL in browser con renderer WASM/LVGL da Emscripten | `scripts/build_web.sh` + server HTTP WSL | FPS browser non equivalgono agli FPS ESP32 |

**Separazione critica:** MotoMap fornisce le **strade grigie dell'ESP32**, Valhalla calcola la **rotta verde**, Photon cerca **luoghi e indirizzi**. Importare uno dei tre file non sostituisce gli altri. Non esiste più un gateway Render/OSRM né la ricerca Overpass continua; fuori copertura MotoMap il corridoio OpenFreeMap è attivabile esplicitamente, non è prova di dati OSM equivalenti a v4.

## Decisioni tecniche e UX ancora vincolanti

- Un solo ramo stabile: `valhalla`; test e PR isolati solo quando necessari. Nessun nuovo ramo `main`; non toccare upstream.
- Firmware: rotta selezionata **verde**, strade di contesto **grigie**, posizione moto e flag di arrivo a scacchi. **Niente frecce ordinarie di svolta o rotonda**: la guida visuale è lo zoom progressivo della geometria, insieme a distanza svolta/arrivo e limite velocità. Mantenere la visuale leggibile anche senza MotoMap.
- Zoom contestuale per svolte/rotonde/tornanti; priorità agli anelli completi e uscite OSM; conservare geometria della rotta, ricalcoli, progressione e BLE. Non riattivare pagina velocità, bussola, musica o OTA nella navigazione.
- Notifica tappa raggiunta di circa sette secondi; arrivo finale separato. Preferenze Touring moto e preferenza curve, tappe intermedie ordinate. Non modificare il protocollo Android↔BLE↔ESP32 per ottimizzazioni dei dati.
- Modalità radio Wi-Fi separata e ancora sperimentale: non confonderla con una pagina musicale durante la navigazione.
- GPS grezzo validato su Android governa progressione/manovre; previsione del movimento è visuale sul dispositivo. Non alimentare il motore di routing con extrapolazioni solo grafiche.
- Regola CI: **una verifica consolidata alla fine delle modifiche**, non una build per ogni commit. Distinguere sempre compilazione, test nativi e test su dispositivo.

## Codice e generatori da usare oggi

- `scripts/offline_map/pbf_to_motomap.py`: produce MotoMap **v4**. Per copertura z14 usare `--coverage-poly` con file `.poly` coerente con il PBF (richiede Shapely).
- `scripts/offline_map/photon_to_sqlite.py`: produce **Photon SQLite v2**, `--input`, `--output`. Da PowerShell sul PC usare il launcher **`py`**, non presumere che `python` sia nel PATH.
- `scripts/offline_map/build_valhalla_moto_v5.sh`: da **WSL Ubuntu con Valhalla 3.9.1**, `--zstd --threads 2`, configurazione Mjolnir `include_bicycle=false`, `include_pedestrian=false`, `include_driving=true`. `--config-only` usa un'altra cartella per preview. Non sovrascrive V4.
- `scripts/offline_map/analyze_valhalla_graph.py`: confronta TAR/ZST (conteggi livelli/nodi/archi/header) senza estrazione delle tile.
- `platforms/android/app/src/main/java/com/motogps/android/viewmodel/OfflinePhotonPlaceSearch.kt`: import/ricerca v2, ordinamento con `JOIN cities c` e `c.name`.
- `shared/nav_ui/src/moto_nav_ui.cpp` + `road_segment_clip.hpp`: rendering grigi, culling sperimentale per segmento; `platforms/esp32/main/board_port_waveshare_1_75c.cpp` emette la diagnostica `LVGL_ROADS`.
- `scripts/build_web.sh`: compila simulatore Emscripten; per vederlo usare HTTP locale, non `file://`.
- `docs/MANUALE_OPERATIVO.md`: procedure aggiornate per Git, CI, Windows/ESP-IDF, Android, WSL, mappe, radio, seriale, replay.

## Collaudi e rischi aperti

| Priorità | Verifica | Stato |
| --- | --- | --- |
| P0 | Clipping anticipato dei segmenti grigi con telemetria ESP32 | **Da testare** dopo rinvio per maltempo; nessun rollback deciso |
| P1 | Confronto visivo FPS a basse/alte velocità, sosta, rotonde e strade dense | Da raccogliere con log seriale/Android |
| P1 | Stabilità del routing V5 in aree secondarie/montane e altri tragitti offline | Test iniziale utente positivo; copertura completa non dimostrata |
| P2 | Confronto quantitativo tempo query Photon v2 vs v1 | Dimensione e correttezza testate, benchmark non effettuato |
| Roadmap | Modalità pausa caffè, percorsi panoramici/ad anello, indicatori di tappa sulla linea verde | Idee concordate, **non dichiarare implementate** senza verifica del codice |

## Storico e note obsolete

La cronologia completa di commit, prove, diagnosi e rollback dal settembre al 14 novembre 2026 è conservata senza modifiche in [docs/archive/PROJECT_STATE_PRE_CLEANUP_2026-11-15.md](docs/archive/PROJECT_STATE_PRE_CLEANUP_2026-11-15.md). **È un archivio storico, non un manuale operativo.** Contiene ipotesi ed istruzioni successivamente superate (Overpass, MotoMap v2/v3, Photon v1, branch di test eliminati, problemi LVGL già corretti). Fare riferimento sempre a questo file corrente e al codice del branch `valhalla`.

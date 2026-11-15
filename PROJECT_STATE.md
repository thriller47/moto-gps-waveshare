# Moto Chat GPS — stato operativo del progetto

## STATO ATTUALE — RIPARTIRE DA QUI

**Aggiornato:** 15 novembre 2026. **Branch stabile/predefinito:** `valhalla`, con integrazione MotoPack completata tramite fast-forward al commit `57117dd4203ce43c8f5fe0f2c87d31f99cc2eef8`. **Branch sperimentale attivo:** `test/unified-offline-map-20261115` riutilizzato su richiesta dell'utente per recuperare selettivamente le migliorie da `gemini_review`; **non effettuare merge automatico**. `gemini_review` è divergente (8 commit propri e 28 commit di integrazione assenti).

**Base stabile validata dall'utente:** generazione Italia completa da PBF/POLY/Photon su WSL, verifica SHA-256 di `.motopack`, importazione Android e ricerca/routing/mappe offline funzionanti. Photon 1.074 MiB, Valhalla 1.766 MB, MotoMap 763,73 MB installati. Questi risultati si riferiscono alla base `valhalla`, non alle nuove patch BLE/IMU.

**Nuove modifiche sul solo branch test:** decoder Kotlin `MotoBLEProtocolCodec` con reset su CRC errato, verifiche di offset/sequence/type/length, limite 1500 ms fra frammenti, reset su end prematuro; test JUnit specifici per sequenza, CRC, timeout e recupero; esportazioni diagnostiche/replay con `lifecycleScope` e `Dispatchers.IO`; controllo I²C prima di inizializzare QMI8658. Sono stati mantenuti delay GATT 18 ms, pipeline GPS/callback già collaudata e gestione energia GPIO3; non è stato incluso il WakeLock proposto da Gemini. Nessuna modifica al formato BLE, alle mappe o al renderer.

**Verifica:** prova su strada del 15/11 **riportata positiva dall'utente** (grafica, fluidità, rotonde, tornanti, zoom) con log ESP32/Android/replay analizzati; 6 ricalcoli Valhalla offline riusciti, nessun BLE_WRITE_FAILURE/BLE_ERROR, 4 TE timeout isolati, cali grafici intermittenti fino a 3,9 FPS. **CI del commit sperimentale ancora da verificare**; non attribuire l'esito integrato alla validazione separata di ogni singola modifica.

**Rischi aperti:** la soglia dei frammenti 1500 ms può richiedere regolazione in presenza di congestione GATT; inizializzazione I²C duplicata da evitare (controllo esplicito prima di init). Prova clipping LVGL per singoli segmenti grigi ancora separata e non confermata; non modificare il renderer.

**Prossima azione unica:** controllare la CI del branch test e poi valutare con l'utente il merge selettivo su `valhalla`. Il collaudo visivo del clipping è riuscito; conservare un monitoraggio dei picchi LVGL, senza interventi preventivi.

## Collaudo su strada e diagnostiche — 15 novembre 2026

**Riscontro utente:** grafica corretta, fluidità buona, zoom di rotonde e tornanti corretto, navigazione funzionante. Log condivisi: `serial_20261115_164752.txt`, `motogps-android-diagnostics(20261115-164519).zip` e `motogps-replay(20261115-164520).jsonl`. Questi dati si riferiscono alla prova utente, non a un test automatico in CI.

**Analisi log:** Android 6 ricalcoli Valhalla offline riusciti (16:56:11, 16:56:24, 16:57:26, 16:58:55, 17:02:03, 17:05:01), senza `BLE_ERROR` o `BLE_WRITE_FAILURE` registrati. GPS: 712 valori age_ms, mediana 373 ms, massimo 1660 ms, un solo valore > 1000 ms; 3 snapshot `gnss_stale=true` consecutivi in una breve sosta. Replay: 4158 snapshot, 1039 fix, 548 geometrie di rotta, 194 scene. ESP32: handshake BLE completato alle 16:47:59, zoom_x100 raggiunge 400; 4 warning isolati `TE wait timeout 1/3` con fail-open; nessun errore seriale grave visto. L'ultima disconnessione alle 17:06:11 segue il termine dei GPS log Android alle 17:05:48 ed è compatibile con chiusura/interruzione della sessione, ma non dimostrata come intenzionale.

**Clipping e rendering:** 214 contatori `LVGL_ROADS` e 215 misure FPS parseabili; FPS medio per finestra ~20,9 (min 3,9, max 28,1). In scene pesanti 3–4 flush/frame; minimo 3,9 FPS alle 17:04:08 e 4,4 FPS alle 17:04:14, con render 67–69 ms medi. Totali diagnostici `seg=906671`, `seg_skip=535496`, quindi ~37% delle valutazioni di segmento marcate skip. Quattro TE timeout isolati. Heap interno libero stabile 79–80 KiB nel log, ma minimum storico 8 KiB. **Conclusione:** prova visiva clipping riuscita e nessuna regressione riferita dall'utente; picchi di carico grafico ancora presenti e da monitorare, senza modifica preventiva del renderer.

**Prossima azione:** verificare lo stato CI finale e decidere con l'utente se effettuare il merge selettivo BLE/IMU in `valhalla`. Non inferire che singole modifiche BLE/IMU siano isolate e provate separatamente; il giro conferma il comportamento integrato del branch test. Nessun merge o nuovo intervento codice in questa analisi.

## Recupero selettivo da gemini_review — 15 novembre 2026 (branch test)

L'utente ha chiesto di integrare le migliorie nel branch sperimentale ancora presente. **Non è stato effettuato alcun merge diretto di `gemini_review`**, divergente e privo di MotoPack. Cambiati `MotoBLEProtocolCodec.kt`, `MainActivity.kt`, `motion_heading_sensor.cpp/.h`; aggiunto `MotoBLEProtocolCodecFragmentTest.kt`. Il decoder Bluetooth scarta frammenti non contigui e incongruenti, introduce il controllo timeout da 1500 ms ed evita di mantenere payload corrotti dopo CRC/end invalidi. L'esportazione diagnostica usa coroutine legate all'Activity; l'IMU verifica l'inizializzazione del bus I²C. Deliberatamente esclusi: WakeLock senza permesso manifest/alto consumo, GPIO3/deep sleep non provati, modifiche invasive al GPS e eliminazione del delay GATT 18 ms.

**Test nuovi:** JUnit con frame BLE generati artificialmente (contiguità, offset invalido, timeout, sequenza, CRC errato e fine prematura). **Non ancora eseguiti** su runner o dispositivo in questa sessione. Procedura di collaudo descritta in `docs/MANUALE_OPERATIVO.md`. **Prossimo passo:** verificare CI unico e provare APK/ESP32 sperimentali prima di qualsiasi merge su `valhalla`.

## Validazione end-to-end e merge autorizzato — 15 novembre 2026

**Collaudo finale:** utente conferma che la pipeline WSL completa ha generato il nuovo `.motopack` a partire da PBF, POLY e archivio Photon e ha superato il controllo SHA-256; l'importazione Android del file rigenerato, la ricerca Photon, il routing Valhalla e le strade MotoMap funzionano. Dimensioni dichiarate: ZIP MotoMap 739M, SQLite Photon 1,1G, ZST Valhalla 661M, pacchetto 2,5G; componenti Android circa Photon 1.074 MiB, Valhalla 1.766 MB e MotoMap 763,73 MB. **Autorizzazione esplicita:** merge del branch `test/unified-offline-map-20261115` in `valhalla` e aggiornamento manuale. Documentazione aggiornata in questa sessione; merge e CI post-merge vanno verificati direttamente.

**Prossimo passo:** controllare ref remoto di `valhalla`, esito della CI e che la procedura WSL del manuale sia disponibile nel branch predefinito. Non modificare il clipping non collaudato.

## Correzione prima importazione unificata — 15 novembre 2026

**Riscontro hardware Android dell'utente:** prima della prova erano state eliminate tutte le mappe precedenti; l'APK sperimentale mostra MotoMap v4 e Photon v2 installati ma non il grafo Valhalla. La verifica del pacchetto `.motopack` generato dai tre file è PASS riportato dall'utente; non attribuire il problema ai dataset senza prove.

**Diagnosi verificata nel codice:** `UnifiedOfflineMapImporter` estraeva i payload come `motomap.pending`, `photon.pending`, `valhalla.pending`. `ValhallaGraphImporter.displayName` richiede `.tar` o `.tar.zst` e usa quest'ultima estensione per attivare la decompressione Zstandard. Il nome temporaneo rendeva impossibile l'installazione Valhalla dalla procedura unificata. La precedente presenza MotoMap/Photon non era dovuta a installazioni precedenti (eliminate dall'utente).

**Correzioni isolate su branch test:** nomi degli staging originali (`italy-v4.motomap.zip`, `valhalla_tiles.tar.zst`, `photon-italy-v2.sqlite`), riconoscimento esplicito URI file locali nei vecchi importatori MotoMap/Valhalla e lettura size locale Photon; telemetria dei byte decompressi Valhalla e controllo finale che il TAR Valhalla sia effettivamente presente. Aggiunto test JUnit per preservare le estensioni dei tre file. Nessun cambiamento a motori, BLE o firmware; pacchetto `.motopack` già creato riutilizzabile. **Compilazione CI e prova su telefono della correzione ancora da verificare.** Recupero immediato possibile tramite import Valhalla V5 TAR.ZST autonomo.

**Prossima azione unica sul test:** verificare Android CI del commit finale e ripetere l'importazione con lo stesso `.motopack`, confermando la dimensione del grafo nella schermata e una rotta offline. Non mergiare su `valhalla` prima della prova.

## Generatore end-to-end in WSL — 15 novembre 2026 (branch test)

**Scope:** nuova pipeline `scripts/offline_map/build_motopack_wsl.sh` sul solo branch `test/unified-offline-map-20261115`: PBF OpenStreetMap + POLY dell'estratto + dump Photon JSONL/JSONL.ZST producono MotoMap v4, Photon SQLite v2 e grafo Valhalla Italia V5 mediante i convertitori già presenti; infine `motopack.py` assembla e verifica SHA-256 del contenitore finale. Nessun cambiamento a BLE, ESP32, Valhalla runtime, ricerca Android o wire format. Usa workspace nuovo in WSL, rifiuta sovrascritture, ammette `--resume` per riutilizzare intermedi completi senza cancellare una build di grafo incompleta; mantiene input e intermedi. Manuale operativo aggiornato con procedura, ambiente e caveat spazio disco.

**Test eseguiti nella sessione:** `bash -n` e 4 smoke test Python per help, input mancanti, dump Photon errato e validità sintattica; **4/4 PASS**. CI native del branch estesa con `test_build_motopack_wsl.py`. La pipeline completa su PBF Italia, lo spazio disco e il tempo reale di conversione **non** sono stati testati. La verifica precedente del motopack assemblato da tre file già convertiti è stata riferita dall'utente come positiva (`OK: MotoMap v4 + Valhalla V5 + Photon v2`); build Android/native precedente riferita verde dall'utente. Non confondere questi riscontri con la CI del nuovo script.

**Prossima azione per l'esperimento:** controllare l'esito della CI native sul commit conclusivo, poi eseguire una conversione completa in WSL con PBF, .poly e dump Photon reali (nomi da controllare in `C:\MotoGPS\PBF`). Nessun merge su `valhalla` senza consenso esplicito. Il collaudo stradale clipping LVGL resta sospeso/indipendente.

## Esperimento pacchetto offline unificato — 15 novembre 2026 (branch test)

**Branch attivo:** `test/unified-offline-map-20261115` creato da `valhalla`. **Non mergiato.** Nessuna modifica a ESP32, BLE, routing, clipping o agli artefatti installati. La prova stradale del clipping LVGL rimane ancora aperta sul branch di integrazione.

**Implementato nel test:** `scripts/offline_map/motopack.py` crea/verifica un contenitore ZIP64 `.motopack` con tre payload distinti (MotoMap v4 ZIP, Valhalla V5 TAR.ZST, Photon v2 SQLite), `manifest.json` e SHA-256. Gli artefatti attuali rimangono utilizzabili; non è stata eseguita alcuna conversione reale dei grandi dataset Italia. In Android `UnifiedOfflineMapImporter.kt` verifica tutti i payload e il manifest prima di richiamare in sequenza i tre importatori esistenti; pulsante unico in Gestione mappe; ViewModel presenta stato di avanzamento. I tre importatori sono **atomicamente protetti ciascuno per sé**, ma **non** costituiscono un'unica transazione: in caso di errore durante l'installazione può restare uno stato misto. Lo spazio richiesto per i file temporanei è elevato.

**Verifiche:** 5 test locali Python su fixture fittizie superati nella sessione di sviluppo, inclusi SHA alterato, manifest duplicato, formato MotoMap errato e sovrascrittura; nuovi test `scripts/offline_map/test_motopack.py` integrati nella job native. **Ancora da verificare:** compilazione e test Android GitHub Actions del branch, creazione di pacchetto con i veri asset Italia, import su telefono, spazio disponibile, interruzioni e compatibilità del file URI Android. Nessuna verifica hardware effettuata. Non dichiarare collaudato il contenitore fino a test APK sul dispositivo.

**Manuale:** aggiunta procedura sperimentale PowerShell per build, verify e installazione; non confonderla con procedura stabile. **Prossima azione unica:** verificare CI Android/native sul branch, correggere eventuali problemi e poi provare la prima installazione con file reali prima di proporre il merge.

## Pulizia repository e documentazione — 15 novembre 2026

**Scope:** pulizia della documentazione del progetto originario cinese/inglese non più utilizzata: vecchi README, tutorial iOS/gateway, piani software superati, duplicati bilingui e proposta completa di hardware non attuale (PDF/DOCX/asset e relativo generatore); rimozione dei banner pubblicitari ereditati. Sostituiti README principale, guida ai contributi, avvisi terze parti e indici `hardware/` e `platforms/esp32/` con versioni italiane coerenti con Android+ESP32, Photon v2, MotoMap v4 e Valhalla V5. **Intatti:** `LICENSE.md`, `LICENSES/`, `.gitmodules`, submodule `third_party/lvgl`, `shared/offline_map/ATTRIBUTION.txt`, sorgenti, risorse della scheda, CAD/KiCad e file di produzione hardware, script di build e i due documenti operativi aggiornati. Il materiale eliminato rimane recuperabile nella cronologia Git; non ricreare guide obsolete. La licenza principale non sostituisce MIT, Apache-2.0, OFL o ODbL per componenti/dati di terzi.

**Verifiche:** censimento completo del tree GitHub e verifica dei riferimenti principali; diff finale e assenza di modifiche a codice/SDK da controllare. Intervento esclusivamente documentale/asset pubblicitari, nessuna nuova prova hardware o build richiesta. **Il test su strada del clipping LVGL resta P0**, rinviato per maltempo e non ancora confermato. Il vecchio ramo di test rimane finché il clipping non è collaudato.

## Sostituzione integrale cartella hardware — 15 novembre 2026

**Richiesta esplicita dell'utente:** svuotare `hardware/` e conservare **esclusivamente** lo ZIP allegato, qui registrato come `hardware/MotoNavFusion_v015.zip`. Prima della pulizia erano presenti **499 file** (circa 61 MB) di proposte PCB/CAD e riferimento; tutti rimossi dalla versione corrente. Lo ZIP originale ricevuto (17.351 byte) è verificato senza modifiche mediante SHA-256 `0e6f654885c166b53af280af98c6cdf0fe84fd3b1f1ebaacc2f9f3ec1d71f969` e Git blob SHA `f22d74f6c1222212f5d63c9e04340cfee4bb8989`. Contiene `MotoNavFusion.py`, `MotoNavFusion.manifest` e `LEGGIMI_v015.txt`. Variante Fusion v0.15 del supporto con pin antisgancio profondo 9 mm, tasche power/reset e rinforzo guide: **richiede ancora controllo CAD/Fusion e test meccanico reale**. Materiali PCB eliminati ancora reperibili nella cronologia Git; nessuna modifica a sorgenti di navigazione, licenze root o workflow. README e avvisi di terze parti aggiornati per non fare riferimento alla cartella hardware precedente. **Prossima azione funzionale invariata:** collaudo clipping LVGL su strada.

## Splash e homepage GitHub — 15 novembre 2026

Aggiunta la splash **rettangolare** Moto Chat GPS alla testata del README GitHub su `valhalla`, riutilizzando l'immagine già realizzata nel progetto (montagne, rotta verde, logo e crediti Valhalla/OSM/Photon). File ottimizzato WebP 900 px di larghezza, 12.078 byte, in `assets/brand/moto-chat-gps-splash.webp`. Non confondere con il logo circolare destinato alla UI ESP32. Nessuna modifica a codice, licenze, firmware o APK; nessuna CI necessaria. La prova clipping su strada resta aperta.

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

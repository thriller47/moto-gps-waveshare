# Moto Chat GPS — stato operativo del progetto

## STATO ATTUALE — RIPARTIRE DA QUI

**Aggiornato: 24 novembre 2026.** Repository: `thriller47/moto-gps-waveshare`. **Branch di integrazione: `valhalla`**; branch sperimentale UI attivo: **`NewUI`**, creato dal ramo `valhalla` il 23/11/2026; nessun branch sperimentale Road DNA attivo (il branch `feature/road-dna-motopack-v2` è stato integrato con PR [#79](https://github.com/thriller47/moto-gps-waveshare/pull/79) e rimosso). `main` non esiste più. Questo è il punto di ripartenza; per le diagnosi e i passaggi intermedi vedere lo [snapshot storico completo del 22/11](docs/archive/PROJECT_STATE_SNAPSHOT_2026-11-22.md) e l'[archivio precedente al 19/11](docs/archive/PROJECT_STATE_PRE_CLEANUP_2026-11-19.md).

**Ultimo lavoro su `NewUI` (24/11):** integrazione selettiva di Gemini-DEBUG, sorgente `174b3f69`: cache MotoMap, ricerca offline, protezione sessioni/ricalcoli, GNSS, collegamento tour UI, ottimizzazioni rotonde e IMU. Layout orizzontale invariato per richiesta utente. Il precedente NewUI è stato provato dall'utente con esito positivo in verticale e difetto di scorrimento in orizzontale. L'utente conferma build verdi di Gemini-DEBUG; la nuova integrazione non è ancora compilata. Prossima azione: una sola build consolidata Android/native/ESP32 quando autorizzata, poi prova su strada. Nessuna modifica a valhalla.

**Lavoro precedente su `NewUI` (23/11):** spostato il profilo DEM esclusivamente nella sezione espandibile dell'anteprima percorso Android; Road DNA mantiene curve e luoghi turistici, e l'altimetria non compare durante navigazione né su ESP32. Recupero `/height` isolato, su richiesta quando si espande la card, con cancellazione su cambio rotta/avvio guida. Precedente integrazione di fallback quote online Valhalla `/height` su richiesta e font Space Grotesk/Manrope OFL incorporati nell'APK, oltre all'analisi Android Road DNA altimetria/curve e MotoPack v3 con DEM Skadi dopo la prima trasposizione del design Google Stitch "Dark Touring" nei Composable Android: tema/tipografia, root `MaterialTheme`, BottomSheet, dialoghi e due viste (home e anteprima percorso) di `NavigationScreen.kt`. I parametri e callback preesistenti dei Composable sono conservati; per il pannello DEM in anteprima sono stati aggiunti a `NavigationScreen` tre parametri **opzionali** con collegamento a `MainActivity`, quindi la firma testuale non è più identica a `valhalla`. Le richieste e il formato JSON Valhalla sono estesi con i campioni di quota DEM; selezione del percorso, guida, geometria BLE e firmware ESP32 restano invariati. **Non compilato né provato su dispositivo** per ordine esplicito dell'utente; il confronto statico delle firme pubbliche è positivo. Le differenze, i mockup non implementabili senza nuovi dati e i rischi sono annotati in [audit NewUI Stitch](docs/NEWUI_STITCH_AUDIT.md). **Baseline precedente `valhalla`:** integrazione Road DNA, ricerca turistica offline/online, MotoPack v2 (MotoMap v4, Photon SQLite v2/POI, Tourist SQLite v1, Valhalla V5), interfaccia Android, manuale, diagnostica ELF 16 KiB e toolchain Android (AGP 9.1.1, Gradle 9.3.1, NDK 29, compileSdk 37). Il workflow usa il nome SDK effettivo `platforms;android-37.0` per installare API 37. Correzioni CI native al mock di `--resume`. **Nessuna modifica al protocollo BLE o al firmware ESP32 in questi fix CI.**

**Verifiche della sessione NewUI (23/11):** nessuna build, test Gradle, test runner, CI, flash o prova hardware eseguita; **0 workflow runs GitHub Actions su `NewUI`** verificati il 23/11, branch avanti 66 commit rispetto a `valhalla` (nessuna divergenza sul baseline alla verifica). Eseguiti controlli statici dei sorgenti, confronto firma pubblica `NavigationScreen` identica a `valhalla` **soltanto prima dell'estensione opzionale del profilo**; verifica staticamente la compatibilità delle chiamate esistenti, controllo bilanciamento dei nuovi Kotlin e verifica SHA binari dei font su GitHub. Non sono test di compilazione. Quanto segue riguarda esclusivamente verifiche precedenti sulla baseline `valhalla`:

**Verifiche precedenti `valhalla`:**
- **Native:** job GitHub Actions **verde**, run [45017550666](https://github.com/thriller47/moto-gps-waveshare/actions/runs/45017550666), commit `c5bf531`.
- **Android:** job GitHub Actions **verde secondo conferma dell'utente** dopo la correzione SDK nel commit `10049f4`; ID della run finale non ancora annotato in questo documento. La precedente run [45017757425](https://github.com/thriller47/moto-gps-waveshare/actions/runs/45017757425) era fallita prima di Gradle per nome pacchetto SDK errato.
- **Samsung:** utente conferma build locale, installazione e funzionamento dell'APK; audit ELF 16 KiB **6/6 PASS**, nessun segmento LOAD non valido. Il risultato `zipalign` locale e la dimensione delle pagine del kernel Samsung non sono stati registrati separatamente.
- **ESP32:** job saltato nei run di correzione CI, **non** equivale a job verde su questo commit. Le precedenti prove hardware BLE/radio e navigazione restano valide solo per le revisioni effettivamente provate.
- **Rigenerazione completa MotoPack v2 su PBF Italia:** il wrapper e i test sintetici sono presenti; non attribuire automaticamente il successo di APK/importazione al completamento di una nuova ricostruzione integrale su dataset reale.

**Rischi aggiuntivi `NewUI`:** MotoPack v3, downloader Tilezen/SRTMv3 e import DEM ancora senza validazione reale; un MotoPack v2 precedente non è più importabile su `NewUI`. Per le quote offline non bastano i tile del grafo Valhalla: servono anche i tasselli DEM installati sul telefono. Resa grafica non verificata su telefono/orientamento, compatibilità dei componenti Material 3 da validare con build su ordine dell'utente, possibile restringimento della mappa nella home con risultati e tour in pausa, alcune schermate Stitch senza equivalente nei dati/callback correnti, font Dark Touring inseriti nel sorgente ma non ancora verificati su dispositivo, mappe OSMDroid non vettoriali. **Rischi e limiti precedenti:** test su moto reale dell'IMU QMI8658 (piega, verticale stimata, bias e vibrazioni); verifica finale dei dettagli Road DNA e dei provider online su dati reali; conferma di un ciclo completo di rigenerazione/importazione MotoPack v2; valutazione OpenFreeMap senza topologia OSM per rotonde complesse. Evitare di confondere `skipped` con `success` in CI.

**Prossima azione:** dopo autorizzazione esplicita eseguire **una sola** build Android consolidata di `NewUI`, correggere eventuali errori e provare il pannello altimetrico nell'anteprima con/senza DEM, orientamenti e accessibilità. Quindi verificare `/height` reale e rigenerazione DEM/MotoPack v3 su WSL; nel frattempo **non lanciare build/CI** né effettuare merge su `valhalla`.

## Sessione 24 novembre 2026 — integrazione selettiva Gemini-DEBUG

- Sorgente Gemini-DEBUG 174b3f69; selezione di patch, non merge integrale. Cache ZIP MotoMap (48 tile), repository persistente, distanza dai segmenti per limite velocità, ottimizzazione topologia e scene, cache SQLite Photon/POI, controlli import e risposte Valhalla, font variabili, lifecycle mappe, collegamento tour circolari.
- GNSS: consenso di tre fix accurati, orologio monotono, tolleranza skew in sorgente di produzione (non duplicato dei test), protezione sessioni e cancellazione ricalcoli dopo stop/arrivo. Test sorgente consenso/freshness aggiunti.
- IMU: filtro a dt reale, limite varianza, 40 campioni stabili per bias iniziale, singolo adattamento runtime e re-init periodico su errori; mantenuta risposta originale verticale (alpha 0.02). Nessuna modifica timing radio, PWR, mutex BLE, watchdog GATT, codec o zoom contestuale.
- Esclusi riuso anteprima fino a 150m, ETA proporzionale, latch stallo, hold rotonda 400m, modifiche replay e layout. Manuale: nessuna nuova procedura operativa.
- Verifica: utente riferisce test positivi e build verdi di Gemini-DEBUG. La revisione integrata NewUI richiede build e prova hardware. Nessuna build avviata.

## Sessione 23 novembre 2026 — grafico DEM spostato nell'anteprima Android (`NewUI`)

- **Commit fonte implementazione:** `c7311278035bac63e48e5f63046c5d384a907f1f`; rifinitura UI e documentazione: `adc30e29ae7770ac55c93ac9646b4e9c448098d5`, entrambi su `NewUI` con `[skip ci]`.

- **Decisione utente:** grafico, quota minima/massima, salita e discesa consultabili **solo durante la pianificazione** nella schermata Android con una card `Profilo altimetrico` espandibile. La card è dentro la lista scorrevole, quindi non copre la mappa o il pulsante `AVVIA NAVIGAZIONE`. Non introdurre quota/grafico nel BLE o ESP32, né nella UI di guida.
- **Sorgenti:** `ui/NavigationScreen.kt` (card espandibile visibile solo se `!isNavigating`, rimossi dati quota dalla card percorso condivisa), `ui/RoadDnaTerrainPanel.kt` (curve Road DNA separate dal profilo usato in preview), `viewmodel/NavigationViewModel.kt` (nuovi `StateFlow` DEM preview, richiesta online su espansione, cancellazione su cambio/chiusura/avvio), `MainActivity.kt` (tre parametri opzionali di `NavigationScreen` collegati). Nessun cambio a schema BLE, routing Valhalla, mappe, firmware ESP32.
- **Comportamento:** DEM già presenti usati senza rete; se assenti, una sola POST `/height` per selezione quando si apre la card. Eventuali risposte errate o 429/503 mostrano assenza quote; no retry automatico, no SQL turistico/ricerca Road DNA indiretta. Road DNA rimane curve, categorie e luoghi.
- **Compatibilità:** parametri e callback precedenti sono invariati; `NavigationScreen` ha **tre nuovi parametri opzionali**, quindi non è più testualmente identica a `valhalla`. Non eseguite build, test Gradle, CI, prova APK/telefono o hardware. Nuovi collegamenti e divieti di navigazione verificati staticamente; aspetto grafico e fetch DEM su rete restano da collaudare.
- **Documentazione:** aggiornati `docs/NEWUI_STITCH_AUDIT.md` e `docs/MANUALE_OPERATIVO.md`, lasciando evidenza storica della precedente collocazione in Road DNA.
- **Prossimo passo:** dopo autorizzazione esplicita, una sola build Android di `NewUI` e prova anteprima con/ senza DEM, verticale/orizzontale, prima del merge in `valhalla`.

## Sessione 23 novembre 2026 — font integrati e DEM `/height` su richiesta (`NewUI`)

- **Richiesta utente:** completare il recupero online della quota anche su itinerari fuori dal MotoPack e incorporare i font del mock Stitch. Modificato solo il branch `NewUI`; `valhalla`, BLE, firmware ESP32 e algoritmo di navigazione non sono stati modificati.
- **Fonte DEM online:** `routing/OnlineValhallaElevation.kt` interroga l'endpoint Valhalla pubblico FOSSGIS `/height` con la polilinea reale, campionata per distanza con massimo 4.500 vertici, richiesta POST `range=true`, parsing del solo `range_height` con campioni validi. Timeout 4/8 secondi, nessun retry, HTTP non 2xx e DEM nulli non alterano la rotta.
- **Attivazione e stato Android:** `NavigationViewModel.analyzeSelectedRoadDna()` pubblica subito la ricerca/analisi Road DNA offline; **solo se** manca un profilo DEM valido fa una singola richiesta `/height` per quella specifica `RouteOption` e aggiorna `RoadDnaSummary.elevation`. `RoadDnaSummary.elevationLoading` distingue attesa/assenza. Se route/request cambia, ignora il risultato. Nessuna rete in navigazione, GPS update, ricalcoli o BLE; nessun callback/firma pubblica `NavigationScreen` modificato.
- **Font:** i veri TTF variabili ufficiali delle famiglie Manrope e Space Grotesk di Google Fonts sono presenti in `platforms/android/app/src/main/res/font/`, utilizzati da `ui/MotoChatTheme.kt` per corpo testo e titoli/etichette, con attribuzione in `MotoChatInfoDialog.kt`. Licenze SIL OFL 1.1 integrali in `docs/licenses/MANROPE_OFL.txt` e `SPACE_GROTESK_OFL.txt`. Font disponibili nell'APK senza download a runtime.
- **Test introdotti:** `routing/OnlineValhallaElevationTest.kt` verifica costruzione, round-trip polyline6, parsing, mancanti e ordine range. Controlli statici effettuati: delimitatori Kotlin bilanciati, risorse binarie verificate tramite SHA su GitHub, firma `NavigationScreen` invariata; GitHub Actions su `NewUI` = 0. **Non eseguite build, test Gradle/unitari, installazione APK, rete `/height` reale, rebuild MotoPack/DEM o prove su moto.**
- **Rischi aperti:** timeout/429/503 o quota nulla del server demo, accuratezza campionamento e stima pendenze, resa font a schermo, eventuali errori di compilazione, download DEM su WSL, dimensione del MotoPack v3. Documentazione aggiornata in `docs/NEWUI_STITCH_AUDIT.md` e `docs/MANUALE_OPERATIVO.md`.
- **Prossima azione:** attendere ordine esplicito dell'utente per una sola build Android consolidata su `NewUI`, quindi correggere soltanto problemi riproducibili e verificare un percorso con e senza DEM; merge in `valhalla` solo dopo approvazione.

## Sessione 23 novembre 2026 — Road DNA quote e curve / MotoPack v3 (`NewUI`, non compilato)

- **Richiesta:** l'utente autorizza la rigenerazione Valhalla e la rottura della compatibilità col vecchio pacchetto. Rimangono invariati firmware ESP32, payload BLE, geometria di navigazione e firme/callback pubblici dei Composable.
- **Nuovi file Kotlin:** `viewmodel/RoadDnaTerrain.kt` (campionamento geometria, curve classificate e salita/discesa DEM), `ui/RoadDnaTerrainPanel.kt` (grafico e statistiche Dark Touring), `routing/ValhallaElevationImporter.kt` (estrazione sicura tasselli Skadi).
- **Modificati:** `OfflineValhallaRouter.kt` e `OnlineValhallaRouter.kt` chiedono quote ogni 30 m; `ValhallaRouteAdapter` conserva `elevation_profile` separato dalle coordinate BLE; `NavigationScreen.kt` mostra Road DNA reale e anteprima; `RoadDnaAnalysis.kt` utilizza il nuovo indice curve; `UnifiedOfflineMapImporter.kt` richiede MotoPack v3, verifica tutti i cinque componenti e installa i DEM per l'uso offline.
- **WSL:** `scripts/offline_map/motopack.py` richiede ora `elevation-italy.zip` in MotoPack **v3** con SHA-256; `prepare_elevation_zip.py` assembla il dataset Skadi; `build_valhalla_moto_v5.sh` configura DEM; `build_motopack_wsl.sh` usa `valhalla_build_elevation`, cache persistente `~/motogps-build/DEM-Italia` e output v3; `rebuild_motopack_wsl.sh` accetta sei artefatti. Procedure spiegate nel manuale.
- **Test sorgente:** aggiunti `RoadDnaTerrainTest.kt`, aggiornati fixture Python `test_motopack.py` e test staging importatore. **Non eseguiti** test runner, build Android/native, GitHub Actions, WSL DEM downloader, MotoPack reale, installazione, simulatori o prova hardware per ordine precedente dell'utente.
- **Rischi:** compatibilità API Valhalla Mobile, riferimenti DEM, memoria/ingombro su telefono, download Skadi e risultati numerici curve/quote richiedono validazione; il vecchio MotoPack v2 è intenzionalmente incompatibile con l'importatore sperimentale. Se il DEM è assente o incompleto l'app mostra "Quote non disponibili" invece di valori fittizi. Font Stitch non ancora incorporati.
- **Prossimo passo:** verificare dipendenze WSL e attendere ordine utente per una sola build Android; successivamente confermare importazione MotoPack v3 e Road DNA su un percorso reale prima di qualunque merge.

## Sessione 23 novembre 2026 — `NewUI` (redesign Android, nessuna build)

- **Branch:** `NewUI`, derivato dall'ultimo `valhalla` disponibile all'inizio della sessione. Nessun merge e nessuna modifica di `valhalla`; i commit sono marcati `[skip ci]`.
- **Implementato:** `MotoChatTheme.kt` (palette/typo/shape Dark Touring); `MainActivity.kt` (tema attivato); `MotoChatInfoDialog.kt`; `RoutePreferencesDialog.kt`; `RouteWaypointsSheet.kt`; `ValhallaRoutingSection.kt`; `NavigationScreen.kt` (OfflineMapsDialog, dialog Road DNA/chiavi/diagnostica/pausa, tour salvati, builder circolare, home e anteprima rotta). Adattamento visuale dei mockup HTML/Tailwind di Stitch a Jetpack Compose Material 3.
- **Invarianti:** firme pubbliche dei Composable interessati confrontate con `valhalla` senza differenze, callback e relativi argomenti conservati; schermate Android e collegamento NavigationViewModel rimangono nello stesso flusso. Non modificati firmware ESP32, librerie native, BLE, calcolo Valhalla, MotoPack, modello di ricerca, licenze o manuale operativo.
- **Verifiche realmente eseguite:** ispezione manuale dei mockup e dei file Kotlin, confronto statico dei parametri e controllo delimitatori del sorgente. **NON eseguite:** build Android/native/ESP32, test unitari/strumentali, preview Compose, test UI, prova su dispositivo/strada. La correttezza di compilazione e la fedeltà visiva non sono ancora dimostrate.
- **Differenze aperte:** schermate altimetriche/curvatura/GPX senza dati o API di presentazione adeguati, mancanza dei font Space Grotesk/Manrope, incongruenze nell'archivio Stitch (screenshots e token), mappe basate su osmdroid anziché mockup vettoriale, possibili riduzioni dello spazio mappa e touch-target da ispezionare. Dettaglio senza omissioni nel documento `docs/NEWUI_STITCH_AUDIT.md`.
- **Manuale:** verificato come requisito; nessuna nuova procedura operativa o cambio toolchain/ADB/flash/import, quindi non modificato.
- **Prossima azione univoca:** attendere ordine dell'utente per lanciare **una** build consolidata su `NewUI`, poi validare le schermate e correggere le incongruenze secondo priorità; eventuale merge in `valhalla` solo dopo consenso esplicito.

## Architettura e dati

| Componente | Funzione | Note |
| --- | --- | --- |
| Android | Ricerca, routing, tour, Road DNA, gestione MotoPack, diagnostica e collegamento BLE | L'APK installato può differire dall'ultimo commit |
| Valhalla V5 | Routing e ricalcoli offline moto; fallback online ove previsto | Il grafo Italia non copre automaticamente i Paesi confinanti |
| MotoMap v4 | Strade grigie e topologia per la scena ESP32 | Distinto dal grafo Valhalla |
| Photon SQLite v2 + POI | Ricerca offline indirizzi e attività | Fallback online quando disponibile; i POI dipendono dai tag OSM |
| Tourist SQLite v1 | Luoghi e aree di interesse per Road DNA offline | Dati OSM, non garanzia di accessibilità o apertura |
| ESP32-S3 AMOLED 1.75C | Display LVGL 466×466, BLE, IMU QMI8658 | Non cambiare protocollo senza test incrociati |
| Simulatore LVGL/WASM | Replay GPS e verifica grafica | Non simula tutti i sei assi dell'IMU |

**Input mappe:** `C:\MotoGPS\PBF`: `italy-latest.osm.pbf`, `italy.poly`, `photon-italy.jsonl.zst`. La rigenerazione completa è `scripts/offline_map/rebuild_motopack_wsl.sh` in WSL Ubuntu 24.04, con ambiente `~/valhalla-env`; ricostruisce Valhalla dal PBF senza riutilizzare il vecchio `valhalla_italia_v5.tar.zst`. I file sorgenti non vanno cancellati o sovrascritti.

## Decisioni da preservare

- **UI navigazione:** rotta verde, strade grigie, badge limite di velocità, distanza all'arrivo e distanza alla svolta sottostante; zoom contestuale su rotonde/tornanti. Non reintrodurre frecce di svolta ordinarie o pagine velocità, bussola e musica.
- **Tappe:** marker gialli numerati, destinazione `A` blu, bandiera a scacchi ridotta; salto tappa con conferma, mai saltare l'arrivo.
- **Routing/ricerca:** Valhalla, MotoMap, Photon e Tourist hanno ruoli distinti; online non significa offline. Geoapify e OpenTripMap sono opzionali e richiedono credenziali dell'utente, mai nel repository o nei log.
- **IMU:** QMI8658 senza magnetometro; il GPS resta autorevole per routing/progressione, l'IMU assiste l'orientamento grafico. Non introdurre nuove fusioni senza misure.
- **Git e CI:** `valhalla` è l'unica baseline; merge solo con consenso esplicito. Una sola CI consolidata al termine delle modifiche di codice. **Per questa sessione solo documentazione, nessuna build o flash.**

## Prossimi collaudi e attività

| Priorità | Verifica | Stato |
| --- | --- | --- |
| P0 | Audit motociclistico QMI8658: cavalletto, rettilineo, piega 15/30/45°, frenata, perdita/ripresa GNSS | Da implementare/provare in sicurezza |
| P1 | Road DNA: schede turistiche, ricerca intorno alla mappa, ricerca lungo rotta, provider e accessibilità POI | Funzionalità integrate; approfondimenti su percorsi reali |
| P1 | MotoPack v2: ricostruzione completa da PBF e importazione, verifica Tourist/Photon/Valhalla | Procedura documentata; confermare collaudo integrale |
| P1 | OpenFreeMap online: anelli/uscite in rotonde complesse senza topologia originale | Fallback geometrico; da verificare su strada |
| P1 | Hardware: BLE, marcatori, ricalcoli e standby in uso motociclistico | Collaudi precedenti positivi; non generalizzare a ogni revisione |

## Documenti da usare

- [Manuale operativo per principianti](docs/MANUALE_OPERATIVO.md): installazione strumenti, aggiornamento repo, Android/ADB, ESP-IDF/flash, WSL/MotoPack, simulatore, radio e log.
- [Roadmap](docs/ROADMAP.md): richieste future e verifiche non concluse.
- [Road DNA design](docs/ROAD_DNA_DESIGN.md): schema, limiti e criteri di qualità.
- [Snapshot storico integrale al 22/11](docs/archive/PROJECT_STATE_SNAPSHOT_2026-11-22.md): cronologia precedente, inclusi errori e ipotesi superati. **Non usare come istruzioni operative correnti.**

# Moto Chat GPS — stato operativo del progetto

## STATO ATTUALE — RIPARTIRE DA QUI

**Aggiornato: 23 novembre 2026.** Repository: `thriller47/moto-gps-waveshare`. **Branch di integrazione: `valhalla`**; branch sperimentale UI attivo: **`NewUI`**, creato dal ramo `valhalla` il 23/11/2026; nessun branch sperimentale Road DNA attivo (il branch `feature/road-dna-motopack-v2` è stato integrato con PR [#79](https://github.com/thriller47/moto-gps-waveshare/pull/79) e rimosso). `main` non esiste più. Questo è il punto di ripartenza; per le diagnosi e i passaggi intermedi vedere lo [snapshot storico completo del 22/11](docs/archive/PROJECT_STATE_SNAPSHOT_2026-11-22.md) e l'[archivio precedente al 19/11](docs/archive/PROJECT_STATE_PRE_CLEANUP_2026-11-19.md).

**Ultimo lavoro su `NewUI` (23/11):** prima trasposizione del design Google Stitch "Dark Touring" nei Composable Android: tema/tipografia, root `MaterialTheme`, BottomSheet, dialoghi e due viste (home e anteprima percorso) di `NavigationScreen.kt`. Nessuna modifica intenzionale a firme dei Composable, stati/callback verso `NavigationViewModel`, geometria delle mappe, routing o BLE. **Non compilato né provato su dispositivo** per ordine esplicito dell'utente; il confronto statico delle firme pubbliche è positivo. Le differenze, i mockup non implementabili senza nuovi dati e i rischi sono annotati in [audit NewUI Stitch](docs/NEWUI_STITCH_AUDIT.md). **Baseline precedente `valhalla`:** integrazione Road DNA, ricerca turistica offline/online, MotoPack v2 (MotoMap v4, Photon SQLite v2/POI, Tourist SQLite v1, Valhalla V5), interfaccia Android, manuale, diagnostica ELF 16 KiB e toolchain Android (AGP 9.1.1, Gradle 9.3.1, NDK 29, compileSdk 37). Il workflow usa il nome SDK effettivo `platforms;android-37.0` per installare API 37. Correzioni CI native al mock di `--resume`. **Nessuna modifica al protocollo BLE o al firmware ESP32 in questi fix CI.**

**Verifiche della sessione NewUI (23/11):** nessuna build, test Gradle, CI, flash o prova hardware eseguita; il branch `NewUI` non è tra i trigger push del workflow `project-build.yml`. Sono stati effettuati solo lettura dei sorgenti, confronto statico delle firme dei Composable e controllo di bilanciamento sintattico delimitatori. Quanto segue riguarda esclusivamente verifiche precedenti sulla baseline `valhalla`:

**Verifiche precedenti `valhalla`:**
- **Native:** job GitHub Actions **verde**, run [45017550666](https://github.com/thriller47/moto-gps-waveshare/actions/runs/45017550666), commit `c5bf531`.
- **Android:** job GitHub Actions **verde secondo conferma dell'utente** dopo la correzione SDK nel commit `10049f4`; ID della run finale non ancora annotato in questo documento. La precedente run [45017757425](https://github.com/thriller47/moto-gps-waveshare/actions/runs/45017757425) era fallita prima di Gradle per nome pacchetto SDK errato.
- **Samsung:** utente conferma build locale, installazione e funzionamento dell'APK; audit ELF 16 KiB **6/6 PASS**, nessun segmento LOAD non valido. Il risultato `zipalign` locale e la dimensione delle pagine del kernel Samsung non sono stati registrati separatamente.
- **ESP32:** job saltato nei run di correzione CI, **non** equivale a job verde su questo commit. Le precedenti prove hardware BLE/radio e navigazione restano valide solo per le revisioni effettivamente provate.
- **Rigenerazione completa MotoPack v2 su PBF Italia:** il wrapper e i test sintetici sono presenti; non attribuire automaticamente il successo di APK/importazione al completamento di una nuova ricostruzione integrale su dataset reale.

**Rischi aggiuntivi `NewUI`:** resa grafica non verificata su telefono/orientamento, compatibilità dei componenti Material 3 da validare con build su ordine dell'utente, possibile restringimento della mappa nella home con risultati e tour in pausa, alcune schermate Stitch senza equivalente nei dati/callback correnti, font Google Stitch non inclusi nell'archivio, mappe OSMDroid non vettoriali. **Rischi e limiti precedenti:** test su moto reale dell'IMU QMI8658 (piega, verticale stimata, bias e vibrazioni); verifica finale dei dettagli Road DNA e dei provider online su dati reali; conferma di un ciclo completo di rigenerazione/importazione MotoPack v2; valutazione OpenFreeMap senza topologia OSM per rotonde complesse. Evitare di confondere `skipped` con `success` in CI.

**Prossima azione:** dopo l'autorizzazione esplicita dell'utente eseguire una sola build consolidata di `NewUI` e verificare home, anteprima rotta, dialoghi, orientamenti e accessibilità dei pulsanti; fino ad allora **non lanciare build** e **non effettuare merge su `valhalla`**.

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

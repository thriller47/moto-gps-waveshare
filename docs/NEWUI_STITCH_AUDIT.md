# NewUI — audit integrazione Google Stitch / Moto Chat GPS

**Data:** 23 novembre 2026  
**Repository:** `thriller47/moto-gps-waveshare`  
**Branch sperimentale:** `NewUI` (creato da `valhalla`).  
**Stato:** sorgenti aggiornati, **NON compilati e NON testati** per scelta esplicita dell'utente. Nessuna CI avviata. Nessun merge in `valhalla`.

## Vincoli inderogabili

- Nessuna modifica alle firme pubbliche dei Composable, ai parametri che ricevono, agli stati osservati, alle callback provenienti da `NavigationViewModel` o al loro significato.
- Nessun intervento su routing Valhalla, Android-BLE-ESP32, protocollo, MotoPack, modelli dati, map matching o ricerca.
- Applicazione soltanto del linguaggio visivo, senza mostrare dati finti o creare pulsanti senza effetto. L'ordine logico di alcuni componenti visuali è cambiato.
- Le preview Stitch sono **HTML/CSS/Tailwind**, non sorgenti Jetpack Compose utilizzabili direttamente.
- Una build di verifica e i controlli Android sul dispositivo verranno fatti **solo su ordine dell'utente**.

## Sorgenti Android modificati

| File | Intervento |
| --- | --- |
| `platforms/android/app/src/main/java/com/motogps/android/ui/MotoChatTheme.kt` | Palette semantica Dark Touring, tipografia e forme; mantiene `MotoChatColors` e `MotoChatDarkColorScheme`. |
| `platforms/android/app/src/main/java/com/motogps/android/MainActivity.kt` | Attiva typography/shapes nel `MaterialTheme` root, senza modificare lo stato raccolto da `NavigationViewModel`. |
| `platforms/android/app/src/main/java/com/motogps/android/ui/MotoChatInfoDialog.kt` | Layout crediti/licenze, conservando testi e link originali. |
| `platforms/android/app/src/main/java/com/motogps/android/ui/RoutePreferencesDialog.kt` | Bottom sheet compatto, chip di percorso, preferenze e slider curvatura. |
| `platforms/android/app/src/main/java/com/motogps/android/ui/RouteWaypointsSheet.kt` | Gestione tappe, riordinamento, ricerca, aggiunta/rimozione. |
| `platforms/android/app/src/main/java/com/motogps/android/ui/ValhallaRoutingSection.kt` | Scheda gestione grafo offline. |
| `platforms/android/app/src/main/java/com/motogps/android/ui/NavigationScreen.kt` | Dialog integrati, home cockpit, anteprima percorso, Road DNA, credenziali API, tour circolare, tour salvati, coffee timeout e diagnostica. |

## Corrispondenza delle 15 cartelle nell'esportazione Stitch

| Cartella Stitch | Esito in NewUI | Cosa resta aperto |
| --- | --- | --- |
| `moto_chat_gps_home_cockpit` | **Integrato visualmente**: titolo, ricerca, stato BLE ESP32, azioni tour e dock inferiore, map card. | Posizione e dimensione della mappa reali da verificare su schermi piccoli; screenshot contiene sezioni con dati esemplificativi non nel ViewModel. |
| `moto_chat_gps_route_preview_options_1` | **Integrato parzialmente**: varianti Valhalla a schede, selezione e avvio, mappa e stato connessione. | Non replicati grafici o telemetria aggiuntiva inesistente nel contratto attuale. |
| `moto_chat_gps_route_preview_options_2` | **Integrato parzialmente** nello stesso layout di anteprima selezionabile. | Lo Stitch presenta ulteriori dettagli analitici non esposti dal ViewModel corrente. |
| `moto_chat_gps_tour_builder_road_dna` | **Integrato visualmente**: raggio distanza, direzione cardinali, tappe obbligatorie e ricerca, richiamo Road DNA. | In archivio manca `screen.png` della cartella: solo `code.html`; UI reale da confrontare con HTML. |
| `moto_chat_gps_mappe_offline_motopack_v2` | **Integrato parzialmente** nel dialog Mappe offline e nella scheda Valhalla: import pacchetto e controlli separati invariati. | `code.html` contiene sia "Mappe offline & Pacchetti" sia "Tour Circolare con Tappe". Lo `screen.png` non è una rappresentazione coerente dell'intero componente offline. |
| `moto_chat_gps_api_road_dna` | **Integrato parzialmente** nei dialog Road DNA/API. | `code.html` combina "API Road DNA" e builder del tour; lo screenshot mostra parte del builder, non una pagina API isolata. |
| `moto_chat_gps_api_road_dna_credential_manager` | **Integrato** con due schede Geoapify e OpenTripMap e stato configurato/non configurato. | I callback di salva/verifica/elimina sono gli originali; schermata dedicata non introdotta. |
| `moto_chat_gps_pausa_caff_timeout_display` | **Integrato** nel dialog esistente: descrizione del risveglio, slider 1–30 minuti e scelte rapide 5/10/15/30. | Confermare usabilità del dialog su telefono e persistenza del timeout. |
| `moto_chat_gps_diagnostica_e_replay_gps` | **Integrato parzialmente**: stato registrazione, livelli BASE/DEBUG e esportazione ZIP tramite callback originale. | Durata/volume log, contatori e statistiche del mock non sono esposti dai parametri; non inventati. |
| `moto_chat_gps_informazioni_e_licenze` | **Integrato**: pannello dark, attribuzioni OSM/Valhalla/Photon con link reali. | Non inventata una versione APK presentata nel mock; MapLibre non è una libreria UI Android attestata nel codice, perciò evitare attribuzioni tecniche non verificate. |
| `moto_chat_gps_i_miei_tour_salvati` | **Integrato parzialmente**: tour conservati a card con nome, km richiesti, tappe e due opzioni di partenza. | Le statistiche di durata, dislivello e dettagli simulati nel mock non sono proprietà del tipo `SavedCircularTour`; non visualizzati. |
| `moto_chat_gps_esporta_e_condividi_gpx` | **Non implementato** come nuova funzione. | Nel Composable attuale mancano callback per serializzazione/condivisione GPX e flussi appositi: introdurli violerebbe i vincoli dati dall'utente. Non confondere con la preesistente esportazione diagnostica ZIP. |
| `moto_chat_gps_profilo_altimetrico_espanso` | **Implementato nel codice**: grafico DEM e dislivelli nel pannello Road DNA. | Build/importazione MotoPack v3 e fedeltà grafica da verificare. |
| `moto_chat_gps_profilo_altimetrico_touring` | **Implementato parzialmente**: profilo Road DNA e dati quota sulla scheda del percorso. | Non è stata creata una pagina Touring autonoma; senza DEM quote assenti. |
| `moto_chat_gps_analisi_curve_touring` | **Implementato parzialmente**: conteggio dolci/medie/strette/tornanti stimati e indice. | Solo geometria, nessuna misura di aderenza/raggio reale/velocità sicura. |

## Incongruenze di asset, design e comportamento da risolvere

1. **Colori incompatibili nello stesso Design System:** il front matter YAML di `dark_touring/DESIGN.md` dichiara `background: #101413` e `primary: #70F6AF`, mentre la sezione narrativa dichiara esplicitamente `Background #080C0B` e `Primary Accent #50D995`. È stata adottata la palette narrativa, coerente con route mint e revisioni Android già presenti; richiede eventuale conferma grafica.
2. **Font risolti nel codice (23/11):** i due font variabili ufficiali Space Grotesk e Manrope sono stati inseriti in `res/font` e configurati in `MotoChatTheme.kt` per titoli e testi. Le licenze SIL OFL 1.1 sono in `docs/licenses/` e attribuite nel dialog Informazioni. La resa tipografica sul dispositivo va ancora verificata con la prima build.
3. **Riferimenti visivi ambigui:** gli HTML della cartella Mappe offline e della cartella API Road DNA contengono markup di più schermate e screenshot riferiti prevalentemente al tour builder; la cartella principale tour builder non contiene `screen.png`. La traduzione privilegia il nome funzionale delle sezioni reali.
4. **Tecnologia cartografica:** le schermate Stitch raffigurano mappe scure/vettoriali; il progetto usa la vista `osmdroid` su `MAPNIK` con filtro notturno. Cambiare renderer, tasselli o fonte cartografica sarebbe fuori ambito e potrebbe pregiudicare routing e waypoint. Non è stata sostituita la mappa.
5. **Guida a frecce vs requisiti storici:** la descrizione Stitch menziona "routing turn arrows", ma `PROJECT_STATE.md`/`AGENTS.md` vietano le normali frecce di svolta nel dispositivo ESP32. Rimane la navigazione a linea verde/distanza/zoom; nessuna freccia ripristinata.
6. **Dati/stati mancanti (aggiornamento):** GPX, pendenze puntuali certificate, stime avanzate dei tour, dimensioni/durata dei log e RSSI esatto. Altimetria e analisi curve sono integrate a livello sorgente su `NewUI`, ma non ancora collaudate.
7. **Touch target e spazi:** i nuovi pulsanti principali sono generalmente 52–56dp, ma alcune `IconButton`, `TextButton` e controlli preesistenti di dettagli Road DNA possono ancora essere inferiori; va fatta verifica sistematica con guanti e su schermi piccoli, non attribuire conformità totale al requisito 52dp.
8. **Dialoghi e scrolling:** parte dei dialog Road DNA mantiene il comportamento scrolling/stato precedente per non rompere il flusso; non tutti sono copie 1:1 dei mock. Controllare overflow e accessibilità del pulsante mappa, soprattutto con tastiera e landscape.
9. **Densità home/mappa:** con search results, tour in pausa e dock inferiore, la vista mappa pesata può restringersi. Non è stata introdotta una nuova regola di visibilità che nasconda stati precedenti; occorre prova visiva.
10. **Indicatori BLE:** badge colore connesso/non connesso basato su `NavigationReconnectPolicy.isReady(connectionState)`, non su confronto approssimativo di testo; lo stato originale resta visibile. Lo status "ricerca" non possiede sempre semantica distinta nei parametri attuali e può avere lo stesso colore di "scollegato".
11. **Valori/etichette demo dei mock:** nessuna distanza, ETA, quota, versione APK, livello batteria, nome tour o località demo è stata codificata come valore reale.
12. **Fonte delle licenze:** attribuzioni originali mantenute e cliccabili; alcune etichette extras presenti nello Stitch non sono state importate se non verificabili come componenti effettivamente usati.
13. **Qualità non validata:** nessuna compilazione o snapshot test effettuato. Rimangono da controllare compilazione con versioni Material3/Compose correnti, overflow, semantic/accessibility e colori sul dispositivo AMOLED. Il controllo statico dei delimitatori non sostituisce Gradle.
14. **Test manuali precedenti:** il successo Samsung ELF 16 KiB e i tre check verdi su `valhalla` non certificano `NewUI`; non attribuirli a questo branch.
15. **Istruzioni AGENTS vs heading documentale:** `AGENTS.md` esemplifica il titolo esatto "Moto GPS — Project State", mentre il documento usa da tempo "Moto Chat GPS — stato operativo del progetto". La sezione obbligatoria di handoff `## STATO ATTUALE — RIPARTIRE DA QUI` è presente e rimane prima sezione.

## Verifiche statiche eseguite

- Confermata identità testuale delle firme pubbliche dei Composable `NavigationScreen`, `RouteMapView`, `MainMapView`, `OfflineMapsDialog`, `MapDownloadProgressBox`, `MotoChatInfoDialog`, `RoutePreferencesDialog`, `RouteWaypointsSheet`, `ValhallaRoutingSection` tra `valhalla` e `NewUI`.
- Ispezionati collegamenti dei callback alle azioni esistenti. I pulsanti legacy sono stati ricollocati, non rimossi dal flusso. Alcuni callback di test/elimina provider sono ora rappresentati da un solo call-site di una funzione locale riusata per entrambe le schede; i due provider passano i loro ID originali.
- Dopo una correzione di un `Surface` nel pannello Diagnostica, controllo statico di parentesi/graffe/parentesi quadre di `NavigationScreen.kt` positivo (esclusi commenti e stringhe).
- **Non eseguiti:** `gradlew`, GitHub Actions, Kotlin compiler, preview Compose, APK, installazione ADB, unit tests, native, ESP32 e prova hardware.

## Prossimo passo

Attendere **ordine esplicito** prima della prima build; eseguire una sola build Android consolidata su `NewUI`, registrare errori di compilazione e schermate reali, risolvere solo dopo diagnosi e senza perdere le firme/callback. Le estensioni altimetria/analytics al ViewModel sono ora implementate nel codice, GPX resta una funzione separata da concordare. Valutare merge in `valhalla` **solo dopo validazione e autorizzazione**.


## Aggiornamento 23 novembre 2026 — altimetria e analisi curve

- **App Android:** `RoadDnaTerrain.kt`, `RoadDnaTerrainPanel.kt` e `NavigationScreen.kt` mostrano classificazione e densità delle curve, tornanti indicativi e profilo DEM reale con dislivelli; nessuna nuova firma/callback pubblica di `NavigationScreen`.
- **Routing:** le richieste Valhalla chiedono quote ogni 30 m, l'adattatore conserva una serie separata dalla geometria BLE. Le risposte senza quote danno uno stato esplicito, mai valori inventati.
- **MotoPack v3:** un quinto componente `elevation/elevation-italy.zip` contiene i tasselli Skadi necessari in esecuzione sull'Android. `UnifiedOfflineMapImporter` li verifica e `ValhallaElevationImporter` li installa, quindi il factory Valhalla punta alla cartella DEM.
- **Rigenerazione:** `valhalla_build_elevation` acquisisce DEM Tilezen/SRTMv3, cache esterna all'output; il generatore grafo usa `--additional-data-elevation`. Ora il MotoPack v2 non è accettato da questo importatore, con consenso dell'utente.
- **Restano da verificare:** disponibilità CLI su WSL, rete/coverage Tilezen, tempo/spazio, ZIP/import Android, API v0.6.6 nel progetto, build e rendering, indici curve su strade reali, resa dei font Stitch sul dispositivo ancora non verificata. Nessuna build, CI, test runner o navigazione hardware eseguiti in questa sessione.

## Aggiornamento 23 novembre 2026 — `/height` online e font Dark Touring

- **Online DEM:** `routing/OnlineValhallaElevation.kt` prepara una polyline6 dalla vera geometria selezionata, limita i punti a 4.500 (campionamento a distanza non inferiore a 100 m), invia una sola POST `/height` e convalida `range_height`; quote nulle o insufficienti restano non disponibili. Nessun ricalcolo rotta, auto-retry o chiamata in navigazione.
- **Attivazione:** soltanto con apertura Road DNA su una rotta priva di quote utilizzabili, dopo la pubblicazione del risultato turistico offline. `NavigationViewModel` pubblica lo stato `elevationLoading` e poi il profilo, se valido; una selezione rotta cambiata invalida la risposta. Non modifica i dati BLE/ESP32.
- **Risorse font:** due TTF variabili ufficiali Google Fonts (Manrope e Space Grotesk), inclusi nell'APK senza dipendenza dalla rete a runtime; OFL in `docs/licenses/MANROPE_OFL.txt` e `SPACE_GROTESK_OFL.txt`. `MotoChatTheme.kt` usa Space Grotesk per titoli/etichette e Manrope per corpo testo.
- **Test aggiunti:** `routing/OnlineValhallaElevationTest.kt` verifica payload, parsing delle quote, dati nulli e ordinamento. Non eseguiti build, test runner o prove reali su server pubblico; verificare HTTP 429/503, timeout e resa sul telefono nella prima sessione autorizzata.

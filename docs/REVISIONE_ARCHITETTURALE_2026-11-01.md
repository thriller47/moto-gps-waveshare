# Moto GPS — Revisione architetturale (2026-11-01)

Stato: DA RIPRENDERE PIÙ AVANTI. Nessuna implementazione autorizzata ora.

## ESP32
- ESP-01: pulizia UI legacy (bassa priorità).
- ESP-02: conteggio perdite ring PSRAM diagnostico (alta).
- ESP-03: verificare limite estrapolazione MapOriginPredictor (alta).
- ESP-04: misurare copie NavSnapshot e lock LVGL (media).
- ESP-05: priorità al traffico BLE navigazione rispetto ai log (alta).

## Android
- AND-01: circuit-breaker Overpass e cache prudente (media).
- AND-02: test del contatore svolta prematuro (alta).
- AND-03: estrarre collaboratori da NavigationViewModel (media).
- AND-04: profilare logging asincrono già presente (bassa).
- AND-05: heading GPS stabile durante soste e partenze (alta).

## Regole di ripresa
Conservare valhalla stabile; test/openfreemap-mvt separato. Nessuna nuova freccia; zoom 3.4x e UI concordata; nessun logger flash; PSRAM/BLE 60 minuti; nessun tasto cancellazione. Test dedicati e verifica hardware prima di ogni merge.

Ordine suggerito: ESP-02/05, AND-02/05, ESP-03/04 e AND-01, infine ESP-01/AND-03/04. Verificare sempre codice aggiornato e log prima di intervenire.
================================================================================
MOTO GPS (moto-gps-waveshare) — REVISIONE ARCHITETTURALE E MIGLIORIE CONSIGLIATE
Ramo di riferimento: valhalla (unico branch di integrazione)
Data revisione: 2026-11-01
================================================================================

1. VINCOLI E LIMITI DI INTERVENTO RISPETTATI (da AGENTS.md e PROJECT_STATE.md)
--------------------------------------------------------------------------------
Ogni proposta sotto elencata rispetta tassativamente i seguenti paletti:
- Ramo target: esclusivamente `valhalla`. Nessun riferimento o merge verso il
  ramo eliminato `main` o il ramo storico `signal`.
- Guida di svolta: mantenuto lo zoom progressivo 3.4x sulla linea verde della
  rotta. Nessuna reintroduzione di frecce di svolta o frecce di rotatoria
  (nemmeno in assenza delle strade grigie MotoMap).
- Layout UI ESP32 invariato: distanza prossima svolta a ~ore 8 (28 px, <= 200 m),
  distanza alla destinazione centrale, bandiera a scacchi di arrivo, badge
  limite di velocità e strade grigie di contesto con etichette sopra la rotta.
  Nessuna pagina/funzione Speed, Compass, Music o OTA nel flusso di navigazione.
- Diagnostica ESP32: vietato riabilitare il vecchio logger su flash SPI
  (`diag` 0xC20000) durante la marcia. Mantenuta l'architettura PSRAM (64 KiB,
  2048 record numerici, TTL 60 minuti, max 4 record/s su BLE, OFF al boot e
  reset a OFF su disconnessione, nessun pulsante di cancellazione manuale).
-Separazione netta tra asset offline (grafi Valhalla *.tar.zst e MotoMap ZIP)
  e ricerca destinazioni online (Photon).
- Compatibilità protocollo Android <-> BLE <-> ESP32 preservata.


2. MIGLIORIE CONSIGLIATE — FIRMWARE ESP32 (ESP-IDF / NimBLE / LVGL)
--------------------------------------------------------------------------------

[ESP-01] Pulizia delle superfici legacy nel contratto UI (moto_nav_ui.h / .cpp)
- Osservazione: In `moto_nav_ui.h` e `phone_nav_bridge.h` sono ancora presenti
  enum, callback e membri di stato legati a pagine rimosse dalle specifiche
  concordate (`MOTO_UI_PAGE_SPEED`, `MOTO_UI_PAGE_COMPASS`, `MOTO_UI_PAGE_MUSIC`,
  `moto_music_state_t`, `render_media_state_`, `music_page_enabled_`).
- Miglioria: Isolare o rimuovere a tempo di compilazione i rami morti delle
  pagine non utilizzate nel build di navigazione (mantenendo intatti i file
  separati dell'esperimento `signal_*`). Questo riduce l'impronta in RAM di
  `moto_ui_state_t` e semplifica la macchina a stati in `PhoneNavBridge`.

[ESP-02] Gestione saturazione e contatore drop nel ring PSRAM (moto_diagnostics_ram.cpp)
- Osservazione: Con l'estensione del TTL diagnostico a 60 minuti e il limite di
  trasmissione BLE fissato a 4 record/s, nelle sessioni `LOG DEBUG` ad alta
  frequenza (es. tracciamento `RenderPhase`) il ring da 2048 eventi può
  saturarsi prima che il task di background dreni i batch `0x22`.
- Miglioria: Senza alterare il formato dei pacchetti BLE di navigazione né
  toccare la flash, riservare uno dei parametri `int32_t` (a..e) del primo
  evento utile dopo una saturazione (o introdurre una politica di drop che
  scarti i soli eventi `RenderPhase` preservando `Boot`, `Link` e
  `RenderLockTimeout`) per segnalare il numero esatto di record sovrascritti.
  In questo modo l'analisi del file `esp32-current.log` su Android mostrerà con
  certezza eventuali gap temporali nella parte finale dei 60 minuti.

[ESP-03] Fluidità di traslazione mappa e interpolazione (MapOriginPredictor / PhoneNavBridge)
- Osservazione: Dal resoconto su strada del 31 ottobre emerge che, risolti i
  blocchi disattivando il flash logger e verificato lo zoom 3.4x, la
  traslazione della mappa nel firmware precedente risultava ancora a scatti,
  motivo per cui è stato introdotto `MapOriginPredictor` (`612a0c5`).
- Miglioria: Verificare che nel task di rendering di `PhoneNavBridge`
  (`present_motion()` / `render_pending()`) l'aggiornamento ad alta frequenza
  guidato da `MotionHeadingSensor` (125 Hz) e `MapOriginPredictor` applichi un
  clamp sull'estrapolazione massima (es. non oltre 1.2–1.5 secondi dall'ultimo
  anchor GPS/geometry) per evitare rimbalzi visivi ("rubber-banding") quando
  arriva un nuovo `NavigationSnapshot` su BLE in uscita da una curva o galleria.

[ESP-04] Ottimizzazione delle copie di NavSnapshot e lock LVGL (phone_nav_bridge.cpp)
- Osservazione: `NavSnapshot` occupa diversi kilobyte perché include le
  geometrie della finestra mappa. `PhoneNavBridge` mantiene correttamente
  `snapshot_` e `render_snapshot_` per rilasciare `state_mutex_` prima di
  acquisire `board_port_lock(250)`.
- Miglioria: Evitare la copia integrale dei vettori `road_points` e
  `building_points` da `snapshot_` a `render_snapshot_` nei tick in cui è
  cambiato solo lo stato di moto (`present_motion`, heading/posizione) ma
  `map_scene_revision` e `geometry_sequence_` sono rimasti identici. Copiare i
  soli campi scalari di movimento riduce la latenza prima di `board_port_lock`
  e abbassa la pressione sul bus PSRAM mentre il DMA QSPI del display CO5300
  sta effettuando il flush.

[ESP-05] Backpressure BLE tra messaggi di navigazione e batch diagnostici (ble_nav_transport_nimble.cpp)
- Osservazione: `BleNavTransport` gestisce una coda RX (`kRxQueueDepth`) e un
  meccanismo di `PendingAck` sul canale TX. Quando la modalità diagnostica è
  `BASE` o `DEBUG`, `service_diagnostics` invia fino a 4 record/s (messaggio
  `0x22`).
- Miglioria: Assicurarsi che in `BleNavTransport::send_message` (o in
  `PhoneNavBridge::service_diagnostics`) l'invio di un `DiagnosticBatch` (`0x22`)
  venga saltato/posticipato al ciclo successivo se `pending_ack_.active` è true
  oppure se il pool mbuf di NimBLE è sotto una soglia di guardia, garantendo che
  gli `Ack` verso Android (`MapScene` / `RouteGeometry`) e i comandi critici non
  subiscano mai ritardi di accodamento.


3. MIGLIORIE CONSIGLIATE — APPLICAZIONE ANDROID (Kotlin / Jetpack Compose / JNI)
--------------------------------------------------------------------------------

[AND-01] Resilienza e caching di GatewayRoadTiles / Overpass (GatewayRoadTiles.kt / HybridMapRepository.kt)
- Osservazione: Senza un pacchetto MotoMap locale, i test su strada hanno
  mostrato fallimenti ripetuti (HTTP 502 / timeout) sui server Overpass pubblici.
  Sebbene l'uso di MotoMap ZIP resti la baseline raccomandata, il fallback
  `overpass.private.coffee` -> `overpass-api.de` può ancora subire rate-limiting
  se interrogato troppo frequentemente durante il movimento.
- Miglioria:
  1) Implementare un circuit-breaker con exponential backoff per endpoint in
     `GatewayRoadTiles.kt`: se un endpoint restituisce HTTP 429/502/504 o va in
     timeout, metterlo in cooldown per 60–120 secondi passando subito al
     secondario senza attendere il timeout completo ad ogni lookup.
  2) Espandere leggermente il margine di prefetch spaziale della tile in cache
     lungo il vettore di moto e non invalidare l'ultima `MapScene` valida
     inviata all'ESP32 quando un lookup Overpass successivo restituisce 0
     segmenti per errore di rete.

[AND-02] Scomparsa prematura del contatore numerico di svolta (RouteProgressMatcher.kt / RouteDistanceGuard.kt / ValhallaTurnGuidance.kt)
- Osservazione: In `PROJECT_STATE.md` è documentato che nei test con MotoMap il
  contatore numerico della distanza alla svolta (visibile entro 200 m a ore 8)
  tendeva a sparire ~20–30 metri prima della svolta fisica.
- Miglioria: Ispezionare la soglia di avanzamento step/manovra tra
  `RouteProgressMatcher.kt` e `RouteDistanceGuard.kt`. Spesso l'errore di
  proiezione GPS o il raggio di cattura del vertice di manovra fa avanzare
  l'indice della manovra corrente quando il pilota si trova ancora a 20–25 m
  dall'incrocio. Ridurre la distanza di snap anticipato alla manovra successiva
  (o vincolare lo switch della manovra al superamento effettivo del punto di
  svolta lungo la polilinea oppure a una variazione di bearing coerente con il
  ramo d'uscita) manterrà il contatore e lo zoom 3.4x attivi fino all'esecuzione
  reale della curva.

[AND-03] Decomposizione di NavigationViewModel (NavigationViewModel.kt)
- Osservazione: `NavigationViewModel.kt` ha raggiunto ~69 KB, concentrando
  logica di fusione posizione/heading, orchestrazione BLE, gestione code
  `MapScene`/`RouteGeometry`, timer diagnostici da 60 minuti, ricalcolo
  Valhalla e stato UI.
- Miglioria: Estrarre in collaboratori dedicati e testabili in isolamento
  almeno due responsabilità:
  1) `BleSyncCoordinator`: gestione dell'invio cadenzato di `NavigationSnapshot`,
     windowing di `RouteGeometry` e revisioni `MapScene` con gestione ACK/retry.
  2) `DiagnosticsSessionController`: gestione del countdown di 60 minuti per
     gli stati `OFF`/`BASE`/`DEBUG`, sincronizzazione con `MainActivity` /
     `NavigationScreen` e scrittura su `DiagnosticLog`.
  Questo riduce il rischio di regressioni accidentali quando si interviene su
  un singolo sottosistema.

[AND-04] Scrittura asincrona e non bloccante dei log diagnostici (DiagnosticLog.kt)
- Osservazione: Con l'aumento della capienza dei file ruotati a 1 MiB x 2 per
  Android e 2 MiB x 2 per ESP32 (~6 MiB totali) e sessioni di 60 minuti, la
  rotazione dei file e la generazione dello ZIP di esportazione gestiscono
  volumi di I/O più importanti.
- Miglioria: Verificare che tutte le append in `DiagnosticLog.kt` (specialmente
  alla ricezione dei batch BLE `0x22` dall'ESP32 e dei fix GPS rapidi) avvengano
  tramite un canale bufferizzato (`Channel(capacity = ...)` consumato su
  `Dispatchers.IO`) con flush periodico anziché aprire/chiudere o sincronizzare
  lo stream ad ogni singola riga, evitando micro-stutter sul thread BLE o main.

[AND-05] Validazione Heading in partenza e a bassa velocità (NavigationHeadingPolicy.kt / RouteDirectionPolicy.kt)
- Osservazione: `NavigationHeadingPolicy` ignora correttamente l'azimut
  magnetico del telefono dopo il primo fix valido per evitare rotazioni spurie
  della mappa quando il telefono si muove sulla sella o nella borsa.
- Miglioria: Assicurarsi che durante le soste (es. semaforo, velocità < 1.5 m/s)
  in cui il GPS course nativo può diventare instabile o azzerarsi su alcuni
  chipset Android (es. Allwinner / Qualcomm), `NavigationHeadingPolicy` congeli
  l'ultimo `travelCourse` valido finché lo spostamento cumulato (`displacement`)
  non supera il raggio di incertezza `gpsAccuracy`, evitando rotazioni della
  visuale da fermo e fornendo un bearing stabile a `RouteDirectionPolicy` in
  caso di ricalcolo immediato.


4. PIANO DI VERIFICA CONSIGLIATO (in linea con le regole di sessione)
--------------------------------------------------------------------------------
1. Non applicare queste migliorie in un unico blocco: procedere con modifiche
   atomiche su un branch di test dedicato solo dopo aver completato e
   verbalizzato in `PROJECT_STATE.md` la prova su strada del commit corrente
   (test >10–60 minuti con `LOG DEBUG` e verifica dell'export ZIP).
2. Per ogni intervento sul matching di svolta ([AND-02]) o sull'interpolazione
   ([ESP-03]), aggiungere prima uno unit test riproducibile rispettivamente in
   `RouteProgressMatcherTest.kt` / `RouteDistanceGuardTest.kt` e nei test
   nativi del bridge (`phone_nav_bridge_platform_stubs.cpp`).
3. Consolidare i commit per attivare una sola run CI GitHub Actions per
   modifica e validare sempre su hardware reale prima di dichiarare risolto
   qualsiasi comportamento su strada.
================================================================================

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


## Addendum 2026-11-01 — nuova prova su strada, log e seconda revisione

**Fonte:** test utente con APK da `test/openfreemap-mvt`, ZIP Android/ESP32 delle 15:13:37–15:24:51 (ora italiana) e documento di revisione `valhalla_migliorie_consigliate(1).txt`. Nessun intervento sul codice è approvato da questo addendum; stato: backlog da verificare.

### Evidenza della prova

- L'app ha registrato **104 `OSM_CONTEXT` con `segments=0` e 104 `OSM_EMPTY`**. Sono state avviate 21 richieste Overpass, tutte `available=false`; riscontrati timeout e HTTP 502 sui due endpoint.
- **Zero eventi `OFM_MVT_TEST` / `OFM_MVT_TEST_ERROR`: il probe OpenFreeMap manuale non risulta attivato.** Non dedurre da questa prova che il decoder MVT sia difettoso: il fallback automatico resta Overpass nel ramo test.
- **12 trigger e 12 successi `REROUTE`**, provider `valhalla-online`, senza errori di ricalcolo. L'utente segnala miglioramento visivo e assenza dei precedenti salti nella distanza di arrivo; considerare il risultato positivo ma ancora da replicare.
- Traccia ESP32: 2425 record sequenziali (seq 1–2425, senza gap osservati nel file); non equivale a una garanzia generale di assenza di perdite.

### Nuovi suggerimenti da tenere in priorita alta

- **BLE-Android A-1:** verificare in `MotoBLEManager.processNextWrite` i risultati di `writeCharacteristic`, assicurare timeout del write pendente e recupero da errori/busy. Una coda GATT bloccata può far mancare scene e navigazione.
- **BLE-JNI A-2:** audit della concorrenza su `CodecStorage`, `SequenceGenerator` e `Reassembler`. Verificare se le chiamate sono davvero simultanee prima di scegliere mutex o dispatcher unico. Aggiungere test di concorrenza.
- **BLE-S-1:** controllare crittografia effettiva e pairing: un flag `encrypted_` impostato al connect non dimostra un link cifrato. Valutare protezione del canale di navigazione; compatibilità Android obbligatoria.
- **ESP-P0-4:** verificare se `service_diagnostics()` può attendere il TX BLE nel task clock da 40 Hz; se confermato, rendere il servizio non bloccante o separarlo dal clock. Si collega alle proposte ESP-02/05 gia archiviate.
- **Android N-3:** migliorare il badge limite velocità usando proiezione sul segmento e coerenza con heading/rotta, evitando il vertice più vicino e valori stantii quando manca il contesto. I limiti generici del decoder MVT **non sono limiti legali certi**.
- **Android N-5:** verificare l'aggiornamento prematuro di `lastMapSceneSendTimeMs` prima del successo: applicare throttling ai fallimenti senza ritardare la prima scena finalmente disponibile.

### Da testare successivamente (priorita media)

- **ESP F-1/F-2:** blend della mappa dipendente dal tempo reale tra frame, e zoom interpolato fra snapshot senza alterare la distanza numerica. Test nativi e verifica visiva; mantenere 3.4x.
- **ESP P0-1/P0-2:** strumentare attesa TE (25 ms) e utilizzo PSRAM/DMA prima di fare prove A/B su TE gating, timeout o allocazioni. La nuova prova non segnala blocchi: classificare come **diagnosi preventiva**, non come bug P0 attualmente riprodotto.
- **ESP P0-3:** misurare copia delle geometrie ad ogni frame prima di introdurre double buffering; stessa finalita di ESP-04.
- **BLE S-2/S-4:** contatori drop della coda RX e verifica revisioni `MapScene` fra reconnect/reset, con test del protocollo.
- **Android A-3/A-4/A-5/A-6:** valutare priorita connessione GATT, scansione con durata limitata, strategia reconnect ed enum di stato anziche confronti testuali; modifiche indipendenti e solo dopo test di compatibilita.
- **Android N-1/N-2:** esaminare valori GNSS hardcoded e `routeGeneration` nei reroute; compatibilita Android/ESP32 prima di modificarne la semantica.
- **Android N-4, N-7/N-8, M-3/M-4:** ottimizzare scansione/calcolo delle distanze nel contesto, backoff rete, riduzione log di fix rifiutati, cache e validazione degli import. Verificare prima misure e codice reale.

### Proposte con priorita ridotta o condizionale

- **P0-5 e F-3/F-4/F-5:** retry/invalidate/render completo, segmenti mappa e refresh LVGL: solo con metriche per dimostrare un collo di bottiglia.
- **S-3:** non modificare le priorita FreeRTOS senza evidenza di starvation del rendering.
- **N-6:** la revisione stessa indica comportamento attualmente corretto; non richiede fix.
- **N-9/N-10:** cancellazione reroute allo stop e piccole allocazioni nel percorso GPS: verifiche a basso impatto, dopo gli interventi critici.
- **M-1/M-2/M-5:** audit manifest, confronto debug/release, e logger Android; l'I/O diagnostico è già asincrono e a rotazione, quindi evitare refactor non misurati.

### Metodo concordato

Prima di qualsiasi patch: confrontare ogni affermazione [VERIFICATO] del documento esterno col codice attuale e con la recente prova senza freeze; non considerare conclusivo un audit di sola lettura. Cambiamenti atomici su un ramo distinto da `test/openfreemap-mvt`, test specifici, una CI consolidata, prova hardware prima del merge in `valhalla`. **Prossimo test OpenFreeMap:** a navigazione ferma premere `Test OpenFreeMap — 1 tile MVT`, poi controllare esito, conteggio strade e invio `MapScene` nel log.


## Backlog aggiuntivo dalla revisione `valhalla_migliorie_consigliate (1).txt` — 2026-11-01

Stato: **da verificare e implementare solo con approvazione successiva**, senza modifiche non autorizzate al ramo `valhalla`. La revisione è basata soprattutto su ispezione statica, non su test hardware. Alcuni elementi precedentemente annotati qui (A-1, A-2, N-3, N-5, P0-4, S-1) sono sovrapposti e non vanno duplicati nel backlog operativo.

- **Alta priorità, dopo i primi fix:** B-2 lifecycle Activity/manager BLE; U-1/U-2 ricostruzione overlay osmdroid ad ogni fix; U-3 pulsante stop navigazione raggiungibile; B-9 coordinate POI vs vertice finale Valhalla; B-7 permessi separati e notifiche; N-3 limite per segmento/direzione con fallback generico; N-5 cooldown su scene vuote; B-0 validazione limiti payload e test 63/95 byte.
- **Navigazione e fluidità, con test prima di cambiare:** B-3 scansioni O(N); B-4 finestre rotta sovrapposta; B-5 semantica merge 37/38 (non trattare sempre come semplice continue); B-6 offset della manovra; B-8/F-2 zoom interpolato con velocità e durata dal fix, non doppia predizione; F-1 blend dipendente da dt; F-3/F-4/F-5 invalidate/tempo flush; H-1/H-3/H-5 IMU e anchor; N-1/N-2 GNSS e routeGeneration; AND-02 contatore svolta prematuro solo se riprodotto.
- **Trasporto/diagnostica:** A-2 sincronizzazione CodecStorage Kotlin e parità encoder/reassembler C++; A-3 priorità connessione e MTU; A-4 timeout scan; A-5 riconnessione (autoConnect non garantisce miglioramento); A-6 enum connessione; S-1 pairing/secure connections con test Android↔ESP32; S-2 contatore rx_queue_drop; S-3 priorità task solo con misure; S-4 revisione MapScene a reconnect; P0-4 backpressure log vs clock, ESP-02/05 ring e priorità ACK.
- **ESP32, preventive e non P0 riprodotti:** P0-1 attesa TE; P0-2 contesa PSRAM; P0-3 copie snapshot; P0-5 invalidazione dopo timeout; H-4 polling I2C; H-6 nessun lock LVGL nel campionamento; non cambiare TE, buffer o priorità senza misure. La prova del 2026-11-01 non segnala freeze con logger PSRAM/BLE.
- **Servizi/app:** B-10 lifecycle foreground service, errori GPS, contentIntent e power management; B-11 privacy bias Photon e stato routing online; B-12 CI release/lint/versioni ESP-IDF; N-4 caching distanze; N-6 nessun bug; N-7 politiche fair-use Overpass; N-8 riduzione log fix; N-9 stop reroute; N-10 allocazioni; M-1 permessi rete/backup; M-2 profilo debug/release; M-3 cache tile; M-4 import atomico; M-5 profilazione log esistente; U-4 conferma long press; U-5 accessibilità e lifecycle osmdroid.
- **Backend storico BK-0..BK-8:** marcare `backend/` legacy rispetto alla runtime Android, senza cancellarlo. Prima di qualunque rimozione esaminare dipendenze del workflow, fixture Jinan, script che importano `backend/src/coordinates.js`, console web, docs e distribuzione Worker; audit secondario su allineamento Node/Worker, cache, healthz, rate limiting, timeout e Photon. Non alterare `signal` né CI finché non approvato.

**Vincoli permanenti:** zoom 3,4× senza frecce, distanza svolta a ore 8 entro 200 m, distanza destinazione e bandierina, strade grigie e limite velocità, nessuna scrittura flash ESP32 durante navigazione, diagnostica PSRAM→BLE a 60 minuti, compatibilità del protocollo e prove di una variabile per volta. Nessun merge automatico in `valhalla`.

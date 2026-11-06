# Moto GPS — Project State

## Handoff prima della pausa di sviluppo — test motociclistico 2026-11-06

**Contesto:** l'utente si assenta per un paio di giorni per una gita; conservare risultati e priorità senza fare ulteriori implementazioni o merge non autorizzati. Riferimento al branch sperimentale `test/road-contrast-cleanup-20261104`; il branch d'integrazione `valhalla` rimane invariato. I dati GPS esportati dall'utente sono privati e **non devono essere committati** nel repository.

**Feedback del giro del 6 novembre:** navigazione percepita come ulteriormente migliorata. Giro contenente tappa intermedia, deviazioni/ricalcoli, rotatorie e tornanti (i tornanti erano circa a metà percorso). Analisi dei file `motogps-android-diagnostics.zip` e `motogps-replay.jsonl`: ~19 minuti e 38 secondi di replay, 6.736 eventi (1.178 fix, 4.732 snapshot, 200 scene); nel diagnostico sei ricalcoli Valhalla riusciti, ~212 scene trasmesse, nessuna disconnessione BLE durante il periodo esaminato e arrivo finale registrato. Sono **risultati dell'analisi dei log**, non prove di prestazioni del simulatore o esecuzioni di build.

**Tappa intermedia:** l'evento `WAYPOINT_REACHED index=1 total=1` compare attorno alle 12:05:16 nel diagnostico e nel replay. Il riconoscimento Android è quindi verificato; **manca il feedback visivo** sull'app e sull'ESP32. Decisione già definita: messaggio «TAPPA 1/… RAGGIUNTA» per **5–10 secondi** (inizialmente provare **7 secondi**) senza oscurare la mappa, senza aggiungere frecce o interrompere la navigazione. Disegnare sulla **linea verde** un piccolo marcatore geografico numerato per ogni tappa intermedia; dopo il passaggio rimuoverlo o attenuarlo.

**Destinazione finale:** disegnare sulla linea verde anche un **piccolo marcatore a bandiera a scacchi nella posizione effettiva dell'arrivo**; mantenere separatamente la bandiera grande già presente nell'area delle vecchie indicazioni di svolta quando la navigazione termina. Tappe e destinazione devono condividere trasformazione, rotazione e zoom della rotta, senza scambiare il bordo della finestra parziale BLE con il punto reale di arrivo. Questi miglioramenti grafici **non sono implementati**, ma sono già registrati nella sezione «Decisione UI: tappa visibile sul percorso».

**Tre ricalcoli ravvicinati intorno alle 12:09:** eventi segnalati alle ~12:09:18, ~12:09:27, ~12:09:36; in uno compare indicazione di inversione di marcia. Ipotesi iniziale dell'utente: uscita dal parcheggio, poi corretta dopo aver notato velocità GPS di circa **58 e 71 km/h** nei ricalcoli esterni: più plausibile una sequenza di ricalcoli in marcia dopo una deviazione. **Non classificare come bug accertato** e non cambiare ancora le soglie: riprodurre il tratto col simulatore, confrontandolo con la geometria e con la manovra reale, quando il replay sarà utilizzabile.

**Timeout ESP32:** due eventi di attesa lock LVGL da **250 ms**, riportati attorno alle 12:04:38 e 12:10:05. `phone_nav_bridge.cpp` usa `board_port_lock(250)`; in caso di mancata acquisizione rinvia il frame e ritenta senza bloccare la ricezione BLE. Eventi **non equivalenti a crash o riavvio**, e la registrazione di nuovi frame successivi indica recupero; monitorare solo se diventano frequenti o se l'utente nota scatti/blocchi reali. La causa precisa (carico LVGL, flush display, contesa) non è identificata.

**Ripresa del lavoro, in ordine:** (1) compilare con Emscripten e aprire il prototipo simulatore in `platforms/web/simulator`, ora solo predisposto, non validato visivamente; (2) verificare che il replay reale del 6 novembre carichi route/scene/snapshot e permetta pausa, seek e analisi dei tre ricalcoli; (3) ampliare la diagnostica delle strade scartate se necessaria per confrontare selettori MotoMap; (4) implementare insieme notifica tappa 5–10 s, marcatori numerati, marcatore destinazione e relativi test Android/BLE/ESP32/native, senza disturbare l'arrivo già funzionante; (5) poi sviluppare zoom intelligente basato su geometria di rotatorie, svincoli e tornanti. La pulizia dei documenti e la licenza restano fasi separate; nessun cambio alle licenze attuali.

**Modifiche di questa annotazione:** solo `PROJECT_STATE.md`, nessuna modifica a sorgenti, APK, firmware, protocollo, manuale operativo o dati. Build, test, test dispositivo e replay browser **non eseguiti per questa annotazione**. Commit `[skip ci]`; non eseguire CI per la sola documentazione. **Prossima azione concreta:** validare la build Emscripten e il primo replay reale prima di intervenire sul comportamento dello zoom.


## Decisione UI: tappa visibile sul percorso e conferma di raggiungimento — 2026-11-06

**Richiesta utente confermata:** quando la tappa intermedia viene raggiunta, mostrare sull'ESP32 una conferma discreta per **5–10 secondi** (proposta iniziale: **7 secondi**, da validare), non i 2 secondi della prima bozza. Durante la conferma la mappa, la rotta verde e la guida devono restare leggibili; non sostituire la schermata di navigazione e non usare la bandiera di arrivo finale.

**Tappa sulla linea verde:** visualizzare un piccolo marcatore riconoscibile (proposta: cerchietto chiaro con numero progressivo) nella posizione geografica della prossima tappa, proiettato con la medesima trasformazione di rotta e strade, davanti alla linea verde. Non inventare la posizione se fuori campo; gestire clipping sul display circolare e sovrapposizioni con la distanza di manovra. Dopo il raggiungimento, confermare la tappa e poi rimuovere o attenuare il marcatore; la navigazione prosegue alla destinazione senza cambi arbitrari di zoom, senza frecce.

**Destinazione finale sulla linea verde — nuova richiesta:** mostrare anche il punto di **arrivo finale** direttamente sulla mappa, sopra o all'estremità corretta della polilinea verde, con un **marcatore a bandierina a scacchi** piccolo e leggibile, distinguibile dalle tappe intermedie numerate. La bandiera a scacchi già presente nell'area delle vecchie indicazioni di svolta **resta** come conferma di navigazione terminata: le due indicazioni hanno scopi diversi (posizione geografica sulla rotta vs. stato di arrivo). Il marker di destinazione deve restare visibile durante l'avvicinamento, quando incluso nel campo visibile, senza spostarlo artificialmente al bordo; utilizzare la posizione di arrivo effettiva fornita dalla rotta e la stessa proiezione di percorso/contesto, gestendo clipping e possibili perdite/rinnovi della finestra BLE. A destinazione raggiunta mantenerlo con la scena conclusiva, se i dati sono disponibili. Non ricavare arbitrariamente l'arrivo dal limite della finestra parziale di rotta; prima verificare se occorra trasmettere la coordinata di destinazione. Prevedere test nel simulatore con tappe multiple, finestre parziali, zoom e arrivo. **Solo requisito, non implementato.**

**Stato:** solo requisito progettuale, non implementato né collaudato. Il test del 6 novembre ha registrato WAYPOINT_REACHED in Android/replay ma nessuna notifica sul display; l'attuale evento non equivale a un messaggio BLE di avviso. Prima dell'implementazione verificare gli spazi del protocollo BLE, i dati georeferenziati del waypoint, il rendering LVGL e i test di replay/simulatore; collaudo su dispositivo dopo un'unica CI consolidata.


## Audit upstream Android cinese — 2026-11-06

**Richiesta utente:** dopo la fase simulator/registratore, valutare se la prima app Android del fork originale "mx3353672833-debug/moto-gps-waveshare" contenga spunti utili. Verifica iniziale già eseguita senza cambiare codice. Upstream branch "main": commit "2ba1737b5eb521edd6f58ced6a6c27f93539a03c" del **2026-10-30 04:01 UTC**; Android package "top.maler.motogps", Kotlin + Compose, JNI condiviso con iOS, gateway AMap e demo cinese. Da non importare come blocco: differisce dal nostro Android "com.motogps.android" con routing Valhalla offline + MotoMap, e conserva gateway/iOS/media e funzioni escluse.

**Spunti selezionati e prioritizzati:**
1. **BLE robustezza e sessione (priorità alta, audit prima del codice):** upstream "ble/BlePolicies.kt" e "ble/BleProtocolEngine.kt" implementano handshake Ready doppio verificando session ID/capabilities/versione/MTU, timeout e retry bounded; ACK applicativo delle MapScene, timer liveness del device, anti-duplicazione comandi, queue pacing, snapshot coalescing e ordinamento geometria→snapshot. Il nostro "MotoBLEManager.kt" gestisce già code/fragmentazione/CCCD e ha riconnessione fisicamente testata, ma nell'handler inbound verificato qui non risulta un'elaborazione degli ACK MapScene o un timer esplicito per assenza di frame validi, e lo stato Ready controlla essenzialmente role/state; usare l'upstream come **ispirazione e set di test**, non sostituire BLE alla cieca. Una diversa temporizzazione potrebbe regredire le scene e i test di riconnessione attuali. Non assumere direttamente compatibili i codec/flags delle versioni forkate.
2. **Banco prova Android ↔ dispositivo BLE simulato (priorità alta):** "androidTest/BleProtocolEngineSimulationTest.kt" usa un "SimulatedScreen" con codec condiviso per testare handshake, geometry-before-snapshot, ACK/dedup comandi, ACK MapScene, heartbeats, link silenzioso, capability assenti e MTU 20. Noi abbiamo test del codec Kotlin e native, ma non un test Android di intera sessione BLE con peer simulato. Portare gli **scenari** aggiornandoli al nostro protocollo e alla diagnostica, non copiare pacchetti/codice incompatibili; collegare al simulator/replay separatamente.
3. **Foreground service e permanenza della navigazione (priorità media):** "service/NavigationForegroundService.kt" originale include azione "termina navigazione" nella notifica e un wakelock parziale con durata limitata. Il nostro "LocationForegroundService.kt" ha notifica di localizzazione più essenziale; valutare soltanto una modalità controllata di permanenza in background/azione di arresto e misurare consumo e comportamento su Android reale, senza peggiorare la batteria.
4. **Panoramica rotta statica senza map SDK (priorità bassa/media):** "ui/RouteOverviewMap.kt" originale disegna percorso, scala ed estremi localmente in Compose; possibile fallback per anteprima anche senza tile Android. Richiede coordinata corretta WGS84/GCJ e attenzione a non duplicare l'attuale RouteMapView di Moto Chat GPS.
5. **Cache offline/recupero file corrotti (priorità bassa):** "map/MapTileCache.kt" e test relativi hanno quote su byte/tile, pinning e resume di cache danneggiate, ma il loro formato/servizio mappe è diverso da MotoMap v3; recuperare solo principi generali, non il vecchio caricatore gateway.

**Da escludere:** AMap/gateway online originale, ricerca iOS, musica/Apple/MediaSession, coordinate conversion cinesi su percorsi Italia, fixture di Jinan come dati di produzione, codice di retrofit meccanico e UI cinesi. README upstream dichiara che test build/emulatore erano verdi e telefono reale NON ancora testato: non confondere tale dichiarazione con verifica indipendente dell'attuale branch. Non è stato eseguito nessun test upstream/local in questa attività, nessun merge/cherry-pick, nessuna build, nessuna modifica BLE/Android/ESP32, nessuna variazione del manuale. 

**Decisione:** archiviare l'audit come possibile *fase successiva* allo sblocco del simulatore WebAssembly e al suo primo replay reale; iniziare semmai dai test BLE con peer simulato, e valutare separatamente le verifiche di sessione/ACK con esame del protocollo corrente. Branch "valhalla" invariato, commit documentale "[skip ci]". **Prossima azione prioritaria immediata resta:** build Emscripten e primo test visivo del simulatore.


## Primo simulatore browser LVGL e registratore Android — 2026-11-06

**Branch:** test/road-contrast-cleanup-20261104; valhalla invariato, nessun merge autorizzato. L'utente ha richiesto di riutilizzare l'emulatore esistente in platforms/web come primo banco prova per lo zoom intelligente.

**Implementato:** nuovo platforms/web/wasm/replay_web.cpp e aggiornamento CMakeLists.txt: API Emscripten per iniettare route BLE (max 24 punti), scene MotoMap (max 24 polilinee / 192 punti) e snapshot in NavPresenter e nel vero moto_nav_ui. Nuovo platforms/web/simulator/index.html + player.js: interfaccia italiana, importazione locale JSON/JSONL moto-replay-v1, demo sintetica, pausa/play, cursore, frame singolo, velocità 0,25–4× e screenshot PNG subordinato a WebGL. La vecchia demo web cinese/Jinan resta isolata.

**Registratore Android a consenso esplicito:** diagnostics/ReplayTraceRecorder.kt salva JSONL in worker privato, limiti 60 minuti e 24 MiB. Contiene fix GNSS accettati, snapshot navigazione con posizione predetta, finestre rotta, scene MotoMap selezionate per BLE e waypoint raggiunti. MainActivity e NavigationScreen espongono "Avvia/Ferma registrazione GPS" ed esportazione separata tramite FileProvider. Il normale ZIP DiagnosticLog non include geometrie/coordinate. Nuovo evento WAYPOINT_REACHED nel log Android standard per verificare le tappe. Nessuna modifica al protocollo BLE ESP32 o al firmware.

**Limiti:** replay del contenuto tentato in trasmissione Android, non prova della ricezione BLE; non ricalcola il motore Valhalla né il selettore Android. Per analizzare la rotatoria delle 07:49 e confrontare nuovi punteggi occorre poi registrare le candidate MotoMap non selezionate e ricomputare le scene a parità di fix. Demo sintetica non rappresenta una strada OSM. I limiti del browser non misurano FPS e tempi ESP32. File GPS altamente personali: solo esportazione esplicita, nessun caricamento a server.

**Build/test (aggiornamento verificato):** integrazione sorgente commit 25efe935c7300a85c68ff8f806c59345d1c0d629; GitHub Actions [run 42193449565](https://github.com/thriller47/moto-gps-waveshare/actions/runs/42193449565) completata con **success**: job Android superato (Gradle build e test inclusi nel task build), job native e ESP32 **skipped** per i filtri diff del commit finale (file web aggiunti in commit intermedi [skip ci]). Il controllo node --check aggiunto al workflow **non è stato eseguito** e la build WebAssembly con Emscripten resta **NON provata**, così come apertura del browser e test fisici. Nessun firmware ESP32 è stato modificato. Il manuale operativo è stato aggiornato con la nuova sezione 8 e quick reference sezione 9. Le note relative alla demo Jinan restano storiche.

**Prossima azione:** verificare la CI Android; testare in WSL l'esecuzione scripts/build_web.sh con Emscripten, poi aprire il simulatore Windows, verificare la demo sintetica e registrare un breve tratto con l'APK aggiornato per collaudare la riproduzione delle scene e lo zoom. Nessun merge senza approvazione.


## Feedback sul passaggio tappa 2026-11-06 — navigazione proseguita, nessun avviso visivo

**Riscontro diretto utente sul giro mattutino con tappa:** «sono passato per la strada della tappa e ho continuato verso la destinazione. android ed esp non mi hanno segnalato niente». Quindi il percorso è proseguito senza interruzioni apparenti e senza interventi del motociclista, **ma non si può ancora provare dal log che il passaggio alla tappa sia stato riconosciuto dal relativo contatore**.

**Verifica del codice corrente:** `ValhallaRouteLocations.nextIndex()` incrementa `nextWaypointIndex` quando la posizione GNSS è entro **60 m** dalla successiva tappa ordinata, durante `NavigationViewModel` (circa righe 755–762). In caso di incremento emette solo `Log.i("NavigationVM", "Tappa completata: ...")`, **non** un `DiagnosticLog.event` e non una notifica UI/BLE ESP32. La rotta Valhalla contiene le tappe `type=break` e rimane una navigazione unica; ai ricalcoli vengono passate solo le tappe non ancora riconosciute con `activeWaypoints.drop(nextWaypointIndex)`. Il log ZIP `motogps-android-diagnostics(20261106-072929).zip` non riporta né `WAYPOINT_EDIT`/`ROUTE_START` né `Tappa completata`, quindi la continuità osservata è **positiva ma non dimostra automaticamente il corretto avanzamento** della tappa. In particolare «passare per la strada» non garantisce di essersi avvicinati a meno di 60m alle coordinate puntuali del waypoint.

**Miglioramento candidato (non ancora approvato per implementazione):** registrare un evento diagnostico `WAYPOINT_REACHED index=… remaining=…` al momento dell'avanzamento, possibilmente con soglie robuste a GPS impreciso o mancato fix; valutare una segnalazione **breve e discreta** «Tappa raggiunta» su Android e/o ESP32 senza togliere la rotta verde, senza frecce e senza forzare uno zoom. Non introdurre automaticamente nuovo messaggio BLE prima di valutare l'impatto del protocollo: potrebbe bastare un campo transitorio nello snapshot esistente o una UI Android; verificare le alternative con i test. Il funzionamento della destinazione finale e della bandiera d'arrivo rimane invariato.

**Stato:** solo feedback e audit del codice annotati sul branch `test/road-contrast-cleanup-20261104`; nessun sorgente/protocollo/firmware modificato e nessuna nuova build/test eseguita, `valhalla` invariato. Aggiornamento documentale `[skip ci]`. **Prossima azione:** confermare sul prossimo giro che l'APK sia aggiornato e registrare esplicitamente il cambio del contatore waypoint, senza confondere la prosecuzione normale della navigazione con una prova certa di riconoscimento della tappa.


## Analisi log 2026-11-06 — tappa, scena rotatorie e possibile APK non aggiornato

**Fonte test utente:** `motogps-android-diagnostics(20261106-072929).zip` (log Android e diagnostica ESP32). L'utente segnala che **alla prima rotonda circa alle 07:49** alcune strade grigie erano irrilevanti e uno svincolo è diventato visibile soltanto con lo zoom. Questa osservazione visiva è reale feedback del collaudo, ma il trace corrente è **solo metadati**, senza polilinee GPS/OSM: non consente di identificare geometricamente l'uscita mancante.

**Riscontri misurati:**
- Log mattutino 07:33:42–07:53:44 CET: una sola connessione BLE riuscita alle **07:33:44**, nessuna successiva disconnessione visibile. **165** `BLE_MAP_SCENE` Android e **164** eventi ESP `MapScene` correlati; l'unica revisione non correlata è la primissima, prima dell'avvio diagnostica ESP. Tutte le revisioni del periodo 07:49 risultano ricevute; latenza scena Android→ESP circa **1,1–2,5 s** (intera sessione 0,28–2,56 s), non un buco BLE dimostrato.
- Alle **07:49:17**, `OSM_RING`: focus `s:23210446`, `score=100`, `branches=6`, `prioritized=5`; scena `focus=6 exits_sent=5 gps=6 upcoming=4 extra=4 total=20/89`. Alle 07:49:24 e 07:49:34 stessa combinazione base `20/97` e `20/92`; le strade occupano i **20 slot** mentre i vertici usano appena 89–97 dei **160 disponibili**. Segnale a favore di migliorare **rilevanza/assegnazione degli slot**, non aumentare immediatamente il tetto di punti o azzardare un incremento semplice dei pesi.
- Il focus cambia tra più anelli nel minuto precedente. Il punteggio di confidenza raggiunge frequentemente **100** per molte geometrie: verificare che l'anello scelto sia **davanti al percorso e riferito alla manovra corrente**, non soltanto vicino a GPS/preview. Non è dimostrato quale strada specifica sia stata esclusa perché mancano geometrie/candidate nel log.
- **Mismatch APK/codice:** nessuno degli **165** messaggi `OSM_RING` del 6 novembre contiene i campi `route_gap_m` / `route_along_m` che il `NavigationViewModel.kt` del commit `c57adb2` scrive obbligatoriamente; mancano anche `WAYPOINT_EDIT` e `ROUTE_START` attesi dal codice del branch. Molto probabile un APK sul telefono **precedente** alla CI aggiornata [42079332773](https://github.com/thriller47/moto-gps-waveshare/actions/runs/42079332773) sul commit `c57adb2`. Verificare prima la versione installata: non attribuire automaticamente alla logica più recente il comportamento osservato.
- Due ricalcoli `valhalla-local` riusciti **07:43:56** (77m off-route, 4424m nuova rotta) e **07:51:04** (68m, 1283m). `NAV_SNAPSHOT` raggiunge `maneuver=arrive, remain_m=0` alle 07:53:34; **non** si può però certificare l'inserimento e il superamento della tappa intermedia dal solo ZIP senza gli eventi waypoint/route start (né un campo nextWaypointIndex). Un solo `RenderLockTimeout` ESP32 alle circa **07:40:58**, fuori dalla rotatoria segnalata; verificare solo se ricorrente.

**Analisi preliminare del selettore attuale (non modificato):** `RoadContextSceneSelector` prenota prima l'anello e fino a 7 uscite, poi almeno 6 vie GPS e fino a 6 `UPCOMING`; `EXTRA` occupa il resto. `RoadContextUpcoming.probes()` campiona 90/160/240/320/420/500m sul corridoio: possibile finestra cieca sui rami fra 0 e 90m; va verificato con replay, non corretto alla cieca. Il codice recente usa forwardRouteDistance/Along, non presenti nei log acquisiti. Lo zoom ESP32 è un trasformazione comune a verde e grigie: può rendere visibile una polilinea già presente ma fuori campo, quindi il feedback **non prova** da solo una selezione BLE mancante.

**Decisione e prossima azione:** **nessuna modifica dei punteggi ora**: prima installare e confermare l'APK della CI `42079332773` / commit `c57adb2`, eseguire nuovo giro diagnostico (tappa intermedia, rotatoria 07:49), e verificare `route_gap_m`, `route_along_m`, `WAYPOINT_EDIT`, `ROUTE_START ready stops=1`, arrivo/prosecuzione tappa. Se il difetto rimane, usare il registratore GPS/geometrie completo e modificare **priorità uscite effettivamente sulla rotta + rami di manovra** preservando il fallback off-route; aggiungere un identificativo build/commit nell'export per evitare mismatch in futuro. Rispetto alla fase collaudo, nessun codice, BLE, APK o firmware è stato modificato e **nessun nuovo test CI o flash** è stato eseguito da questa analisi. `valhalla` invariato, manuale operativo invariato. Commit documentale `[skip ci]`.


## Roadmap: ripulitura documentale e identità autonoma Moto Chat GPS — 2026-11-05

**Decisione utente:** il repository deve rappresentare **Moto Chat GPS**, un progetto ormai tecnicamente autonomo rispetto al fork originario e rivolto esclusivamente alla **navigazione motociclistica Android + Waveshare ESP32-S3 + Valhalla offline + MotoMap**. Pianificare una pulizia sostanziale della documentazione cinese e della documentazione inglese divenuta inutile. Questa è una fase **successiva**, separata dalla stabilizzazione delle modifiche di navigazione, e non costituisce autorizzazione a cancellare senza audit sorgenti, dipendenze, licenze o artefatti hardware.

**Inventario al 2026-11-05 sul branch `test/road-contrast-cleanup-20261104`:** 119 file `.md` totali, dei quali 47 duplicati/traduzioni `.en.md` e 4 con nomi in caratteri cinesi (ulteriore contenuto cinese risiede anche in file dai nomi ASCII). `README.md` è ancora prevalentemente cinese e descrive il vecchio iPhone, gateway online, Apple Music e funzionalità non più presenti; `README.en.md` replica le vecchie informazioni. Molte guide `docs/`, `hardware/`, `shared/` e `platforms/` riguardano architetture legacy, schede autocostruite non adottate, app iOS o roadmap ormai superate. **Non utilizzare il solo suffisso linguistico come criterio automatico per cancellare un documento ancora tecnico o necessario.**

**Interventi previsti, in questo ordine:**
1. **Nuovo README italiano, accurato e operativo.** Presentare nome, scopo moto, scheda Waveshare 1.75C, Android, BLE, grafo Valhalla offline, strade MotoMap v3, ricerca Photon online ancora in uso, funzionamento reale, stato sperimentale di zoom/selettore e link al manuale. Evidenziare che autospegnimento esiste già ma modalità Pausa Caffè, simulatore Windows, zoom contestuale avanzato e itinerari panoramici/ad anello sono **roadmap e non funzioni già disponibili**. Rimuovere vecchi banner pubblicitari, link di collaborazione estranei e descrizioni del fork che confondono gli utilizzatori.
2. **Inventario e matrice mantieni/aggiorna/elimina.** Eliminare le copie `.en.md` ridondanti e i documenti cinesi obsoleti *dopo* verifica dei link e del contenuto. Valutare `docs/IOS_SIDELOAD*`, `docs/WAVESHARE_IOS_PROTOTYPE*`, `docs/ANDROID_AI_GUIDE*`, `docs/MOBILE_WEB_NAVIGATION_PLAN*`, `docs/technical-proposal/*`, `TODO*`, `CONTRIBUTING*`, `hardware/rev_a/*` e vecchi contenuti del gateway come candidati, non come lista di cancellazione indiscriminata. Distinguere la documentazione del vecchio hardware PCB dal progetto della **culla/ghiera attuale**, conservando i disegni correnti e gli eventuali dati necessari per la loro riproducibilità.
3. **Consolidare una documentazione minima italiana come fonte attendibile.** Mantenere e aggiornare `AGENTS.md`, `PROJECT_STATE.md`, `docs/MANUALE_OPERATIVO.md`, istruzioni Android/ESP32, MotoMap/Valhalla, flashing, comandi seriali radio in modalità sperimentale, diagnostica e test. Per i documenti inglesi tecnici *attualmente utilizzati* da toolchain o sviluppatori, valutare traduzione/riscrittura corretta prima dell'eliminazione; non cancellare per lingua un protocollo o una specifica necessaria.
4. **Licenze e tracciabilità obbligatorie.** **Conservare `LICENSE.md`, `NOTICE`, `THIRD_PARTY_NOTICES*`, `LICENSES/` e attribuzioni pertinenti**, aggiornando eventuali link in italiano senza alterare testi di licenza. L'indipendenza funzionale **non cancella** gli obblighi relativi al software e alle risorse ereditati dal fork (PolyForm Noncommercial e licenze dei singoli componenti, secondo i file effettivamente distribuiti). Non rimuovere asset e font con obblighi di attribuzione, né avallare retroattivamente come originale il codice derivato. Annotare esplicitamente l'origine storica e il percorso evolutivo del progetto, senza mantenere intere guide obsolete.
5. **Rimozione verificata e controlli.** Cercare riferimenti da README, Markdown, workflow GitHub Actions, script, test, CMake e strumenti hardware; aggiornare link relativi e rimandi ai documenti rimossi; controllare assets e submodule non usati **separatamente** dai documenti. Eseguire link checker/controlli di ricerca e build **unica e consolidata** soltanto se si toccano file consumati dal build o se necessario a dimostrare nessuna regressione. L'eliminazione di file dal checkout non cancella la cronologia Git: evitare operazioni di riscrittura della storia e non promettere riduzione del peso del clone senza `git filter-repo` (non previsto).

**Confini e calendario:** pulizia in **change-set separato** dopo il test e l'eventuale merge esplicitamente approvato delle migliorie attuali, senza interferire con le prove BLE, selettore strade e simulatore; sviluppare eventualmente sul branch di test dedicato dal `valhalla` più recente. Nessun merge di pulizia senza controllo dei link e revisione delle licenze. La documentazione operativa radio restaurata **non deve essere nuovamente rimossa** solo perché sperimentale.

**Stato di questa annotazione:** solo `PROJECT_STATE.md` aggiornato, **nessun file cancellato o README sostituito**, nessun codice o firmware modificato, nessun test/build eseguito per questa annotazione, `valhalla` invariato; commit documentale `[skip ci]`. **Prossima azione della fase pulizia:** compilare matrice dei documenti e sostituire `README.md` con la descrizione italiana verificata prima di eliminare le guide legacy.


## Roadmap: modalità Pausa Caffè — standby BLE e riattivazione da Android — 2026-11-05

**Richiesta e obiettivo motociclistico:** dopo l'autospegnimento già realizzato, prevedere una **sosta breve a basso consumo** per una pausa caffè, con riattivazione del navigatore dal telefono senza dover premere il pulsante PWR. **Funzionalità proposta, NON implementata** da questa annotazione.

**Schema di comportamento da prototipare:**
1. Dopo i 120 secondi attualmente usati come timeout dopo arrivo a destinazione oppure disconnessione BLE (il timer si arma solo dopo una prima connessione), **entrare prima in standby** anziché spegnere immediatamente. Valutare eventuale sospensione anticipata tramite comando esplicito dell'utente, senza compromettere l'uso in marcia.
2. Nello standby spegnere in modo sicuro il pannello **CO5300 AMOLED** (non è una retroilluminazione), sospendere rendering LVGL, flush DMA/TE e sensori/attività non necessari, ridurre il carico CPU. Mantenere NimBLE operativo in modalità idonea, con advertising a basso consumo quando scollegato: **questo non è deep sleep** e l'assorbimento residuo deve essere misurato fisicamente.
3. **Risveglio via Android:** alla riapertura dell'app o alla ripresa di una navigazione, Android rileva/riconnette l'ESP32 ancora alimentato e invia un comando di wake autorizzato oppure un messaggio di sessione appropriato (protocollo da definire e collaudare). Ripristinare pannello/LVGL, stato UI e ordine geometry → snapshot, senza reintrodurre il vecchio problema di navigazione non ripresa dopo riconnessione. Evitare wake spurii da scansioni BLE di altri dispositivi.
4. **Sosta prolungata:** durata proposta di **15 minuti di standby**, preferibilmente configurabile da Android, poi spegnimento completo mediante **AXP2101** con fallback deep sleep come nel percorso di power-off esistente. A spegnimento PMIC avvenuto, **Android non può più svegliare via BLE**: resta necessario il tasto PWR, salvo una futura modifica hardware da studiare separatamente. Possibile spegnimento immediato sotto una soglia di batteria sicura.
5. Qualsiasi risveglio deve annullare correttamente il timer di autospegnimento; gestire con priorità l'avvio rotta e la pressione PWR, gli arrivi, la chiusura app, Bluetooth Android spento, timeout e carica USB. Non permettere che un timer vecchio spenga l'ESP32 dopo una riconnessione riuscita.

**Punti tecnici da verificare nel sorgente prima di implementare:** `platforms/esp32/main/app_main.cpp` oggi chiama `power_down()` dopo 120s e usa `board_port_power_off()` AXP2101, con fallback `esp_deep_sleep_start()` e wake su PWR/GPIO3; `board_port_waveshare_1_75c.cpp` gestisce pannello/TE/DMA; BLE è NimBLE e il display usa LVGL. Verificare API CO5300 display on/off, sincronizzazione LVGL worker e sicurezza thread/lock, advertising e parametri NimBLE di risparmio, opzioni sleep CPU che **mantengano effettivamente BLE**, stato di carica e riaccensione del PMIC. Nessuna stima di consumo/risveglio va dichiarata validata prima di misure.

**Test richiesti:** test nativi per la macchina a stati `ACTIVE → STANDBY → ACTIVE / POWER_OFF`, annullamento timeout e comandi autorizzati; test Android per riconnessione/wake; prove su hardware con amperometro a display acceso/spento, BLE connesso/in advertising, batteria e alimentazione USB; cicli ripetuti di sospensione/risveglio, route BLE e ricostruzione completa scena, verifica FPS/lock dopo wake e spegnimento dopo 15 minuti. Prima del merge ottenere test hardware positivo; `valhalla` deve rimanere invariato.

**Collocazione roadmap:** sviluppo isolato dopo il banco prova Windows e il primo consolidamento delle migliorie grafiche, con priorità relativamente alta come funzione d'uso quotidiano in moto. Le procedure utente del manuale non cambiano finché la funzione non esiste. **Questa sessione modifica solo `PROJECT_STATE.md`**, senza test, build o flash; documento versionato con commit `[skip ci]`. **Prossima azione specifica di questa funzione:** definire la macchina a stati e misurare l'assorbimento attuale per stabilire obiettivi realistici di risparmio.


## Decisione di prodotto: solo moto; simulatore, zoom intelligente, routing panoramico/ad anello — 2026-11-05

**Ambito vincolante:** Moto Chat GPS rimane un navigatore **dedicato esclusivamente alle motociclette**. Non sviluppare profili camper, funzioni per veicoli pesanti o restrizioni di sagoma/peso come requisito di prodotto. Il camper era stato menzionato **solo come eventuale mezzo per una prova di lunga percorrenza (long-range test)**, utile a raccogliere log estesi di autonomia, stabilità BLE, GPS, routing e rendering; non cambia l'utenza prevista.

**Tre evoluzioni confermate da pianificare** (nessuna è implementata da questo aggiornamento documentale):

1. **Simulatore Windows / banco prova riproducibile.** Proseguire con la roadmap dettagliata sottostante: prima un registratore Android **opt-in** dei fix GPS/GNSS, geometrie, snapshot, scene MotoMap e traffico BLE in trace versionati, quindi un player Windows che riutilizzi davvero `shared/nav_presenter` / `shared/nav_ui` e renda la schermata ESP32 a 466×466. Supportare pausa, avanzamento per frame/evento, velocità variabile, seek su manovra, screenshot e confronto tra revisioni. Distinguere replay **esatto dei dati realmente trasmessi** dal **ricalcolo** Android/Valhalla a parità di GPS; solo il secondo può misurare modifiche ai criteri di selezione Android. Come evoluzione del banco prova valutare anche l'invio di GPS simulato ad Android e il collegamento con un ESP32 reale via BLE: richiede un percorso di test protetto, non dev'essere attivo durante la guida. Valutare un evento utente "marca problema" per ritrovare nel replay gli istanti critici.
2. **Zoom intelligente e inquadratura contestuale.** Evolvere lo zoom progressivo attuale (in sperimentazione fino a **4,0×**) in una camera dinamica diversa per **rotatorie, svincoli, tornanti, curve ravvicinate e rettilinei**: integrare velocità, distanza/tempo alla manovra, dimensioni del ring, rami d'uscita, curvatura e geometria realmente visibile. Preferire **l'intero anello e le uscite** a un ingrandimento che le tagli; anticipare la manovra successiva solo quando serve, con transizioni smorzate, isteresi, limiti di zoom e fallback a dati GPS imprecisi. Non reintrodurre frecce o nomi strada: restano rotta verde, strade di contesto, distanza di manovra e arrivo. Validare nel simulatore con screenshot e sul display reale, monitorando BLE/FPS. L'algoritmo adattivo **non è ancora sviluppato**.
3. **Routing motociclistico panoramico + percorsi ad anello.** Approfondire le opzioni Touring/panoramico già previste nel progetto, senza considerare automaticamente completo un algoritmo panoramico. Definire un criterio di itinerario per moto che bilanci percorrenza, **curvosità reale**, preferenza per strade secondarie percorribili, penalità autostrade quando selezionate, qualità/connettività del grafo OSM, differenza tempo/km rispetto alla via rapida e, solo se esistono fonti affidabili, dislivello o panorami. Non classificare una strada come "panoramica" dal solo numero di curve. Aggiungere **giri ad anello**: partenza e arrivo nello stesso punto, distanza o durata obiettivo, direzione/fascia geografica facoltativa, tappe desiderate, evitare ripassaggi inutili, con proposta di più varianti e possibilità di modificarle. Rimanere **offline con Valhalla** dove possibile, senza rompere il routing normale o le tappe intermedie; partire da test sintetici e poi verificare percorsi reali con tappe e ricalcoli. Algoritmo panoramico avanzato e generatore di anelli **da progettare/implementare**, non già validati.

**Ordine raccomandato:** completare prova hardware dell'attuale selettore strade → registratore GPS completo → simulatore/replay Windows → zoom contestuale e selezione strade tramite test riproducibili → routing panoramico avanzato e generatore di percorsi ad anello. Il routing può essere sviluppato in un filone indipendente dopo aver stabilizzato il banco prova.

**Stato tecnico di questa sessione:** soltanto `PROJECT_STATE.md` modificato su `test/road-contrast-cleanup-20261104`; `valhalla` invariato, nessun cambio a codice/protocollo/firmware/manuale, nessun test eseguito (non richiesto per annotazione), **nessuna nuova CI** per commit `[skip ci]`. Le istruzioni operative non cambiano, quindi il manuale non necessita aggiornamenti. **Prossima azione concreta:** al primo intervento implementativo definire schema v1 dei trace Android e relativi test di registrazione e replay.


## Roadmap approvata: registrazione GPS completa e simulatore interfaccia ESP32 su Windows — 2026-11-05

**Decisione utente:** prima realizzare un sistema completo di registrazione e riproduzione della navigazione, quindi usare i dati per **simulare sul PC Windows il comportamento grafico dell'ESP32** e accelerare il debug di svincoli, rotatorie, tornanti, zoom, strade grigie e guasti di comunicazione. Questo è un **piano, non un'implementazione**. Continuare a lavorare nel branch sperimentale `test/road-contrast-cleanup-20261104`; nessun merge su `valhalla` senza autorizzazione. Lo stato delle prove stradali resta distinto dal CI.

### Fase 0 — Proteggere la versione funzionante e preparare casi riproducibili

- L'ultima prova ha confermato **solo l'inserimento senza disconnessione immediata** di una tappa: non sono ancora verificati l'arrivo alla tappa, la prosecuzione, il ricalcolo con tappe né la ri-sincronizzazione dopo disconnessione volontaria durante navigazione.
- L'ultima selezione corridoio/rotatorie è il commit Android `c57adb2`; [CI 42079332773](https://github.com/thriller47/moto-gps-waveshare/actions/runs/42079332773) verificata `completed/success` (Android verde, ESP32/native saltati perché non modificati). Mancano confronti visivi e performance su strada della nuova selezione.
- Conservare campioni di prova riproducibili: rotatorie con 5–8 rami, svincoli, viadotti/sottopassi, tornanti, percorso fuori rotta, GPS fermo o poco preciso, tappa intermedia e riconnessione BLE. Stabilire *prima* gli indicatori: comparsa delle uscite alla distanza corretta, copertura contestuale, 20/160, drop delle scene, FPS e display-lock timeout.

### Fase 1 — Nuovo registratore GPS/navigazione completo sul telefono Android (PRIMA del simulatore)

**Limite dei log attuali:** `DiagnosticLog` salva solo eventi sintetici e **omette volutamente latitudine/longitudine, geometrie e contenuti completi**; gli ZIP attuali non consentono replay fedele. Non estendere indiscriminatamente il log testuale continuo né scrivere sulla flash ESP32 durante la guida.

Definire un **trace versionato** separato, ad esempio archivio ZIP contenente `manifest.json` + `events.jsonl` (JSON Lines; nome/schema da confermare all'implementazione), con **record ordinati e numerati**. Registrazione esplicitamente attivata dall'utente e esportabile; nessuna raccolta automatica continua di itinerari e coordinate precise. Campi richiesti, ove forniti dalle API e con indicazione chiara di valore mancante:

1. **Sincronizzazione:** timestamp UTC, tempo monotono Android, progressivo evento, sessione, versione APK/firmware, versioni codec/protocollo, coordinate e unità, sorgente dei dati, impostazioni navigazione. Preservare intervalli reali, ordinamento e ripartenze di processo/sessione BLE.
2. **Fix GNSS grezzi e accettati/rifiutati:** lat/lon, velocità, rotta/bearing, accuratezza orizzontale e verticale, accuratezza velocità/rotta, quota, provider, età fix, flag mock se disponibile, esito e motivo filtro GNSS. Separare movimento reale, predizione/interpolazione e heading derivato da sensori/IMU.
3. **Routing e progresso:** origine/destinazione e tappe ordinate, preferenze, fonte Valhalla offline/online, rotta completa in punti e manovre/offset, generazione/token, progress metri, distanza manovra/arrivo, velocità e limite strada, stato navigazione/arrivo, trigger e risultati di `REROUTE`, prossimo waypoint, errori.
4. **Scena reale destinata all'ESP32:** snapshot di navigazione **già normalizzato**, finestra di rotta trasmessa, origin/radius/revision, polilinee grigie **con punti, classe e ordine**, candidate/focus ring/rami/slot, zoom e stato telefono/BLE ove disponibili. Registrare esattamente cosa è stato **prodotto e inviato**, distinguendolo dai dati MotoMap/Valhalla originali.
5. **Trasporto e display:** inizio/fine handshake, session ID, GATT errors e retry, tempi e ordine dei messaggi BLE (snapshot/geometry/map scene), drop/coalescing, eventuali ACK; diagnostica ESP32 correlata tramite uptime, revisione scena, FPS, lock/timeout, stato batteria/riconnessione. Le immagini effettive sul display **non sono già disponibili** nei log: un eventuale confronto fotografico/video sarà un dato separato, non inventato dal trace.

**Vincoli:** scrittura asincrona su Android con buffer/coda limitata, file a rotazione e limiti dimensionali; priorità alla navigazione e a BLE, mai bloccare UI/GPS; contatori di perdita espliciti; no coordinate/password nei log normali; opzione di esclusione o anonimizzazione per gli export. Verificare permessi e privacy perché un trace completo contiene **posizioni e itinerari personali**. Test: serializzazione/deserializzazione con golden fixtures, monotonicità, perdita controllata, export/import, percorsi lunghi, riconnessione e compatibilità fra versioni. **Deliverable:** una registrazione reale di 10–20 minuti che possa essere caricata e riprodotta senza rete, telefono o ESP32.

### Fase 2 — Simulatore Windows dell'ESP32, basato sul renderer di produzione

- Usare il **medesimo codice** `shared/nav_presenter` e `shared/nav_ui` (LVGL, RGB565, canvas 466×466) e, ove possibile, la macchina di stato effettiva `platforms/esp32/main/phone_nav_bridge.cpp`. I test `tests/native/nav_ui_render_tests.cpp` già renderizzano in modalità headless con LVGL; non rappresentano però una GUI interattiva Windows già pronta. Preparare port desktop LVGL con framebuffer o SDL, CMake e toolchain Windows (MSVC/MinGW da scegliere dopo spike).
- Player per importare il trace, play/pausa, velocità 0,25×–8×, singolo frame/evento, avanzamento di 1/5/10 s, seek a timestamp/manovra, loop di un tratto, freeze GPS, deviazioni e disconnessioni BLE controllate. La riproduzione deve usare il **tempo del trace**, non l'orologio del PC, e conservare l'ordine originale dei messaggi.
- Visualizzare virtualmente il display AMOLED circolare 466×466, con pannello di debug separato: GPS vs posizione renderizzata, heading, velocità, raggio/zoom, distanza svolta, rotta verde, ring e uscite, conteggio strade/punti per ruolo, revisione/latency della scena, stato BLE; esportare screenshot PNG e sequenze di immagini per confronto. Non creare mappe/uscite artificiali assenti dagli eventi registrati.
- Specificare due modalità: **replay esatto degli input BLE registrati** (diagnosi del renderer/ESP32) e **ricalcolo offline lato Android con gli stessi fix GNSS** (diagnosi della selezione MotoMap, utile ma dipendente dagli asset/engine). Tenere separati chiaramente gli esiti dei due esperimenti. Se si simula il solo presenter non chiamarlo una riproduzione esatta del firmware su hardware.

**Deliverable:** eseguibile Windows avviabile senza ESP32, che ricostruisce la stessa sequenza di schermate da uno stesso file; validazione visuale del primo trace reale su scene di rotatoria e svincolo. Esplicitare limiti dell'emulazione: timings QSPI/TE/DMA, BLE radio, PSRAM e sensore fisico richiedono ancora test sull'hardware.

### Fase 3 — Debug deterministico, regressioni e interventi alla selezione strade

- Automatizzare fixture e screenshot/frame-hash dal renderer **reale** (come i test headless LVGL esistenti); confrontare stato e grafica prima/dopo una modifica e costruire test di regressione per uscita mancante, ring incompleto, incrocio sopra/sotto, strada irrilevante, sovraccarico 20/160, zoom 4×, posizione 20–30m in ritardo, BLE reconnect, passaggio waypoint.
- Usare il replay per misurare **quanto prima** un'uscita viene selezionata, se è stata omessa per mancanza di nodi OSM o per slot, e quali strade secondarie hanno occupato il budget. Mantenere il filtro layer/grade separation **disattivato** salvo una soluzione verificata; 24/192 solo come successivo esperimento distinto dopo test di fluidità.
- Generare report comparabili: tempo/posizione di prima apparizione uscita, numero di uscite topologicamente valide e realmente trasmesse, differenze di scena e pixel, costo CPU/tempi update host (non equiparare FPS host a FPS ESP32), coalescing/ACK e gap del trace. Dopo i test sintetici, validare le modifiche sul mezzo con lo stesso percorso e nuovi log.

### Ordine operativo e criterio di completamento

1. Congelare un trace minimo e un contratto di eventi/manifest; implementare/esportare/verificare la cattura Android **prima** del simulatore.
2. Produrre un primo replay headless identico in sequenza e un player Windows con vista 466×466.
3. Importare un giro reale, riprodurre una rotatoria con uscita tardiva e determinare se il difetto nasce **in Android, durante BLE o nel renderer ESP32**.
4. Aggiungere test regressione e migliorare selezione/zoom; **una CI consolidata a fine change-set**, test hardware prima dell'eventuale merge `valhalla`.

**Stato e controlli 2026-11-05:** roadmap e ripristino documentazione radio **soltanto documentali**; nessun nuovo registratore o simulatore implementato, nessun test eseguito per questa nota, nessun firmware flashato. La procedura del manuale radio è stata ripristinata riferendosi al parser `signal_wifi_console.cpp` realmente presente e viene etichettata sperimentale/isolata. **Prossima azione di sviluppo:** definire schema v1 del trace e test di roundtrip/import, poi instrumentazione Android opt-in a basso impatto.


## Priorità corridoio di percorso e anticipazione svincoli/rotatorie — 2026-11-05

**Feedback:** miglioramenti visibili ma ancora troppe strade periferiche che saturano i 20 slot; svincoli e uscite di rotatoria compaiono in ritardo. Non confondere con il bug precedente della tappa: ultima prova di semplice inserimento riuscita, test completo con tappa su strada ancora da fare. **Branch sperimentale** `test/road-contrast-cleanup-20261104`; `valhalla` invariato.

**Cambiamento Android sperimentale:** `RoadContextSceneSelector` mantiene fino a 7 uscite realmente collegate al ring e **almeno 6 vie GPS locali**, ma ora valuta più campioni del corridoio verde *davanti* (circa 90–500m) per anticipare rami e incroci con una scelta ordinata; priorità fino a 6 strade future prima delle vie locali meno utili, più gli eventuali rimanenti corridoio negli slot extra. Le geometrie scelte vengono ritagliate attorno al rispettivo punto futuro, non a un unico upcoming di circa 90–220m. Quando la rotta è assente/lontana oltre 80m dal GPS, fallback locale e precedente logica senza filtro rigido: fondamentale per deviazioni. `RoadContextJunctions` ammette un anello con uscite topologicamente dimostrate che si trova **entro 500m lungo il percorso futuro** e entro 32m dal suo asse; il punteggio premia la prossimità al corridoio. Un crossing geometrico da solo NON certifica alcuna uscita: solo node IDs condivisi tra ways. `NavigationViewModel` anticipa la cadenza scene a 3,5s/30m (in precedenza 5s/40m) solo a manovra nota entro 260m (prima 110m); registrati anche gap/allineamento del focus in `OSM_RING`.

**Invarianti:** stesso payload BLE, massimo 20 strade/160 punti, ESP32/Valhalla/MotoMap v3 invariati; nessun filtro layer/grade separation, nessun taglio totale delle strade fuori percorso, nessuna freccia reintrodotta. **Rischi:** costo CPU/cadenza BLE leggermente maggiori, eventuali falsi focus a un circuito urbano con uscite OSM valide che passa vicino alla geometria Valhalla, affollamento; attenzione a GPS fuori rotta, rami di cavalcavia/sottopassi e finestra di route con geometria corta. Test sintetici aggiunti per rampa futura e ring a ~430m, oltre ai precedenti test 20/160/off-route. **Verifiche al momento del commit:** sorgenti/test preparati; test automatici e hardware non ancora eseguiti, attendere l'unica CI consolidata. **Prossima azione:** verificare il job Android, poi prova su strada con log `OSM_RING` (`route_gap_m`/`route_along_m`), `OSM_SCENE_POLICY`, `BLE_MAP_SCENE`, FPS/GATT e comparsa anticipata di svincoli/uscite; tornare a cadenza precedente se peggiora fluidità. Procedura APK invariata, manuale non aggiornato.


## Verifica in presenza di una tappa — 2026-11-05, 18:39 CET

**Feedback utente:** «Ora sembra funzionare, non si disconnette più ESP». Analizzato `motogps-android-diagnostics (1).zip` (`current.log` 18:30:40–18:39:31, `esp32-current.log` relativo alla sessione 18:38:30–18:39:30). Connessione BLE riuscita 18:38:30, `WAYPOINT_EDIT action=add count=1` alle 18:39:06, `ROUTE_PREVIEW ready stops=1 options=1` alle 18:39:06, `ROUTE_START ready stops=1 route_m=11544 points=503 provider=valhalla-local` alle 18:39:11. Nei circa 25 secondi successivi all'aggiunta tappa, nessuna disconnessione `BLE_LINK`, `BLE_GATT` di errore, `BLE_WRITE_FAILURE`, `ROUTE_START_ERROR` o `ROUTE_PREVIEW_ERROR`; `NAV_SNAPSHOT` Android continuativi e diagnostica ESP32 aggiornata. Le due precedenti voci «Non connesso» 18:30:42 e 18:31:04 sono **antecedenti** alla connessione stabile delle 18:38:30 e all'aggiunta tappa; non sono regressioni osservate durante il test.

**CI della correzione `863f559`:** [Project Build 42070455233](https://github.com/thriller47/moto-gps-waveshare/actions/runs/42070455233) `completed/success`; Android verde, ESP32 e native saltati perché invariati. **Stato:** prova positiva per aggiunta di una tappa e avvio navigazione a dispositivo pressoché fermo (velocità GNSS prevalentemente 0 m/s), non prova su strada né test di disconnessione/riconnessione volontaria DURANTE una rotta attiva. `BLE_NAV_RESYNC` non appare nel nuovo ZIP perché non c'è stata riconnessione durante navigazione; la resilienza alla riconnessione resta da collaudare. Causa originaria della caduta BLE non dimostrata. Nessun cambio a sorgenti, protocollo, MotoMap, Valhalla o procedure del manuale; `valhalla` non toccato. **Prossima azione:** breve test su strada con tappa, poi test volontario di disconnessione/riconnessione BLE a navigazione attiva verificando il ritorno del percorso e `BLE_NAV_RESYNC` nel log.

## Disconnessione BLE con tappa intermedia e ripristino rotta — 2026-11-05

**Segnalazione utente:** attivando una tappa intermedia l'ESP32 si disconnette; riconnettendolo la navigazione non riprende, finché non si riavvia senza tappe. ZIP diagnostico `motogps-android-diagnostics.zip` (17:44:52–17:46:59): disconnessioni BLE 17:46:35 e 17:46:45, seguite da handshake riusciti alle 17:46:39 e 17:46:49; `NAV_SNAPSHOT` continua su Android dopo entrambe. Un `REROUTE` Valhalla locale riesce alle 17:46:47; questo non dimostra che l'ESP abbia ripristinato la geometria. Nel log non è registrato l'evento di inserimento della tappa, quindi la **causa della disconnessione resta da accertare**: non attribuirla senza prova a Valhalla o al traffico BLE.

**Correzione su `test/road-contrast-cleanup-20261104`:** all'evento BLE `Connesso` e con navigazione già attiva, ritrasmettere la finestra geometrica della rotta prima dello snapshot attivo, usando la stessa politica del sender iniziale e senza cambiare codec, payload o limiti 20 strade/160 punti. Non riprodurre navigazione quando lo stato è idle, né geometria dopo arrivo; mantenere il solo snapshot per rotte senza geometria. Aggiungere eventi diagnostici `BLE_GATT` (stato e status Android), `BLE_WRITE_FAILURE` (GATT), `WAYPOINT_EDIT`, `ROUTE_PREVIEW`/`ROUTE_PREVIEW_ERROR`, `ROUTE_START`/`ROUTE_START_ERROR`, `BLE_NAV_RESYNC`/`BLE_NAV_RESYNC_ERROR`, senza coordinate. Test JVM mirati per la policy di replay; rilascio previsto con unica CI consolidata.

**Validazione hardware ancora indispensabile:** riprodurre la navigazione verso una destinazione con una tappa, disconnettere/riconnettere ESP32 durante la rotta, verificare che torni la linea verde senza riavvio; esportare log includendo l'istante esatto in cui si aggiunge la tappa, i codici `BLE_GATT`, il replay `BLE_NAV_RESYNC`, l'eventuale `ROUTE_*_ERROR`. Se BLE cade ancora, analizzare motivo GATT/ESP32 separatamente. Nessuna rigenerazione MotoMap/Valhalla, nessun nuovo flash ESP32 per questa sola modifica Android; `valhalla` invariato. Il manuale operativo non necessita aggiornamenti. **Prossima azione:** verificare l'esito della CI Android e effettuare prova di riconnessione con tappa sul dispositivo.


> **Updated: 2026-11-04** (Europe/Rome). Living project handoff; branch-specific outcomes are marked below. **Code/CI success is not proof of on-road hardware stability.**

## Aumento priorità rotonde/uscite e zoom manovre 4,0× — 2026-11-05

**CI consolidata completata:** [workflow 42028360341](https://github.com/thriller47/moto-gps-waveshare/actions/runs/42028360341), commit `e2b4c17`, **Android/native/ESP32 tutti verdi** (`completed/success`); artifact `Glimpse-MOTO-GPS-Android-Final.apk` ed `ESP32-Firmware` disponibili. Questo verifica compilazione e test automatici, **non** fluidità e visibilità delle uscite sul mezzo. Il firmware va flashato per attivare il nuovo zoom, APK Android va aggiornato per il nuovo budget; nessuna rigenerazione MotoMap. Prossima azione: giro reale con log, controllare anticipo delle rotonde e che lo zoom 4× non tagli gli anelli dal display; monitorare eventuali regressioni BLE/GATT e contesto fuori rotta. Annotazione CI aggiunta con commit documentale `[skip ci]` senza nuova build.

**Branch:** `test/road-contrast-cleanup-20261104`, a partire dal commit `8ce8a96` (nessun merge su `valhalla`). Nuova prova dell'utente: miglioramenti ulteriori e visibili, ma desiderata maggiore priorità a rotonde/uscite e zoom fino a **4×**. Log `motogps-android-diagnostics(20261105-122934).zip`: 159 scene Android, **134 a 20 strade**, massimo **133 punti su 160**; 88 scene con focus ring, tutte chiavi `s:`; **85** con 5 uscite prioritarie ma 6–10 rami distinti rilevati. In 156 scene con record in entrambe le tracce Android/ESP32 combaciano revisione e conteggi; nessun timeout grafico e nessun errore GATT rilevato in questo archivio. Limite riscontrato: numero **slot strade**, non vertici. I rami OSM non equivalgono necessariamente a uscite fisiche: non mostrare strade prive di nodi OSM condivisi.

**Cambiamento sperimentale:** `RoadContextJunctions` dà priorità fino a **7 rami distinti**, partendo dal più rilevante e distribuendo i successivi attorno all'anello; `RoadContextSceneSelector` usa fino a **20 punti per l'anello** e **10 per ciascuna uscita**, contro 16/8 precedenti, mantenendo **20 strade / 160 punti aggregati**, **almeno 6 slot GPS locali** se disponibili e **fino a 4 slot upcoming**. In caso di focus assente resta il fallback usuale. `shared/nav_presenter/src/moto_nav_presenter.cpp`: zoom progressivo manovra invariato come comportamento, ma massimo da **3,4× a 4,0×** nei 100 m finali; vale per rotta verde e contesto grigio, senza aggiungere frecce o nuove indicazioni. Test Android aggiornati per 7 uscite, protezione GPS e capienza; test C++ verificano massimo 4×.

**Vincoli:** filtro layer/grade separation **sempre disabilitato**; routing Valhalla e codec BLE invariati; nessuna rigenerazione MotoMap v3, nessuna modifica protocollo; serve **APK Android aggiornato + nuovo flash ESP32** per vedere entrambe le migliorie. Il solo zoom è nel presenter condiviso e richiede firmware ESP32. **Stato:** prima della CI nessuna validazione automatica o su strada di queste modifiche; verificare esito dei job Android/native/ESP32; rischi di scena più densa e uscita meno visibile con zoom 4×, da osservare nel giro reale. **Prossima azione:** un'unica CI consolidata e prova sul percorso conosciuto, con diagnostica `OSM_RING`, `OSM_SCENE_POLICY`, FPS/GATT e disponibilità strade fuori rotta. Nessun cambiamento alle procedure del manuale operativo.

## Correzione test selezione uscite più vicine — 2026-11-05

**CI iniziale commit `4eb35bc`:** run [42020202806](https://github.com/thriller47/moto-gps-waveshare/actions/runs/42020202806), `native` verde e `Android` fallito per **un solo test** su 135, `RoadContextJunctionsTest.eightConnectedBranchesAreDistributedAroundRingWithinFiveSlots` (riga 143). Il fixture ottagonale usa coordinate lat/lon; la prima uscita nell'array non è necessariamente quella metrica più vicina al GPS. La nuova asserzione verifica la scelta rispetto alla **distanza quadratica metrica corretta**, senza dipendere dall'ordine nominale di generazione. Nessuna modifica alla logica runtime in questa correzione. Nuova CI consolidata da verificare; non installare APK di commit `4eb35bc` senza esito verde. **Prossima azione:** CI completa Android/native/ESP32 e poi test hardware su strada.

## Raffinamento uscite raggruppate e palette grigia — 2026-11-05

**Branch:** `test/road-contrast-cleanup-20261104`, a partire dalla versione `abc7ae6`. Dopo la nuova prova (utente: situazione decisamente migliorata ma non perfetta), il log `motogps-android-diagnostics(20261105-112340).zip` mostrava 114 scene, 91 al massimo di 20 strade, 80 con focus ring, 112/112 corrispondenze Android/ESP32 nel periodo confrontabile, 5 ricalcoli Valhalla riusciti e zero timeout grafici ESP32 rilevati. In 72 scene il ring aveva 6–8 ways connesse ma solo 5 slot uscite: **ways collegate non equivalgono necessariamente a uscite fisiche**. Multi-way nel giro non osservato come focus scelto (chiavi `s:`); resta da collaudare sul campo.

**Modifiche sperimentali per il prossimo giro:** `RoadContextJunctions.kt` raggruppa le ways che condividono lo stesso nodo OSM con l'anello **e hanno direzione di partenza simile (entro 25°)**, evitando che due spezzoni dello stesso ramo consumino slot separati. Non raggruppa automaticamente rami con direzioni diverse allo stesso nodo. Se più di cinque gruppi, sceglie una diramazione vicina a GPS/percorso imminente e poi rami distribuiti lungo l'anello, anziché scegliere le prime cinque ways nell'ordine della tile. Ring conserva i nodi delle **uscite effettivamente prioritarie**. Diagnostica `OSM_RING`: `exit_ways` (ways topologicamente collegate), `branches` (direzioni raggruppate), `prioritized` (massimo cinque); `OSM_SCENE_POLICY.exits_sent` indica gli slot realmente inviati. Test sintetici di duplicati stessa uscita, uscite di direzione differente allo stesso nodo e otto attacchi distribuiti. Il raggruppamento è euristico, **non** una classificazione legale/OSM certa; monitorare uscite mancanti.

**Palette renderer ESP32 `shared/nav_ui/src/moto_nav_ui.cpp`:** grigio strada principale `#E0E4E6 → #D0D5D8`; strade normali `#BEC5C9 → #A8B0B5`; strade secondarie di servizio `#929CA2 → #788188`. Verde rotta e altre parti UI invariate. **Filtro livelli/grade separation disabilitato**, 20 strade/160 punti invariati, nessuna modifica a Valhalla o protocollo BLE. La modifica include `shared/nav_ui`, quindi necessita **nuova build e flash ESP32 oltre ad aggiornamento Android** per verificare entrambe le novità.

**Validazione:** da verificare tramite un'unica CI Android+native+ESP32; hardware e prova su strada non ancora verificati. Prossima azione: installare APK nuovo, flashare ESP32, provare rotonde con più uscite e deviazioni; esportare nuovo ZIP diagnostico confrontando `exit_ways/branches/prioritized/exits_sent`, eventuali errori BLE e frame/timeout ESP32. Il manuale operativo non richiede nuove istruzioni: procedura flash già documentata. Nessun merge su `valhalla` prima del test.

## Riconoscimento rotonde multi-way, scoring e priorità GPS — 2026-11-05

**Verifica CI successiva al commit `abc7ae6`:** workflow [41990146732](https://github.com/thriller47/moto-gps-waveshare/actions/runs/41990146732), stato `completed/success`. Job Android `Build Android app` riuscito con `./gradlew build -x lint -x lintVitalRelease --build-cache` (compilazione e test Gradle) e APK debug caricato come artifact. Job `native` ed `esp32` correttamente saltati per assenza di modifiche. **Nessun collaudo su strada di questa nuova logica effettuato**: il risultato CI non verifica antìcipo delle uscite, falsi positivi, stabilità FPS/BLE o scene senza contesto; questi restano il passo successivo. Per la sola documentazione usare `[skip ci]` e non avviare un'altra build.

**Branch di lavoro:** `test/road-contrast-cleanup-20261104`, baseline runtime `8243eb8` (20/160). **NON integrato in `valhalla`.** Modifica sperimentale successiva alla prova 20/160 (178 scene, 152 scene al limite 20 strade, massimo 132 punti, 153/153 revisioni combacianti Android/ESP32), per migliorare riconoscimento anticipato delle rotonde e preservare strade locali dopo deviazioni. **Filtro dei livelli e grade separation ESPLICITAMENTE DISABILITATO** per decisione dell'utente: non è stato riattivato.

**Implementazione Android predisposta in un unico change-set:**
- `RoadContextJunctions.kt`: disaccoppiato rilevamento/score/selezione; cerca anelli geometricamente chiusi su singole OSM ways e circuiti distribuiti su **2–8 ways con estremi OSM effettivamente condivisi**. Ricerca topologica limitata a 90 edge locali, 3500 stati DFS e 48 candidati; verifica diametro, circonferenza, compattezza/area, prossimità GPS o `upcoming`, classe e connettività delle uscite. **Confidenza iniziale >=70**, un solo anello massimo, fallback normale se nessun candidato qualificato. Vietato dedurre uscite da crossing 2D: soltanto nodi OSM comuni. Non utilizza `layer`.
- `RoadContextWindow.closedRing`: preserva chiusura, nodi di connessione alle uscite e cambi di curvatura nel budget invece di ricampionare esclusivamente a passo costante; rifiuta una polilinea aperta. Il ring singolo/multi-way è rappresentato come UNA polilinea BLE; i suoi membri non vengono inviati di nuovo come vie indipendenti.
- `RoadContextSceneSelector.kt`: quote esplicite **1 ring + massimo 5 uscite + minimo 6 strade GPS distinte dal focus + massimo 4 upcoming + fallback nearest** con deduplicazione, prenotazione punti per gli slot residui e tetto invariato **20 polilinee/160 punti** (massimo 16 punti per ring e 8 per strada ordinaria). Le uscite sono centrate sul nodo di attacco; le strade locali sempre sulla posizione GPS reale, anche quando fuori percorso.
- `RoadContextUpcoming.ahead`: punto interpolato lungo la geometria della finestra verde, anticipo adattivo indicativo 90–220 m in funzione di velocità e precisione GNSS, al posto del primo vertice >=160 m.
- `NavigationViewModel`: integra rilevatore e selettore senza modificare Valhalla né l'algoritmo di progresso. Nuovi eventi `OSM_RING` (numero candidati/qualificati, focus/score, distanza GPS/upcoming, uscite e scarti) e `OSM_SCENE_POLICY` (slot, punti per focus/GPS/upcoming, uscite realmente inviate, strade locali fuori rotta, omissioni). `BLE_MAP_SCENE` conserva 20/160. Nessuna variazione a ESP32, codec/protocollo BLE o MotoMap v3.
- Test Kotlin sintetici per ring single-way/multi-way, 1–2 uscite, connessioni mancanti, incrocio normale, blocco stradale senza uscite, chiusura imprecisa/falsa, uso invariato dei layer, ring lontano, selezione GPS in scene dense, deduplicazione, fallback off-route, `upcoming` mancante/adattivo e geometria con pochi punti/nodi obbligati.

**Stato verifiche al momento della modifica:** codice e test preparati, **non ancora validati da CI o prova su strada**; non dichiarare il problema delle rotonde risolto senza collaudo. **Rischi e limiti:** tag `junction=roundabout` assente dal formato MotoMap v3, quindi scoring euristico (possibili falsi positivi per piccoli circuiti urbani); mancanza di qualche way/nodo nel pacchetto o ai confini tile; complessità di ricostruzione se molti cicli urbani; geometrie delle uscite spezzate; costo/latency delle scene BLE e due precedenti `DISPLAY_LOCK_TIMEOUT`. **Prossima azione:** eseguire una sola CI consolidata Android, correggere eventuali test falliti in batch, poi confronto reale su rotonde e deviazioni con diagnostica completa. Se risultato positivo e fluido, solo in fase separata valutare 24/192. **Manuale operativo:** procedure invariate, nessun aggiornamento.

## Prova stradale 20/160 con due video e diagnostica — 2026-11-05

**Branch:** `test/road-contrast-cleanup-20261104`, commit runtime Android `8243eb8`, `valhalla` non modificato. **CI**: [41888380616](https://github.com/thriller47/moto-gps-waveshare/actions/runs/41888380616) `completed/success`. L'utente riferisce **miglioramento concreto ma non ancora ottimale** dopo l'aumento 20 strade/160 punti; file esaminati `20261105_074318.mp4`, `20261105_075058.mp4`, `motogps-android-diagnostics(20261105-070238).zip` con `current.log` e `esp32-current.log`. Nei video il display ESP32 è perlopiù fuori inquadratura nel primo e soltanto parzialmente leggibile nel secondo: non usarli come prova conclusiva di quali uscite fossero effettivamente presenti; la valutazione visiva positiva ma incompleta deriva dal riscontro dell'utente.

**Numeri verificati sul log mattutino 2026-11-05:** 178 scene `BLE_MAP_SCENE`, **152/178 (85,4%) al massimo di 20 strade**, strade medie 18,4 (minimo 4), punti medi 96,9, massimo punti **132/160**, nessuna scena a 160. Ciò significa che è il tetto **strade** a saturarsi più spesso, NON quello dei punti. Fra attivazione diagnostica ESP32 (07:36:41) e ultima scena (07:53:45), **153 revisioni `MAP_SCENE` ESP32 coincidono esattamente** con le 153 revisioni Android nello stesso intervallo, anche nei conteggi strade/punti; non risulta perdita di scene in quel tratto monitorato, ma il tracciato non misura direttamente frame-rate o latenza BLE end-to-end. Sono presenti **due `DISPLAY_LOCK_TIMEOUT` da 250 ms** (circa 07:37:00 e 07:43:11): indagare se causano esitazioni percepite; non affermare che rendering/fps siano perfetti.

**Navigazione:** 12 trigger `REROUTE` e 12 successi `valhalla-local`, nessun `REROUTE_ERROR`. Due trigger `reverse=true` con soli **1 m e 3 m** di distanza dalla rotta (07:45:09 e 07:49:57) potrebbero meritare isteresi/controllo separato; non considerarli automaticamente bug senza traiettoria GPS. Nel periodo indicativo del **primo video** (07:43:18–07:45:45) ricorrono manovre `roundabout`, 7 ricalcoli dopo l'inizio ripresa e 25 scene tutte da 20 strade / massimo 111 punti; nel **secondo** (07:50:58–07:51:51) manovra `roundabout`, 1 ricalcolo, 9 scene tutte da 20 strade / massimo 125 punti. Associazione ai video basata sugli orari dei nomi file, da considerare indicativa. GPS generalmente accurato negli intervalli esaminati (circa 1,5–3 m).

**Decisione tecnica per la prossima fase:** non attribuire la carenza di uscite all'esaurimento dei 160 punti: migliorare riconoscimento rotonde multi-way, selezione dei rami effettivamente connessi, conservazione dei nodi di uscita nel resampling e budget esplicito con fallback GPS fuori rotta, come pianificato nelle sezioni seguenti. Aggiungere diagnostica `ring candidates / selected / exits / slot focus-nearest-upcoming` prima di dichiarare successi visivi. Un esperimento a **24/192** resta possibile dato il frequente raggiungimento dei 20 slot, ma è un **passaggio successivo e separato**; monitorare lock timeout, BLE/GATT e leggibilità, senza presumere che risolva le rotonde. **Filtro livelli/grade separation ancora DISABILITATO** per decisione utente, nessuna nuova verifica né modifica di questa logica. **Test codice dopo questa nota:** nessuno; solo analisi video e log. **Prossima azione:** predisporre test deterministici multi-way/uscite e selezione misurabile sul solo branch test, poi una CI consolidata e prova A/B su strada. Nessuna nuova procedura nel manuale operativo.

## Prossima prova stradale: 20/160 prima di valutare 24/192 — 2026-11-04

**Decisione concordata, DA TESTARE:** mantenere per ora la configurazione Android **20 strade / 160 punti** introdotta nel commit `8243eb8`; **non aumentare automaticamente** al massimo disponibile. Durante il prossimo giro, con diagnosi Android/ESP32, verificare: (1) fluidità e tempi di aggiornamento del display; (2) traffico BLE, latenza di trasmissione delle scene, errori GATT, eventuali code e disconnessioni; (3) continuità del contesto GPS anche fuori rotta prima del ricalcolo; (4) anticipo di apparizione delle rotonde complete e delle uscite; (5) numero **effettivamente trasmesso** di strade e vertici, non solo i limiti configurati; (6) eventuale rumore visivo introdotto dalle strade aggiuntive.

**Solo se la prova 20/160 è positiva**, proporre un nuovo esperimento separato sul branch `test/road-contrast-cleanup-20261104` a **24 strade / 192 punti**, massimo attualmente previsto dalla catena firmware/protocollo, seguito da un **secondo test su strada** con gli stessi indicatori. Se la fluidità, la stabilità BLE o la leggibilità peggiorano, tornare a 20/160: il massimo tecnico non è necessariamente il miglior compromesso. Non dare per risolto il riconoscimento delle rotonde grazie al solo aumento della capacità; mantenere separato il backlog multi-way/scoring/budget e contesto fuori rotta riportato sotto.

**Vincoli invariati:** `valhalla` non modificato e nessun merge senza approvazione; **filtro dei livelli disabilitato**, da riesaminare solo in una fase successiva; nessun cambiamento a ESP32, BLE protocol, Valhalla routing o MotoMap v3 ora. **Stato:** promemoria di prova futura, nessuna nuova implementazione e nessuna verifica sul campo di questa decisione. **Prossima azione:** analizzare il prossimo log stradale 20/160 e decidere se autorizzare la prova 24/192. Manuale operativo: nessun comando o procedura cambiata.

## Incremento capacità scena stradale a 20/160 — 2026-11-04

**Decisione utente:** sul branch sperimentale `test/road-contrast-cleanup-20261104`, aumentare da **16 a 20 polilinee stradali** e da **128 a 160 punti complessivi** per scena BLE. Modificato `NavigationViewModel.kt`: limiti effettivi e campi diagnostici `Context BLE TRIAL`/`BLE_MAP_SCENE capacity=20/160`. Rimane il massimo di 8 punti per strada ordinaria e 16 per l'anello chiuso della rotonda, con limite aggregato di 160: **20 strade è un massimo, non una garanzia**, specie quando l'anello richiede 16 punti. Capacità note del firmware/protocollo **24 strade/192 punti**, quindi il nuovo budget resta inferiore ai limiti esistenti. Nessun cambio alla selezione degli anelli, al contesto fuori rotta, alla topologia, al filtro livelli (deve restare **disabilitato**), a MotoMap v3, Valhalla, ESP32 o al protocollo BLE.

**Verifiche:** controllo statico dei limiti prima del commit, CI Android e prova in moto non ancora verificate per la modifica; non attribuire successo a test precedenti. **Rischio:** scene più grandi possono aumentare frammentazione/latency BLE e rumore visivo senza correggere gli errori di priorità. **Prossima azione:** verificare la CI consolidata e confrontare su strada copertura delle uscite e contesto GPS durante una deviazione; controllare numero punti/strade effettivamente inviati, stabilità GATT e frequenza delle scene. **Manuale operativo:** nessuna nuova procedura, non richiede aggiornamento.

## Raffinamenti alla prossima fase: selezione misurabile, budget e fallback — 2026-11-04

**Stato:** suggerimenti tecnici da applicare nella **prossima fase**, non ancora implementati né verificati. Integrano, non sostituiscono, il piano “Prossima fase proposta” sottostante. Riferimento: `test/road-contrast-cleanup-20261104` al commit `09d3aa8`, con `valhalla` invariato.

1. **Diagnostica quantitativa per ciascuna scena (`BLE_MAP_SCENE`/evento dedicato):** registrare numero di anelli candidati e sopra soglia, anello scelto e confidenza, uscite collegate trovate/effettivamente inviate, strade locali fuori percorso preservate, slots e vertici **effettivamente trasmessi** per categorie `focus` (anello+uscite), `nearest` (GPS), `upcoming` (percorso imminente), scarti per capienza e cause. Distinguere sempre candidati trovati, selezionati e serializzati per evitare falsi positivi diagnostici; evitare eccesso di log in movimento.
2. **Una sola rotonda per scena, con soglia:** `RoadContextJunctions.find()` sceglie già **un solo** anello con `minByOrNull`, ma oggi considera soltanto chiusura/plausibilità/prossimità; la modifica richiesta è valutare **più candidati con confidenza**, scegliere il migliore solo sopra soglia e lasciare la selezione normale quando nessuno è sufficientemente affidabile. Scartare/declassare falsi ring (semplice incrocio a quattro vie, loop semichiuso, viabilità urbana ad anello, incrocio multilivello come geometria; non filtrare i livelli).
3. **Policy esplicita e testabile `selectRoadPriority(...)`:** riservare posti prima di assemblare il `LinkedHashSet` e prima del `RoadContextWindow`, evitando l'ordine cumulativo implicito `ring + exits + nearest(8) + upcoming(6) + rest` che può esaurire gli slot. Quote **indicative**, da validare: anello 1, uscite fino a 5, **almeno 6 strade locali GPS**, fino a 4 contesto imminente; totale massimo **20 polilinee / 160 vertici**, budget restituito alle altre categorie quando inutilizzato. Applicare anche un budget separato per punti, non solo slot, semplificando prima le strade meno prioritarie. Testare deduplicazione del `LinkedHashSet` per evitare di perdere una strada/uscita importante per uguaglianza o duplicazione fra categorie. Lo scopo è preservare il contesto utile prima e dopo una deviazione, non selezionare solo la rotta.
4. **Geometria del focus quando la capienza scarseggia:** conservare la chiusura dell'anello solo se identificato come circuito chiuso; se il budget insufficiente non permette una figura valida, usare fallback `RoadContextWindow.around` oppure riassegnare i punti semplificando altre polilinee. **Nota sul codice esistente:** il fallback è già usato quando `pointsRemaining < 12` (non manca questa guardia); ciò che manca è la prenotazione preventiva dei punti per anello, uscite e strade locali. Mantenere i nodi delle uscite e i cambi di curvatura nella futura semplificazione.
5. **`upcoming` più robusto:** l'attuale criterio del primo punto della finestra a distanza GPS >=160 m è rigido e dipende dai vertici della polyline. Valutare posizione **lungo la geometria** del percorso, direzione di marcia, velocità e accuratezza GNSS per scegliere una distanza di anticipazione adattiva e limitata. Verificare `upcoming = null`, vicinissimo o molto lontano; non affidarsi a un singolo vertice GPS-prossimo per sapere quando compare la rotonda.
6. **Contesto off-route obbligatorio anche con GNSS incerto:** preservare un numero minimo di vere strade circostanti alla **posizione GPS**, senza filtrare per aderenza alla rotta, e considerare un raggio di selezione graduato con precisione GNSS e distanza dalla rotta. Non ampliare indiscriminatamente le tile caricate o il payload BLE: verificare densità e disponibilità locale. Misurare continuità e numero di strade mentre si devia **prima** che Valhalla ricalcoli.
7. **Test edge/regressione:** rotonde con 1–2 uscite, rotonde multi-way, anelli irregolari e falsi positivi, strade parallele off-route a 20–40 m, GPS fuori rotta vicino a un anello, `upcoming` nullo/vicino/lontano, budget punti quasi esaurito e ring completo con poco spazio, deduplicazione `LinkedHashSet`, scene dense, riduzione del rumore visivo. Per strade con layer diversi aggiungere test **osservazionali/non filtranti**: il filtro grade/layer rimane **DISABILITATO** su richiesta esplicita, da sperimentare in una fase separata.
8. **Metriche di accettazione:** confrontare prima/dopo su stesso giro e log: anticipo di apparizione del ring e delle uscite, quota scene con contesto locale sufficiente durante deviazioni, slot/punti medi per categoria, percentuale di ring scartati con motivo, errori BLE/GATT e scene non trasmesse. Un solo risultato CI consolidato dopo codice+test+docs, poi test hardware reale prima di autorizzare merge.

**Ordine di lavoro suggerito:** A) rilevatore multi-way + scoring con fixture; B) `selectRoadPriority` e allocazione budget slot/punti con fallback off-route; C) scelta `upcoming` e semplificazione geometrica; D) diagnostica strutturata e test di regressione; E) CI Android unica e giro di confronto. **Vincoli:** nessuna attivazione filtro livelli; invariati firmware ESP32, protocollo BLE, routing Valhalla, MotoMap v3 e guida 3,4×. **Test di questa aggiunta:** nessuno (sola documentazione); manuale operativo invariato perché non cambiano le procedure. **Prossima azione:** implementare A in modo isolato sul branch di test, con test deterministici, prima di modificare la policy BLE.

## Prossima fase proposta: rilevamento robusto rotonde e contesto fuori rotta — decisione 2026-11-04

**Stato: backlog approvato come direzione di sviluppo, NON ancora implementato né collaudato.** Partire dall'esperimento del commit `09d3aa8` sul branch `test/road-contrast-cleanup-20261104`, senza merge automatico su `valhalla`. L'obiettivo è mostrare rotonde complete e uscite per tempo, conservare la rete stradale locale quando la moto devia prima del ricalcolo e limitare falsi positivi e saturazione BLE. Non ripetere il selettore restrittivo del commit `4226b63` (test negativo).

1. **Separare rilevamento, confidenza e selezione.** Generare candidati anello, attribuire un punteggio e selezionare **al massimo un anello per scena** solo oltre una soglia; altrimenti mantenere la normale selezione GPS/itinerario, senza svuotare il contesto. Il punteggio **iniziale sperimentale** può usare chiusura topologica OSM (+30), prossimità geometrica primo/ultimo (+10), diametro/perimetro plausibili (+15), vicinanza alla parte imminente del percorso (+15), almeno tre connessioni reali (+20), classe compatibile (+10), soglia indicativa 70/100. Calibrare con fixture e prove; non sommare come indipendenti segnali correlati (chiusura geometrica e topologica). Declassare anelli troppo piccoli/grandi/lontani o scarsamente connessi e documentare i motivi di esclusione. Non classificare ogni loop stradale come rotonda.

2. **Ricostruire rotonde multi-way.** Utilizzare il grafo OSM dei nodi condivisi per riconoscere circuiti chiusi distribuiti fra più ways, non soltanto `points.first == points.last` in una way. Imporre limiti al numero di segmenti esplorati, alla lunghezza totale e al raggio per contenere tempi e falsi positivi. MotoMap v3 già contiene node IDs; verificare se bastino senza rigenerare il PBF/ZIP. Il tag `junction=roundabout` **non è attualmente esportato**: l'eventuale uso richiederebbe evoluzione esplicita e compatibile del formato, non darlo per presente.

3. **Uscite realmente connesse.** Considerare come uscite soltanto le ways con nodi OSM condivisi con il circuito, senza inferire connettività da incroci 2D. Dedicare un massimo di slot alle uscite e preservare un fallback quando node IDs sono assenti o incompleti; verificare segmenti spezzati e unioni imperfette. Evitare che le uscite consumino tutto il budget locale.

4. **Budget esplicito e flessibile.** Restare, almeno inizialmente, nel limite **20 polilinee / 160 punti**: obiettivo indicativo 1 slot anello, fino a 5 uscite, **almeno 6 strade vicine al GPS**, fino a 4 strade del percorso imminente. Le quote non usate tornano alle altre categorie; nessuna selezione orientata esclusivamente alla rotta. Il GPS reale deve governare il contesto locale anche quando `offRoute` o durante il ricalcolo. Se si supera il budget, semplificare le geometrie meno importanti prima di eliminare il fallback fuori rotta. Conservare headroom BLE e monitorare frammentazione/GATT.

5. **Fedeltà geometrica.** Applicare il resampling soltanto a un circuito effettivamente chiuso; mantenere esplicitamente il punto di chiusura, i nodi delle uscite e i cambi di curvatura più forti. Distribuire il budget residuo sul perimetro. Non usare soltanto resampling uniforme se cancella raccordi, uscite o anelli irregolari.

6. **Diagnostica strutturata.** Loggare numero di candidati, identificativo e motivo dell'anello selezionato, punteggio di confidenza, distanza GPS e distanza dal tratto imminente, numero e ID delle uscite, eventuali motivi di scarto dei candidati, ripartizione slot/punti fra anello/uscite/strade locali/percorso e pressione sul budget. Log sufficienti per distinguere errore di rilevamento, selezione, geometria o BLE senza saturare diagnostica.

7. **Test obbligatori:** rotonda costituita da più ways; loop stradale non-rotonda; anello irregolare, estremi leggermente distanti e budget ridotto; uscite senza nodi condivisi o con nodi incompleti; deviazione prima del ricalcolo con strade locali ancora visibili; scena urbana densa quasi al limite 20/160; strade a livelli differenti **senza filtrarle**; assenza di candidati (fallback); stabilità del numero di frame e payload BLE. Preferire test sintetici deterministici prima del giro reale e una sola CI consolidata.

**Vincoli espliciti:** **non attivare il filtro dei livelli** né nuovi controlli layer/bridge/tunnel in questa fase; rinviato a prova separata. Non cambiare Valhalla, protocollo BLE, firmware ESP32, zoom 3,4× né le frecce di navigazione (restano assenti). Non modificare il manuale operativo finché non cambiano comandi o procedure.

**Sequenza proposta:** (A) ricostruzione topologica e scoring con test; (B) selezione con riserve di budget e fallback GPS/off-route; (C) diagnostica strutturata; (D) singola CI Android, test su strada rotonde e deviazione con log e confronto rispetto a `09d3aa8`. **Verifiche al momento:** nessuna nuova modifica di codice o test eseguito per questa nota. **Prossima azione concreta:** implementare la fase A sul branch sperimentale e aggiungere fixture di rotonde single-way e multi-way prima di cambiare il selettore BLE.

## Prova rotonde complete e contesto fuori rotta — 2026-11-04

**Branch:** `test/road-contrast-cleanup-20261104`; partenza dal rollback `daa02d3`, `valhalla` non toccato. **Decisione utente:** dare priorità alla visualizzazione completa delle rotonde con le uscite e conservare le strade locali quando il motociclista prende una direzione diversa da quella calcolata. **Non abilitare il filtro livelli/attraversamenti** in questa prova: il precedente `RoadContextTopology` resta nel sorgente per un esperimento futuro ma non viene richiamato nella generazione `MapScene`.

**Nuova implementazione sperimentale:** `RoadContextJunctions` individua anelli OSM chiusi e compatti in prossimità del GPS o del tratto imminente; seleziona l'anello intero e le strade che condividono nodi OSM con esso. `RoadContextWindow.closedRing` conserva la circonferenza chiusa campionando vertici lungo il perimetro (massimo 16 punti per anello), invece di tagliarla attorno al GPS. Il resto della scena mantiene **almeno una priorità di 8 strade locali più vicine**, la riserva per il percorso imminente e il fallback generale: nessun filtro che nasconda le strade perché estranee alla rotta. Scene entro **16 polilinee / 128 vertici**, massimo firmware 24/192; un anello può usare fino a 16 vertici anziché gli ordinari 8. Nessun cambiamento a Valhalla, protocollo BLE, ESP32, PBF/MotoMap v3 o zoom. Il formato attuale non contiene `junction=roundabout`: l'identificazione riguarda **solo i circuiti chiusi riconoscibili nella geometria MotoMap**, non garantisce tutte le rotonde divise in più OSM ways. Prova su strada indispensabile.

**Test aggiunti:** ricerca degli anelli e delle uscite con nodi condivisi, assenza di ring/fallback, curvatura non chiusa, distanza dal GPS e resampling completo a 12 punti. **Non eseguiti:** Gradle/CI, firmware/hardware e verifica reale al momento della stesura; non dichiarare risultati verdi. **Rischi:** rotonde spezzate in più ways non riconosciute, uscita tagliata quando la relativa way è molto lunga, costo BLE per geometria estesa, altre strade con forma di anello che potrebbero essere selezionate. **Prossima azione:** una build Android consolidata, poi prova di rotonde e deviazione prima del ricalcolo con log diagnostico; nessun merge prima di valutazione. **Manuale:** procedure di compilazione/importazione/flash invariate, nessun aggiornamento operativo necessario.

## Regressione selezione contesto e rollback — 2026-11-04 (giro 17:33–17:54)

**Branch:** `test/road-contrast-cleanup-20261104`, NON integrato in `valhalla`. **Test su strada negativo** sul commit `4226b63`: l'utente segnala uscite delle rotonde tardive, assenza di contesto stradale quando devia dal percorso prima del ricalcolo, e strade su livelli differenti ancora visibili. Analizzato `motogps-android-diagnostics.zip` del 4 novembre: 181 eventi `BLE_MAP_SCENE`, **40 scene con 4 strade o meno**, pur essendo disponibili in molti punti centinaia di segmenti `OSM_CONTEXT`; 11 trigger e 11 successi `REROUTE`, compresi due `reverse=true` a soli 1 m di deviazione. Il nuovo `RoadContextSelection` restringeva troppo la rete alle strade aderenti al percorso, penalizzando rotonde e situazioni fuori rotta. Il filtro `RoadContextTopology` elimina soltanto alcuni crossing 2D non connessi: non è un filtro completo per livelli, e i log `OSM_GRADE_CROSSING` mostrano spesso pochi esclusi rispetto ai molti mantenuti. La reazione grafica al ricalcolo e la condizione `reverse=true` richiedono diagnosi dedicata.

**Decisione:** rollback integrale dell'esperimento `4226b63` (selettore `RoadContextSelection`, bonus di continuità, ancoraggio con hint al progresso), tornando alla precedente selezione **16 strade / 128 punti** del commit `985700e` più la compatibilità MotoMap v3 e filtro cavalcavia. Il rollback non dimostra che il comportamento originale sia perfetto: il precedente giro aveva già evidenziato uscite tardive e vie limitrofe eccessive. Non aumentare il payload o inasprire ulteriormente il filtro senza test reali. **CI, build e test hardware del rollback non verificati.** Prossima azione: verificare la build Android consolidata, poi studiare un selettore che riservi geometria completa all'anello e alle uscite e mantenga il contesto stradale locale anche fuori rotta; verificare separatamente `reverse=true` a bassa deviazione e livelli strada. Il manuale non necessita aggiornamenti operativi. Nessun merge su `valhalla`.

## Prova stradale MotoMap v3 e aumento conservativo contesto — 2026-11-04

**Branch:** `test/road-contrast-cleanup-20261104` (NON integrato in `valhalla`). Pacchetto Italia generato dall'utente: ZIP con 278.048 tile stradali z15, `motomap_v3.json`, `coverage_z14.txt` e 214.320 celle z14 certificate; usare `italy.poly` corrispondente al PBF. Nella prova pre-navigazione Cuneo → Reggio Calabria Android segnalava 667 tile mancanti. Corretto `OpenFreeMapRouteCorridor.missing()` (commit `35a5e02`) affinché accetti i metadati v3, non solo `version == 2`, e mantenga la compatibilità v2; l'utente conferma **zero tile mancanti** dopo l'aggiornamento.

**Prova reale:** l'utente riferisce che l'autostrada sotto il cavalcavia non è più mostrata come strada di contesto al crossing senza connessione; riscontro positivo sul filtro topologico MotoMap v3. Resta un'impressione soggettiva di comparsa irregolare delle strade grigie (non ancora attribuita a un bug preciso). Audit codice: `NavigationViewModel` sceglie e reinvia periodicamente le strade più vicine e quelle verso il percorso imminente; i limiti prima della prova erano **12 strade / 96 punti** con fino a 8 vertici per strada, inferiori alla capacità firmware 24/192. **Esperimento non ancora verificato su strada:** commit `985700e` porta a **16 strade / 128 punti**, selezione iniziale 8+8 vicine/future, mantenendo al massimo 8 vertici per strada; niente cambi a firmware ESP32, BLE, Valhalla, convertitore o filtro topologico. Non confondere questa configurazione sperimentale con quella già collaudata. Monitorare continuità visiva, rotonde, eventuali ritardi BLE e GATT; se persiste l'effetto di apparizione/scomparsa, valutare isteresi/stabilità fra selezioni anziché aumentare ancora il payload. Non effettuare il merge finché l'utente non approva la prova.

**Documentazione:** il manuale operativo descrive MotoMap v3, importazione e verifica metadati; le precedenti sezioni datate restano come cronologia. Nessun risultato CI attribuito al commit `985700e` senza verifica.

## CI finale MotoMap v3 — 2026-11-04

Run 41808834169 (commit `2824fa7`): `completed/success`; Android `Build Android app` verde, `testDebugUnitTest` e `testReleaseUnitTest` verdi, APK pubblicato. Run precedente 41808280423: native e test convertitore Python verdi; l'errore Android era soltanto un import JUnit del test dell'importatore, corretto. ESP32 saltato perché nessun file firmware modificato. **Hardware/topologia reale non collaudati**: rigenerare MotoMap v3, installare APK di test e verificare cavalcavia/svincoli/rotatorie prima del merge in `valhalla`.

## Correzione CI Android MotoMap v3 — 2026-11-04

La CI [41808280423](https://github.com/thriller47/moto-gps-waveshare/actions/runs/41808280423) del commit `71a71d6` ha eseguito con successo i test `native` e `Verify MotoMap v3 converter contract`; il job Android ha fallito **solo** alla compilazione dei test Kotlin: in `MotomapPackImporterTest.kt` mancava l'import di `org.junit.Assert.assertEquals`. Corretta l'importazione, lasciando invariato il codice runtime Android e Python. Il job ESP32 è stato saltato per assenza di modifiche firmware. **Prossima verifica:** esito CI Android successiva, con unit test topologici. Nessun collaudo fisico né import del pacchetto v3 ancora eseguiti.

## MotoMap v3 e filtro topologico delle strade — 2026-11-04

**Branch sperimentale:** `test/road-contrast-cleanup-20261104`, sviluppo successivo alla CI verde [41805366504](https://github.com/thriller47/moto-gps-waveshare/actions/runs/41805366504) per palette/edifici. L'utente rigenererà il pacchetto Italia, quindi è autorizzato il passaggio a un nuovo MotoMap v3: `scripts/offline_map/pbf_to_motomap.py` scrive `motomap_v3.json`, `layer`, OSM way ID e ogni OSM node ID originale in base36, conservando la struttura ZIP/tiles. Android `HybridMapRepository` carica il metadata per ogni strada, `RoadContextTopology` riconosce tratti effettivamente sovrapposti alla geometria Valhalla, usa l'uguaglianza degli ID dei nodi OSM come prova di connessione e scarta i crossing geometrici 2D senza connessione. **Fail-open:** senza metadati o match di percorso affidabile non sopprime strade. `NavigationViewModel` filtra la sola scena grigia **prima** della selezione BLE 12/96; percorso, comandi, speed limit, BLE e firmware non variano. `MotomapPackImporter` accetta metadati v3 e v2. Test Android introdotti per cavalcavia, sottopasso, incrocio reale, rotonda, vecchi dati e match incerto; script convertitore verificato con test Python sintetici senza libosmium e CI native aggiornata. **Richiesto all'utente:** nuova build Android e rigenerazione/importazione dell'archivio Italia v3; ESP32 attuale con nuova palette già sufficiente. **Verifiche:** solo statiche finora, CI del nuovo commit da verificare, nessun collaudo reale eseguito. **Rischi:** maggior dimensione MotoMap, matching ambiguo a rampe ravvicinate e assenza di cross quando le geometrie non coincidono in 2D. **Prossima azione:** verificare CI Android/native/ESP32, poi collaudare sullo stesso cavalcavia e su svincoli/rotatorie prima di integrare in `valhalla`. Dettagli: `docs/ROAD_CONTEXT_FILTER.md`.

## Correzione seconda CI nativa — 2026-11-04

**Branch:** `test/road-contrast-cleanup-20261104`. La CI [41803141056](https://github.com/thriller47/moto-gps-waveshare/actions/runs/41803141056) fallisce nella compilazione di `nav_presenter_tests.cpp`: `main()` invoca ancora `test_near_turn_zooms_all_map_layers_together_and_restores_scale()`, rinominata `test_near_turn_zooms_route_and_roads_together_and_restores_scale()` dopo l'eliminazione degli edifici. Corretta la sola invocazione; verificata staticamente la corrispondenza fra tutte e 15 le funzioni test e le 15 invocazioni nel file, e controllati anche i nomi dei test in `nav_ui_render_tests.cpp` e `phone_nav_bridge_tests.cpp`. La build precedente aveva già compilato `nav_ui_render_tests` e `moto_nav_presenter`; Android ed ESP32 sono stati **saltati**, non riprovati, nella seconda CI perché la modifica interessava soltanto test/documentazione. **Verifiche:** analisi log job nativo 126483611886; nuova CI sul fix da verificare; nessuna prova fisica ancora eseguita. **Prossima azione:** verificare il job `native` della CI finale e poi svolgere la prova di contrasto ESP32 diurna prima di qualsiasi merge.

## Correzione CI nativa palette/edifici — 2026-11-04

**Branch:** `test/road-contrast-cleanup-20261104`. CI iniziale [41801935997](https://github.com/thriller47/moto-gps-waveshare/actions/runs/41801935997): `esp32` e `android` conclusi con successo; `native` fallito **in compilazione** `tests/native/nav_ui_render_tests.cpp` perché le fixture facevano ancora riferimento ai campi `moto_ui_state_t::building_point_count`, `building_points`, `building_footprint_count` e `building_footprints`, rimossi intenzionalmente dal renderer. Rimossi solamente i setup, il movimento e l'azzeramento edificio dalle fixture native; restano invariati controlli di determinismo pixel, route/roads, interpolazione 40 Hz e geometria cancellata/ridisegnata. Nessun cambiamento a ESP32, Android, BLE, grafica o routing in questo fix. **Verifiche:** errore identificato nei log del job nativo 126479622114; CI sul commit correttivo da controllare, nessun risultato ancora dichiarato. **Prossima azione:** verificare una sola nuova CI finale, poi test reale della palette al sole prima del merge.

## Palette diurna e pulizia edifici — 2026-11-04

**Branch sperimentale:** `test/road-contrast-cleanup-20261104`, derivato da `valhalla` `b26357f`; `valhalla` resta invariato. Dopo l'archiviazione verificata del precedente esperimento LVGL sul tag `archive/esp32-lvgl-profiling-20261103`, rimosse le strutture edificio non utilizzate nella catena `NavSnapshot` -> `NavPresenter` -> `moto_ui_state_t` -> widget LVGL (incluse interpolazioni e buffer). Il bridge ignora i poligoni edificio eventualmente contenuti in `MapScene` e mantiene la validazione e la gestione delle sole strade; Android continua a trasmettere `buildings = emptyList()`. **Protocollo BLE/JNI deliberatamente invariato** per compatibilità: i vecchi dispositivi e le fixture possono ancora decodificare il campo, ma non si alloca/renderizza più la geometria nell'interfaccia. Aggiornati i test nativi mantenendo un caso che trasmette edifici legacy assieme alle strade. Palette RGB strade: principali `#E0E4E6`, residenziali `#BEC5C9`, altre/minori `#929CA2` (opacità piena e larghezze esistenti; verde percorso intatto). **Filtro cavalcavia/sottopassi:** solo audit e specifica `docs/ROAD_CONTEXT_FILTER.md`; formato MotoMap esistente non include tag `bridge`/`tunnel`/`layer` né ID nodi/topologia, e Valhalla route points da soli non distinguono un incrocio 2D da connessione reale: nessuna esclusione automatica rischiosa introdotta. **Verifiche:** revisione statica, CI nuova da verificare, nessun test hardware ancora eseguito. **Prossima azione:** CI native/ESP32/Android, poi confronto reale alla luce del sole senza regressioni rendering/BLE; solo dopo analizzare formato con metadati di connessione e un futuro nuovo export MotoMap, preservando il formato legacy come fallback.

## Merge alimentazione ESP32 e diagnostica BLE — 2026-11-04

**Branch integrazione:** `valhalla`, autorizzato dopo collaudo hardware delle funzioni batteria a ore 2 e autospegnimento automatico a 120 s, e giro BLE del 4 novembre con DEBUG attivo. Origine: `test/esp32-power-battery-20261103`, commit `1944dc4` e `d813565`. **CI del commit finale di test:** [41705482208](https://github.com/thriller47/moto-gps-waveshare/actions/runs/41705482208) `success`. **Riscontro utente del 4 novembre:** dopo chiusura/riapertura Android riprendono connessione BLE, navigazione, strade di contesto e distanze alle svolte; batteria e autospegnimento verificati sul dispositivo. **Diagnostica Android:** sessione 07:30:58–07:51:21, 13/13 ricalcoli Valhalla locali riusciti; disconnessione 07:42:39 e riconnessione 07:42:51. **Traccia ESP32 disponibile solo fino alle 07:42:38:** nessun timeout LVGL, un boot iniziale, sequenze esportate senza gap; diagnostica post-riconnessione non coperta dallo ZIP. I tre minuti con `turn_m=0` corrispondono a sosta confermata dall'utente, non classificati come bug. **Integrazione:** conserva spegnimento AXP2101 e fallback deep sleep, lettura SOC AXP2101, buffer PSRAM a perdita misurabile, diagnostica BLE best effort e test nativi dei flag di alimentazione; nessuna modifica a Valhalla routing. **Da verificare:** CI del commit di integrazione su `valhalla` e test specifici della diagnostica in congestione. **Prossima azione:** eliminare il branch remoto di test integrato, poi preparare in ramo sperimentale la palette strade più luminosa, la rimozione degli edifici inutilizzati e l'analisi del filtro cavalcavia/sottopassi, con test prima di un nuovo merge.

## Robustezza BLE e diagnostica PSRAM — 2026-11-03

**Branch:** `test/esp32-power-battery-20261103`, coordinato con le modifiche non ancora collaudate di autospegnimento (120 s) e indicatore batteria a ore 2. Nel trasporto NimBLE i `DiagnosticBatch` hanno mutex TX non bloccante (0 tick) e vengono rinviati quando un comando aspetta ACK: nessuna diagnostica può occupare il mutex per 500 ms in attesa. `PhoneNavBridge` conserva fino a 4 record selezionati se l'invio fallisce e li ritenta al ciclo da 1 s seguente; con LOG OFF o disconnessione li scarta per non mischiare sessioni. Nel ring PSRAM viene contato il numero cumulativo esatto di record sovrascritti (reset alla nuova cattura); il firmware lo segnala nel log seriale quando cambia. Nessun cambiamento al protocollo BLE, al routing, all'APK o al timer di autospegnimento. Aggiunto test nativo degli stati connected/arrived/reconnected utilizzati dal timer di alimentazione. **Prova hardware:** ancora da eseguire a ESP32 carico, in particolare ricongiungimento BLE prima dei 120 s, con/senza DEBUG, stato batteria e risveglio da PWR. **CI:** da verificare sul nuovo commit, non dichiarare verde preventivamente. **Prossima azione:** CI, poi test alimentazione e BLE con ESP32 carico; mantenere `valhalla` intatto fino a conferma.

## Integrazione avvio navigazione immediato — 2026-11-03

**Branch di destinazione:** `valhalla`, merge autorizzato dopo prova positiva dell'utente dell'avvio navigazione dall'app. Origine: `test/android-auto-start-cached-20261103` (`1337589c9`); CI originale: [41691472701](https://github.com/thriller47/moto-gps-waveshare/actions/runs/41691472701) completata con successo. Il bottone Avvia navigazione in `NavigationScreen.kt` evita il dialogo soltanto con zero tile mancanti; lo conserva per tile assenti o avvisi di copertura/geometria. **ESP32, BLE e Valhalla invariati.** Lo spegnimento automatico e l'indicatore batteria rimangono sperimentali in `test/esp32-power-battery-20261103`, in attesa di test su hardware dopo la ricarica; non sono inclusi nel merge. **Verifiche:** prova reale app riportata positiva; CI del branch di test verde; CI del merge da verificare. **Prossima azione:** verificare la CI di `valhalla`, poi rimuovere branch Android integrato e proseguire con il test alimentazione ESP32 quando pronto.


## Spegnimento automatico e batteria ESP32 — 2026-11-03

**Branch sperimentale:** `test/esp32-power-battery-20261103` (basato su `valhalla`). Modificato esclusivamente firmware ESP32 e UI condivisa: spegnimento automatico tramite AXP2101 dopo 120 secondi dall'arrivo effettivo o dalla disconnessione del telefono, armato solo dopo una prima connessione; riconnessione o ripresa navigazione annullano il timer. Riutilizzato spegnimento manuale e fallback deep sleep risvegliabile con PWR GPIO3. Lettura livello batteria ESP32 da AXP2101 SOC 0xA4 e stato carica tramite I2C ogni 30 s; percentuale visibile **a ore 2** nell'interfaccia LVGL, non a ore 5 perché occupato dall'indicatore velocità. Dati invalidi vengono nascosti; nessuna modifica BLE/Android. **Da verificare:** CI, accuratezza indicatore su batteria reale, comportamento con USB, riaccensione da tasto PWR, eventuali interferenze UI. Non effettuato test fisico: non integrare in `valhalla` prima della verifica. **Prossima azione:** verificare la build CI e poi flashare solo ESP32 per test statico e connessione/spegnimento.

## Merge ottimizzazione rendering ESP32 — 2026-11-03

**Branch di integrazione:** `valhalla`; merge fast-forward autorizzato dall'utente del branch `test/esp32-render-performance-20261103`, preservando tre commit di test e documentazione e il precedente esito della prova. Cambiamenti: `platforms/esp32/main/phone_nav_bridge.cpp` evita la ricopia dell'intero `NavSnapshot` ai soli tick IMU, conservando heading e origine GPS misurata; `tests/native/phone_nav_bridge_tests.cpp` aggiunge copertura per manovra e distanza mantenute durante 40 frame di movimento. **Convalida:** CI branch di test run 41643405815 `success` (job nativi/ESP32/Android), breve giro riferito dall'utente con miglior fluidità percepita, navigazione regolare. **Log pertinenti SOLO 15:07–15:13:43 del 3 novembre:** 366 snapshot Android, sei ricalcoli Valhalla su sei riusciti, 1.377 eventi ESP32, zero timeout di lock LVGL, zero errori BLE Android, nessun panic/watchdog esplicito. Le sessioni precedenti nello stesso ZIP sono escluse. **Da verificare:** CI del commit d'integrazione su `valhalla`; assente misura comparativa FPS, test di durata rinviato. **Prossima azione:** controllare CI, eliminare il branch sperimentale ormai integrato, poi studiare l'effettiva contesa del lock LVGL senza introdurre variazioni premature.

## Breve prova ESP32 rendering positiva — 2026-11-03

**Branch:** `test/esp32-render-performance-20261103`. L'utente conferma che il giro con l'ottimizzazione rende la mappa *percepita più fluida*, con il resto della navigazione regolare. **Rettifica intervallo log:** lo ZIP `motogps-android-diagnostics(20261103-143643).zip` contiene sessioni differenti: per valutare questa prova considerare **solo dal 2026-11-03 ore 15:07 Europe/Rome**, escludendo completamente le sessioni precedenti. Nell'intervallo dalle 15:07 alle 15:13:43: **366 NAV_SNAPSHOT Android**, **6 REROUTE trigger e 6 success `valhalla-local`**, **1.377 eventi ESP32**, **0 eventi `RenderLockTimeout` (codice 7)**, **0 BLE_ERROR Android**, nessun panic/watchdog esplicito. Nel log ESP32 un evento Boot (codice 1) circa alle **15:07:55**, all'inizio del nuovo segmento diagnostico, senza altri boot registrati nell'intervallo; non attribuirlo a un crash. La precedente lettura (1.029 snapshot, 9 ricalcoli, un errore GATT) mescolava anche la vecchia sessione e **non descrive il test di questa modifica**. CI run 41643405815: `completed/success`. **Limite:** non disponibili dati FPS comparativi; durata breve e hardware non stressato a lungo. **Prossima azione:** chiedere autorizzazione merge in `valhalla` dopo questo test positivo, poi solo eventualmente profilare LVGL.


## Rendering ESP32: copia selettiva frame IMU — 2026-11-03

**Branch:** `test/esp32-render-performance-20261103` (derivato da `valhalla`; `valhalla` invariato). La CI del precedente commit `faf2d17` è risultata verde, incluso il job Android. Profilo statico del renderer: `PhoneNavBridge::render_pending` copiava l'intero `NavSnapshot` su ogni evento `RenderMotion` fino a 40 Hz, benché `on_imu_sample` aggiorni soltanto `snapshot_.heading_deg`. Ora il frame di navigazione usa la copia completa, mentre il frame IMU copia solo heading e origine geometrica *misurata* (per non reimmettere nel predittore la posizione già estrapolata); dati strada/edifici e manovre rimangono nello snapshot render precedente. Mantiene `state_mutex_`, coalescing render, lock LVGL e BLE invariati. Esteso test nativo `phone_nav_bridge_tests` con verifica che 40 frame IMU conservino manovra e distanza. **Verifica CI/nativa non ancora eseguita**, latenza non misurata in hardware: la riduzione delle copie è certa dal codice, il beneficio in FPS deve essere misurato. **Prossima azione:** validare CI, poi flash di test ESP32 e confrontare fluidità/log su percorso breve prima di autorizzare merge.

## Correzione CI Android: installazione NDK — 2026-11-03

**Branch:** `valhalla`; intervento limitato a `.github/workflows/project-build.yml`. CI merge `fa40d64`: job native e ESP32 **success**, job Android **failure in configurazione Gradle**, prima della compilazione Kotlin. Causa letta dal log job 125916354075: Android SDK tenta installazione `ndk;27.0.12077973`, archivio ZIP scaricato non valido (`ZipFile unknown archive`), `InstallFailedException`; non è un errore applicativo dimostrato. Aggiunto step NDK esplicito con fino a tre tentativi, eliminazione della directory di installazione incompleta e verifica `source.properties`. Il filtro changes ora considera il workflow come dipendenza del job Android per verificare la correzione con una sola nuova CI. **Nessuna modifica** al codice navigazione, Android, BLE o ESP32. **Da verificare:** esito del job Android e build APK sul nuovo commit; un ulteriore errore a valle richiederà diagnosi specifica. **Prossima azione:** controllare l'Actions run generato dal commit unico.

## Integrazione validata fasi 2–3 — 2026-11-03

**Branch:** `valhalla`. Su autorizzazione dell'utente, integrazione del branch `test/turn-heading-render-20261102` dopo prova positiva del 3 novembre: distanze visibili prima di tutte le svolte e rotatorie testate, inclusi passaggi ravvicinati; diagnostica Android: 663 NAV_SNAPSHOT, 8 roundabout con distanza positiva, 3 ricalcoli Valhalla locali riusciti (46–55 ms), nessun crash riconosciuto nei log. Le CI del branch sperimentale, inclusa `54a4e1f`, risultavano verdi. Conservate regole AGENTS.md, correzione LF in .gitattributes e cronologia dei documenti presenti in `valhalla`. Dal branch di test arrivano inoltre la pulizia del backend legacy, il limite prudente del MapOriginPredictor, test heading/svolte e le correzioni delle rotatorie. **Non validato:** test lungo ESP32/BLE rinviato per meteo; la CI del merge su `valhalla` è da verificare. **Prossima azione:** confermare CI del merge, poi misurare costo copie snapshot e contesa lock LVGL su branch di test prima di modificare rendering; eliminare branch sperimentale fuso dopo verifica (il connettore corrente non espone cancellazione ref).

## Terminatori di riga Bash — 2026-11-02

**Branch:** `valhalla`. Aggiunto `*.sh text eol=lf` a `.gitattributes` per impedire conversioni CRLF degli script Bash durante checkout su Windows/WSL. Preservate le regole binarie e le eccezioni esistenti. Modifica solo di configurazione Git; nessuna build hardware effettuata. Per file già estratti con CRLF occorre normalizzare o rieseguire il checkout. **Prossima azione:** verificare con `git check-attr text eol -- scripts/offline_map/build_valhalla_graph.sh`.

## Regola ciclo di vita dei branch — 2026-11-02

**Branch:** `valhalla`. Aggiornato `AGENTS.md`: conservare soltanto `valhalla` e normalmente un singolo branch sperimentale `test/*`; cancellare i branch di test dopo merge riuscito e verificato, previo controllo commit esclusivi e possibile archiviazione via tag. Non ricreare `main`; `signal` è obsoleto, da eliminare dopo le verifiche. Branch attivo attuale: `test/turn-heading-render-20261102`. Questa modifica stabilisce una regola e **non cancella** branch; `test/navigation-hardening-20261101` presenta commit divergenti che devono essere tutelati prima della cancellazione. **Test:** modifica soltanto documentale, nessun test hardware o Android richiesto. **Prossima azione:** archiviare eventuali commit esclusivi e ripulire i branch remoti superflui.

## Test su strada rotatorie riuscito — 2026-11-03

**Branch:** `test/turn-heading-render-20261102`. Dopo correzione decoder ingressi 26/uscite 27, l'utente conferma che **tutte le svolte e rotatorie percorse hanno mostrato la distanza**. Verificato ZIP `motogps-android-diagnostics.zip` del 3 novembre: 663 `NAV_SNAPSHOT`; 8 manovre `roundabout` distinte per route/ID, tutte con campioni di distanza positiva prima di 0 m; 3 ricalcoli `valhalla-local` riusciti (circa 46–55 ms); nessuna stringa ERROR/Exception in Android né panic/Guru Meditation/watchdog nei log ESP32. **Conclusione:** regressione delle rotatorie consecutive risolta nella prova fornita, senza modifica al firmware. **Prossima azione:** valutare merge in `valhalla` solo su autorizzazione esplicita; poi proseguire con profiling rendering ESP32 e test lungo quando fattibile.

## Correzione rotatorie consecutive e manovre d'uscita — 2026-11-03

**Branch:** `test/turn-heading-render-20261102`; `valhalla` invariato. Analizzato il nuovo ZIP del 3 novembre (12:00–12:10): 674 snapshot, coppie di ID `roundabout` a cui seguono ID a distanza sempre 0 m (p.es. 2→3, 8→9, 10→11, 12→13). Il decoder `ValhallaRouteAdapter.maneuverType` etichettava erroneamente **sia** `kRoundaboutEnter=26` **sia** `kRoundaboutExit=27` come una nuova rotatoria, mentre il firmware ESP32 nasconde la distanza a 0 m. Corretto l'adapter: soltanto tipo 26 è `roundabout`, tipo 27 è `continue` (non nuova istruzione); preservato l'indice finale di geometria dell'ingresso per mantenere il suo hold fino all'uscita. Aggiunto test Android con due rotatorie ravvicinate e coppie ingresso/uscita 26/27, che verifica il passaggio al successivo ingresso con distanza positiva. Nessuna modifica a ESP32/BLE, MotoMap, Valhalla o zoom. **Da verificare:** CI Android e prova in strada; non ancora confermato se le rotatorie sulla stessa strada senza manovra Valhalla vengano indicate. **Prossima azione:** CI e test delle medesime rotatorie per confermare distanze prima di ciascuna, mantenendo `valhalla` stabile.

## Correzione rotatorie bloccate a zero — 2026-11-03

**Branch:** `test/turn-heading-render-20261102`; `valhalla` invariato. Dal log mattutino riferiti 831/988 snapshot roundabout con 0m e ID persistente a lungo, pur con ricalcoli e UI fluidi. In `ValhallaTurnGuidance.Plan.next()` limita la permanenza della manovra roundabout a 250 m dopo l'ingresso, oltre ai consueti 15 m di isteresi: un indice `roundabout_end_point_index` anomalo non può più mantenere la manovra a zero per chilometri. Il conteggio chilometrico e il routing restano immutati; test di regressione su indice uscita errato e test esistente su uscita normale. **Limite noto:** rotatorie eccezionalmente lunghe oltre 250 m potrebbero essere rilasciate prima dell'uscita; hardware/on-road non ancora validato. **Prossima azione:** verificare i test Android CI e la corretta successione manovre in prova su strada; non fare merge finché non autorizzato.

## Manuale operativo e build Ryzen — 2026-11-02

**Branch:** `test/turn-heading-render-20261102`. Aggiornato `docs/MANUALE_OPERATIVO.md`: rimosse sezioni Docker legacy, radio non più operativa, diagnostica duplicata e testo accidentalmente inserito in blocco di codice. Aggiunta procedura WSL2 Ubuntu 24.04 su Ryzen 5 3600, 12GB RAM WSL e 8GB swap, PBF su ext4 Linux, `pyvalhalla==3.9.1`, due thread, validazione e confronto Routing.earth (1.591 tile, 11.457.028 nodi, 26.093.538 archi, zero tile non verificabili). Lo script `scripts/offline_map/build_valhalla_graph.sh` usa ora `--mjolnir-concurrency 2` come default, sovrascrivibile con `--threads N`; compatibile con uso precedente `[--zstd]`. **Test:** revisione statica; la build di successo è quella segnalata dall'utente (con due thread), ma questa nuova interfaccia CLI non è ancora stata provata su Ryzen. **Prossima azione:** verificare sintassi Bash/CI e una nuova build soltanto al successivo aggiornamento del PBF; nessuna modifica al branch stabile.

## Analizzatore grafi Valhalla — 2026-11-02

**Branch:** `test/turn-heading-render-20261102`. Esteso `scripts/offline_map/analyze_valhalla_graph.py` per accettare identificativi di versione 3.x con suffissi commit (es. `3.9.0-685e3d8`, Routing.earth), e per indicare separatamente le cause degli header non riconosciuti con esempi, invece di attribuirli silenziosamente alla versione. Dopo il confronto degli archivi: ridotto il limite minimo di lunghezza delle tile da 512 a 48 byte, pari all'intestazione letta; 618 tile proprie e 287 di Routing.earth erano state escluse solo perché inferiori a 512 byte. Restano i controlli sulla versione e sul campo letto. Test end-to-end sui due archivi ancora da ripetere. Nessuna modifica alla generazione del grafo o all'Android/ESP32. **Test:** verifica statica del codice; confronto reale da ripetere con entrambi i TAR.ZST. **Prossima azione:** eseguire lo script aggiornato e ispezionare le cause di scarto, poi valutare conteggi confrontabili.

## Rimozione backend gateway legacy — 2026-11-02

**Branch:** `test/turn-heading-render-20261102`; `valhalla` invariato. Rimossa la directory `backend/` e le guide `docs/GATEWAY_SETUP*.md`. Copiata la conversione WGS84/GCJ-02 utilizzata dalle fixture in `scripts/fixture_coordinates.mjs` e spostato il relativo controllo nel job native CI. Eliminato il job backend e aggiornati i trigger dei branch di test. `GatewayRoadTiles.kt` Android è mantenuto perché il calcolo della tile è usato da `HybridMapRepository` per individuare file locali: non significa che il vecchio gateway server sia ancora necessario. Nessuna variazione al protocollo BLE, al routing Valhalla o all'ESP32. **Test:** nuova CI avviata dal commit finale; esito da controllare. **Prossima azione:** controllare CI e riferimenti residui prima del merge approvato.

## Terminatori di riga Bash — 2026-11-02

**Branch:** `test/turn-heading-render-20261102`. Aggiunto `*.sh text eol=lf` a `.gitattributes` per impedire conversioni CRLF degli script Bash durante checkout su Windows/WSL. Preservate le regole binarie e le eccezioni esistenti. Modifica solo di configurazione Git; nessuna build hardware effettuata. Per file già estratti con CRLF occorre normalizzare o rieseguire il checkout. **Prossima azione:** verificare con `git check-attr text eol -- scripts/offline_map/build_valhalla_graph.sh`.

## Fasi 2 e 3 — test mirati heading, svolte e predizione mappa — 2026-11-02

**Branch:** `test/turn-heading-render-20261102`, derivato da `valhalla` dopo merge offline. Su richiesta dell'utente, il test prolungato ESP32/BLE è rinviato a un weekend utile per condizioni climatiche; priorità a AND-02/05 e ESP-03/04. Prima tranche conservativa: limite massimo di estrapolazione senza nuovo anchor geometria ridotto da 4200 a 1500 ms in `map_origin_predictor.hpp`; test nativo per assenza aggiornamenti e test Android per contatore svolta immobile e heading a bassa velocità. Nessuna variazione a Valhalla, MotoMap, protocollo BLE, ESP32 UI, frecce, zoom 3.4 o distanza arrivo. **ESP-04** (copia selettiva di snapshot) non modificato: richiede profiling del lock e del costo delle copie prima di ottimizzare, per evitare race nei vettori. **Test e CI:** da verificare sul commit conclusivo; prova su strada e in hardware rimandata. **Prossima azione:** verificare CI, poi esercitare heading stop/partenza, rotonde, vibrazioni e BLE intermittente in test protetti prima di merge.

## Merge e prova su strada offline — 2026-11-02

**Branch di destinazione:** `valhalla`; integrazione di `test/openfreemap-mvt` autorizzata esplicitamente dopo test motociclistico positivo. L'utente ha percorso strade secondarie/comunali con dati mobili disattivati: ricalcoli Valhalla giudicati coerenti, strade grigie visibili, interfaccia fluida e puntuale. Analizzato `motogps-android-diagnostics.zip`: 20 eventi REROUTE (trigger e success), provider `valhalla-local`, 3 eventi OFM_COVERAGE, inclusi corridoi con 31/31 e 33/33 tile MotoMap certificate senza tile mancanti; 1.746 NAV_SNAPSHOT con fix_age_ms mediano 807 ms e predicted_m mediano 8 m, max 12 m. Nessun evento ERROR/FATAL/Exception nel log Android; nessuna firma Guru Meditation/panic/watchdog nel log ESP32. Il log non certifica assenza assoluta di problemi hardware. Ultimi commit del branch di test con CI verde, compreso `0afa371`. La documentazione di revisione architetturale rinviata dal branch valhalla è preservata. **Prossima azione:** controllare CI del commit di merge su valhalla e poi mantenere le proposte architetturali in backlog, senza implementarle automaticamente.

## Revisione architetturale rinviata — 2026-11-01

Le proposte ESP-01..ESP-05 e AND-01..AND-05, con priorità e piano di verifica, sono conservate in [docs/REVISIONE_ARCHITETTURALE_2026-11-01.md](docs/REVISIONE_ARCHITETTURALE_2026-11-01.md). Decisione: rimandarne l'implementazione. Nessun cambiamento firmware/Android, mantenere le proposte architetturali rinviate e non implementarle durante questo merge. Alla ripresa usare un branch di test e verificare i test hardware prima del merge.


## Analizzatore grafi Valhalla — 2026-11-02

**Branch:** `test/openfreemap-mvt`, `valhalla` invariato. Aggiunto `scripts/offline_map/analyze_valhalla_graph.py` per confrontare TAR/TAR.ZST autoprodotti e routing.earth senza estrazione: tile per livello, nodi e archi diretti dal formato GraphTileHeader v3, versioni tile, conteggi non verificabili e report JSON. Il confronto A-B dei directed edges si attiva solo se tutti gli header sono riconosciuti. Aggiornato `docs/MANUALE_OPERATIVO.md` con comandi WSL2 e limiti interpretativi. Motivazione: grafo Italia dell'utente 880 MB .zst contro routing.earth 1.42 GB .zst, generato con soli 8 GB RAM; dimensione compressa non dimostra copertura equivalente.

**Test eseguiti localmente:** compilazione sintattica Python e TAR sintetico con un header v3: 17 nodi, 31 archi diretti, 1 tile riconosciuti. **Non eseguiti:** confronto su TAR Italia reale, CI, verifica Android. **Prossima azione:** utente confronta i due archivi con lo script e fornisce report JSON; controllare differenze per livello, poi test routing locale.

## Ottimizzazione cadenza snapshot e diagnostica GPS — 2026-11-02

**Branch:** `test/openfreemap-mvt`. Separato il timer diagnostico `GPS_LATENCY` da `GPS_FIX` (il precedente condivideva erroneamente `lastDiagnosticFixMs`, sopprimendo quasi tutti gli eventi). Registrati anche `received_ago_ms` e `fix_age_ms` negli snapshot per separare età GNSS e coda di ricezione Android. Ridotto soltanto l'intervallo BLE snapshot su rettilineo da 300 a 250 ms; svolte restano a 200 ms. Predizione limitata a 12 m, GPS autorevole per ricalcoli, scene stradali e navigazione invariati. **Verifica hardware e CI ancora da completare**; dopo compilazione verificare GPS_LATENCY più regolare e confrontare fix_age_ms e received_ago_ms in una prova su strada. Nessun cambiamento a ESP32, BLE codec, MotoMap o Valhalla.

## Riduzione ritardo di posizione display — 2026-11-02

**Branch:** `test/openfreemap-mvt`; `valhalla` invariato. Dopo test MotoMap v2 + Valhalla offline riuscito (3 ricalcoli, 30/30 tile coperte, zero download OpenFreeMap), utente riferisce ritardo di 20–30 m rispetto alla moto. Nota: l'evento GPS_FIX è limitato a circa un log/s e NON misura la cadenza reale del GNSS. Aggiunto `GPS_LATENCY` con età monotona del fix e `fix_age_ms` + `predicted_m` agli snapshot diagnostici; `NavigationPositionPrediction` applica una stima solo alla posizione usata per la visualizzazione della navigazione (max 700 ms e 12 m, velocità 3–35 m/s, precisione <=25 m, fix non più vecchio di 1500 ms, bearing disponibile). La posizione reale resta autoritativa per ricezione fix, ricalcoli e scene stradali. Nessuna modifica a BLE, ESP32, Valhalla, MotoMap o OpenFreeMap. Aggiunti test unitari per fermo, vecchio fix, precisione, direzione e limite. **Test/CI non ancora verificati al momento del commit**; prossima azione: una CI e prova su strada comparativa con GPS_LATENCY e NAV_SNAPSHOT, verificare se il ritardo percepito cala e non compaiono anticipi nelle svolte.

## Correzione importazione MotoMap v2 — 2026-11-02

**Branch:** `test/openfreemap-mvt`. Su Android aggiornato, importazione di un MotoMap v2 Italia falliva con `Dimensione tile non valida: coverage_z14.txt`. Il limite dedicato al file di indice viene aumentato da 2 MiB a 16 MiB sia nella validazione ZIP sia nella lettura del corridoio. L’errore indica ora dimensione reale e limite, e `NavigationViewModel` registra `OSM_IMPORT_ERROR` nel log persistente esportabile. Nessuna modifica al contenuto del convertitore, BLE, ESP32 o routing. Il file v2 già creato può essere riutilizzato. **Non eseguiti** test su archivio Italia reale e dispositivo; verificare una sola CI del commit finale e riprovare importazione sul telefono. La causa dimensionale è probabile ma la dimensione effettiva dell’entry nel file utente non è stata misurata.

## Strumenti di compilazione mappe e manuale — 2026-11-02

**Branch:** `test/openfreemap-mvt`; `valhalla` non modificato. Aggiunto `scripts/offline_map/build_valhalla_graph.sh`, procedura Bash per Valhalla CLI su Linux/WSL2 (config, timezone/admin se disponibili, build tiles, extract TAR, verifica `.gph`, compressione Zstandard opzionale); non dipende da routing.earth. Aggiornato `docs/MANUALE_OPERATIVO.md` con URL ufficiale `https://download.geofabrik.de/europe/italy.poly`, conversione MotoMap v2 e comandi Windows/WSL2 per produrre grafo Valhalla da PBF Geofabrik.

**Test:** controllati sorgenti e documentazione ufficiale; conversione Italia, strumenti Valhalla locali, importazione Android e test sul dispositivo non eseguiti. Rischi: RAM/disco per grafi nazionali, disponibilità tool Valhalla in Linux/WSL, compatibilità binaria con JNI Android. **Prossimo passo:** verificare workflow del singolo commit, poi eseguire prima un build regionale e validarne il routing offline sul telefono. Nessun cambio a BLE/ESP32 o logica di navigazione.

## MotoMap v2 — convertitore e lettore Android — 2026-11-02

**Branch:** `test/openfreemap-mvt`, `valhalla` invariato. Aggiunti `motomap_v2.json` e `coverage_z14.txt` al convertitore Python. L'indice di copertura viene generato **solo** con `--coverage-poly italy.poly` (richiede `shapely`) e contiene esclusivamente tile z14 interamente coperte dal poligono dichiarato dell'estratto; tile marginali o estratti senza poligono non sono certificati e usano il fallback conservativo v1. Android importa e legge entrambi i formati, senza estrarre gli ZIP e senza modificare le strade grigie.

**Comando:** `python -m pip install osmium shapely` e `python scripts/offline_map/pbf_to_motomap.py italy.osm.pbf -o italy-v2.motomap.zip --coverage-poly italy.poly`. Il file `.poly` deve descrivere **esattamente** l'estratto PBF usato; se non disponibile, omettere l'opzione: formato v2 ma senza certificazione di aree vuote. La verifica non avvalora i pacchetti vecchi, che restano supportati. **Test aggiunti:** whitelist metadata nell'importatore; **CI, conversione effettiva su PBF e prova dispositivo non ancora eseguite**. Prossima azione: verificare la singola CI Android e convertire un piccolo estratto PBF con il proprio `.poly` prima di rigenerare Italia. Necessaria cautela se il poligono non corrisponde precisamente al ritaglio.

## Correzione esportazione diagnostica copertura — 2026-11-02

**Branch:** `test/openfreemap-mvt`. Il log Android del 2 novembre (09:24–09:26) conferma 2 tile scaricate e strade utilizzabili, ma non includeva `OFM_COVERAGE` perché tali eventi erano scritti solo in Logcat Android (`Log.i`), non nel registro persistente ZIP. Corretto `OpenFreeMapRouteCorridor.kt` affinché il riepilogo e le tile mancanti siano registrati tramite `DiagnosticLog.event()`, esportabili in `current.log`. Nessun cambiamento all'algoritmo di copertura né al download. **Build/test non ancora verificati dopo questa correzione**; prova successiva: creare un percorso, esportare ZIP e cercare `OFM_COVERAGE` e `OFM_COVERAGE_MISSING`. `valhalla` invariato.

## Diagnostica copertura MotoMap e rimozione fallback Overpass — 2026-11-02

**Branch:** `test/openfreemap-mvt`. Il controllo di copertura `OpenFreeMapRouteCorridor.missing()` registra ora `OFM_COVERAGE` con tile pianificate, già in cache OpenFreeMap, coperte da MotoMap e ancora mancanti, e `OFM_COVERAGE_MISSING` per ogni tile z14 da scaricare con coordinate z/x/y e l'elenco delle quattro tile z15 MotoMap assenti/presenti. Scopo: diagnosticare le due tile scaricate dall'utente nell'entroterra piemontese, non vicino al confine geografico Italia; una tile ZIP assente può rappresentare una zona senza segmenti OSM compatibili e non necessariamente un errore di copertura.

Disattivato il recupero automatico delle strade via **Overpass** da `NavigationViewModel` e rimosso `HybridMapRepository.ensureOnlineTile()`; restano lettura offline MotoMap, tile OpenFreeMap richieste esplicitamente dal corridoio, log di contesto e il routing Valhalla esistente. La cache storica OSM non è cancellata. Nessuna modifica al firmware ESP32, alla schermata di guida o a `valhalla`. **Test e CI non eseguiti durante la stesura; compilazione e prova manuale ancora necessarie**. Prossima azione: una sola CI sul commit finale, poi ripetere la rotta e inviare il nuovo ZIP Android con `OFM_COVERAGE_MISSING` per verificare i nomi delle tile z15 effettivamente assenti.

## Pulizia UI mappe — 2026-11-02

**Branch:** `test/openfreemap-mvt`. Rimosso dalla finestra «Gestione mappe» il comando sperimentale «Test OpenFreeMap — 1 tile MVT» e il testo relativo; eliminati callback e metodo ViewModel dedicati alla prova manuale. Il decoder, il download del corridoio e il componente `OpenFreeMapTileProbe.downloadTile` utilizzato dal corridoio rimangono attivi. Nessun cambiamento a firmware ESP32, protocollo BLE, dati importati o branch `valhalla`.

**Verifiche:** ispezione sorgente; build/test automatici e prova hardware non eseguiti in questa sessione. **Prossima azione:** verificare CI Android del ramo e controllare che la finestra Gestione mappe mostri ancora import MotoMap/Valhalla e stato download senza il vecchio pulsante.

## Ottimizzazione download corridoio con MotoMap importata — 2026-11-02

**Branch:** `test/openfreemap-mvt`; nessun merge in `valhalla`. Nel file `OpenFreeMapRouteCorridor.kt` il calcolo delle tile mancanti controlla ora sia i file OpenFreeMap già salvati sia la copertura dei pacchetti compressi `.motomap.zip` importati. Ogni tile OpenFreeMap z14 viene esclusa dal download soltanto quando sono presenti **tutte le quattro tile MotoMap figlie z15**, anche distribuite fra più archivi; copertura parziale, ZIP illeggibile o tile assenti mantengono il fallback OpenFreeMap con conferma esplicita. Gli archivi vengono consultati tramite indice ZIP (senza estrarre le tile) e soltanto per il percorso pianificato. I grafi di routing Valhalla non vengono scambiati per mappe stradali MotoMap.

**Test aggiunti:** `OpenFreeMapRouteCorridorTest.kt` per copertura completa, una tile figlia assente e copertura estranea. **Non eseguiti in questa sessione:** Gradle/Android CI e test hardware con pacchetto MotoMap Italia installato; non dichiarare riuscito finché verificato. **Rischio residuo:** la presenza della tile ZIP non garantisce una strada effettivamente utilizzabile all'interno; la regola è volutamente conservativa sulla copertura geometrica, ma non esamina il contenuto di ogni tile. **Prossimo passo:** verificare CI del branch, importare MotoMap ZIP e controllare che lungo un percorso interamente coperto non sia richiesto download; testare anche una rotta sul bordo della copertura, dove siano proposte soltanto le tile mancanti. Nessuna modifica a ESP32, BLE, zoom o routing.

## Test su strada positivo e diagnostica Android — 2026-11-02

**Branch:** `test/openfreemap-mvt`. **Fonte:** resoconto diretto dell'utente e ZIP `motogps-android-diagnostics(4).zip` (solo `current.log`, nessun log ESP32). **Nessun cambiamento al codice.**

- **Prova del 2 novembre 07:30:34–07:52:49 (Europe/Rome):** l'utente riferisce navigazione fluida senza salti o blocchi, ricalcoli corretti e strade grigie presenti lungo tutto il corridoio, inclusi i ricalcoli.
- Dal log: 189 `OSM_CONTEXT` nella sessione mattutina, **189/189 con `usable>0`** (6–497); 189 `BLE_MAP_SCENE` con `roads>0`; 1.477 `NAV_SNAPSHOT`, compresi 11 con `maneuver=arrive`; link `Connesso` alle 07:30:36 e nessun successivo `Non connesso` nel registro della sessione.
- **12 trigger `REROUTE` e 12 `success`**, tutti `provider=valhalla-online`: confermano il funzionamento del fallback online, **non** il routing offline. Alcuni ricalcoli ravvicinati tra le 07:43 e le 07:44: monitorare, senza ritoccare le soglie in assenza di difetto percepito.
- La sera del 1 novembre il log registra `OFM_CORRIDOR start missing=31` e `complete tiles=31`. Overpass aveva riportato timeout e HTTP 502; nella prova mattutina non si registrano nuove richieste Overpass e il contesto locale è risultato disponibile.
- **Limiti:** impossibile certificare frame-rate, mutex LVGL/TE o assenza di perdite di pacchetti BLE lato ESP32 in quanto LOG DEBUG non era attivo. Il resoconto visivo è positivo; i limiti di stringhe UTF-8, gli errori di avvio GATT e il watchdog su callback mancanti non sono esercitati specificamente da un test strumentato.
- **Verifiche:** build riuscita comunicata dall'utente; log Android effettivamente analizzato, **nessuna build/flash o test automatico eseguito in questa sessione**.
- **Decisione:** mantenere per ora la build sperimentale come baseline di prova stabile, nessun merge su `valhalla` senza approvazione. **Prossimo passo:** scegliere se validare anche Valhalla *offline* su una tratta con grafi importati, eventualmente raccogliere un test DEBUG PSRAM→BLE 60 minuti, quindi valutare merge con verifica CI e rispetto condizioni d'uso delle tile.

## Esito verifiche preliminari e prova su strada pianificata — 2026-11-01

**Branch:** `test/openfreemap-mvt` (nessun merge verso `valhalla`). Dopo l'integrazione selettiva delle correzioni Android B-0/B-1/A-1, l'utente ha confermato: **build riuscita**, **download del corridoio OpenFreeMap funzionante**, **ESP32 connesso via BLE**, e **strade grigie visibili sull'ESP32 all'inizio della navigazione**. Queste sono osservazioni riportate dall'utente; non dimostrano ancora la persistenza del contesto stradale lungo il viaggio.

**Non ancora verificati:** continuità delle strade grigie ai cambi di tile e lungo il corridoio, fluidità della linea verde, zoom 3,4×/distanza alla svolta, ricalcolo in movimento, stabilità BLE/display nelle percorrenze prolungate, limiti UTF-8 e recupero errori GATT su hardware. Il watchdog per callback GATT mancanti resta nel backlog. Nessuna ulteriore modifica al codice in questa sessione; aggiornato solo questo documento.

**Prossima azione (2026-11-02, prova utente in viaggio):** effettuare il test su strada usando la build attuale senza altri cambiamenti, osservando soprattutto la continuità delle strade grigie mentre la posizione avanza; controllare linea verde, ricalcoli, zoom e connessione BLE. Attivare eventualmente diagnostica PSRAM→BLE DEBUG e, a fine prova, esportare ZIP Android/ESP32. Analizzare i log prima di decidere eventuali correzioni o un merge; **`valhalla` rimane invariato**.

## Integrazione correzioni Android — 2026-11-01

**Branch** `test/openfreemap-mvt`. Integrate selettivamente dal ramo sperimentale `test/navigation-hardening-20261101` le seguenti correzioni: B-1 denominatore della proiezione su segmento (`dy*dy`), B-0 limiti stringhe UTF-8 nello snapshot BLE (63 byte roadName e 95 byte instruction), A-1 gestione di ritorni GATT falliti all'avvio di `writeCharacteristic` con scarto dei frammenti della stessa sequenza. Portati i file `MotoBLEManager.kt` e `MotoBLEProtocolCodec.kt`, modificata una sola riga di `NavigationViewModel.kt`. Aggiunto il backlog restante in `docs/REVISIONE_ARCHITETTURALE_2026-11-01.md`. Conservati download corridoio, MVT, cache, ricerca e routing già presenti; nessuna modifica al firmware, al formato BLE o a `valhalla`.

**Verifiche:** revisione sorgenti e confronto dei due rami; build CI, unit test e hardware ancora da verificare. Rischi: GATT write privo ancora di watchdog sui callback assenti; servono prove BLE frammentazione, UTF-8, reroute e scena OpenFreeMap. **Prossima azione:** verificare un unico build Android sul branch e testare navigazione con download corridoio prima del merge eventuale in `valhalla`.

## Aggiornamento 2026-11-01 — corridoio OpenFreeMap offline (test)

**Branch** `test/openfreemap-mvt`, sperimentale, senza modifiche a `valhalla`.
Dopo il successo del probe z14 (18 strade, 47 polilinee, 513 punti;
utente conferma che le strade grigie compaiono su ESP32), aggiunto
download delle tile MVT lungo la geometria della rotta Valhalla selezionata,
con margine di una tile per lato. Conferma esplicita alla pressione di
`Avvia navigazione`, oppure avvio senza download. La navigazione parte
senza attendere la fine del download.

**Condizioni d'uso OpenFreeMap** (14 ottobre 2026):
https://openfreemap.org/tos/ — download offline espliciti fino a 3000
tile o 50 MB, al massimo quattro richieste parallele; questa
implementazione usa una sola richiesta alla volta e User-Agent dedicato.
Nessun download GPS continuo. Le tile salvate .motomap sono già
leggibili dal flusso strade grigie. Nessun cambiamento a BLE, firmware,
routing Valhalla, e priorità MotoMap offline. La cache riduce i
download ripetuti.

**Verifica:** compilazione Android avviata tramite commit di integrazione; esito CI e prova
fisica del corridoio **ancora da verificare**. Rischi: lungo percorso
oltre 3000 tile, rate limiting, mancata visibilità immediata delle
strade fino al refresh della scena. **Prossimo passo:** attendere la
CI Android, installare APK del branch test, selezionare una rotta breve,
confermare il download e verificare progressi, strade e log.

## Aggiornamento 2026-11-01 — test OpenFreeMap MVT isolato

**Ramo sperimentale:** test/openfreemap-mvt (partenza dal commit
c23d193 su valhalla). Non modificare valhalla né il routing Valhalla.

- Decoder OpenMapTiles/MVT puro Kotlin: legge il solo layer transportation,
  LineString e attributi class/subclass, riconverte le geometrie in righe
  MotoMap già compatibili con la visualizzazione ESP32, con limiti per
  grandezza compressa/espansa, numero di feature, parti e vertici.
- Comando esplicito in Gestione mappe: Test OpenFreeMap — 1 tile MVT.
  Scarica una singola tile z15 per la posizione GPS corrente e la scrive
  come ofm_trial_*.motomap; il BLE MapScene esistente può mostrarla.
  Nessun ciclo di prefetch, scansione regionale o sostituzione automatica
  di Overpass. Solo su ramo di prova.
- Uso pubblico: una richiesta per tocco, User-Agent unico, nessuna
  raccolta automatica. Verificare condizioni pubbliche prima di estendere.
  https://openfreemap.org/tos/
- Limiti: OpenMapTiles transportation non garantisce il tag maxspeed
  originale, quindi nel test vengono usati valori generici di classe,
  mai come limiti legali affidabili. La tile è geometria per rendering,
  non per Valhalla/routing.
- Verifiche: quattro test sintetici MVT eseguiti localmente con kotlinc
  (compressione gzip, classi stradali, input errato/oversize, tile vuota),
  4/4 superati. GitHub Actions da verificare. Accesso HTTP effettivo,
  decodifica di una tile reale e prova BLE su strada ancora da verificare.
- Prossimo passo: APK del ramo test, prova manuale a GPS fermo, controllare
  nel log OFM_MVT_TEST numero strade/vertici/latency, test con la rete
  grigia ESP32, senza avviare alcuna acquisizione automatica continua.

## Aggiornamento 2026-11-01 — diagnostica probe OpenFreeMap

**Ramo esclusivamente sperimentale** `test/openfreemap-mvt`; nessun
cambiamento a `valhalla`. L'utente ha eseguito il probe manuale
alle 17:55:18 e 17:55:24: entrambi `OFM_MVT_TEST_ERROR` con zero
strade utilizzabili. Il log non distingue ancora layer assenti,
classe stradale esclusa o risposta HTTP con contenuto inatteso.

Aggiunti al decoder i contatori dei layer, delle classi e delle feature
e al messaggio di errore i byte HTTP, il Content-Type e l'ID della tile.
Non abilitati download automatici, nessuna modifica BLE o ESP32.
**Verifica:** unit/build Android nella singola Project Build del commit;
il test su tile reale rimane da ripetere esplicitamente. Prossimo passo:
premere una sola volta `Test OpenFreeMap — 1 tile MVT` e inviare lo ZIP.

## Aggiornamento 2026-11-01 — durata diagnostica estesa

**Branch:** `valhalla`. Dopo la prova su strada con ESP32/Android
PSRAM/BLE (l'utente conferma nessun blocco anche con il logging attivo),
è stata richiesta una copertura più lunga: **60 minuti**, senza un
pulsante per cancellare i file.

- Firmware: `platforms/esp32/main/moto_diagnostics_ram.cpp`, TTL
  OFF/BASE/DEBUG da 10 a 60 minuti. OFF resta la modalità iniziale e
  la disconnessione/riavvio interrompe la cattura. Rimane l'architettura
  2048 eventi in PSRAM e al massimo 4 record/s su BLE; **nessuna
  scrittura sulla flash ESP32**.
- App: `MainActivity.kt` e `NavigationScreen.kt` allineati a 60 minuti.
  `DiagnosticLog.kt` conserva sempre due file ruotati, ora fino a
  1 MiB/file Android e 2 MiB/file ESP32 (circa 6 MiB complessivi,
  escluso ZIP), senza introdurre cancellazione manuale. Nessuna
  variazione al logging Android sempre attivo.
- Documenti: `docs/ESP32_DIAGNOSTICA_RAM.md`,
  `docs/MANUALE_OPERATIVO.md` e nota di obsolescenza nella guida
  del vecchio logger flash.
- **Verifiche:** build CI Android/ESP32 da controllare dopo il singolo
  commit; non sono stati eseguiti flash o test hardware in questa sessione.
- **Rischi:** possibile perdita di record quando la produzione supera
  il BLE sostenibile (4/s), durante disconnessioni o reset; la durata
  di 60 minuti non implica che si possano recuperare tutti i record
  in caso di saturazione del ring.
- **Prossima azione:** aggiornare app/firmware dopo CI verde, raccogliere
  una traccia di oltre 10 minuti con LOG DEBUG e verificare che l'export
  contenga correttamente gli eventi della parte finale della prova.

## Aggiornamento 2026-10-31 — prova su strada e diagnostica RAM opzionale

**Ramo:** `valhalla`. Modifica corrente: nuova diagnostica RAM/Android
(commit di questa revisione; SHA consultabile nella cronologia Git). Progetto
in precedenza aggiornato con `612a0c5`: interpolazione mappa,
direzione GNSS nei ricalcoli e limiti servizio online. La Project Build di
`612a0c5` è risultata **success** su GitHub Actions, ma la prova su strada
di quelle modifiche resta da effettuare.

**Resoconto dell'utente, 31 ottobre:** prova su strada senza blocchi con il
logger flash ESP32 disattivato; lo zoom risulta corretto, la traslazione
della mappa avanza ancora a scatti nel vecchio firmware. Il confronto prima/
dopo l'introduzione del logger attribuisce con elevata confidenza i blocchi
alle operazioni diagnostiche sulla flash, pur senza una traccia hardware che
dimostri il preciso meccanismo di stallo. Non riabilitare il flash logger.

**Modifiche diagnostiche implementate in questa revisione:**
- `moto_diagnostics_ram.cpp` sostituisce il vecchio logger flash nel build
  ESP32: 64 KiB PSRAM, 2048 record numerici, OFF al boot, BASE/DEBUG solo
  su comando BLE, timeout di 10 minuti, reset OFF a disconnessione;
  niente letture/scritture/cancellazioni flash da questo modulo.
- `moto_diagnostics.h`, `phone_nav_bridge.*` e `app_main.cpp` espongono
  l'interfaccia runtime; registrazioni con brevi sezioni critiche, trasmissione
  da task non grafico a massimo 4 record/secondo. Nessun invio diagnostico
  dall'ESP32 quando OFF.
- Estensione opzionale del protocollo BLE in `shared/ble_protocol`:
  comando modalità 0x21, batch numerico 0x22 e capability dedicata, senza
  cambiare i payload dei messaggi di navigazione.
- App Android: pulsante LOG con OFF/BASE/DEBUG, comando esplicito soltanto
  alla scelta utente, file ruotati `esp32-current.log`/
  `esp32-previous.log` aggiunti allo ZIP di diagnostica, timeout di 10
  minuti per l'indicatore UI. La diagnostica Android preesistente rimane.
- Test aggiunti: roundtrip/validazione messaggi BLE, controllo opt-in
  del bridge nativo, encoder/decoder di eventi Android.
- Documentazione: `docs/ESP32_DIAGNOSTICA_RAM.md`.

**Verifiche:** modifiche sorgente ispezionate; CI Android/native/ESP32
**da eseguire con il commit**; nessuna prova su hardware eseguita in questa
sessione. Una CI verde non dimostra che BASE/DEBUG siano innocui su strada.

**Rischi aperti:** perdita dei record ancora in PSRAM dopo reset/assenza BLE;
possibile ritardo dei pacchetti diagnostici se il link è congestionato;
l'acquisizione DEBUG richiede test di carico e non deve essere usata come
modalità predefinita.

**Prossima azione:** verificare la CI unica della revisione, poi flashare
Android ed ESP32 compatibili e fare prima la prova su strada **LOG OFF**
(confrontando fluidità e stabilità), successivamente una prova controllata
BASE/DEBUG ed esportare il file ZIP.

## Sources of truth and working rules

1. The currently checked-out branch code, build configuration and tests.
2. Git commits, PRs, GitHub Actions results and hardware logs.
3. This document: a handoff, **not** proof that a fix works on hardware.
4. Conversation history as context until corroborated by source, CI or a repeatable physical test.

Read root `AGENTS.md` before changes. Keep each session's decisions, changed paths, verification status, regressions and next step in this file. Do not silently overwrite historical decisions; explicitly record reversals.

## Project / active branches

- Repository: `https://github.com/thriller47/moto-gps-waveshare` (upstream reference: `mx3353672833-debug/moto-gps-waveshare`).
- **Primary/default and sole integration branch: `valhalla`** (confirmed by GitHub repository metadata on 2026-10-30). Last firmware code change covered by this handoff: `4c363b8fcd70636bddbe09fd3afddf3b41628ab2`; subsequent docs-only commits have not altered firmware.
- **`signal` is retained only as the historical online OSM implementation** (earlier OSRM/gateway approach), not as an active or parallel production branch. Code history may contain separate radio experiments, but that is not its purpose in the branch policy.
- `test/ble-scene-ack` is a separate, experimental test branch; do not merge without review and explicit permission.
- **`main` no longer exists**, nor does obsolete `NoOta`. Do not recreate them, target them for merges, or use them as workflow bases. Never push to upstream.
- Prefer one consolidated commit and **one CI run** per change; avoid unrelated modifications or repeated workflow triggers.
- Hardware: Waveshare ESP32-S3-Touch-AMOLED-1.75C, 466×466 CO5300 AMOLED, CST9217 touch, QMI8658 gyro, 8 MB PSRAM and 32 MB flash. Firmware ESP-IDF + NimBLE + LVGL; Android companion app communicates via BLE.
- Windows ESP-IDF / esptool and Android Studio are used for local flashing; a Linux machine is also available.

## Current architecture (verified in source)

| Component | Current implementation | Important limitation |
| --- | --- | --- |
| Routing | Android uses offline **Valhalla** when its graph is installed; an online Valhalla path also exists. Offline graph import accepts `*.tar.zst`. | Do not conflate routing graphs with gray road context. |
| Place search | Online **Photon** search. | A graph/MotoMap import does **not** make destination search offline. |
| Gray OSM roads | `HybridMapRepository` loads imported local **MotoMap ZIP** data; if coverage is missing, `GatewayRoadTiles` requests public **Overpass** data and caches available tiles. Android sends `MapScene` over BLE. | **Render is not used for gray-road retrieval.** Public Overpass is unreliable; requests can fail or return no usable context. |
| Green route | Android's selected route geometry is windowed and sent via BLE; `NavPresenter` projects it for the ESP32 display. | Green routing/geometry can remain active even when gray-road `MapScene` is absent. |
| Heading | Android GPS bearing while moving, position displacement fallback; ESP32 `MotionHeadingFusion` uses phone travel course as absolute reference and QMI8658 yaw for interim motion. | Phone magnetic azimuth must not rotate the bike map just because the handset moves on a seat. |
| Diagnostics | Android persistent rotating files and **Export diagnostics** share action. ESP32 256 KiB `diag` partition remains allocated, but runtime flash logging is **disabled by default** in the latest firmware. | ESP32 flash logger caused a *suspected* stability regression; not yet established as causal. |

### Map imports and user-confirmed behavior

- Italy OSM PBF from Geofabrik (`italy-261026.osm.pbf`) was converted to `italy-261026.motomap.zip`; Italy graph import used `Italy.tar.zst` from Routing Heart. The user confirmed that **with MotoMap loaded, gray roads appear** and route navigation generally works.
- Remove/keep removed obsolete 8-km local-map action, region list and downloads, online/offline toggles and corridor workflow. Retain direct MotoMap ZIP and Valhalla graph import controls.
- Route preference UI includes motorcycle touring/panoramic requirements; intermediate waypoints and arrival finish signaling remain project features. Verify exact implementations against current code before changing them.
- **Without MotoMap**, road tests on 2026-10-30 repeatedly showed **no gray context roads**. Android diagnostics reported zero usable segments across repeated lookups and no successful context scene; Overpass errors included timeouts and HTTP 502. The code now prefers `overpass.private.coffee` with `overpass-api.de` fallback, but the new endpoint has **not yet been shown to solve this on the road**.
- Do not claim that gray roads can reliably be retrieved offline without imported map data. Do not infer availability of local Photon search.

## Agreed ESP32 navigation UI — current, not historical

- **Green** selected-route line; gray OSM roads behind it and road labels above the line where present. Route line should cover the useful circular viewport and follow motion smoothly.
- **Turn arrows were removed** in `61410c27`. The user confirms the replacement is **route zoom at turns without arrow glyphs**, including roundabouts. Keep the arrival checkered flag and rider-position marker. A historical no-MotoMap test mentioned arrow-like symbols, but that is not a reason to restore the obsolete arrows.
- Distance to the next turn: **28 px at roughly 8 o'clock**, visible within **200 m** (changed from earlier proposed center-under-road-name and 100–150 m thresholds). Keep destination distance in its existing central position. The former curved/scrolling street-name arc and verbose turn instructions were removed/hidden by later UI changes.
- **Progressive 3.4× zoom around turns is the user-approved final visual guidance**, returning to normal after the maneuver. `9caccf03` allows this with only green route, without gray-road context. No fallback arrows, even without MotoMap.
- Remove speed, compass, music and OTA pages/functions; retain the speed-limit badge. **Important caveat:** the separate `signal` radio experiment and some radio-mode code remain in the repository; do not confuse those with requested navigation pages.
- **Historical, superseded turn issues:** inverted roundabout arrows and timing concerns preceded the switch to zoom. An earlier test reported the numeric counter disappearing ~20–30 m before the physical turn; retain as historical evidence, and revisit only if reproduced in the current zoom-based interface. Closely spaced maneuvers must still respect route progress.

## Recent changes — chronology and reasons (2026-10-28 to 2026-10-30)

| Commit / date | Change and scope | Verification / caveat |
| --- | --- | --- |
| `9f29b5cf` · Oct 28 | Established root `PROJECT_STATE.md` / `AGENTS.md` continuity notes. | Document-only baseline, now updated. |
| `9dabc718` · Oct 28 | Smoother near-turn zoom with shared road context and faster GPS/BLE cadence. | Road tests still showed heading/turn issues. |
| `70286995`, `c1681ed8` · Oct 29 | Raised zoom, simplified guidance layout / hid verbose road arc, turn distance moved to ~8 o'clock. | Later threshold superseded. |
| `61410c27` · Oct 29 | Removed normal maneuver arrows; retained finish flag and numeric counter. | Arrow-like symbols reported earlier without MotoMap are historical; user chose zoom instead of restoring arrow glyphs. |
| `887339c2` · Oct 30 | Added `RouteDistanceGuard`, faster route/map checks, more responsive ESP32 heading fusion, more eager reroute (45 m, 3 fixes, 4 s, 10 s cooldown), faster Overpass timeouts / larger bounding area. | Unit/smoke checks and CI do not resolve the premature turn-counter disappearance on road. |
| `9caccf03` · Oct 30 | Allows 3.4× turn zoom without gray-road `MapScene`, with no-context native test. | **Agreed solution: turn zoom replaces turn-arrow graphics.** |
| `91db9e3d` · Oct 30 | Added ESP32 flash ring logger (`diag`, 256 KiB at `0xC20000`), Android rotating file logger and shareable ZIP, diagnostics decoder/script. | Android export still active. Flash logger later became the main suspected cause of LVGL stalls. |
| `ed805f5c` · Oct 30 | Added `NavigationHeadingPolicy`: after first valid fix, ignore handset compass azimuth; use moving GPS course or displacement fallback. Updated Overpass endpoint and endpoint-error diagnostics. | Android-only; physical improvement should be retested. |
| `9f8dcc6f` · Oct 30 | Capped external LVGL mutex wait at 250 ms and added `RENDER_PHASE` / `DISPLAY_LOCK_TIMEOUT` traces. | Exposed a stalled LVGL/display worker, but timeout **did not cure** the freezes. |
| **`4c363b8f` · Oct 30** | **Disabled ESP32 flash diagnostics by default** via `MOTO_ESP_DIAG_FLASH_ENABLED=OFF`, stubbing logger calls and excluding its implementation at build time. Preserves `diag` partition and old records. | **A/B road test with this firmware pending.** Do not pronounce the freeze fixed yet. |

Relevant code: `platforms/android/app/src/main/java/com/motogps/android/viewmodel/NavigationViewModel.kt`, `NavigationHeadingPolicy.kt`, `RouteDistanceGuard.kt`, `map/GatewayRoadTiles.kt`, `diagnostics/DiagnosticLog.kt`; `platforms/esp32/main/phone_nav_bridge.cpp`, `motion_heading_fusion.hpp`, `moto_diagnostics.cpp`, `board_port_waveshare_1_75c.cpp`; `shared/nav_presenter/src/moto_nav_presenter.cpp`, `shared/nav_ui/src/moto_nav_ui.cpp`. See also `docs/diagnostics/ROAD_TEST_LOGGING.md`.

## Latest road tests and failure analysis (Oct 30)

**Test A: earlier firmware, without MotoMap**
- Slow/absent gray-road context, occasional unexpected left/roundabout symbols, heading alignment delayed. With MotoMap installed, gray roads rendered correctly but turn distance still vanished too early.
- User confirmed **both Android and ESP32** had been flashed with the current `valhalla` build; do not attribute anomalies to an old image without evidence.

**Test B: flash logging enabled; Android ZIP + ESP32 CSV**
- Moving the phone on a car seat could cause the magnetic azimuth to compete with actual travel course. The GPS-heading policy above addresses the code path; do not attribute display freezes to phone orientation.
- Manual ESP32 restarts were logged with reset code `POWERON`. **The user reset the device deliberately after the map stopped updating**: these are **not** evidence of spontaneous panic, reset or battery failure.
- Analysis of the first logs found BLE `NAV_SNAPSHOT` events continued for roughly 35–104 seconds after the last successful `ESP_RENDER`. The display side stopped updating while BLE reception remained active.
- A subsequent **10-minute road test without MotoMap had four display freezes**, requiring manual restarts. ESP32 diagnostic output contained **206 `DISPLAY_LOCK_TIMEOUT` events**; all four sessions showed successful rendering followed by failed attempts to obtain the LVGL display mutex while navigation snapshots continued.
- That proves a **display-side stall / lock contention**, but does **not** yet prove why the LVGL worker retained the lock. The specific suspected regression is the new flash logger writing ~64-byte records repeatedly from `moto_demo` at up to one flush attempt per 25 ms, with sector erases; flash/cache/PSRAM contention is a credible mechanism, not a proven root cause.
- User explicitly noted the freezes **began after enabling ESP32 diagnostics**. Thus the A/B firmware **with logger entirely disabled** is the priority before touching TE synchronization, LVGL internals or gyro logic.

**Test C: pending**
- Flash latest commit `4c363b8f` (only app binary at `0x20000` if the updated partition table is already installed), run the **same no-MotoMap trip** for at least 20–30 minutes, and report whether freezes recur. Android logging/ZIP continues working.
- With ESP32 flash diagnostics disabled, **no new ESP32 diagnostic records** will be written. Existing `diag` flash data can still be read; they are historical and must not be mistaken for current-run events.
- Do not start additional GitHub Actions merely for documentation or exploratory checks.

## Diagnostics status and proposed v2

### Present implementation
- Android: `DiagnosticLog.kt` writes `current.log` / `previous.log` (each ~384 KiB), shares `motogps-android-diagnostics.zip` from the **Share/Export diagnostics** icon via Android `FileProvider`. Includes navigation, heading, BLE, Overpass, GPS and reroute metadata. No ADB or laptop required on the road.
- ESP32: partition table allocates `diag` `0xC20000`, size `0x40000` (256 KiB), outside app and existing `storage`; decoder `scripts/diagnostics/decode_esp32_trace.py` converts a raw dump to CSV. **Logging is compiled out by default** in commit `4c363b8f`. Do not enable for road use until an alternative is tested.
- Windows sample read (replace COM port): `esptool.exe --chip esp32s3 -p COM5 -b 460800 read-flash 0xC20000 0x40000 esp-diag.bin`; then `python scripts\\diagnostics\\decode_esp32_trace.py esp-diag.bin`. Do **not** run `erase-flash`.
- First installation of the diagnostic partition required a new `partition-table.bin` at `0x8000`; subsequent app-only flashes use `0x20000`. Keep the original app offset; do not use `0x10000`.

### Future architecture (design only; **not implemented**)
- Investigate a **preallocated circular PSRAM ring** (initial proposal 128 KiB, subject to memory measurements) with compact numeric records; no blocking/formatting/allocation/flash I/O in BLE, QMI8658 or LVGL callbacks. Drop low-priority records on overflow.
- Optional, versioned **BLE diagnostic retrieval**, negotiated by capabilities and lower priority than navigation/geometry/scene frames. Prefer small periodic critical-event summaries to Android; bulk transfer on demand **after stopping navigation**, including when screen is frozen but BLE is still alive.
- Android should persist fetched ESP32 events immediately and add them to the existing diagnostic ZIP, correlating ESP uptime/connection sessions with Android wall time.
- **PSRAM is volatile:** a physical power reset loses unsent events. Periodic small summaries persisted by Android mitigate but cannot eliminate this; do not claim guaranteed retrieval after power loss.
- Retain flash partition for future optional bench/offline dump **only when navigating/display DMA is stopped**, after validation. Do not reintroduce high-frequency flash writes.
- Develop v2 on an **isolated test branch**; host ring-buffer and decoder tests, BLE fragmentation/ACK/reconnection/backpressure tests, simulated frozen UI retrieval, then 30–60 minutes hardware stress with and without MotoMap before considering merge to `valhalla`.
- The proposed design was discussed on Oct 30. A prior attempt to create a GitHub Issue was rejected because Issues are disabled; the design is captured here so it is not lost.

## Verification and CI status at this update

| Commit / workflow | GitHub Actions result | What it establishes |
| --- | --- | --- |
| [`91db9e3d` — run 41051151667](https://github.com/thriller47/moto-gps-waveshare/actions/runs/41051151667) | **Success** (Android, ESP32, backend; native skipped) | Builds of the initial diagnostic implementation, **not** runtime reliability. |
| [`ed805f5c` — run 41060444084](https://github.com/thriller47/moto-gps-waveshare/actions/runs/41060444084) | **Success** (Android; other unnecessary jobs skipped) | Compiles changed Android heading / Overpass code. |
| [`9f8dcc6f` — run 41061406359](https://github.com/thriller47/moto-gps-waveshare/actions/runs/41061406359) | **Success** (ESP32 and backend; Android/native skipped) | The instrumentation build compiled; later road tests **still froze**. |
| [`4c363b8f` — run 41094597313](https://github.com/thriller47/moto-gps-waveshare/actions/runs/41094597313) | **Success** (ESP32; remaining jobs skipped when irrelevant) | The flash-logging-disabled firmware builds; **on-road A/B result pending**. |

Earlier Android and native navigation tests covered `RouteDistanceGuard`, heading selection and no-map zoom. The user reported four green jobs for a separate upstream-comparison test effort; **do not claim those experimental changes were merged** into `valhalla`. No physical-device tests were performed in this documentation session.

## Confirmed decisions — 2026-10-30

- GitHub reports `default_branch=valhalla`; `main` has been removed. `signal` remains **only as historical online OSM/OSRM reference**; `test/ble-scene-ack` is experimental.
- The user confirmed **turn visualization is resolved by progressive zoom of the green route**: no ordinary maneuver arrows, including at roundabouts and with missing gray context. Historical arrow reports are archived; they are not an active requirement.
- These updates change **documents and agent guidance only**, not navigation or diagnostic behavior. The ESP32 freeze A/B test without flash logging remains pending.

## Open problems and priorities

| Priority | Issue | Known facts | Next action |
| --- | --- | --- | --- |
| **P0** | ESP32 screen/map freezes | BLE navigation updates still arrive; LVGL mutex timeouts; regression began after flash logger was introduced | Finish A/B road test with logger fully disabled. If it still freezes, instrument LVGL worker/TE/DMA without writing to flash in motion. |
| **P1** | No gray context without imported MotoMap | Local MotoMap works; public Overpass requests timed out/HTTP 502; changed endpoint needs verification | Review Android ZIP after A/B, verify endpoint responses/OSM cache; prefer offline import as reliable baseline. |
| **P1** | Heading alignment / phone moving on seat | Magnetic heading was allowed to overwrite travel course; Android code now prioritizes moving GPS | On-road validation of `NavigationHeadingPolicy` + ESP32 QMI8658 fusion. |
| **P2** | ESP32 diagnostic recording for future bugs | Flash logger is unsafe until disproved; now off | Design/test PSRAM→low-priority BLE→Android v2 separately; leave `valhalla` unchanged meanwhile. |
| Later | Offline search/Photon; historic online-OSM branch | Photon remains online; `signal` is historical only | Do not mix archived online OSM code into `valhalla` stability changes. |

## Decisions and exclusions to preserve

- `valhalla` is the **primary/default branch**; `main` is deleted; `signal` is archived history for online OSM; `test/ble-scene-ack` is experimental.
- Maintain the **existing destination-distance position**, current turn counter (8 o'clock, 28 px, <=200 m), current zoom 3.4×, green route, genuine gray MotoMap roads, checkered arrival flag and speed-limit badge.
- **Use green-route 3.4× zoom for turns, never ordinary left/right/roundabout arrows.** Keep numeric turn and arrival distances and the checkered finish flag. No OTA or speed/compass/music navigation pages.
- With missing MotoMap, do not make route visibility/zoom dependent on gray context.
- Preserve Android↔BLE↔ESP32 protocol compatibility; tests are required before packet/schema changes.
- Do not call a fix “resolved” simply because CI is green or because a reset reason was `POWERON`.
- Avoid generating duplicate workflow runs; consolidate commits and keep experimental work off the tested `valhalla` baseline.
- Do not delete user maps, NVS or diagnostic partition while flashing; preserve flash layout.

## Next concrete action / session handoff

1. **First:** after the successful CI build, test `4c363b8f` on the ESP32 **with the flash logger disabled**, on the same no-MotoMap scenario where four stalls occurred in ten minutes. Android APK can stay on the heading-policy/Overpass version; export the Android ZIP after the ride. Compare freeze frequency and GPS/BLE symptoms. Do **not** use old ESP32 CSV records as evidence from the new run.
2. **If stable:** document the A/B outcome, keep logger off, and develop the v2 RAM/BLE design **on a test branch**, with no writes to SPI flash during navigation.
3. **If still frozen:** instrument the *LVGL worker, QSPI DMA/flush progress and TE gating* with nonintrusive sampling, then isolate by controlled changes. Avoid layering several unrelated fixes into one road test.
4. **In every session:** inspect the current branch/SHA, applicable `AGENTS.md`, pertinent files and CI; update this document with **what changed, exact tests actually run, remaining hardware verification and next step**.


## Operational handbook — 2026-10-30 (documentation only)

- Added **docs/MANUALE_OPERATIVO.md** with reproducible Windows CMD instructions for clone/update of the **valhalla** branch, downloading unmodified **ESP32-Firmware** GitHub Actions artifacts, flashing with the original directory structure at **COM3** (0x0/0x8000/0x20000), converting Geofabrik OSM PBF into MotoMap ZIP, building/importing Valhalla TAR using the official scripted Docker image, reading and decoding legacy flash logs at 0xC20000, and the exact serial-radio commands (including **wifi set SSID|PASSWORD**).
- Linked the manual visibly from the root README while warning that older README text is upstream/iPhone-oriented.
- Explicitly marked **ESP32 flash logging disabled** in current builds and **PSRAM→BLE diagnostics v2 not yet implemented**. No firmware/application code was changed and no CI/hardware checks were run for this docs-only update.
- Next concrete action remains the road A/B test of ESP32 firmware **4c363b8f** without flash diagnostics; revise the manual when the new diagnostics is genuinely implemented and validated.

## Ambiente Windows ESP-IDF 5.5.6 — 2026-10-31

- Confermato sul PC dell'utente: framework in `C:\Espressif\frameworks\esp-idf-v5.5.6`, virtualenv Python in `C:\Espressif\python_env\idf5.5_py3.10_env`, repository in `C:\moto-gps-waveshare`.
- `export.bat` falliva finché cercava Python sotto `C:\Users\MoD\.espressif`; la procedura riuscita è stata `install.bat esp32s3`, poi impostare `$env:IDF_TOOLS_PATH = "C:\Espressif"` prima dell'export. Verificato `idf.py --version` → `ESP-IDF v5.5.6-dirty`.
- Aggiunta una procedura di reinstallazione Windows e recupero funzione PowerShell `moto` senza Notepad a `docs/MANUALE_OPERATIVO.md`. **Non confondere attivazione riuscita con compilazione/flashing/hardware verificati**. CI GitHub continua su 5.5.5. Solo documentazione; nessun test o CI eseguito.

## Windows reinstall and handbook path correction — 2026-10-31

- The user's **current**, confirmed Windows installation uses ESP-IDF 5.5.6 under `C:\Espressif\frameworks\esp-idf-v5.5.6`, Python **3.11.2** at `C:\Espressif\python_env\idf5.5_py3.11_env\Scripts\python.exe`, and checkout `C:\MotoGPS\moto-gps-waveshare`. Supersedes earlier 3.10 / `C:\moto-gps-waveshare` paths for the current workstation.
- Activation requires setting `IDF_TOOLS_PATH=C:\Espressif` and prepending the Python 3.11 venv Scripts directory to the session PATH. `install.bat esp32s3` then completed successfully; `export.bat` reported Python dependencies OK and ESP-IDF activation. User confirmed that PowerShell shortcut `moto` works after `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned` enabled the local profile. No post-reinstall build/flash verification reported.
- Updated `docs/MANUALE_OPERATIVO.md` with the reproduced repair sequence, PowerShell-vs-CMD distinction, script execution policy, and current clone/update paths. Documentation-only change; no CI triggered intentionally. Next: user can test local ESP32 build when convenient.

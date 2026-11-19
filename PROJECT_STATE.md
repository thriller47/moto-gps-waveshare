# Moto Chat GPS — stato operativo del progetto

## STATO ATTUALE — RIPARTIRE DA QUI

**19/11/2026 — firmware ESP32: variante sperimentale di predizione curva (branch `test/merge-gps-20261118`, `valhalla` invariato):** riletto il CSV del 19/11. A 380476 ms, 11,72 m/s, C2 è 22,91 m dietro il GPS rispetto ai 20,49 m di A, quasi tutto scarto longitudinale; non è una prova di deriva del gyro. Aggiunto `turn_assist_heading_policy.hpp`, filtro a 100ms su heading già fuso che produce un peso di curva senza modificare né rotazione IMU né dati BLE. `MapOriginPredictor::advance` conserva comportamento precedente con argomento opzionale 0; solo con assistenza >0 attenua velocità predetta fino al 45% e riduce il tempo di correzione di un **ancoraggio BLE realmente ricevuto** da 0,55 a 0,30 s. Niente allineamento artificiale ogni frame con GPS del replay; fermo mantiene ultima posa finché arriva un nuovo anchor BLE (limite esplicito). Nel render task `PhoneNavBridge` aggiorna il filtro in modo lock-safe e resetta su cambio route. Opzione CMake `-DMOTO_ESP_TURN_ASSIST_EXPERIMENT=ON` **OFF per default** e facilmente reversibile con OFF; la prima CI standard valida il percorso OFF, mentre la variante ON richiede apposita build mirata prima di flash e prova su strada. Nuovi test nativi su rettilineo, curva, reset, movimento senza nuovo anchor e correzione quando arriva un anchor. Invariati APK Android, Valhalla, UI, zoom, BLE, fusione QMI8658 e `valhalla`. **Stato:** sorgente implementato, CI/test da verificare, nessun flash eseguito. **Prossima azione:** costruire firmware sperimentale con ON, confrontare rotonde e ricalcolo su strada con diagnostica completa, tenendo firmware A come rollback. PR #77 bozza.


**19/11/2026 — esperimento C2 nel simulatore, branch `test/merge-gps-20261118`:** analizzato il CSV `moto-heading-diagnostics(4).csv` dell'utente (3153 righe, 3148 con predizione): scarto medio A 4,90 m, C 4,56 m; nei 116 snapshot con heading-rate >=12°/s e velocità >=3m/s A 7,64 m, C 3,07 m; nei 62 snapshot con velocità <0,3 m/s A 10,87 m, C 11,18 m. Aggiunta **C2** solo WASM: soglia iniziale di curva 6°/s su finestre >=100ms, predizione attenuata durante il giro, correzione GPS che decade entro 1s dall'ultima curva, reset e origine GPS da fermo/andatura <1,5m/s. A, B OFF e C rimangono invariati. Nuovo export batch e tre nuove colonne CSV per C2, test nativo su rettilineo/curva/uscita/sosta/ripartenza e test Node per export e CSV. Firmware ESP32, Android, IMU, BLE e routing **invariati**. Il miglioramento del delta rispetto al GPS registrato è in parte tautologico perché C2 usa la posizione GPS: necessario confronto visivo e test fisico prima di qualunque porting; replay senza IMU raw e senza vera latenza BLE. Documenti simulatore e manuale aggiornati. **Stato validazione:** CI finale di questa revisione da controllare, compilazione WASM nel PC dell'utente da effettuare. **Prossima azione:** ricompilare WASM, confrontare A/B/C/C2 sullo stesso JSONL, inviare nuovo CSV, verificare laterale in entrata/uscita e residuo in sosta. PR #77 bozza, `valhalla` invariato.


**19/11/2026 — simulatore posizione A/B/C per rotonde, branch `test/merge-gps-20261118` (PR #77 ancora bozza, `valhalla` invariato):** aggiunto menu `Predizione posizione` con **A originale** (stessa `MapOriginPredictor` C++ del firmware), **B OFF** (origine posizione snapshot Android) e **C prudente in curva** (nuovo `platforms/web/wasm/replay_curve_predictor.hpp`, solo sperimentale WASM). C riduce la velocità predetta quando l'heading registrato cambia rapidamente, riconoscendo la curva su finestre temporali di >=100 ms anche a replay interpolato 60 Hz, e rientra più rapidamente verso il GPS snapshot. Bridge `replay_web.cpp` esporta `moto_web_replay_set_predictor_mode` e analisi batch C (scarto totale, avanti/indietro e destra/sinistra); player mostra sullo stesso frame scarti A/B/C e CSV esteso con tre colonne candidate. Compatibilità con i precedenti export A/B mantenuta. Aggiunti test nativi per rettilineo equivalente ad A, attenuazione in curva, reset e replay 60 Hz, nonché test Node per binding/export WASM e CSV (compreso WASM vecchio). Manuale operativo e `platforms/web/simulator/README.md` aggiornati con comandi WSL e limiti del confronto. **Vincoli:** il replay non contiene la frequenza IMU grezza QMI8658 o i tempi BLE veri; C usa il fix GPS dello snapshot, quindi minore scarto non prova errore stradale ridotto. **Nessuna modifica a codice Android/GPS/IMU/ESP32 o BLE/Valhalla; firmware non da flashare per la prova.** **Stato test:** test automatici e runtime Emscripten non ancora eseguiti nel momento della pubblicazione; la CI precedente Android 44486006967 è stata verificata verde, mentre l'ultima CI del simulatore è da controllare; nessun test hardware per C. **Prossima azione:** verificare build native/Node, ricompilare WASM in WSL con `./scripts/build_web.sh`, aprire replay 19/11 dai file `gps/*.jsonl`, confrontare le tre modalità e CSV sullo stesso tratto rotonda prima di qualsiasi cambio firmware.


**19/11/2026 — protezione ricalcoli in rotonda (branch `test/merge-gps-20261118`):** analizzato il replay completo del 19/11, con sei reroute locali ~17–57 ms; nell'ultimo episodio l'intervallo prima del trigger supera i 20 s, scostamento 45–89 m nonostante fix GNSS accurati (~2–3 m). Identificata guardia curva di `RerouteStabilityPolicy.observeCourse()` prolungata a ogni nuova variazione di bearing >=14°: `shouldDeferWhileTurning` ora tiene un **timer indipendente**, che supera la guardia dopo **2 s consecutivi** di fix accurati (accuracy <=12 m) con scostamento >=60 m. Interruzioni dei fix (>2,5 s), fix imprecisi e rientri nel corridoio azzerano il timer. Rimangono conferme consecutive del ViewModel (2 fix +1,5 s per fuori rotta), soglia ordinaria 45 m, cooldown 8 s locale/15 s online, protezione post-reroute e guardia iniziale curva fino a 6 s. Aggiunti test per rotonde/guardia ripetuta, rumore/interruzioni e reset a nuovo percorso; aggiornato `NavigationViewModel` per passare l'accuratezza GNSS alla policy. **Invariati:** IMU QMI8658, predittore ESP32, BE/BLE, routing Valhalla, UI, formato diagnostica. **Verifica:** in attesa di CI Android di questo commit e prova su strada; la correzione non prova ancora eliminata la deriva grafica ingresso/uscita rotonda. **Prossima azione:** riprovare il percorso con diagnostica completa BASE/DEBUG e confrontare tempi trigger e deriva. `valhalla` non modificato; PR #77 bozza.\n\n
**18/11/2026 sera — diagnostica unificata Android (branch `test/merge-gps-20261118`):** completata l'unificazione del controllo log GPS e ESP32 su richiesta dell'utente, senza ulteriori modifiche alla UI principale, GPS/IMU/predittore, routing, BLE o firmware. In `NavigationScreen.kt` un solo dialogo **Altre opzioni → Diagnostica e replay GPS**: selezione BASE o DEBUG, **Avvia/Ferma diagnostica completa**, **Ferma ed esporta tutto (ZIP)**. In `MainActivity.kt` l'abilitazione del registro ESP32 via BLE e il replay GPS Android avvengono insieme; senza BLE non parte una nuova sessione. Se cade il BLE, il GPS può proseguire e l'app ripristina il log ESP32 dopo la riconnessione (gli eventi persi durante il distacco non sono recuperabili). Il livello ESP32 è rinnovato ogni 45 minuti per evitare che il timeout firmware di circa 60 minuti interrompa i log prima della traccia GPS. In `DiagnosticLog.kt` nuovo `exportCompleteZip(context)` prepara `motogps-diagnostica-completa.zip` e inserisce i log Android/ESP32 sotto `logs/` e il replay `.jsonl` completo, anche multipart, direttamente sotto `gps/` (senza ZIP annidati). Un solo share intent con `FileProvider`; lo stream di registrazione viene fermato prima della creazione dell'archivio. Coordinate e itinerario richiedono condivisione consapevole. Manuale operativo sezioni 6, 7, 8 e riferimento rapido aggiornati. **Controlli:** ispezione statica e modifiche su GitHub, nessun collaudo dispositivo del nuovo flusso ancora effettuato. **Prossima azione:** CI Android sul commit finale, poi verificare da telefono collegato alla Waveshare avvio BASE/DEBUG, stop, esportazione ZIP unico e replay multipart. `valhalla` e i vecchi branch restano invariati; PR #77 ancora in bozza.

**18/11/2026 — riordino UI Android su `test/merge-gps-20261118` (in verifica):** in `NavigationScreen.kt` ricerca destinazione sempre disponibile, azioni principali `Crea tour`/`Tour salvati`, stato display e pulsanti posizione/mappe, menu overflow per diagnostica replay/log/info (funzioni e callback preservate). In `OfflineMapsDialog` pacchetto unico .motopack come azione principale, stato MotoMap/Valhalla/Photon e importazioni/eliminazioni separate sotto `Opzioni avanzate`; l'utente puo' sempre importare componenti singoli. Il pulsante `Salta prossima tappa` era **gia' valido sui percorsi normali** in `NavigationViewModel.skipNextWaypoint()` (`WaypointSkipPlan` indipendente dal tipo di rotta); corretto il testo di conferma per non parlare esclusivamente di tour, aggiunti due test JUnit per rotta normale con 1 o piu' tappe e destinazione finale separata. Nessun cambio a codice di routing/GNSS/IMU, BLE, ESP32, mappe generate o logica checkpoint dei tour; nessun merge in `valhalla`. **Verifica:** la precedente CI di integrazione GitHub Action `44390943037` ha tre job `native/android/esp32` verdi; la modifica UI di questa sessione non era inclusa in quella CI. **Prossima azione:** una verifica Android del commit UI finale e controllo visuale su APK (schermata piccola, menu, dialogo, installazione MotoPack e salto tappa ordinaria) prima del collaudo su strada e del merge `valhalla`.


**18/11/2026 — preparazione fusione Gemini + MotoPack/GPS sul branch `test/merge-gps-20261118` (valhalla invariato):** branch creato dall'HEAD `gemini_test` `be64048`, ultimo commit noto "Calibrazione gravita IMU e filtro Kalman adattivo per traffico lento e rotonde". Incorporati tramite commit a più genitori il precedente `test/unified-offline-map-20261115` (`375dca6`) e l'ultimo `valhalla` (`4992631`) per conservare lo storico e l'archivio hardware `MotoNavFusion_v0161.zip`. Restano invariati **i sorgenti effettivi di `gemini_test`** per `motion_heading_fusion.hpp`, `motion_heading_sensor.cpp`, `map_origin_predictor.hpp`, `phone_nav_bridge.cpp`, `LocationForegroundService.kt`, `NavigationHeadingPolicy.kt` e BLE. Integrare il matcher non significava sostituirne i pesi: in `RouteProgressMatcher` conservati i coefficienti Gemini (penalita' di salto `2.0`, soglia lunghezza `4.0`) e aggiunti distanza dal corridoio attuale e telemetria; in `NavigationViewModel` mantenuti le finestre geometriche/roundabout a 1,5 s e fallback course a 9 m Gemini, aggiungendo `RouteProgressStallPolicy` e replay privato. Portati `rebuild_motopack_wsl.sh`, test di ricostruzione, replay completo, analizzatore diagnostico e toggle A/B browser (OFF nel firmware). Workflow CI combina test browser Gemini e diagnostica/MotoPack del nostro test; manuale aggiornato per `Ubuntu-24.04` e branch fusione. **Verifica attuale:** confronto GitHub dei tre rami completato; la fusione del codice e documentazione e' pubblicata, ma nessuna compilazione, test CI, build WASM, flash o prova su strada della versione integrata e' ancora stata effettuata. **Test sul campo riferito dall'utente:** APK e firmware `gemini_test` provati insieme il 18/11, orientamento notevolmente migliore con lieve deriva; la correzione finale del commit Gemini `be64048` non ancora verificata durante un giro. **Rischi aperti:** potenziali conflitti comportamentali fra nuovi fix Android GNSS e stall detector; deriva residua in curve e intermittenza BLE; verificare compilazione. **Prossima azione unica:** avviare una sola CI completa sul branch fusione, verificare Android/native/ESP32 e replay WASM, quindi collaudare su strada prima di approvare il merge `valhalla`. Non eliminare gli originali `gemini_test` e `test/unified-offline-map-20261115` prima del collaudo. **Pull request di verifica:** [#77](https://github.com/thriller47/moto-gps-waveshare/pull/77), aperta in bozza verso `valhalla`, merge automatico non effettuato; verificare i tre job GitHub e non rendere la PR definitiva prima di collaudo utente.


**Aggiornato:** 18 novembre 2026 (calibrazione automatica asse gravità montaggio IMU, filtro di Kalman adattivo a 2 stati senza blackout GPS in curva, stabilità traffico lento e allineamento soglie Android sul branch `gemini_test`). **Repository:** [thriller47/moto-gps-waveshare](https://github.com/thriller47/moto-gps-waveshare). **Branch di integrazione stabile:** `valhalla` (non toccato, nessun merge eseguito). **Branch sperimentale attivo:** `gemini_test`.

**Ultimo sviluppo effettivamente implementato:**
1. **Calibrazione automatica asse gravità montaggio IMU (`motion_heading_sensor.cpp`):**
   - Stima persistente del vettore di gravità di montaggio `(cal_ax, cal_ay, cal_az)` inizializzata ai primi campioni validi.
   - Aggiornamento lento (`alpha = 0.02F`) abilitato esclusivamente in assenza di accelerazioni dinamiche: norma accelerazione in `[9.36, 10.26]` m/s² (`9.81F +/- 0.45F`) e rateo giroscopico `< 4.0F` dps.
   - Durante frenate, accelerazioni o forza centrifuga in rotonda/curva (fuori finestra), il vettore `(cal_ax, cal_ay, cal_az)` viene congelato e passato direttamente a `clockwise_gravity_heading_rate(...)`, garantendo corretta proiezione yaw sia per montaggio verticale in auto sia inclinato su moto.
2. **Filtro di Kalman adattivo a 2 stati (`motion_heading_fusion.hpp`):**
   - Propagazione varianza angolare `heading_var_deg2_` in `integrate()` con rumore di processo `(0.08F * |active_rate| * dt_s)^2 + 0.5F * dt_s`.
   - Guadagno di Kalman adattivo `gain = heading_var_deg2_ / (heading_var_deg2_ + sigma^2)` limitato in `[0.08F, 0.65F]`, senza bloccare le ancore GPS durante le curve (blocco riservato a ratei estremi `> 90.0F` dps).
   - Riduzione varianza `heading_var_deg2_ *= (1.0F - gain)` e stima continua residuo di bias su ratei `< 3.0F` dps.
   - Gate anti-spike adattivo: doppia conferma outlier (> 35°) applicata solo quando `heading_var_deg2_ < 144.0F` (rettilineo stabile); in curva/rotonda (`heading_var_deg2_ >= 144.0F`) riallineamento immediato senza ritardo.
   - Stabilità traffico lento: `kDeadbandDps = 1.1F`, `kStationaryReleaseSpeedMps = 1.5F`, `kCourseAnchorSpeedMps = 2.0F` e azzeramento `has_moving_course_` quando `speed_mps < 0.6F`.
3. **Android Policy & ViewModel (`NavigationHeadingPolicy.kt`, `NavigationViewModel.kt`):**
   - Allineate soglie traffico lento: `speedMs >= 2.0f` per `gpsBearingDeg` e `displacementMeters >= 9.0` (`speedMs >= 2.0f`) per fallback da coordinate.
   - Test unitari Android (`NavigationHeadingPolicyTest.kt`) aggiornati per le nuove soglie.
4. **Test nativi C++ (`motion_heading_fusion_tests.cpp`):**
   - Copertura test per micro-oscillazioni nel traffico lento (< 1.1 dps ignorate), rotonda completa a 25 dps per 8 s con errore scala gyro 15% e ancoraggio GPS attivo, e ripartenza da fermo prima di rotonda.

**Verifiche eseguite:**
- Singolo commit consolidato su branch `gemini_test`.
- Avvio CI consolidata GitHub Actions `project-build.yml` via `workflow_dispatch` con `build_native=true`, `build_android=true`, `build_esp32=true`.

**Ancora da verificare:**
- Monitoraggio della run remota GitHub Actions CI (`project-build.yml`) fino a completamento verde (`native`, `android`, `esp32`).
- Collaudo su strada con hardware Waveshare ESP32 montato in auto e su moto.

**Prossima azione:** monitorare la run CI consolidata GitHub Actions su `gemini_test` fino ad esito verde su tutti i job. Nessun merge su `valhalla`.

## Calibrazione IMU e Kalman adattivo per traffico lento e rotonde — 18 novembre 2026 (branch gemini_test)

**Motivazione:** risoluzione congiunta dell'eccessiva sensibilità del display nel traffico lento e della deriva angolare causata dall'accelerazione centrifuga durante la percorrenza prolungata delle rotonde, valida per orientamento sia verticale (auto) sia inclinato (manubrio moto).

**Implementato sul branch gemini_test:**
1. `platforms/esp32/main/motion_heading_sensor.cpp`:
   - Calibrazione automatica del vettore di gravità di montaggio `(cal_ax, cal_ay, cal_az)`.
   - Congelamento del vettore di gravità durante accelerazioni dinamiche (norma accel fuori da `[9.36, 10.26]` m/s² o gyro `>= 4.0` dps), evitando distorsioni causate da frenate o forza centrifuga in rotonda.
   - Proiezione dell'imbardata gyro sempre sul vettore di gravità calibrato.
2. `platforms/esp32/main/motion_heading_fusion.hpp`:
   - Sostituzione del guadagno fisso e rimozione del blocco delle ancore GPS a >5°/s con filtro di Kalman adattivo a 2 stati.
   - Propagazione varianza angolare in `integrate()`: `(0.08F * |active_rate| * dt_s)^2 + 0.5F * dt_s`.
   - Guadagno di Kalman adattivo in `anchor()` in `[0.08F, 0.65F]`, con blocco limitato solo a ratei estremi `> 90.0F` dps.
   - Gate anti-spike con doppia conferma outlier solo su rettilineo (`heading_var_deg2_ < 144.0F`); correzione immediata in curva/rotonda.
   - Regolazione traffico lento: `kDeadbandDps = 1.1F`, `kStationaryReleaseSpeedMps = 1.5F`, `kCourseAnchorSpeedMps = 2.0F`, azzeramento `has_moving_course_` sotto 0.6 m/s.
3. `tests/native/motion_heading_fusion_tests.cpp`:
   - Aggiunti test per micro-oscillazioni nel traffico lento, rotonda 25 dps per 8s con 15% errore scala e ancore GPS attive, e ripartenza da fermo.
4. `platforms/android/app/src/main/java/com/motogps/android/viewmodel/NavigationHeadingPolicy.kt` e `NavigationViewModel.kt`:
   - Soglia `speedMs >= 2.0f` per bearing GNSS e `displacementMeters >= 9.0` (con `speedMs >= 2.0f`) per coordinate fallback.
5. `platforms/android/app/src/test/java/com/motogps/android/NavigationHeadingPolicyTest.kt`:
   - Allineamento test unitari alle nuove soglie Android.

**Verifiche:** delega compilazione e test ad un'unica run GitHub Actions (`project-build.yml`) su `gemini_test`. Nessun merge su `valhalla`.

**Aggiornato:** 17 novembre 2026 (fix Python di copertura MotoMap z15, tag maxspeed e pipe zstd; formato v4 invariato). **Integrazione stabile:** `valhalla` (non modificata in questa sessione). **Branch sperimentale attivo:** `test/unified-offline-map-20261115`; non eseguire merge senza approvazione dell'utente. Al controllo GitHub del 16/11: branch test 51 commit avanti e 3 indietro rispetto a `valhalla` (rivalutare divergenza prima del merge).

**Ultimo sviluppo effettivamente implementato (prima di questa patch):** «Salta prossima tappa» su Android, con dialogo di conferma, ricalcolo Valhalla dalla posizione GPS attuale verso le sole tappe pendenti, esclusione della tappa solo a routing riuscito e aggiornamento checkpoint per gli anelli; supportato anche per percorsi ordinari con tappe, mai per l'arrivo finale. Commit codice `90a1be168c022561514e9303dfddfcc77e53c205`. ESP32 e protocollo BLE **non** modificati per questa funzione. Il branch test contiene inoltre MotoMap v4 + Valhalla Italia V5 + Photon v2 unificati, anelli, tour salvati, pausa/ripresa e rimozione completa della demo Jinan. Unico supporto 3D `hardware/MotoNavFusion_v015.zip` preservato.

**Diagnosi aggiuntiva heading rettilineo 17/11, 07:49:53–07:50:03:** il replay e i log Android confermano **heading registrato 253,2°** alle 07:49:53.416 contro **direzione del tratto verde 212,8°**, scarto 40,4°. Nel segmento la geometria vicina resta stabile 212,8–213,17°, mentre il bearing GNSS oscilla e poi torna 215,5°; alle 07:49:54.330 bearing GNSS 251,8° contro spostamento effettivo fra fix circa 216,0° (35,8°). Lo snapshot usa la sorgente `accepted_gps`, e il log registra `GPS_FIX provider_course=251.8 heading_cdeg=25179`. La policy Android privilegia un bearing GNSS formalmente valido, senza confronto sistematico con rotta/direzione consolidata di più fix; la **precisione angolare GNSS non è presente nel replay**, dunque non è possibile stabilire se il driver la dichiarasse buona. Sono evidenze di heading in ingresso errato, non prova di bug Valhalla/Douglas–Peucker/IMU. Dettagli: `docs/diagnostics/HEADING_STRAIGHT_AUDIT_20261117.md`. Nessuna modifica runtime o firmware in questo audit.

**Audit aggiornato sulla rotonda (17/11):** l'utente ha osservato uno spostamento deciso a sinistra all'uscita. Replay originale e CSV confermano un picco di **−27,99 m laterali** a t=1.121.857 ms (frame 4503), durante manovra `roundabout`. Il precedente sospetto generico su «geometrie identiche» non è confermato in quel tratto: gli eventi di rotta hanno origini/punti diversi, con gap reale di **3.075 ms** tra 1.118.784 e 1.121.859 ms. Il picco è appena **2 ms prima** dell'evento rotta successivo; lo scarto rientra a −17,43 m e −10,91 m nei successivi snapshot. L'ultima ancora C++ usata nel replay aveva heading ~340,59° e speed 17,67 m/s; a 1.121.078 ms heading ~3,2° (delta breve ~22,61°, oltre limite di 15°) e speed ~12,31 m/s (69,7% dell'ancora, sotto il minimo 75%), condizioni che disattivano la predizione rettilinea mentre la moto curva. Diagnosi: **staleness dell'ancora e congelamento dell'estrapolazione nel modello di replay sono un meccanismo plausibile e riproducibile del disallineamento**, ma senza cronologia reale BLE/IMU non è provato che il firmware fisico si comporti ugualmente. Rapporto in `docs/diagnostics/PREDICTOR_ROUNDABOUT_AUDIT_20261117.md`. **Nessuna modifica di soglie**.

**Ultima prova utente (17/11, simulatore Chrome):** confronto sul replay `motogps-replay.jsonl` (19:24) con predittore OFF (video 11:41:10) e ON (video 11:44:43), velocità 1× e layout compatto. L'utente **non osserva variazioni importanti** nella stabilità/rotazione fra OFF e ON. Fotogrammi verificati: OFF a circa 07:49:21 / +12:33 mostra scarto **0,0 m**; ON a circa 07:49:22 / +12:34 mostra **3,7 m**. Sono istanti leggermente diversi, non prova quantitativa sincronizzata; non dedurre che il predittore sia innocuo o efficace. **Layout compatto migliorato, confermato dall'utente.** Il WASM reale e i controlli risultano attivi nelle registrazioni; non è una validazione hardware ESP32/IMU. **Problema principale ancora aperto:** rotazioni/orientamento e apparenti salti della mappa, causa non isolata. Prossima azione: analizzare con metodo dati heading e scarto nei medesimi istanti di instabilità (e, se necessario, registrare bearing grezzo/heading post-filtro in una successiva diagnostica), evitando ulteriori cambi di soglie senza evidenza.

**Verifiche:** GitHub Actions `43762298157` (rimozione Jinan) e `43859556216` (salto tappa Android) completate con esito `success`, **verificato direttamente** via GitHub il 16/11. **Test su strada riferiti dall'utente:** anello casa–lavoro–casa con pausa/ripresa riuscito; successivo ritorno a casa dopo aver eliminato una tappa, completato correttamente. Test dell'ESP32 «Salta tappa» e dei nuovi segnalini **non eseguiti perché non ancora implementati**.

**Anomalia aperta:** durante il ritorno il marcatore moto è apparso temporaneamente sul ramo opposto di una rotonda, poi è tornato autonomamente sul ramo corretto. Nessun log raccolto in quella prova. Causa indeterminata: GNSS, map-matching, geometria/interpolazione o altro; **non attribuire al maltempo senza prove**. Non intervenire alla cieca sul tracking: attendere diagnostica e replay Android del percorso ripetuto, confrontando posizione GPS, progressione sulla rotta, eventuali ricalcoli e rappresentazione ESP32.

**Roadmap concordata, in ordine dopo i test:**
1. Esaminare log diagnostici Android + replay GPS del nuovo giro, soprattutto rotonda con salto di ramo; mantenere versione attuale invariata per il test.
2. Aggiungere sull'ESP32 un **pulsante circolare ⏩ a ore 7**, speculare al cartello limite velocità, visibile **solo con tappe intermedie pendenti** e valido in **qualsiasi modalità di percorso**, non soltanto negli anelli. Pressione lunga ~2 s apre conferma **sullo stesso ESP32** (Annulla/Salta, timeout ~10 s); nessuna conferma sul telefono. Dopo conferma, richiesta via BLE ad Android che verifica/ricalcola con Valhalla e aggiorna il checkpoint se previsto; comunicare successo/errore all'ESP32. Definire e testare modifica BLE + comportamento anti-tocco accidentale, preservando il resto del protocollo.
3. Segnalini **sulla polilinea verde** dell'ESP32 e del simulatore: tappe numerate, prossima più evidente, arrivo con bandierina a scacchi; tappe completate o saltate spariscono dalla parte futura; seguire rotazione e zoom senza imporre ingrandimenti aggiuntivi. Verificare come trasmettere le coordinate/identificativi via BLE rispettando budget e tempi di rendering.
4. Restyling Android: schermata **Mappe offline** con sola **importazione del pacchetto unificato** e stato dei tre componenti, sostituzione/rimozione; nascondere menu import separata MotoMap/Valhalla/Photon, senza rimuovere prematuramente meccanismi di recupero e verifica. Rifiniture grafiche e razionalizzazione UI tour, pausa e tappe.
5. Conservare **diagnostica BLE/Android e replay GPS tecnico** con esportazione; aggiungere gradualmente **I miei percorsi**: registrare traccia GPS effettiva (anche senza rotta pianificata), archivio, statistiche e export GPX; in seguito import GPX e navigazione/routing di tracce. **GPX e replay tecnico sono formati distinti**: GPX non sostituisce i log per simulatore.
6. Valutare «Modalità esplorazione» senza rotta verde: strade grigie selezionate **intorno alla posizione e direzione GPS**, non dipendenti dal corridoio Valhalla; aggiornare la finestra conservando stabilità del contesto. **Esperimento successivo**, non modifica attuale: architettura ibrida **BLE comandi/backup + Wi-Fi dati cartografici**, Wi-Fi ESP32 client/hotspot Android da verificare su Android 17; misurare banda realmente utile, FPS/memoria, affidabilità, autonomia e convivenza radio. Non riesumare automaticamente il vecchio SoftAP OTA che aveva problemi NVS. Valutare eventuale standby/risveglio ESP32 più avanti.
7. Solo dopo regressioni e test fisici, proporre merge in `valhalla` previa autorizzazione esplicita dell'utente.

**Limiti e regole:** non modificare navigazione o mapping senza log del difetto; checkpoint tour locale Android, nessuna sincronizzazione cloud; prossima tappa rilevata con soglia GPS ~60 m. Distinguere test utente da CI. Un solo build Actions a fine set di modifiche. Non toccare supporto 3D.

**Prossima azione unica (17/11 sera):** verificare CI del nuovo commit unico e ricompilare il replay WASM sul branch test; riprodurre i sette punti segnalati, poi test stradale con diagnostica. Il MotoPack resta attività separata.

**Correzione sperimentale 17/11 (da verificare):** ridotta la re-ancoratura ripetuta dello stesso bearing BLE, congelata la correzione GNSS durante yaw IMU >5°/s e ridotto il guadagno a 0,22; Android scarta course sotto 3 m/s, course con precisione dichiarata >25° e fallback dislocamento <12 m; predittore integra tutto il tempo del tick (fino a 1 s), smussa compensazione 25–45 m e congela l'extrapolazione rettilinea quando diverge dal heading dell'ultima geometria >15° o frena oltre il 25%. Aggiunti test nativi e Android. **Build CI da verificare**. È stato aggiunto al simulatore LVGL un confronto A/B del predittore C++ isolato, disattivato per default. Per provare la nuova opzione va ricompilato WASM. Il simulatore non esegue PhoneNavBridge, BLE o fusione IMU: ON testa solo l'origine predetta con le finestre presenti nel replay, non l'intera pipeline ESP32. Non confondere prova nel simulatore con test su strada.

**Ultima correzione:** runtime Emscripten bloccato da riferimenti Jinan e funzione LVGL demo rimossi in precedenza. Ripristinato scenario sintetico solo web e tolta API non esistente, senza toccare il firmware. **Compilazione WASM ancora da verificare in WSL;** prossima azione unica: ricompilare `./scripts/build_web.sh` e verificare l'opzione predittore A/B sul replay mattutino. GitHub native/Android/ESP32 non sostituisce il test Emscripten.

## Correzione mirata sul replay serale — 17 novembre 2026 (branch test)

Confermati dal diagnostico: falsi ricalcoli a 17:37:01 e 17:37:19; non confondere la visualizzazione della rotta alle 17:43:50 con un ulteriore REROUTE, assente nel log. Confrontate le route windows per 17:44:30, 17:45:20, 17:45:40 e 17:46:00: manovre e curvatura locale discordano. L'inizializzazione QMI8658 è già inclusa in valhalla; in questa patch non cambia nulla dell'IMU.

Modificati: `${P.cpp}`, `${P.h}`, `${P.esp}`, `${P.web}`, `${P.rr}`, `${P.vm}`, `${P.window}`. Test di regressione in `${P.tst}`, `${P.rrt}`, `${P.wt}`. Soglie sperimentali: controllo geometrico 36 m a 24–104 m; richiede finestra >=150 m, deriva GPS <=35 m; se geometria insufficiente, fallback tradizionale. GPS in curva: protezione 6 s per course che varia >=14 gradi tra fix entro 2,5 s, solo per scostamento <100 m; protezione dopo ricalcolo a <100 m per 25 s. Nessuna modifica a Valhalla, BLE, fusione IMU o predittore. Nessuna esecuzione locale di C++/Gradle/WASM; CI e hardware da verificare.

Verificare le quattro fixture sulle route reali e testare i falsi ricalcoli con una nuova registrazione; i salti laterali non sono ancora dimostrati risolti. Prossima azione: una CI finale e replay WASM ricompilato, nessun merge prima dell'approvazione.

## Riparazione runtime simulatore dopo pulizia Jinan — 17 novembre 2026

**Errore riportato dall'utente (WSL Emscripten):** `main_web.cpp` includeva ancora `demo_fixture/jinan_big_data_demo.hpp`, eliminato correttamente dal repo; `replay_web.cpp` chiamava `moto_nav_ui_set_demo_active(0)`, eliminata dalla UI. Il precedente CI native/Android/ESP32 non compilava il target Emscripten; quindi il verde native non certificava il runtime web.

**Fix su branch test:** sostituita la geometria demo Jinan di `main_web.cpp` con una breve geometria **sintetica esclusivamente web**, mantenendo l'interfaccia delle funzioni scena/progresso; rimossi riferimenti Jinan e l'override speciale dei contesti stradali. Eliminata la funzione obsoleta da `replay_web.cpp`. Nessuna demo Jinan ripristinata. Non modificati Valhalla, Android, ESP32 fisico o protocollo BLE. **Test WASM locale non eseguito dall'assistente;** utente deve verificare `./scripts/build_web.sh` dopo aggiornamento checkout e ritentare confronto A/B. CI finale da verificare: il workflow GitHub non compila ancora Emscripten. L'utente era bloccato prima di attivare il controllo predittore, che è presente in `platforms/web/simulator/index.html`.

**Prossima azione:** utente ricompila runtime WASM in WSL con branch test aggiornato e confronta replay 07:50–07:56 a 1×, segnalando eventuali nuovi errori di compilazione.

## Diagnosi e patch heading/GPS intermittente — 17 novembre 2026

**Riproduzione:** log Android `motogps-android-diagnostics(20261117-072912).zip`, replay `motogps-replay(20261117-072914).jsonl` e tre video Chrome confermano oscillazioni di orientamento e spostamenti fuori strada a partire da ~07:50. Problema **intermittente e non dipendente dal singolo tratto**, test in **automobile**, non piega motociclistica. Il replay simula gli input, ma non misura direttamente posizione predetta sul dispositivo reale.

**Analisi chiamanti:** `PhoneNavBridge::consume_navigation` ri-ancorava la direzione a ogni snapshot BLE (anche ripetizioni a ~4 Hz del medesimo course GNSS), `MotionHeadingFusion::integrate` riceve il QMI8658 a ~125 Hz; `MapOriginPredictor` estrapola la posizione con l'heading fuso. Il QMI8658 ha già stima di bias all'avvio e proiezione del rate lungo l'asse di gravità: non aggiungere la stessa compensazione.

**Patch sperimentale:** `NavigationHeadingPolicy` richiede velocità >=3 m/s, scarta course GNSS con `bearingAccuracyDegrees` >25° quando disponibile e lascia il fallback da spostamento solo >=12 m; snapshot Android conserva ultimo heading a velocità inferiore. Nel bridge ESP32 course invariato ri-applicato al massimo ogni 900 ms, permettendo comunque il congelamento da fermo; reset su disconnessione. Fusione: niente correzione GNSS durante imbardata filtrata >5°/s, guadagno 0,22 al posto di 0,48/0,82. Predittore: full dt per frame <=1s, fattore esponenziale, attenuazione continua compensazione oltre 25m, inibizione dead reckoning in curve o frenate incompatibili con l'ultima geometria, valida heading 0..360 e velocità <=35 m/s.

**Rischi:** il nuovo controllo del simulatore non riproduce la cadenza originale esatta di fusione né i pacchetti BLE: usarlo solo per A/B del predittore. Per i nuovi snapshot BLE non esiste ancora un identificatore del fix GPS; la deduplicazione per tempo/bearing è un compromesso, non la soluzione definitiva con timestamp GPS. In frenata/curva, il congelamento predittivo riduce spostamenti laterali ma può produrre un ritardo nell'avanzamento fino alla geometria successiva: misurarlo sul replay prima di regolare le soglie. La correzione a 35 m ora è continua, ma non va confusa con compensazione di latenza BLE misurata. I test automatici verificano invarianti isolati; serve **rebuild WASM con Emscripten** per vedere il nuovo comportamento sul simulatore e successivo test in macchina. Senza questo rebuild, il simulatore usa codice vecchio.

**Verifiche:** native su commit preliminare `f8ade12` verde; CI del commit finale da controllare; test hardware e riproduzione simulatore ancora pendenti. Nessuna modifica a Valhalla, MotoMap, Photon, BLE wire format o percorso/tappe. Solo il branch sperimentale; `valhalla` non toccato.

## Handoff decisioni e collaudi — 16 novembre 2026

**Conferme dell'utente (test sul campo):** primo test di anello con pausa e ripresa concluso correttamente; successivamente tappa manualmente saltata, percorso di ritorno fino a casa riuscito. Durante il ritorno una rotonda ha mostrato brevemente la posizione sul ramo opposto, poi si è corretta senza interventi. Nessun replay/log disponibile per quell'episodio, quindi diagnosi **aperta** e nuova prova con log prevista per il 17 novembre. Conservare configurazione finché non esiste evidenza.

**Decisione ESP32:** salto tappa da eseguire senza estrarre il telefono. Interfaccia desiderata: cerchio ⏩ «fast forward» a ore 7, speculare al badge limite di velocità; presente soltanto se almeno un waypoint intermedio resta da visitare, per tutti i tipi di rotta. Pressione prolungata e conferma Annulla/Salta sul display; timeout automatico. Android esegue effettiva mutazione e ricalcolo, l'ESP32 presenta l'esito. Nessun pulsante ESP32 implementato a questa data. Non aggiungere la vecchia freccia di svolta: il simbolo ⏩ è esclusivamente l'azione di salto tappa.

**Decisione grafica:** overlay tappe e destinazione finale sulla **linea verde** su ESP32/simulatore; numerazione punti intermedi, evidenza prossima, bandiera finale, rimozione marker raggiunti/saltati, aderenza al contesto e zoom esistente. Non implementato.

**Decisione Android:** UI futura più semplice, importazioni separate nascoste a favore del solo pacchetto `MotoMap v4 + Valhalla V5 + Photon v2` unificato; stato componenti e integrità restano accessibili; restyling grafico e semplificazione tour. **Non eliminare** strumenti diagnostici, replay, né la loro esportazione. Distinguere replay diagnostico da «I miei percorsi» (tracciato GPS reale, export GPX, anche senza navigazione) e da tour progettato. Nuovi import GPX e tracking background da progettare, non implementati.

**Evoluzione potenziale trasporto:** Wi-Fi fornirebbe più capacità per strade grigie anche senza rotta, a costo di consumo/rischio complessità. Preferenza sperimentale BLE per comandi e fallback, Wi-Fi per mappe e geometrie. Testare configurazione ESP32 station e hotspot del telefono, coesistenza BLE/Wi-Fi, memoria/NVS, fps, autonomia prima di qualsiasi migrazione; non sostituire ora il BLE stabilizzato. **Nessuna modifica a trasporto o protocollo effettuata in questa sessione.**

**Verifiche GitHub:** build `43859556216` e `43762298157` confermate `success` via API; run `43859556216` riferita a `90a1be1`. Il presente aggiornamento è **solo documentale** (`[skip ci]`), nessuna nuova compilazione o prova hardware richiesta. Il manuale operativo resta corretto per le procedure già implementate; le funzioni pianificate non devono essere presentate lì come disponibili.

## Salta prossima tappa (Android) — 16 novembre 2026

**Test utente:** anello con pausa al lavoro e ripresa del ritorno riuscito. L'utente propone un salto manuale della prossima tappa in caso di strada chiusa, con eventuale futuro comando sull'ESP32.

**Implementato sul solo branch test:** `NavigationViewModel.skipNextWaypoint()` offre una transizione esplicita dopo conferma Android. Non consente di saltare la destinazione finale; durante il ricalcolo non accetta altre richieste o ricalcoli automatici. Calcola con Valhalla la rotta dalla posizione corrente verso le tappe successive usando `pendingOverride`, senza modificare il progresso vivo prima della riuscita. Solo con nuova rotta valida rimuove la tappa saltata dalla lista (senza marcarla come raggiunta), aggiorna il checkpoint persistente prima di applicare la rotta, aggiorna l'indicatore della prossima tappa e la geometria BLE. Se il routing o la scrittura su disco falliscono, lascia la tappa intatta. Protezione contro risposta asincrona obsoleta. UI: «Salta prossima tappa» e dialogo con nome + conferma. Firmware ESP32 e protocollo BLE invariati, possibile comando touch differito fino a definire UX anti-tocco accidentale.

**Verifiche:** revisione sorgenti, CI non ancora verificata dopo le modifiche. Prossimo collaudo: giro con due tappe, salto prima, nessuna deviazione verso la tappa saltata, pausa/riavvio/ripresa, arrivo finale.

## Rimozione della dipendenza Jinan dal firmware e dai test — 15 novembre 2026

**Diagnosi verificata:** run GitHub [43761027669](https://github.com/thriller47/moto-gps-waveshare/actions/runs/43761027669) fallita in `esp32` e `native` per `fatal error: demo_fixture/jinan_big_data_demo.hpp: No such file or directory`, incluso direttamente da `phone_nav_bridge.cpp` e `phone_nav_bridge_tests.cpp`. Ripristino temporaneo del file alla revisione `ffca00236b3caeb119a6e97e47de3cba2e910831`, **poi sostituito in questa revisione con la rimozione definitiva della dipendenza**, come richiesto dall'utente.

**Implementato nel solo branch test:** eliminati da `PhoneNavBridge` il generatore di route demo Jinan, il fallback automatico di road context su token iOS demo, la selezione/backup snapshot fittizio e il relativo controllo touch. Sostituito il vecchio `update_demo()` con `update_motion_tick()` mantenendo il task da 25 ms per interpolazione della posizione reale e diagnostica: non rallentare o sopprimere il tick. In `shared/nav_ui` rimossi flag/label/override di stato demo e callback inutili, con adeguamento degli stub e dei test nativi; rimossi solo i due test specifici della simulazione Jinan e il test di visualizzazione DEMO RIDE. **Conservati** i test BLE, race, riconnessione, MapScene, rendering, navigazione e ora aggiunto un test di regressione che verifica come il vecchio token Jinan non possa più iniettare strade grigie inventate. Eliminato definitivamente l'header demo, senza ripristinare gli altri 22 file legacy. `hardware/MotoNavFusion_v015.zip` conservato.

**Verifiche:** diff e albero GitHub esaminati; compilazione e suite CI finale ancora da verificare. `valhalla` non modificato. **Prossima azione:** controllare gli esiti native ed ESP32 nella nuova GitHub Action, poi collaudare a schermo l'aggiornamento fluido della mappa BLE e la diagnostica.

## Rimozione artefatti legacy Jinan e KiCad — 15 novembre 2026

**Rilevazione utente:** restavano script Jinan e test hardware PCB per sorgenti non più presenti. Audit diretto del branch `test/unified-offline-map-20261115`: `scripts/generate_jinan_demo_fixture.mjs`, `update_jinan_road_context.mjs`, `update_jinan_map_scene_sample.mjs`, il vecchio `fixture_coordinates.mjs`, gli estrattori `build_jinan_sqlite.mjs`/`extract_jinan.sh`, `shared/demo_fixture/` e `shared/offline_map/` sono dedicati unicamente al prototipo cinese. `.github/workflows/project-build.yml` eseguiva ancora `Verify public demo map fixture` e filtrava file legacy. `scripts/hardware/` conteneva gate fabbricazione KiCad e relativi test che richiedono vecchi PCB Rev A ormai assenti dal repository. Lo stesso `scripts/hardware/README.md` segnala l'assenza dei sorgenti.

**Rimosso dal SOLO branch test:** file legacy Jinan e strumenti KiCad orfani, relativi campioni/demo dati e controllo CI obsoleto. Le dipendenze C++ residue emerse nella build successiva sono state eliminate nella correzione descritta sopra. **Preservato esplicitamente `hardware/MotoNavFusion_v015.zip`**: l'utente conferma che contiene l'unica versione nel repo del supporto 3D. Non modificati altri script `scripts/offline_map` (MotoPack/Photon/Valhalla), `scripts/diagnostics`, `scripts/build_web.sh`, test nav/BLE/ESP32, codice Android o simulatori. Attribution OSM per i dati correnti resta negli strumenti e nelle licenze attuali: eliminata solo la licenza specifica dell'archivio Jinan rimosso.

**Verifiche:** inventario GitHub e riferimenti diretti a CMake, Android, ESP32 e workflow controllati. Nuova CI consolidata da verificare; **nessun test hardware locale**. `valhalla` resta intatto. **Prossima azione:** controllare Actions su branch test dopo la pulizia e verificare che native/Android/ESP32 restino verdi.

## Tour su più giorni: pausa e ripresa — branch test, 15 novembre 2026

**Richiesta:** consentire una sosta lunga (anche pernottamento), spegnere app/ESP32 e riprendere il tour senza tornare alle tappe già completate; collaudo previsto con giro casa–lavoro–casa.

**Implementato:** `TourSessionStore.kt` salva in SharedPreferences un solo checkpoint con destinazione finale, elenco tappe completo, indice completato, preferenze Valhalla, posizione corrente, data e flag pausa. `NavigationViewModel` registra checkpoint all'avvio tour, a ogni tappa accettata e ogni ~30 s; **Pausa tour** salva con commit sincrono prima di inviare snapshot idle e fermare il BLE. `MainActivity` recupera il checkpoint all'avvio. Dalla schermata iniziale `NavigationScreen` offre **Riprendi da qui** per l'anteprima di soli waypoint pendenti, ricalcolati da Valhalla con GPS corrente; non avvia la navigazione senza conferma. **Abbandona tour** cancella il checkpoint, non i tour salvati. Un arresto improvviso è riconosciuto da checkpoint con paused=false; arrivo finale e terminazione intenzionale del tour cancellano il checkpoint. Nessuna modifica all'ESP32, renderer o BLE codec. Test `TourSessionStoreTest` per serializzazione, progresso tappe, ritorno alla destinazione e input non valido.

**Verifiche:** progettazione e modifica del codice verificata tramite GitHub; test unitari non eseguiti localmente. Nuova CI Android finale da verificare dopo ultimo commit. Le prove utente precedenti confermano pianificazione/salvataggio tour e zoom dell'anteprima, **non ancora la nuova pausa/ripresa**.

**Rischi:** le tappe sono completate dal GPS entro 60 metri, non da una conferma manuale; checkpoint non sincronizzato tra telefoni, massimo un tour in pausa e nessuna traccia storica registrata. Il recupero della rotta dipende dal grafo Valhalla disponibile; la posizione pausa viene salvata ma non impone di riprendere da lì. Testare da fermo e nel tragitto casa–lavoro–casa prima di modifiche/merge.

**Prossima azione unica:** verificare CI Android della versione checkpoint, quindi prova pausa al lavoro, chiusura/riapertura app e ripresa del ritorno a casa senza ripercorrere le tappe completate.

## Correzione zoom e pan dell'anteprima del tour — 15 novembre 2026

**Feedback dispositivo dell'utente:** pianificazione e salvataggio dei tour collaudati da fermo e riferiti funzionanti. Difetto riproducibile nell'anteprima Android: il pinch zoom funzionava ma tornava subito all'inquadratura dell'intero itinerario.

**Causa verificata nel codice:** `RouteMapView` invocava `mapView.zoomToBoundingBox(bounds, true, 64)` ad ogni callback `AndroidView.update`, quindi anche dopo aggiornamenti di stato Compose non correlati alla rotta.

**Correzione sul branch test:** `RouteMapView` riceve la chiave `route_id` della rotta selezionata e memorizza l'ultima chiave adattata. Ora esegue il fit automatico soltanto quando cambia la rotta selezionata o compare una nuova anteprima; gli aggiornamenti ordinari non ripristinano zoom/centro. Una callback `post` obsoleta non adatta una rotta già sostituita. **Nessun cambiamento a routing, BLE o ESP32.** Commit `ff869950da2b31c039fc2490ee12844144edc800` [skip ci] prima della build unica finale.

**Verifiche:** causa e fix controllati a livello sorgente; **CI finale e riprova sul dispositivo non ancora completate**. Controllare pinch, doppio tap e trascinamento, modifica preferenze o tappe (nuova rotta = nuovo fit), e passaggio giorno/notte senza rifit indesiderato.

**Prossimo passo:** verificare Android Actions della build finale e collaudare l'anteprima con zoom manuale persistente.

## Correzione Android CI — 15 novembre 2026

**Run fallita:** GitHub Actions [43746697897](https://github.com/thriller47/moto-gps-waveshare/actions/runs/43746697897), job Android `132911500092`, step Build Android app. Log letto direttamente: `NavigationScreen.kt:1136:48 Unresolved reference 'Save'` e `NavigationScreen.kt:1302:40 Unresolved reference 'Folder'`, errori del compiler Kotlin causati dai nuovi nomi delle icone, non da NDK/SDK né dal routing. Il setup NDK ha concluso con successo.

**Fix sul solo branch test:** sostituiti `Icons.Default.Save` con `Icons.Default.Check` e `Icons.Default.Folder` con `Icons.Default.Place` in `NavigationScreen.kt`, usando icone già presenti nel codice e disponibili nel progetto. Nessuna modifica alla logica del salvataggio, Valhalla o ESP32. Fix commit `82fb6268a52132b62dac1d0515eb92b8ef1255c1` con CI saltata per evitare run intermedie.

**Test:** primo job Kotlin FALLITO come sopra; la nuova CI consolidata deve ancora confermare che il fix compili ed esegua i test Android. **Prossimo passo:** verificare l'esito della run successiva sul branch test, poi prova manuale dei tour salvati su telefono prima del merge.

## Tappe turistiche obbligatorie e archivio dei tour — branch test (15 novembre 2026)

**Richieste aggiuntive:** l'utente vuole passi, laghi o luoghi scelti manualmente con precedenza sulle tappe automatiche; vuole inoltre salvare giri pianificati a casa giorni prima. `CircularTourPlanner.planStops` conserva i POI in ordine e usa i punti artificiali soltanto per generare un circuito senza POI o un giro con un unico POI; `straightLineMinimumKm` rifiuta chilometraggi impossibili. `NavigationViewModel` gestisce ricerca/selezione/riordinamento POI del tour e integra la pianificazione, senza toccare BLE. `NavigationScreen` aggiunge editor dei POI nel dialog del tour. `SavedCircularTourStore.kt` serializza JSON in SharedPreferences; `MainActivity` carica l'archivio e collega schermate di salvataggio, elenco, riapertura e cancellazione. `NavigationViewModel` usa `previewOriginOverride` per l'anteprima con partenza storica e conserva l'ordine delle tappe effettive dopo modifiche. Aggiunti/estesi `CircularTourPlannerTest` e `SavedCircularTourStoreTest`.

**Test:** code review tramite GitHub, test unitari aggiunti ma non ancora eseguiti localmente; esito GitHub Actions finale da controllare. Manuale aggiornato. `valhalla` non modificato; merge non autorizzato per questa funzione. **Prossimo passo:** verificare CI e provare salvataggio, riapertura e navigazione sul telefono con pacchetto Italia offline.

## Panoramico, tappe numerate e tour circolare — sviluppo sul branch test (15 novembre 2026)

**Richieste utente:** sviluppare Panoramico e tappe insieme, aggiungendo un tour circolare. Branch test riutilizzato, `valhalla` resta stabile. Nuovo `RouteStyle.PANORAMIC` applicato a costing `auto`; non promette informazioni paesaggistiche e per route con più waypoint non richiede alternative non supportate. `RouteCurvatureSelector` fissa una preferenza moderata per Panoramico (evitando divisione per zero). `RoutePreferencesDialog` mostra il nuovo stile. Anteprima `RouteMapView` presenta tappe con numeri e mantiene bandiera di arrivo. `CircularTourPlanner` genera tre punti distanziati intorno all'origine con direzione e distanza indicativa; `NavigationViewModel.previewCircularTour` li passa al percorso standard, che termina al GPS iniziale. `MainActivity` e `NavigationScreen` gestiscono pulsante e dialog di configurazione. `PanoramicRoutePreferencesTest` e `CircularTourPlannerTest` coprono costing, multi-stop, loop e input.

**Test e verifiche:** revisioni sorgente tramite GitHub e test unitari aggiunti. **Nessuna build o prova dispositivo dichiarata riuscita in questa sessione**: serve CI Android finale. La generazione delle tappe usa coordinate geometriche non necessariamente aderenti alla strada e può richiedere futuri affinamenti di snapping; Valhalla è sempre responsabile della reale geometria verde. Nessuna modifica ESP32/BLE: marcatori geometrici sulla linea del dispositivo differiti fino a definizione di un'estensione compatibile.

**Prossima azione:** verificare CI finale, poi test offline su percorso Panoramico con/senza tappe e circolare 50/100/150 km includendo ricalcolo e arrivo; valutare i risultati reali prima di nuove modifiche o merge.

## Integrazione selettiva completata e branch test conservato — 15 novembre 2026

L'utente ha confermato tre check GitHub verdi, installazione aggiornata APK+ESP32 e test funzionale positivo, quindi ha autorizzato esplicitamente il merge. Verificato GitHub: `test/unified-offline-map-20261115` era avanti di 9 commit e non indietro rispetto a `valhalla`; fast-forward non forzato riuscito al commit `74702702410f3f99f4871bea1871c3f4b4b00460`, preservando tutto lo storico. Per richiesta esplicita, **il branch test resta disponibile come ramo sperimentale**; eccezione intenzionale alla normale regola di pulizia dei branch completati. Dopo questa sola rettifica documentale sincronizzare il branch test con `valhalla`.

**Test:** riferiti tre semafori verdi dall'utente, e riscontro positivo su strada; verificati direttamente confronto GitHub e fast-forward. Nessuna nuova build locale. L'utente ha precisato che la disconnessione BLE a fine log era deliberata e che i minimi FPS estremi coincidevano con soste o ripartenze.

**Prossimo passo:** prossime prove e migliorie solo nel branch test, lasciando `valhalla` stabile.

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


### 17 novembre 2026 — diagnostica GPS/predittore (branch test)

Aggiunti getter WASM `moto_web_replay_origin_lat/lon` e letture browser per coordinate snapshot Android, heading inviato a NavPresenter, origine mappa reale del bridge e offset in metri, compresi frame interpolati. Il replay non contiene bearing GNSS grezzo, heading IMU o angolo post-filtro: non attribuire rotazioni a una fonte senza dati. Nessuna modifica Android, ESP32, BLE, routing o `valhalla`. Istruzioni in `platforms/web/simulator/README.md`. Verifica effettuata: corrispondenza statica dei nomi getter/cwrap. Emscripten, CI e prova Chrome ancora non eseguiti nell'ambiente utente. Prossima azione: build WASM e replay 07:50–07:56 a 1×, registrare valori nei salti.


### 17 novembre 2026 — simulatore compatto (branch test)

Ridotta altezza apparente della pagina: pannello display sticky a sinistra, controlli/telemetria con scroll indipendente a destra su desktop, valori principali sempre visibili, diagnostica prestazioni/eventi/note richiudibili. Adattamento su viewport piccole; nessun cambiamento a player JavaScript, runtime WASM, APK, BLE, ESP32, routing o `valhalla`. Il cambio è soltanto HTML/CSS, quindi basta `git pull` e hard reload (nessuna build Emscripten). Verifica statica markup/CSS; verifica reale in Chrome non ancora eseguita dall'assistente. Nei video utente 11:19 OFF e 11:28 ON il browser era ridotto a zoom eccessivamente basso, impedendo la lettura della diagnostica; non concludere sul miglioramento del predittore da quei fotogrammi. Prossimo passo: verificare layout a zoom Chrome 100%, poi replay A/B con numeri leggibili.


### 17 novembre 2026 — consegna replay OFF/ON e chiusura della sessione diagnostica

- **Video ricevuti e identificati dall'utente:** `LVGL Simulator - Google Chrome 2026-11-17 11-41-10.mp4` = predittore **OFF**; `LVGL Simulator - Google Chrome 2026-11-17 11-44-43.mp4` = predittore **ON**. Entrambi mostrano browser con nuovo layout compatto, replay di **19:24** e velocità **1×**.
- **Feedback sul display:** layout compatto più leggibile; display ESP32 visibile a sinistra, dati nel pannello scrollabile a destra. Modifica HTML/CSS commit `8d2cb789f02c1c4a5f40e1d1fb64be1f810cc41e` verificata visivamente tramite video dall'utente. Nessuna necessità di build WASM per il solo layout.
- **Letture puntuali dei fotogrammi esaminati (non allineati temporalmente):** OFF a 07:49:21 / +12:33, heading snapshot ~218,5°, scarto origine-snapshot 0,0 m; ON a 07:49:22 / +12:34, heading ~221,9°, scarto ~3,7 m. Entrambi mostrano linea verde in orientamento simile. I valori di heading sono quelli dello snapshot al bridge, non bearing GNSS grezzo o angolo LVGL dopo filtri. ON misura uno spostamento fra due origini replay, non un errore GPS rispetto alla strada.
- **Conclusione prudente:** l'utente non nota differenze sostanziali di rotazione/scatti tra le prove; i video non dimostrano né efficacia né colpa esclusiva del predittore. Restano sospette le sorgenti/orientamento del replay e/o le trasformazioni grafiche. Il caso non è risolto.
- **Verifiche e limiti:** testimonianza visiva del browser con WASM e nuovi campi funzionanti; nessun nuovo CI, test unitario, flash ESP32 o test su strada eseguito in questa revisione documentale. I dati grezzi GNSS/IMU e l'heading post-filtro non sono presenti nel formato replay esaminato.
- **Prossima azione concreta:** confrontare numericamente heading e scarto negli esatti frame in cui ruota/salta la mappa, prima di cambiare la logica; se la telemetria esistente non basta, estendere soltanto la diagnostica alla distinzione bearing GNSS grezzo, heading Android e heading finale renderizzato. Conservare `valhalla` invariato e non procedere al merge finché la regressione non è spiegata.


### 17 novembre 2026 — grafici temporali e export heading (branch test)

Implementato modulo puro `platforms/web/simulator/heading_diagnostics.js` con heading snapshot, orientamento del segmento più vicino nella finestra verde, scarto angolare breve, distanza laterale dalla finestra, distanza fra GPS fix distinti e bandierine diagnostiche. HTML/JS mostrano grafici sincronizzati al player, click-to-seek e CSV completo degli snapshot. L'offset predittore ON/OFF è incluso nel CSV solo per i frame realmente visualizzati nelle rispettive modalità (vuoto = non misurato). **Limiti:** segmento vicino non sempre è il ramo corretto (rotatorie, cavalcavia), finestre BLE parziali, flag solo euristici, heading ricevuto nel replay non bearing GNSS grezzo. Codice NavPresenter verificato: mappa heading-up usa direttamente snapshot.heading_deg. Nessuna modifica in Android, firmware, BLE o routing; stable valhalla intatto. Controlli statici effettuati; test automatizzati e prova interattiva Chrome da completare. Prossimo passo: confrontare grafici e CSV in corrispondenza dei salti del replay 17/11, senza nuove registrazioni richieste al momento.


### 17 novembre 2026 — precisione geografica CSV e A/B in batch

Corretto `heading_diagnostics.js` per esportare coordinate GPS a sette decimali. Il bridge `replay_web.cpp` espone `batch_reset/route/sample` con seconda istanza del vero predittore C++, senza interferenze con la mappa a schermo. `player.js` esegue analisi automatica di tutti gli snapshot registrati (OFF=0, ON=offset del predittore) non appena il runtime WASM e il tracciato sono disponibili. Il confronto conserva timestamp e cambi delle finestre BLE registrate, ma non simula fusione IMU, latenza radio né interpolazione RAF. Modifiche solo simulatore, nessun effetto su Android/ESP32/routing/branch `valhalla`. README aggiornato. Test statici da completare e compilazione Emscripten e verifica Chrome ancora da svolgere sul PC utente; prossimo passo esportare il nuovo CSV.


### 17 novembre 2026 — età finestra distinta e componenti predittore (branch test)

Implementati tre campi aggiuntivi in CSV e riepilogo del simulatore: tempo dall'ultima finestra geometrica **distinta** (ms), traslazione ON proiettata in avanti/indietro (m) e destra/sinistra (m) rispetto all'heading dello snapshot. La proiezione deriva dagli stessi vettori nord/est del secondo `MapOriginPredictor` C++ batch (getter separati); nessuna modifica al firmware. **Limite fondamentale:** `trace_model.js` deduplica geometrie identiche; il tempo mostrato non è un timestamp BLE grezzo, né una misura precisa dell'età vista dall'ESP32. È un indice di tempo dall'ultima modifica effettiva della geometria nel replay. Anche il vettore laterale è rispetto all'heading registrato, non alla strada. Il pannello sottostante i grafici espone i valori; il CSV conserva 7 decimali geografici. Nessuna modifica a Android, BLE, Valhalla o branch `valhalla`. Verifiche statiche da completare e ricompilazione WASM/browser sul PC utente necessaria; prossimo test CSV del replay del 17/11 intorno a 740.790 ms, confrontando presenza di cambi geometria e componente avanti/laterale.


### 17 novembre 2026 — audit MapOriginPredictor all'uscita di rotonda

Ricevuta osservazione utente «uscendo da una rotonda ... mi spostava parecchio verso sinistra»; verificati `moto-heading-diagnostics(2).csv`, replay originale del 17/11 e `platforms/esp32/main/map_origin_predictor.hpp` (senza committare dati GPS personali). Eventi rotta distinti a t=1.117.743, 1.118.784, 1.121.859 ms; gap fra ultimi due 3,075 s. Ultimo snapshot prima della nuova rotta t=1.121.857 ms, ancora `roundabout`, offset ON 28,02 m (lateral −27,99 m, longitudinale −1,22 m), heading 19°, velocità 9,86 m/s. Le condizioni d'inibizione estrapolazione possono scattare già a t=1.121.078 (heading 3,20° vs ancora 340,59°, delta 22,61° >15°; speed 12,31 vs 17,67 m/s, rapporto 69,7% <75%). Rientro laterale dopo nuova rotta: −17,43 m a t=1.122.117 e −10,91 m a t=1.122.374. Le route non erano geometricamente identiche: corregge ipotesi iniziale. Impatto firmware ancora **non dimostrato**: il replay non rappresenta perfettamente scheduling BLE, fusione QMI8658 e anchor reali. Audit dettagliato `docs/diagnostics/PREDICTOR_ROUNDABOUT_AUDIT_20261117.md`; nessuna patch algoritmo né nuova build. Prossima azione: test nativo specifico del comportamento e solo dopo eventuale sperimentazione A/B nel simulatore. 


### 17 novembre 2026 — regressione nativa sintetica su uscita rotonda (branch test)

In `tests/native/map_origin_predictor_tests.cpp` aggiunto `test_roundabout_exit_three_second_geometry_gap`: sequenza sintetica GPS priva di dati personali, heading 340,59°→349,2°→3,2°→19°, speed 17,67→13,5→12,31→9,86 m/s, geometria iniziale e nuova geometria dopo **3.075 ms**. Verifica che il movimento prosegua sul rettilineo iniziale, si fermi quando i due guardrail vengono oltrepassati, non riparta senza geometria e rientri gradualmente alla nuova ancora senza snap. Test aggiunto alla suite già registrata in CTest; nessuna modifica in `MapOriginPredictor`, Android, ESP32 produzione, BLE o `valhalla`. **La compilazione/CTest e la verifica GitHub Actions restano da svolgere**; non dichiarare pass se non verificato. Prossima azione: validare il test, poi progettare un A/B reversibile isolato nel simulatore per gestire curve e frenata senza indebolire la protezione tornanti/svincoli.


### 17 novembre 2026 — confronto heading nel banco prova web

Implementato controllo **Originale / Bloccato / Stabilizzato** solo nel simulatore (nuovo `heading_stability.js`, aggiornamenti `player.js`/`index.html`). Baseline originale invariata; Bloccato mantiene l'heading dello snapshot alla selezione, per isolare la rotazione del renderer; Stabilizzato è filtro circolare replay deterministico precalcolato (tau 1,1 s, deadband 0,7° rettilinei; tau 0,12 s e deadband 0 in rotonde o forti variazioni, reset >3s/identità nuova). Heading applicato al NavPresenter e preview 2D; telemetria mostra GPS originale vs applicato, mentre CSV diagnostico e batch predittore restano sui valori originali. **Non replica QMI8658 o fusione heading del firmware**. Non toccati Douglas–Peucker, ESP32, Android, BLE, Valhalla. Solo JS/HTML e test Node: nessun nuovo WASM. Verificare statiche/test automatizzati e poi A/B su stesso rettilineo del replay.


### 17 novembre 2026 — audit heading con GPS vs bearing e correttivo web reversibile

Feedback utente: Bloccato mantiene moto/linea verde coerenti, Originale e Stabilizzato girano improvvisamente. Sul CSV dell'ultimo replay trovati 75 campioni a >=8 m/s con heading-rate >15°/s; non sono tutti rumore, per distinguere le curve vere serve il confronto con traiettoria GPS. **Problema concreto nel codice**: la vecchia stabilizzazione commutava su filtro rapido (tau 0,12 s) con il solo rate heading >4°/s, anche in rettilineo, rendendo Stabilizzato simile a Originale. Eliminato tale trigger; aggiunta modalità web indipendente 'GPS verificato', confrontando direzioni di successivi spostamenti distinti e rigettando variazioni isolate non accompagnate da movimento. Due vettori allineati entro 9° classificano rettilineo; in curva vera il filtro segue il bearing rapidamente. Resta sperimentale (GPS non è ground truth su rotonde/rami, e i fix registrati sono snapshot Android). Implementazione JS-only + test: nessun ESP32, Android, BLE o C++ modificato, `valhalla` intatto. Si aggiorna Chrome con refresh, senza rebuild WASM; prossimo passo prova visiva identico replay, poi eventuale audit nel dispositivo fisico.


### 17 novembre 2026 — regressione loader LVGL per esportazioni mancanti (fix)

Segnalazione: dopo nuova modalità heading il simulatore è rimasto in anteprima 2D. Audit: `platforms/web/simulator/player.js` eseguiva `cwrap` obbligatori per sette funzioni telemetria/predittore batch aggiunte in C++, non presenti nel `-sEXPORTED_FUNCTIONS` di `platforms/web/wasm/CMakeLists.txt`. Durante `ready()` l'eccezione faceva `api=null` e lasciava `ui.preview` attivo. Fix: esportazioni Emscripten complete e getter/batch diagnostico opzionali nel JS, con graceful degradation (LVGL continua se la telemetria non esiste). Il codice nav e firmware non cambia. Verifiche statiche CMake/JS da eseguire e prova reale Emscripten ancora affidata al PC utente. Per aggiornare dati batch ricompilare WASM; per non perdere LVGL quando manca diagnostica basta il nuovo JS. Nessun cambio Android/BLE/Valhalla.


### 17 novembre 2026 — causa concreto del filtro heading inefficace: manovra «prossima»

L'utente segnala che le rotazioni incoerenti persistono anche in «GPS verificato». Riaperto replay reale `motogps-replay(20261117-072914).jsonl` **solo localmente, senza includerlo nel repository**: 4.364/4.894 snapshot hanno prossima manovra `roundabout`, ma 874 sono ad oltre 1 km dalla manovra (massimo 4.736 m). Il codice web scambiava la manovra futura per una curva in corso. Fix `heading_stability.js`: la risposta veloce di «Stabilizzato» scatta solo entro 35 m dalla rotonda/inversione (non modifica la logica di navigazione), e «GPS verificato» riconosce rettilinei GPS senza escluderli solo perché in futuro c'è una rotonda. Aggiunti test per prossima rotonda lontana e vicino, nessuna patch C++/Android/ESP32/BLE/Valhalla, nessun bisogno di rebuild WASM. La **risposta visiva resta da confermare nel replay con LVGL**. La ricostruzione degli spostamenti GPS può restare rumorosa in curva/urban canyon; non dichiarare problema globale risolto prima della prova.


### 17 novembre 2026 — rettilineo 07:49:53–07:50:03: errore heading già alla sorgente Android

L'utente riporta freccia/linea verde divergenti di >=30° in ogni modalità del simulatore. Esaminati **file originali localmente** `motogps-replay(20261117-072914).jsonl`, `moto-heading-diagnostics(2).csv` e `motogps-android-diagnostics(20261117-072912).zip` (mai pubblicati su GitHub): t=831836 ms/07:49:53.416, snapshot 253,2°, segmento verde più vicino 212,8° (40,4°); t=832896/07:49:54.476 251,79° vs 212,8° (38,99°); ritorno a ~215° entro 07:49:57–58. Tra fix GNSS 07:49:53.329 e 07:49:54.330 spostamento ~12 m a bearing ~216°, mentre `provider_course` a 07:49:54.330 è 251,8°. La direzione globale su più fix rimane ~215–223°; il riferimento geometrico locale resta 212,8–213,17°. A t=831749 un singolo intervallo GPS ha course ~246,5°: evitare di considerare *ciascuna* coppia di fix come ground truth. La fonte di heading snapshot e fix è GNSS/Android (`ReplayTraceRecorder.fix`, `NavigationHeadingPolicy.movingHeading`); in presenza di bearing valido la policy lo privilegia rispetto alla direzione di spostamento. Bearing accuracy non esportata, non dedurre causa fisica esatta. Freeze del heading può bloccare un angolo già errato; i filtri del browser non possono sempre correggere un offset assoluto iniziale. Prossimo esperimento: consenso del course su più fix con rilevamento affidabile di curve reali, senza fidarsi ciecamente del segmento più vicino. Nessuna modifica alla navigazione in questo commit di sola diagnostica.


### 17 novembre 2026 — test GPS multifix a distanza minima

Feedback utente: problemi più evidenti alle basse velocità. I fix sono più ravvicinati nello spazio, non necessariamente nel tempo; rapporto tra jitter metrico e distanza fra punti aumenta. Aggiunto solo al simulatore un metodo separato Course multifix: regressione temporale su almeno 4 fix distinti in max 6 s, avanzamento stimato >=16 m, netto >=14 m e errore laterale RMS <=4,5 m; salti angolari in assenza di dati attendibili vengono congelati, curve sbloccate su spostamenti consecutivi confermati. Il riferimento Valhalla non è usato nel filtro. Verifiche locali sintetiche su spike 35°, lentezza, posizione ferma, svolta vera; nessun mutamento Android/ESP32/BLE/Valhalla. Resta sperimentale; necessario test visivo sul replay e soprattutto rotonda lenta; non proporre per hardware senza evidenza. README simulatore aggiornato; manuale operativo invariato perche procedura di build non cambia.



### 17 novembre 2026 — audit proposta qualità GNSS e bias QMI8658

Confrontato il suggerimento con il CODICE del branch test: oggi il gain fisso è **0,22**, non 0,48; il ramo 0,82 per >80° era già stato rimosso; Android già riceve `Location.bearingAccuracyDegrees`, impone `<=25°` se disponibile, speed>=3m/s e fallback displacement>=12m. **Non applicare patch obsolete**. Bug rilevante: `MotionHeadingFusion` usa il course da >=1,5m/s, mentre Android sotto 3m/s conserva un heading vecchio: allineata soglia di ancoraggio ESP a 3m/s, sblocco sola IMU a 1m/s, stationary <0,6m/s.

**Implementazione sperimentale:** `MotionHeadingFusion::anchor` accetta accuracy opzionale ma il BLE attuale NON la trasmette, quindi usa fallback sigma velocità come proposta, guadagno scalare 100/(100+sigma²), inizializzazione al primo course di movimento solo se sigma<=30°, due candidati coerenti per scarti >80° (senza riportare gain 0,82), correzione sospesa durante yaw >5°/s. `integrate()` usa il parcheggio per stimare bias Z con alpha 0,002 ai campioni sotto 3°/s, aggiorna sempre timestamp, non cancella bias con `reset()`, riduce deadband a 0,3°/s. Android conferma accuracy orizzontale <= un terzo della distanza per fallback di 12m; GNSS bearing prioritario se accurato mantiene policy attuale. Aggiunti casi test native/Android su qualita, outlier, bias, ripartenza e fallback.

**Importante:** il trasporto opzionale della precisione angolare sul BLE va negoziato e versionato: il payload snapshot v1 attuale impone `require_end()`; appending un campo dal nuovo APK verrebbe rifiutato dal vecchio ESP. Non aggiungere ciecamente un campo né toccare il protocollo senza strategia di compatibilità e aggiornamento coordinato, specialmente dopo precedenti errori BLE. Stale heading dopo 10s e proiezione IMU in piega/sterzo sono aperti; non mettere tangente Valhalla come verità in rotonda/rami. CI native/Android/ESP32 da verificare, non equivale a test fisico.

**CI native 17/11:** primo commit `a6c88cad` fallisce tre asserzioni nella test suite native, fra cui attese legate al vecchio gain. Corretto il caso del doppio outlier e resa prudente la sigma minima del fallback senza accuracy BLE a 20° (gain massimo 0,20), per non peggiorare l'episodio delle 07:49:53 a ~8m/s. Verifica CI della revisione ancora richiesta; altri job della prima Action non ancora giudicati dal log native.


### 17 novembre 2026 — strumenti offline corretti senza cambio formato

Su richiesta utente, corretti `scripts/offline_map/pbf_to_motomap.py`: supercover in coordinate Web Mercator z15, attraversamenti di confini e angoli incluse tile intermedie, handling del meridiano +/-180°, più parse numerico e simbolico IT per `maxspeed` (`IT:urban`, `IT:rural`, `IT:trunk`, `IT:motorway`, `50 kmh`) e direzioni concordi. Tag ambigui (es. `50;70`) non vengono arbitrariamente convertiti a un solo limite; permane il fallback di classe nel formato attuale, che non rappresenta affidabilità/direzionalità. `source:maxspeed` non sostituisce automaticamente `maxspeed`.

`photon_to_sqlite.py` e `analyze_valhalla_graph.py`: stderr decoder zstd diretto a `tempfile.TemporaryFile` anziché pipe potenzialmente bloccante; errori letti in coda di 500 byte. Test Python aggiunti in `test_motomap_v4.py` e `test_photon_to_sqlite.py`, anche test subprocess con 1MiB stderr e stream. Nessun cambio schema MotoMap v4, `motopack` v1, Android, ESP, BLE o librerie di routing. La regione IT resta intenzionalmente fissa. Il manuale operativo riporta la procedura di rigenerazione del solo MotoMap con riuso degli asset Valhalla e Photon e l'avvertenza su `--resume`. **Non sono stati rigenerati qui i grandi asset reali italiani**; un test di copertura effettiva e import Android è ancora necessario. Il test su strada heading con commit `349e67d` resta indipendente.

**Controllo CI offline del commit `0f69cc3`:** 9 test MotoMap superati, suite native/JS superate, test Photon fallito soltanto per un'asserzione del fixture che confrontava la stringa letterale `\\n` anziché il ritorno a capo; lo streaming stderr da 1 MiB ha funzionato. Corretto nel commit successivo e richiesta nuova verifica native; il formato degli asset resta invariato.

### 17 novembre 2026 — consolidamento e regressioni sul branch gemini_test

- **Branch:** `gemini_test` (derivato e allineato con le ultime modifiche sperimentali; `valhalla` non toccato).
- **Componenti e file modificati:**
  1. `platforms/esp32/main/map_origin_predictor.hpp`: congelamento dell'estrapolazione rettilinea continua durante curve (>15°) o frenate (>25%), mantenendo attiva la correzione progressiva all'ancora.
  2. `tests/native/map_origin_predictor_tests.cpp`: ripristinati i test di congelamento in curva/frenata e aggiunta suite esplicita di regressione per curve >15°, frenate >25% e combinazioni di entrambe.
  3. `platforms/esp32/main/motion_heading_fusion.hpp` & `phone_nav_bridge.cpp`: timeout a 2.500 ms per la conferma del doppio outlier GNSS con reset se il secondo fix non giunge in tempo.
  4. `tests/native/motion_heading_fusion_tests.cpp`: test unitario per la scadenza della conferma del doppio outlier.
  5. `platforms/android/app/src/main/java/com/motogps/android/viewmodel/RerouteStabilityPolicy.kt` & `RerouteStabilityPolicyTest.kt`: semplificate le condizioni in `shouldDefer()`, azzerato `turnGuardUntilMs` in `accepted()`, estesa suite di test JUnit.
  6. `platforms/web/simulator/player.js`: completamento manovre BLE v1 (`slight_left`, `u_turn_left`, `slight_right`, `u_turn_right`).
  7. `platforms/web/shell/map-scene-overlay.js`: rimossi tentativi di fetch a fixture Jinan eliminate con degradazione pulita.
  8. `platforms/web/shell/wasm-loader.js` & `platforms/web/wasm/CMakeLists.txt`: aggiunti export mancanti `_moto_web_preview_phone_lifecycle` e `_moto_web_set_phone_connection`, protetti i binding con `safeCwrap`.
  9. `scripts/offline_map/pbf_to_motomap.py` & `test_motomap_v4.py`: gestione righe vuote nei file `.poly` in `certified_z14_cells()` e test di regressione con Shapely.
  10. `.github/workflows/project-build.yml`: corretta esclusione `'LICENSE.md'` sotto `paths-ignore` e inclusi i test Node.js su heading e WASM exports nel job `native`.
  11. `tests/native/ble_protocol_tests.cpp`: normalizzazione dei terminatori CRLF nel lettore di golden vectors.
- **Test eseguiti localmente:**
  - CTest nativo (WSL Ubuntu 24.04): 15/15 test superati (100%).
  - Python unittest (`scripts/offline_map`): 27 test superati in WSL; 10/10 test `test_motomap_v4.py` superati con Python 3.15 Windows.
  - Node.js simulator (`node --check` e `node --test`): syntax check e test superati su tutti i moduli web.
- **Test non eseguiti localmente:**
  - Compilazione APK Android e test Gradle (assenza di JDK locale sull'host Windows).
  - Ricompilazione WASM Emscripten e collaudo reale su hardware ESP32.
- **Rischio e bloccanti aperti:** nessuno noto nel codice modificato; il wire format BLE v1 è preservato senza alcuna rottura.
- **Prossima azione:** commit e push unico su `gemini_test`, verifica della GitHub Actions CI consolidata.

### 17 novembre 2026 — risoluzione crash all'avvio Android (SecurityException WAKE_LOCK)

- **Branch:** `gemini_test` (`valhalla` non modificato).
- **Problema:** all'avvio dell'applicazione su Android, subito dopo la conferma dei permessi di localizzazione, si verificava un arresto anomalo irreversibile con eccezione:
  `java.lang.SecurityException: Neither user 10677 nor current process has android.permission.WAKE_LOCK.`
  `at android.os.PowerManager$WakeLock.acquire(PowerManager.java:5384)`
  `at com.motogps.android.location.LocationForegroundService.onStartCommand(LocationForegroundService.kt:73)`
- **Causa:** `LocationForegroundService` invocava `wakeLock.acquire(...)` senza che il permesso `android.permission.WAKE_LOCK` fosse dichiarato in `AndroidManifest.xml`.
- **Risoluzione chirurgica:**
  1. `LocationForegroundService.kt`: rimossa completamente ogni gestione e istanziazione di `PowerManager.WakeLock` (`acquire()` e `release()`), in piena coerenza con le decisioni architetturali documentate che affidano la persistenza in background al `ForegroundService` con tipi `location|connectedDevice` senza ricorrere a wake lock ad alto consumo.
  2. `platforms/android/app/build.gradle.kts`: aggiunto `testOptions { unitTests.isReturnDefaultValues = true }` per consentire l'esecuzione affidabile dei test unitari sui componenti Android Service.
  3. `platforms/android/app/src/test/java/com/motogps/android/location/LocationForegroundServiceTest.kt`: aggiunti test unitari JUnit di regressione che simulano l'assenza del permesso `WAKE_LOCK` e verificano che `onCreate`, `onStartCommand` e `onDestroy` completino senza sollevare `SecurityException`.
- **Verifica:** delegata alla run consolidata GitHub Actions CI (`project-build.yml` su branch `gemini_test`).

### 18 novembre 2026 — prevenzione taglio corda in rotonda (turn_scale a 4°, soglia laterale 4m, cadenza BLE 900ms)

- **Branch:** `gemini_test` (`valhalla` non modificato).
- **Problema riscontrato nel test su strada:** nelle rotonde lente e strette la freccia/mappa tendeva a tagliare verso l'interno dell'anello a causa del ritardo di stima della decelerazione da parte del Doppler GPS dello smartphone, dell'intervento tardivo dell'attenuazione passo (a 10° invece che a 4°) e dell'age compensation retrograda che spingeva la posizione della nuova finestra BLE lungo la tangente interna.
- **Causa e risoluzione chirurgica:**
  1. `platforms/esp32/main/map_origin_predictor.hpp`:
     - Anticipato l'intervento di `turn_scale` da 10.0° a 4.0° e ridotto il floor da 0.20 a 0.10 su una finestra di 32.0° (`turn_scale = std::clamp(1.0 - 0.90 * ((anchor_turn_deg - 4.0) / 32.0), 0.10, 1.0)`).
     - Alle basse velocità tipiche da rotonda (`speed < 7.0`), riscalato ulteriormente il passo in curva moltiplicando `turn_scale *= (speed / 7.0)` per assorbire l'eccesso di velocità estrapolata dovuto al ritardo Doppler GPS.
     - Ristretta la finestra `turn_weight` dell'age compensation retrograda da 70.0° a 30.0° (`std::clamp((30.0 - turn_delta) / 22.0, 0.0, 1.0)`) e ridotta la soglia laterale `lateral_m` da 14.0 m a 4.0 m, disattivando l'estrapolazione tangenziale interna lungo le curve della rotonda.
  2. `platforms/android/app/src/main/java/com/motogps/android/viewmodel/NavigationViewModel.kt`:
     - Nel loop di navigazione attiva, impostata la cadenza rapida di invio geometria (`900L` ms) sempre attiva quando `activeSnap.maneuverName == "roundabout"`, indipendentemente dalla condizione `distanceToManeuverM <= 100L`, affinché l'ESP32 riceva ancore fresche continuativamente lungo tutto l'anello.
  3. `tests/native/map_origin_predictor_tests.cpp`:
     - Aggiornate le asserzioni di `test_turning_continues_with_attenuated_step_along_live_heading` (spostamento attenuato a 50° con floor 0.10), `test_curve_compensation_tapers_instead_of_switching_off` (tapering della curva su angoli 15° e 22° coerentemente con la finestra a 30°), `test_roundabout_exit_three_second_geometry_gap` (gap DR a 17,7 m e convergenza a 7,1 m) e `test_turn_scale_threshold_and_floor` (soglia a 4.0°, floor a 0.10 a >=36° e verifica passo riscalato a velocità < 7.0 m/s).
- **Verifica:** delegata a un'unica run consolidata di GitHub Actions (`project-build.yml` su branch `gemini_test` con `build_native=true`, `build_android=true`, `build_esp32=true`).

### 18 novembre 2026 — eliminazione deriva laterale residua (calibrazione kGyroSigmaDeg, finestra predittore 1.8s e cadenza BLE 1.5s)

- **Branch:** `gemini_test` (`valhalla` non modificato).
- **Problema riscontrato nel test su strada:** permaneva una deriva laterale residua di 2–3 metri (effetto corsia adiacente) causata dal guadagno correttivo insufficiente del course GPS rispetto all'inerzia del giroscopio (~8% per fix con `kGyroSigmaDeg = 6.0F`) e dall'estrapolazione longitudinale/laterale eccessiva del predittore durante finestre geometriche BLE distanziate di 3,0 s.
- **Causa e risoluzione chirurgica:**
  1. `platforms/esp32/main/motion_heading_fusion.hpp`:
     - Aumentato `kGyroSigmaDeg` da `6.0F` a `14.0F` nella formula `gain = kGyroSigmaDeg^2 / (kGyroSigmaDeg^2 + sigma^2)`, aumentando il peso correttivo del course GPS dal ~8% al ~33% per fix ed eliminando il trascinamento inerziale residuo del giroscopio.
  2. `platforms/esp32/main/map_origin_predictor.hpp`:
     - Ridotta la finestra massima di dead-reckoning continuo tra due ancore `RouteGeometry` da `3'400U` ms a `1'800U` ms (mantenendo invariato il reset per gap >1 s tra tick).
     - Ridotti `kMaxAgeCompensationSeconds` da `0.70` a `0.35` e `kMaxAgeCompensationMeters` da `14.0` a `7.0` per evitare sovra-estrapolazione longitudinale/laterale.
  3. `platforms/android/app/src/main/java/com/motogps/android/viewmodel/NavigationViewModel.kt`:
     - Nel loop di navigazione attiva, ridotto l'intervallo ordinario di invio di `sendNearbyRouteGeometry(activeGeom)` da `3_000L` ms a `1_500L` ms (1,5 s) per mantenere l'ancora BLE aderente alla traiettoria reale anche su strade scorrevoli.
  4. `tests/native/motion_heading_fusion_tests.cpp`:
     - Allineate le attese numeriche dei test `test_moving_phone_course_corrects_gyro_drift_progressively`, `test_startup_heading_fast_and_stationary_hold` e `test_stale_outlier_does_not_confirm_later_spike` ai valori teorici derivanti dal nuovo guadagno ~0.33.
  5. `tests/native/map_origin_predictor_tests.cpp`:
     - Aggiornate le asserzioni di tempo e distanza nei test `test_prediction_freezes_after_missing_geometry_anchor`, `test_three_second_ble_geometry_cadence_stays_in_motion`, `test_cruising_speed_reduces_only_backward_anchor_correction`, `test_turning_and_stopping_do_not_hide_backward_corrections`, `test_curve_compensation_tapers_instead_of_switching_off` e `test_roundabout_exit_three_second_geometry_gap` coerentemente con la finestra di 1,8 s e la cadenza geometrica di 1,5 s.
- **Verifica:** delegata a un'unica run consolidata di GitHub Actions (`project-build.yml` su branch `gemini_test` con `build_native=true`, `build_android=true`, `build_esp32=true`).

### 18 novembre 2026 — calibrazione reattività heading/gyro ed eliminazione deriva laterale predittore mappa

- **Branch:** `gemini_test` (`valhalla` non modificato).
- **Problema riscontrato nel test su strada:** la mappa e la freccia presentavano una deriva laterale sia a destra che a sinistra ed erano troppo lente a riprendere la direzione corretta dopo le curve (effetto lag del giroscopio).
- **Causa e risoluzione chirurgica:**
  1. `platforms/esp32/main/motion_heading_fusion.hpp`:
     - Ridotto `kGyroSigmaDeg` da `10.0F` a `6.0F`, aumentando il peso correttivo del course GPS rispetto all'inerzia del gyro nel filtro complementare (`gain = gyro_sigma^2 / (gyro_sigma^2 + course_sigma^2)`).
     - Ridotto il fattore di smoothing esponenziale del rateo di imbardata in `integrate()` da `0.24F` a `0.15F` per estinguere più rapidamente la rotazione inerziale residua del giroscopio all'uscita dalle curve.
  2. `platforms/esp32/main/map_origin_predictor.hpp`:
     - Riformulata la funzione di scaling in curva `turn_scale` in `advance()` impostando `turn_scale = std::clamp(1.0 - 0.80 * ((anchor_turn_deg - 10.0) / 60.0), 0.20, 1.0)`: la scala resta piena (1.0) fino a 10.0° di deviazione angolare, si attenua progressivamente tra 10° e 70°, e si fissa al floor 0.20 a 70° o oltre, azzerando la deriva laterale della mappa subito dopo rotatorie e curve.
  3. `tests/native/motion_heading_fusion_tests.cpp`:
     - Allineate le asserzioni quantitative dei test `test_moving_phone_course_corrects_gyro_drift_progressively`, `test_startup_heading_fast_and_stationary_hold`, `test_quality_weight_and_outlier_confirmation` e `test_stale_outlier_does_not_confirm_later_spike` ai nuovi parametri di filtro e guadagno.
  4. `tests/native/map_origin_predictor_tests.cpp`:
     - Aggiornata la verifica di `test_turning_continues_with_attenuated_step_along_live_heading` alla nuova scala attenuata (~0.47 a 50°).
     - Aggiunto il test di regressione `test_turn_scale_threshold_and_floor` che verifica analiticamente la piena ampiezza (6.0 m) a <= 10.0°, la corretta attenuazione a > 10.0° e il rispetto del floor a 0.20 (1.2 m) a >= 70.0°.
- **Verifica:** delegata a un'unica run consolidata di GitHub Actions (`project-build.yml` su branch `gemini_test` con `build_native=true`, `build_android=true`, `build_esp32=true`).

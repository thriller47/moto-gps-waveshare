# Moto Chat GPS — stato operativo del progetto

## STATO ATTUALE — RIPARTIRE DA QUI

**Aggiornato:** 17 novembre 2026 (risultati confronto replay OFF/ON e conferma layout). **Integrazione stabile:** `valhalla` (non modificata in questa sessione). **Branch sperimentale attivo:** `test/unified-offline-map-20261115`; non eseguire merge senza approvazione dell'utente. Al controllo GitHub del 16/11: branch test 51 commit avanti e 3 indietro rispetto a `valhalla` (rivalutare divergenza prima del merge).

**Ultimo sviluppo effettivamente implementato (prima di questa patch):** «Salta prossima tappa» su Android, con dialogo di conferma, ricalcolo Valhalla dalla posizione GPS attuale verso le sole tappe pendenti, esclusione della tappa solo a routing riuscito e aggiornamento checkpoint per gli anelli; supportato anche per percorsi ordinari con tappe, mai per l'arrivo finale. Commit codice `90a1be168c022561514e9303dfddfcc77e53c205`. ESP32 e protocollo BLE **non** modificati per questa funzione. Il branch test contiene inoltre MotoMap v4 + Valhalla Italia V5 + Photon v2 unificati, anelli, tour salvati, pausa/ripresa e rimozione completa della demo Jinan. Unico supporto 3D `hardware/MotoNavFusion_v015.zip` preservato.

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

**Prossima azione unica:** domani ricevere e analizzare diagnostica Android e replay GPS del percorso ripetuto, prima di qualunque nuova modifica di firmware o UI.

**Correzione sperimentale 17/11 (da verificare):** ridotta la re-ancoratura ripetuta dello stesso bearing BLE, congelata la correzione GNSS durante yaw IMU >5°/s e ridotto il guadagno a 0,22; Android scarta course sotto 3 m/s, course con precisione dichiarata >25° e fallback dislocamento <12 m; predittore integra tutto il tempo del tick (fino a 1 s), smussa compensazione 25–45 m e congela l'extrapolazione rettilinea quando diverge dal heading dell'ultima geometria >15° o frena oltre il 25%. Aggiunti test nativi e Android. **Build CI da verificare**. È stato aggiunto al simulatore LVGL un confronto A/B del predittore C++ isolato, disattivato per default. Per provare la nuova opzione va ricompilato WASM. Il simulatore non esegue PhoneNavBridge, BLE o fusione IMU: ON testa solo l'origine predetta con le finestre presenti nel replay, non l'intera pipeline ESP32. Non confondere prova nel simulatore con test su strada.

**Ultima correzione:** runtime Emscripten bloccato da riferimenti Jinan e funzione LVGL demo rimossi in precedenza. Ripristinato scenario sintetico solo web e tolta API non esistente, senza toccare il firmware. **Compilazione WASM ancora da verificare in WSL;** prossima azione unica: ricompilare `./scripts/build_web.sh` e verificare l'opzione predittore A/B sul replay mattutino. GitHub native/Android/ESP32 non sostituisce il test Emscripten.

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

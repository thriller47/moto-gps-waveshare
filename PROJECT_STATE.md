# Moto Chat GPS — stato operativo del progetto

## STATO ATTUALE — RIPARTIRE DA QUI

**Aggiornato:** 15 novembre 2026. **Integrazione stabile:** `valhalla`, non modificata. **Branch sperimentale:** `test/unified-offline-map-20261115`, conservato per richiesta utente. Non mergiare senza consenso e prova su strada.

**Pulizia legacy e fix:** eliminati dal branch test script/demo Jinan e tool KiCad Rev A; eliminata anche la loro dipendenza residua nel bridge ESP32, nei test native e nello stato demo LVGL. Il tick reale da 25 ms resta attivo. Conservato `hardware/MotoNavFusion_v015.zip` con il supporto 3D. **Confermato dall'utente:** pianificazione e salvataggio dei tour funzionanti da fermo; zoom e pan liberi nell'anteprima collaudati con successo. Versione stabile antecedente con tre check CI verdi e prove BLE/rendering positive. I risultati della nuova funzionalità pausa/ripresa non sono ancora disponibili.

**Novità in test da verificare:** pausa manuale di tour circolare con checkpoint duraturo Android; vengono salvati destinazione, tappe ordinate, indice completato, preferenze di navigazione, posizione e timestamp. Checkpoint anche automatico all'avvio, a ogni tappa raggiunta e periodicamente (~30 s) per recupero dopo crash. Alla riapertura l'app mostra tour in pausa/interrotto; `Riprendi da qui` ricalcola anteprima da GPS corrente verso i **soli waypoint pendenti** e arrivo finale, senza avviare la navigazione automaticamente. Comando di abbandono separato dall'archivio dei tour. Test Kotlin `TourSessionStoreTest` aggiunti, manuale aggiornato. **Firmware ESP32, formato BLE, grafo e grafica invariati.**

**Limiti:** massimo un checkpoint locale, nessuna sincronizzazione cloud; la tappa conta come raggiunta se il GPS arriva entro 60 m. Nel caso di più riprese successive il conteggio cumulativo delle tappe completate resta conservato; ogni ricalcolo Valhalla riceve soltanto le tappe ancora pendenti. Distanza richiesta nei tour indicativa. Valhalla esegue sempre il nuovo routing al ripristino. Verificare compilazione Android e comportamento di pausa/ripresa dopo chiusura app e reboot del telefono; la prova sul tragitto casa–lavoro–casa proposta dall'utente è prioritaria.

**Prossima azione unica:** verificare la CI native+ESP32 dopo la rimozione definitiva della demo dal bridge, poi collaudare sul dispositivo la navigazione BLE e la fluidità prima di qualsiasi merge.

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

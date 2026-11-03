# Moto GPS — Project State

> **Updated: 2026-10-30** (Europe/Rome). Living project handoff; checked against the `valhalla` source, Git history, and GitHub Actions on this date. **Code/CI success is not proof of on-road hardware stability.**

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

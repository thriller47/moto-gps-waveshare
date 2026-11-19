# Simulatore Moto Chat GPS per Windows (anteprima 2D + WebAssembly LVGL)


## Test A/B/C predizione posizione in rotonda — 19 novembre 2026

Nel nuovo menu **Predizione posizione · confronto A/B/C**:

- **A — Predittore ESP32 attuale:** classe C++ `MapOriginPredictor` di produzione, senza modifiche;
- **B — OFF (GPS snapshot):** nessun avanzamento; l'origine della mappa usa la posizione registrata/interpolata, quindi lo scarto rispetto allo stesso snapshot è 0 m per definizione;
- **C — Prudente in curva:** prototipo **esclusivo del WASM** che usa il predittore originale ma attenua l'avanzamento quando cambia rapidamente l'heading registrato e riallinea più rapidamente la posizione alla coordinata GPS dello snapshot. Il firmware ESP32 **non** usa C.

Con il replay del giro del 19/11, estrarre i singoli JSONL da `gps/` in `motogps-diagnostica-completa.zip`. Aprire **un file JSONL alla volta**, impostare `Heading del replay → Originale`, velocità **1×**, poi riprodurre dall'inizio il tratto desiderato, cambiando A, B e C. In `Scarto A / B / C dallo snapshot` sono visibili i tre risultati dello stesso frame; `Esporta diagnostica CSV` include anche `predicted_offset_curve_m`, `predicted_curve_along_heading_m` e `predicted_curve_lateral_heading_m`. Scarto laterale positivo = destra, negativo = sinistra **rispetto all'heading dello snapshot**, non necessariamente rispetto al bordo della strada.

**Limiti fondamentali:** il replay non registra i 125 Hz grezzi del QMI8658, l'heading effettivamente fuso a ogni frame o i tempi reali di consegna BLE. C utilizza il GPS dello snapshot come riferimento aggiuntivo, quindi uno scarto inferiore non dimostra da solo che il mezzo fosse sulla carreggiata. La prova confronta ipotesi sulla predizione della posizione, senza risolvere automaticamente la deriva della rotazione. È necessaria una verifica fisica prima di adottare modifiche al firmware.

**Ricompilazione obbligatoria** perché cambiano `replay_web.cpp` e gli export Emscripten:

```bash
cd ~/MotoGPS/moto-gps-waveshare
git pull --ff-only origin test/merge-gps-20261118
source ~/emsdk/emsdk_env.sh
./scripts/build_web.sh
python3 -m http.server 4173 --directory .
```

Se il server è già attivo, non aprirne un secondo sulla porta 4173: fermare il precedente con Ctrl+C o aggiornare la pagina con Ctrl+F5 dopo il rebuild. Se il menu A/B/C rimane disabilitato, il WASM non contiene ancora il nuovo export. Nessun bisogno di compilare APK o flashare ESP32 per questo test.


**Stato:** simulatore e replay Android integrati in `valhalla` (PR #73 e #74); matcher dei ricalcoli verificato su strada il 10 novembre 2026. Due modalità separate. **Anteprima geometrica 2D** immediata (JavaScript, non è la grafica LVGL del firmware) e **renderer LVGL + NavPresenter reali** quando si compila il runtime WebAssembly. Non richiede ESP32 né GPS del PC. Nessun tracciato viene caricato su un server. FPS del browser e latenza BLE non rappresentano l'hardware.

## Aprire subito l'anteprima 2D senza compilatore

Su Windows, dopo aver aggiornato il branch `valhalla`, aprire direttamente `platforms/web/simulator/index.html` nel browser. Se il runtime WebAssembly non è presente appare **ANTEPRIMA 2D · NON LVGL**: la geometria del percorso e delle strade MotoMap segue coordinate, heading e scala calcolata del tracciato, ma la grafica **non corrisponde esattamente** a quella dell'ESP32. È una modalità di analisi, non una prova del renderer.

Caricare il file `motogps-replay.jsonl` esportato dall'app tramite **Tracciato GPS**. Il file resta nel browser sul proprio PC. L'anteprima è utilizzabile anche senza Python o Emscripten; per il **renderer LVGL reale** servono invece la compilazione e un server HTTP locale, come segue.

## Nuova prova: zoom intelligente tornanti (sperimentale, solo nel simulatore LVGL)

Dopo aver aggiornato `valhalla`, **ricompilare** il runtime con `source ~/emsdk/emsdk_env.sh` e `./scripts/build_web.sh`, ricaricare la pagina con **Ctrl+F5**, poi caricare il tracciato del 6 novembre. L'interruttore **Zoom tornanti sperimentale** si abilita solo quando il vero WASM è caricato. A parità di frame, OFF mostra lo zoom originale (1× lontano dalle manovre; fino a 4× sulla rotatoria), ON cerca nella finestra della **linea verde** un cambio di direzione marcato, lo ingrandisce progressivamente fino a 3× e mantiene il massimo fra quello e lo zoom tradizionale. Lo zoom mostrato nella pagina, con LVGL attivo, proviene dal **NavPresenter C++** (non da una formula JavaScript).

Inizia dal tratto **12:10:50 e successivi**, confrontando lo stesso frame con OFF e ON. Il rilevamento non si basa sui waypoint, non ricalcola la rotta Valhalla, non invia nulla al BLE e **non è abilitato nel firmware ESP32**. Rimane una prova esplorativa: può essere sensibile a finestre geometriche parziali, rotonde e curve a S. Non considerarlo validato su strada finché non lo avremo testato.

## Movimento a scatti con snapshot GPS ripetuti (8 novembre 2026)

Una registrazione reale conteneva **4.732 snapshot UI ma soltanto 1.178 fix GPS**. Il vecchio player interpolava solo due snapshot adiacenti: se riportavano coordinate identiche la mappa restava ferma, poi saltava alla successiva posizione GPS anche con RAF/WASM/LVGL regolari. Il player ora precalcola gli intervalli tra **fix GPS distinti** e interpola posizione, rotta angolare breve e velocità durante Play; conserva gli snapshot originali con Step/Seek. Non interpola attraverso ricalcoli/cambi di rotta, navigazione non attiva, buchi GPS oltre 3 s o spostamenti anomali oltre 100 m. Lo zoom tornanti e l'algoritmo LVGL non sono cambiati.

**Prova veloce:** sul branch `valhalla` fare `git pull --ff-only origin valhalla`, poi in Chrome `Ctrl+F5` e ricaricare il replay privato. **Questa correzione è solo JavaScript:** non richiede ricompilare WebAssembly se è già stato ricompilato dopo la modifica al timer di refresh LVGL da 15 ms. Confrontare alle 12:10:50 a 1×; se restano scatti, registrare entrambi i riquadri di diagnostica e distinguere jerk del GPS da irregolarità del disegno. Nessuna modifica a ESP32, Android, BLE o `valhalla`.


## Scarto anello MotoMap / linea verde (diagnostica browser, 10 novembre 2026)

Il riquadro **Scarto anello prioritario / finestra verde** confronta la prima polilinea chiusa selezionata da Android con la geometria della finestra di rotta BLE registrata nello **stesso frame**. Mostra uno scarto minimo in metri, distinguendo **coincidente** (entro 3 m), **vicino ma esterno** (tra 3 e 32 m) e **esterno alla finestra** (oltre 32 m). Non si tratta di un giudizio di correttezza Valhalla: la finestra è parziale, i livelli OSM non sono filtrati e le due geometrie possono rappresentare strade diverse. Non modifica la linea verde, il disegno LVGL, il firmware, Android o il selettore delle strade.

Con il replay del 10 novembre, alla prima rotatoria (~13:35:14) lo scarto risulta circa **10,8 m**, mentre alla seconda (~13:35:46) risulta **0 m**. Il browser non ha bisogno della geometria privata sul server: calcola tutto localmente. Dopo **git pull** è sufficiente **Ctrl+F5** e ricaricare il file JSONL; **non ricompilare WebAssembly** per questa sola modifica JavaScript. I test sintetici escludono i tracciati GPS personali dal repository.

**Checkout stabile dopo la PR #74 (WSL Ubuntu):**

```bash
cd ~/MotoGPS/moto-gps-waveshare
git switch valhalla
git pull --ff-only origin valhalla
git submodule update --init --recursive
source ~/emsdk/emsdk_env.sh
# Per modifiche C++/LVGL, oppure se manca il runtime:
cmake --build build/web --parallel 1
# In un altro terminale WSL:
python3 -m http.server 4173 --directory .
```

Aprire **http://127.0.0.1:4173/platforms/web/simulator/index.html** e fare **Ctrl+F5**. Dopo modifiche solo JS/HTML, non ricompilare il runtime. Per la prima compilazione, se `build/web` non esiste, seguire il manuale operativo §8.1. Il server si ferma con `Ctrl+C`; il branch `test/esp32-map-dirty-tiles-20261109` è storico dopo il merge.

## Compilazione con Emscripten

Servono Git, CMake, Emscripten/emcmake attivati e il submodule LVGL.

Da WSL/Linux, nella radice del repository:

    git submodule update --init --recursive
    ./scripts/build_web.sh
    python3 -m http.server 4173 --directory .

Da Windows aprire:

http://127.0.0.1:4173/platforms/web/simulator/index.html

Su PowerShell nativo, con Emscripten attivo, si possono usare questi comandi equivalenti (non ancora convalidati su una macchina Windows reale):

    emcmake cmake -S . -B build/web -DMOTO_BUILD_WEB=ON -DMOTO_BUILD_TESTS=OFF
    cmake --build build/web --parallel
    py -m http.server 4173

Il compilatore genera "platforms/web/shell/runtime/moto-gps-device.js" e il relativo WASM (ignorati da Git). **Per LVGL usare HTTP** (non file://): il doppio clic sull’HTML è sufficiente soltanto per l’anteprima 2D senza runtime. Se compare "Runtime LVGL assente", l’anteprima resta disponibile: controllare Emscripten, submodule e build quando si desidera il rendering fedele.

La pagina storica "platforms/web/shell/index.html" è separata: il suo esempio Jinan/AMap è legacy. Non è il nuovo banco prova.

## Registrare il giro Android: dati sensibili

Nel nuovo APK sperimentale aprire **LOG → Avvia registrazione GPS**, quindi **Ferma registrazione GPS** a fine giro. La registrazione non ha più un limite complessivo di 60 minuti / 24 MiB: continua finché viene fermata o finché esiste spazio sul telefono. Vengono create automaticamente parti numerate ogni **30 minuti o circa 12 MiB per parte**.

**Esporta tracciato simulatore** condivide un file `motogps-replay.jsonl` se esiste una sola parte; altrimenti produce `motogps-replay-parts.zip` con tutti i `.jsonl` numerati in ordine cronologico. Sul PC estrarre lo ZIP e aprire nel simulatore **una parte per volta**; il browser attualmente non importa direttamente ZIP o più file contemporaneamente. Ogni parte ha un proprio evento `meta` con `part_index`, `session_elapsed_ms` e `captured_at_ms`, e tempi `t_ms` relativi alla parte.

La registrazione contiene coordinate GPS, velocità, posizione predetta inviata all'ESP32, manovre e distanze, finestre della rotta verde e scene MotoMap. Il normale ZIP diagnostico Android NON contiene questi dettagli. La registrazione è OFF per impostazione predefinita; avviarne una nuova sostituisce sul telefono le parti locali **non esportate** della sessione precedente. Per conservare un giro, esportarlo prima di iniziare quello successivo.

**Privacy:** i replay rivelano spostamenti e orari; non allegarli pubblicamente a issue o commit. Il browser legge i .jsonl localmente, senza upload; il trace documenta l'invio BLE tentato, non la consegna radio.

## Formato moto-replay-v1

Un file JSONL contiene una riga JSON per evento, con tempi relativi "t_ms" (millisecondi). In alternativa caricare un JSON con schema "moto-replay-v1" e array "events".

Esempio compatto (geometrie inventate, per spiegare la struttura):

~~~jsonl
{"schema":"moto-replay-v1","type":"meta","t_ms":0}
{"type":"route","t_ms":0,"origin":[44.392,7.55],"route_token":1,"generation":1,"points":[[44.391,7.55],[44.392,7.55],[44.392,7.551]]}
{"type":"scene","t_ms":0,"revision":1,"roads":[{"road_class":2,"points":[[44.391,7.55],[44.392,7.55]]}]}
{"type":"snapshot","t_ms":100,"lat":44.3915,"lon":7.55,"heading_deg":0,"speed_mps":8,"state":"navigating","maneuver":"turn_right","has_turn":true,"turn_m":60,"remaining_m":500,"total_m":800,"progress_m":300,"duration_s":70,"speed_limit":50,"network":"offline","exit":0}
~~~

Le coordinate sono [latitudine, longitudine] con i valori presenti nel payload BLE, non sono automaticamente convertite da WGS84 a GCJ02 dal player. La finestra rotta può avere massimo 24 punti, la scena fino a 24 strade / 192 punti, in linea con il renderer condiviso (in produzione Android potrebbe usare un budget inferiore: oggi 20/160). Il player mantiene l'ultima rotta e l'ultima scena finché ne arriva una nuova, quindi la riproduzione temporale rispetta gli aggiornamenti intermittenti di geometria.

## Controlli

- Apri JSON/JSONL, oppure "Esempio sintetico" per verificare il motore senza file.
- Play/pausa, pulsanti frame precedente e successivo, cursore temporale, velocità 0,25–4×.
- PNG della schermata corrente: dalla preview 2D oppure, se disponibile, dal canvas LVGL.
- Cronologia navigabile: avvio rotta, **cambi del token BLE** (indizi di ricalcolo), inizio delle istruzioni per rotatoria, tappa raggiunta e arrivo finale.
- Grafico della velocità con indicatori di eventi e cursore cliccabile per saltare a un istante.
- Indicatore di zoom calcolato con la regola attuale del `NavPresenter`: da 1× a 4× entro 100 m dalla manovra, se applicabile.
- Prima ancora di compilare Emscripten è possibile usare e verificare tutti i controlli dati in anteprima 2D.
- La proposta visuale "Tappa raggiunta" per 7 secondi compare **solo nell'anteprima 2D**; NON è ancora presente nel firmware Android/ESP32.

**Aggiornamento incrementale WASM:** durante l'avanzamento dei frame, rotta e strade vengono reinviate al renderer soltanto se è arrivato un nuovo evento geometria/scena. Un seek all'indietro ripristina lo stato prima di mostrare il frame richiesto. Il file di replay descrive ciò che Android ha inviato, non le condizioni di ricezione BLE.

**Limiti dell'MVP:** riproduce l'inquadratura e il contenuto del display con il codice condiviso, ma non ricalcola il selettore strade Android, la route Valhalla o l'arrivo dei pacchetti BLE. Non può riprodurre i vecchi ZIP privi di coordinate/geometrie. Reimpostando lo snapshot per il seek, le animazioni LVGL non sono una misura fedelissima dei tempi reali. Per confrontare algoritmi Android diversi, occorrerà una seconda modalità che ricalcoli la scena MotoMap a parità di trace GPS, non solo la replichi.

## Verifiche

Il workflow nativo controlla la sintassi dei tre file JS e lancia i test `node --test platforms/web/simulator/*.test.cjs`. In sviluppo locale, sette test Node (parser/scene/eventi/zoom) e una prova browser headless con tracciato reale hanno superato i controlli; anche un bridge WASM **simulato** ha confermato che il frame successivo non ricopia la geometria. **Non** è una prova del vero binario Emscripten/LVGL: il toolchain non era disponibile nell’ambiente di sviluppo e la compilazione WebAssembly e la verifica visiva del renderer reale restano da svolgere.

## Confronto sperimentale del predittore GPS — 17 novembre 2026

Nel pannello **Riproduzione** del simulatore LVGL appare **Predittore ESP32 sperimentale · confronto A/B**. Resta spento per impostazione predefinita. Dopo `git pull`, attivare Emscripten in WSL e ricompilare con `source ~/emsdk/emsdk_env.sh && ./scripts/build_web.sh`; fare `Ctrl+F5`. Caricare il replay del 17/11 e riprodurre a 1×, comparando OFF e ON sul tratto 07:50–07:56. ON usa la stessa classe `MapOriginPredictor` C++, con gli eventi di geometria registrati come ancore e il tempo del replay; OFF usa la posizione replay originale/interpolata. **Non è l'intera pipeline ESP32**: non viene emulato il QMI8658, `MotionHeadingFusion`, né l'effettiva ricezione BLE. Il confronto aiuta a riconoscere derive laterali dovute alla predizione, ma non prova la stabilizzazione dell'heading su hardware. Non dedurre un miglioramento in auto da un'immagine statica.

**Fix 17/11/2026:** se il build Emscripten precedente falliva per `demo_fixture/jinan_big_data_demo.hpp` o `moto_nav_ui_set_demo_active`, aggiornare il branch test prima di ricompilare: le chiamate sono state rimosse e l'esempio non usa più dati Jinan. I workflow GitHub native/Android/ESP32 non garantiscono la compilazione WASM.


## Diagnostica posizione / heading — 17 novembre 2026 (branch test)

Il banco prova mostra coordinate dello **snapshot Android**, heading di ingresso a NavPresenter, origine mappa **effettivamente inviata** al renderer dal WASM e scarto tra le due coordinate in metri. Durante Play l'interpolazione JavaScript è segnalata; con Pausa/Frame si legge il valore dello snapshot registrato. Scarto non significa errore GPS. Heading non è bearing GNSS grezzo, fusione IMU QMI8658 né angolo finale post-filtro grafico. Con predittore OFF la differenza è normalmente 0 m; ON può divergere. Firmware, BLE, Android e Valhalla restano invariati.

In WSL: `git pull --ff-only origin test/unified-offline-map-20261115`; `source ~/emsdk/emsdk_env.sh && ./scripts/build_web.sh`; avviare da root `python3 -m http.server 4174 --directory .`. In Chrome fare **Ctrl+Maiusc+R** con cache disabilitata. Le nuove funzioni C++ richiedono il rebuild WASM.


## Layout compatto del banco prova — 17 novembre 2026 (branch test)

Il display resta visibile a sinistra, a dimensione naturale quando la finestra lo permette. Sul desktop il pannello di destra scorre **indipendentemente** dall'ESP32, senza richiedere di ridurre lo zoom del browser. Restano in vista comandi, replay, velocità e letture fondamentali (heading, posizione e offset). «Diagnostica avanzata», «Eventi del giro», «Limiti» e note sono pannelli richiudibili. Su viewport strette si torna alla disposizione verticale. Per aggiornare basta `git pull --ff-only origin test/unified-offline-map-20261115` e ricaricare Chrome con **Ctrl+Maiusc+R**: **nessuna ricompilazione WASM** per questo cambio HTML/CSS. Prima prova: Chrome a 100% di zoom.


## Grafici ed esportazione heading — 17 novembre 2026 (branch test)

Il banco prova aggiunge un grafico **heading snapshot Android vs direzione del segmento più vicino della finestra rotta BLE**, un grafico **distanza dalla finestra di rotta e movimento tra fix distinti** e il pulsante **Esporta diagnostica CSV**. Il cursore verticale dei grafici segue il replay; facendo clic su un grafico si raggiunge il frame corrispondente. La differenza angolare usa il cammino breve (359°→0° non significa salto 359°). Le segnalazioni diagnostiche non modificano la navigazione e non dimostrano necessariamente un errore: sulla rotatoria, su rami sovrapposti o con finestra parziale, il segmento più vicino può essere sbagliato.

Il codice condiviso `shared/nav_presenter/src/moto_nav_presenter.cpp` costruisce la trasformazione heading-up da `snapshot.heading_deg` direttamente. **Non viene inventata una terza fonte di heading finale**: il campo di ingresso è quello che guida la rotazione della geometria nel NavPresenter, mentre IMU, bearing GNSS grezzo e eventuale filtraggio precedente al replay restano fuori dal formato. Il CSV include tutti gli snapshot come registrati, indipendentemente dallo scorrimento, ma gli offset predittore ON e OFF **solo per gli snapshot realmente visualizzati** in quella modalità (celle vuote = non osservato). Il tracciato originale rimane sul PC. Cambi solo JS/HTML: basta `git pull` + refresh senza cache, nessun rebuild WASM.


## CSV ad alta precisione e confronto predittore automatico (17/11/2026)

L'export include coordinate `snapshot_lat/lon` a **7 decimali**; le misure angolari e metriche rimangono a 2. Su ogni replay caricato il browser invoca `MapOriginPredictor` C++ **in un'istanza separata**, elaborando tutti gli snapshot registrati, con tempi e finestre geometriche originali; il CSV ha OFF=0 e ON=scarto calcolato per tutte le righe con rotta. Celle vuote se rotta mancante o WASM assente. Nessuna necessità di riprodurre tutta la traccia per riempire le colonne. Non sono emulati tempi BLE, fusione IMU o interpolazione in RAF. Necessario **ricompilare WASM** (`source ~/emsdk/emsdk_env.sh && ./scripts/build_web.sh`) dopo il pull e ricaricare Chrome senza cache. Non servono nuove registrazioni GPS.


## Telemetria età geometria e componenti vettoriali (17 novembre 2026, branch test)

L'export CSV del banco prova include ora tre ulteriori colonne:
- `geometry_age_since_distinct_route_ms`: tempo trascorso dal cambio dell'ultima finestra **distinta** di geometria letta dal replay. Il parser unifica eventuali pacchetti ripetuti identici: **non è l'età dell'ultimo pacchetto BLE ricevuto, né il cronometro interno del predittore**. Vale 0 al primo snapshot che vede la nuova geometria; può essere indefinito senza rotta.
- `predicted_along_heading_m`: proiezione dello scarto ON rispetto alla posizione snapshot nella direzione dell'heading: positivo in avanti, negativo indietro.
- `predicted_lateral_heading_m`: proiezione sul lato destro della direzione: positivo destra, negativo sinistra.

Le proiezioni utilizzano esattamente la differenza coordinate prodotta dal predictor C++ **di analisi**, nello stesso snapshot del CSV. Non sono coordinate laterali rispetto alla geometria della strada né misure d'errore GNSS. Nella diagnostica sotto i grafici compaiono anche i tre numeri del frame corrente. Per il nuovo codice C++ ricompilare WASM in WSL (`git pull --ff-only origin test/unified-offline-map-20261115`, `source ~/emsdk/emsdk_env.sh`, `./scripts/build_web.sh`), poi ricaricare Chrome senza cache e riesportare il CSV del replay già disponibile.


## Confronto heading originale / bloccato / stabilizzato — 17 novembre 2026

Nel simulatore selezionare **Heading del replay**:
- **Originale:** heading registrato senza cambiamenti, baseline (impostazione predefinita).
- **Bloccato:** memorizza l'heading dello snapshot attivo quando si sceglie la modalità, e lo mantiene costante mentre la riproduzione avanza; serve solo a isolare se il movimento della mappa è una rotazione. Non adatto alla navigazione in curva.
- **Stabilizzato:** filtro JavaScript sperimentale, precalcolato su timestamp di tutti gli snapshot, con interpolazione angolare circolare. Su rettilineo applica costante temporale 1,1 s e deadband di 0,7°, nelle rotatorie o variazioni rapide usa 0,12 s senza deadband; reinizializza dopo buchi >3 s, cambi di identità della rotta e fine navigazione. È un esperimento sul replay, **non il filtro IMU QMI8658 del firmware**.

La modalità agisce esclusivamente sull'heading passato al `NavPresenter` e alla preview 2D, mantenendo intatto il tracciato e il CSV diagnostico originale. La telemetria distingue heading snapshot e applicato. La modalità bloccata va scelta mentre si è già sul rettilineo da analizzare. Se il problema persiste con heading bloccato, cercare movimento della geometria/posizione anziché una pura rotazione. Non modificati firmware, BLE, Android, Valhalla, predittore C++ e Douglas–Peucker.

**Aggiornamento:** `git pull --ff-only origin test/unified-offline-map-20261115` e **Ctrl+Maiusc+R** in Chrome. Le modifiche sono JavaScript/HTML: **nessuna ricompilazione WASM**.


## Analisi di heading incoerente e confronto con direzione GPS — 17/11/2026

Nel replay del 17/11 ci sono 75 snapshot con velocità >= 8 m/s e heading-rate >15°/s; **non sono tutti falsi**: i rettilinei e le curve vanno classificati con traiettoria e manovra, non con il rate da solo. La vecchia modalità "Stabilizzato" selezionava il filtro rapido (tau 0,12 s) quando il solo heading variava >4°/s, **anche se la moto viaggiava dritta**. Eliminata tale scorciatoia: ora la modalità utilizza filtro rapido solo nelle manovre esplicitamente segnalate come rotonda/inversione.

Aggiunta modalità **GPS verificato (rettilinei, sperimentale)**: confronta vettori di spostamento tra fix *distinti* di almeno 6 m, entro 3 s, controlla plausibilità della velocità, rileva rettilineo con due vettori successivi allineati entro 9°; limita l'influenza di variazioni isolate del bearing registrato mentre non cambia la posizione. Con cambi di traiettoria >=9° lascia recuperare rapidamente l'heading. Serve a distinguere rumore di heading da curve reali: non è map matching né stima di yaw IMU, non è garantito quando GPS saltella, a velocità basse o in tratti senza fix distinti. Regola solo il rendering del simulatore, **non Android/ESP32/BLE**. Dopo `git pull` basta ricaricare il browser senza cache; nessun rebuild WASM.

### Prova suggerita
Sul medesimo segmento del replay, confrontare `Originale`, `Bloccato`, `Stabilizzato`, `GPS verificato`. Annotare se in `GPS verificato` spariscono rotazioni anomale ma la mappa segue ancora rotonde e tornanti. Il CSV originale resta intatto.


## Fix bridge LVGL opzionale e esportazioni WASM — 17 novembre 2026

Il loader del simulatore inizializzava `batch_reset`, `batch_route`, `batch_sample`, `batch_along_m`, `batch_lateral_m`, `origin_lat` e `origin_lon` come se fossero tutte funzioni obbligatorie, ma erano **assenti dall'elenco `EXPORTED_FUNCTIONS` di CMake Emscripten**. Questo provoca il ritorno alla preview 2D quando il binding fallisce, anche se il vero renderer è presente.

Correzione: tutte le funzioni sono ora esportate nella build WASM; inoltre il player tratta i getter e la diagnostica batch come **opzionali**, così un vecchio WASM può ancora fornire LVGL senza abbattere l'intero simulatore. Se manca la diagnostica, il CSV lascia vuoti gli scarti ON e la UI lo segnala. Occorre `git pull`, `source ~/emsdk/emsdk_env.sh`, `./scripts/build_web.sh`, poi aggiornare Chrome con Ctrl+Maiusc+R. Per verificare l'export lanciare `grep -o '_moto_web_replay_batch_sample' platforms/web/shell/runtime/moto-gps-device.js | head`. È una verifica delle stringhe del runtime, non del rendering.


## Correzione semantica «prossima rotonda ≠ strada attuale» — 17 novembre 2026

Il replay `motogps-replay(20261117-072914).jsonl` ha 4.894 snapshot; **4.364** riportano `maneuver: roundabout`. In **874** casi la distanza alla prossima rotonda è **maggiore di 1 km** (fino a 4.736 m). Questo è il tipo della **prossima manovra**, non una classificazione dell'andamento della strada attuale.

**Difetto identificato:** la modalità Stabilizzato trattava tutte quelle istanze come «sto percorrendo una rotonda» applicando tau di 0,12 s e nessuna zona morta; la modalità GPS verificato impediva per tutte quelle istanze la classificazione rettilineo anche quando due vettori GPS successivi erano allineati. Ciò spiega la scarsa differenza percepita rispetto a Originale.

**Correzione JS-only:** Stabilizzato usa la risposta rapida da rotonda/inversione solo quando `turn_m` è valido e non superiore a 35 m (soglia diagnostica, non modifica della navigazione). GPS verificato classifica il rettilineo dalla traiettoria GPS indipendentemente dalla manovra futura. Aggiunti test con rotonda lontana e vicino all'uscita. Restano limiti del GPS reale e della direzione fra fix: non è una soluzione verificata in auto, né replica la fusione IMU ESP32.

**Comandi:** aggiornare il branch `test/unified-offline-map-20261115` e usare **Ctrl+Maiusc+R** nel simulatore già attivo con WASM. **Non occorre ricompilare Emscripten** per questa patch JavaScript, se LVGL era già attivo.


## Esperimento Course multifix: bassa velocità e distanza percorsa (17/11/2026)

Nuova modalità Course multifix nel selettore heading del simulatore. La bassa velocità non aumenta necessariamente la frequenza dei fix: riduce la loro distanza spaziale, peggiorando la direzione calcolabile tra coordinate affette da rumore. Il prototipo JS usa fino a 6 secondi e minimo 4 posizioni distinte, con avanzamento stimato >=16 m, netto >=14 m e RMS laterale <=4,5 m. Se insufficienti, mantiene l'heading invece di accettare un salto singolo; le curve richiedono variazioni coerenti tra spostamenti GPS consecutivi. Differisce dalla vecchia modalità GPS verificato: non richiede accordo di 22 gradi con il bearing raw quando la traiettoria multifix è affidabile. Solo simulatore, nessun Android/ESP32/IMU/BLE modificato. A 10-20 km/h può ritardare o perdere vere svolte; non è una modifica pronta per strada. Aggiornare git e Ctrl+Maiusc+R: nessun rebuild WASM richiesto.

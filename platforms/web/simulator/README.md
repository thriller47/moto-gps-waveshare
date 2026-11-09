# Simulatore Moto Chat GPS per Windows (anteprima 2D + WebAssembly LVGL)

**Stato:** integrato nel branch `valhalla` il 9 novembre 2026 (PR #73), con due modalità separate. **Anteprima geometrica 2D** immediata (JavaScript, non è la grafica LVGL del firmware) e **renderer LVGL + NavPresenter reali** quando si compila il runtime WebAssembly. Non richiede ESP32 né GPS del PC. Nessun tracciato viene caricato su un server. FPS del browser e latenza BLE non rappresentano l'hardware.

## Aprire subito l'anteprima 2D senza compilatore

Su Windows, dopo aver aggiornato il branch `valhalla`, aprire direttamente `platforms/web/simulator/index.html` nel browser. Se il runtime WebAssembly non è presente appare **ANTEPRIMA 2D · NON LVGL**: la geometria del percorso e delle strade MotoMap segue coordinate, heading e scala calcolata del tracciato, ma la grafica **non corrisponde esattamente** a quella dell'ESP32. È una modalità di analisi, non una prova del renderer.

Caricare il file `motogps-replay.jsonl` esportato dall'app tramite **Tracciato GPS**. Il file resta nel browser sul proprio PC. L'anteprima è utilizzabile anche senza Python o Emscripten; per il **renderer LVGL reale** servono invece la compilazione e un server HTTP locale, come segue.

## Nuova prova: zoom intelligente tornanti (sperimentale, solo nel simulatore LVGL)

Dopo aver aggiornato `valhalla`, **ricompilare** il runtime con `source ~/emsdk/emsdk_env.sh` e `./scripts/build_web.sh`, ricaricare la pagina con **Ctrl+F5**, poi caricare il tracciato del 6 novembre. L'interruttore **Zoom tornanti sperimentale** si abilita solo quando il vero WASM è caricato. A parità di frame, OFF mostra lo zoom originale (1× lontano dalle manovre; fino a 4× sulla rotatoria), ON cerca nella finestra della **linea verde** un cambio di direzione marcato, lo ingrandisce progressivamente fino a 3× e mantiene il massimo fra quello e lo zoom tradizionale. Lo zoom mostrato nella pagina, con LVGL attivo, proviene dal **NavPresenter C++** (non da una formula JavaScript).

Inizia dal tratto **12:10:50 e successivi**, confrontando lo stesso frame con OFF e ON. Il rilevamento non si basa sui waypoint, non ricalcola la rotta Valhalla, non invia nulla al BLE e **richiede un flag separato nelle build ESP32 (OFF di default su `valhalla`)**. Rimane una prova esplorativa: può essere sensibile a finestre geometriche parziali, rotonde e curve a S. Il firmware sperimentale con zoom tornanti e subpixel attivi è stato provato su strada il 9 novembre; la build standard di `valhalla` mantiene i flag disattivati.

## Movimento a scatti con snapshot GPS ripetuti (8 novembre 2026)

Una registrazione reale conteneva **4.732 snapshot UI ma soltanto 1.178 fix GPS**. Il vecchio player interpolava solo due snapshot adiacenti: se riportavano coordinate identiche la mappa restava ferma, poi saltava alla successiva posizione GPS anche con RAF/WASM/LVGL regolari. Il player ora precalcola gli intervalli tra **fix GPS distinti** e interpola posizione, rotta angolare breve e velocità durante Play; conserva gli snapshot originali con Step/Seek. Non interpola attraverso ricalcoli/cambi di rotta, navigazione non attiva, buchi GPS oltre 3 s o spostamenti anomali oltre 100 m. Lo zoom tornanti e l'algoritmo LVGL non sono cambiati.

**Prova veloce:** su `valhalla` fare `git pull --ff-only origin valhalla`, poi in Chrome `Ctrl+F5` e ricaricare il replay privato. **Questa correzione è solo JavaScript:** non richiede ricompilare WebAssembly se è già stato ricompilato dopo la modifica al timer di refresh LVGL da 15 ms. Confrontare alle 12:10:50 a 1×; se restano scatti, registrare entrambi i riquadri di diagnostica e distinguere jerk del GPS da irregolarità del disegno. Nessuna modifica a ESP32, Android, BLE o `valhalla`.

## Compilazione con Emscripten

Servono Git, CMake, Emscripten/emcmake attivati e il submodule LVGL.

Da WSL/Linux, nella radice del repository:

    cd ~/MotoGPS/moto-gps-waveshare
    git switch valhalla
    git pull --ff-only origin valhalla
    git submodule update --init --recursive
    source ~/emsdk/emsdk_env.sh
    ./scripts/build_web.sh

In un secondo terminale WSL (non riavviare il server se è già attivo):

    cd ~/MotoGPS/moto-gps-waveshare
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

Nell'app Android aggiornata aprire "LOG". Selezionare **Avvia registrazione GPS** prima della navigazione o del passaggio da riprodurre, percorrere la strada, quindi **Ferma registrazione GPS**. Nello stesso pannello usare **Esporta tracciato simulatore (.jsonl)** e salvare il file su Windows; è condiviso solo su richiesta, separatamente dallo ZIP diagnostico ordinario.

La registrazione contiene coordinate GPS, velocità, posizione predetta inviata all'ESP32, manovre e distanze, finestre della rotta verde e scene MotoMap selezionate per BLE. I log standard NON includono questi dettagli. La registrazione è disattivata di default, non persiste dopo un riavvio dell'app e si interrompe automaticamente dopo 60 minuti o circa 24 MB.

**Privacy:** il tracciato rivela i propri spostamenti. Non allegarlo pubblicamente a issue o commit. Il browser apre il file localmente e non lo carica su server. La registrazione memorizza ciò che Android ha tentato di inviare su BLE; non garantisce la consegna radio, che resta verificabile nei log ESP32.

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

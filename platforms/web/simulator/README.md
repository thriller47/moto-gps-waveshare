# Simulatore Moto Chat GPS per Windows (WebAssembly + LVGL)

**Stato:** prima implementazione sperimentale. La stessa UI LVGL del firmware viene eseguita nel browser tramite il programma C++ WebAssembly e il suo NavPresenter. Non richiede ESP32, GPS del PC o rete per il replay dopo aver caricato la pagina. Gli FPS del browser **non** rappresentano quelli dell'hardware.

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

Il compilatore genera "platforms/web/shell/runtime/moto-gps-device.js" e il relativo WASM (ignorati da Git). Aprire la pagina **via HTTP**, non con un doppio clic su un file HTML. Se compare "Runtime WASM assente", compilare prima il progetto e controllare Emscripten e i submodule.

La pagina storica "platforms/web/shell/index.html" è separata: il suo esempio Jinan/AMap è legacy. Non è il nuovo banco prova.

## Registrare il giro Android: dati sensibili

Nel nuovo APK di test aprire "LOG". Selezionare **Avvia registrazione GPS** prima della navigazione o del passaggio da riprodurre, percorrere la strada, quindi **Ferma registrazione GPS**. Nello stesso pannello usare **Esporta tracciato simulatore (.jsonl)** e salvare il file su Windows; è condiviso solo su richiesta, separatamente dallo ZIP diagnostico ordinario.

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
- PNG della schermata corrente se il framebuffer WebGL è accessibile al browser.

**Limiti dell'MVP:** riproduce l'inquadratura e il contenuto del display con il codice condiviso, ma non ricalcola il selettore strade Android, la route Valhalla o l'arrivo dei pacchetti BLE. Non può riprodurre i vecchi ZIP privi di coordinate/geometrie. Reimpostando lo snapshot per il seek, le animazioni LVGL non sono una misura fedelissima dei tempi reali. Per confrontare algoritmi Android diversi, occorrerà una seconda modalità che ricalcoli la scena MotoMap a parità di trace GPS, non solo la replichi.

## Verifiche

Il workflow nativo controlla sintassi di "platforms/web/simulator/player.js" con Node; la CI Android esegue la compilazione e i test della app. Il target WebAssembly completo va compilato in un ambiente con Emscripten attivo e successivamente provato nel browser. **Non dichiararlo validato finché il controllo non è stato eseguito.**

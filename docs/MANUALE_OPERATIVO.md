# Moto Chat GPS — Manuale operativo

**Aggiornato:** 24 novembre 2026 · **Repository:** thriller47/moto-gps-waveshare · **Branch stabile:** valhalla · **Branch di lavoro:** NewUI.

## Prima di cominciare — i quattro comandi quotidiani

Aprire **PowerShell di Windows** dal menu Start. Il prompt deve iniziare con `PS`. Non occorre aprire Android Studio, CMD ESP-IDF o Ubuntu per le operazioni automatiche.

| Comando | Cosa fa |
| --- | --- |
| `moto-android NewUI` | Aggiorna il checkout Windows, esegue test/build e installa l'APK sul Samsung. |
| `moto-esp32 NewUI` | Attiva ESP-IDF, aggiorna, verifica sdkconfig, compila, flasha su COM3 e apre il monitor. |
| `moto-motopack NewUI` | Aggiorna il checkout WSL, genera e verifica le mappe, copia il pacchetto in Windows. |
| `moto-simulator NewUI` | Aggiorna WSL, compila WASM, avvia il server locale e apre il browser. |

Sostituire NewUI con il branch desiderato, per esempio valhalla dopo l'integrazione. I launcher sono disponibili sul branch NewUI e vengono installati fuori dal checkout; il branch da compilare deve avere una configurazione compatibile. **Collaudi riferiti dall'utente:** launcher ESP32 COM4 funzionante, MotoPack v3 generato manualmente e copiato con CopyOnly, import e quote offline funzionanti, simulatore avviato con apertura browser. Restano da verificare la nuova gestione Ctrl+C e gli altri percorsi automatici non ancora provati. La build Android manuale e il nuovo layout Road DNA sono stati confermati dall'utente.

### Installazione iniziale o aggiornamento dei launcher

Solo la prima volta o dopo modifiche agli script, da PowerShell Windows:

~~~powershell
& {
    Set-Location "C:\MotoGPS\moto-gps-waveshare" -ErrorAction Stop
    git switch NewUI
    if ($LASTEXITCODE -ne 0) { throw "Cambio branch fallito" }
    git pull --ff-only origin NewUI
    if ($LASTEXITCODE -ne 0) { throw "Aggiornamento fallito" }
    .\install-moto-commands.ps1
}
~~~

Chiudere e riaprire PowerShell. L'installer controlla la sintassi PowerShell dei quattro launcher prima di copiarli e dei due script con percorso riscritto prima di salvarli; in caso di errore interrompe l'installazione. L'installer salva il profilo precedente, aggiorna il proprio blocco senza duplicarlo e conserva il comando moto. Copia i launcher in LOCALAPPDATA. Non modifica la policy di esecuzione; se bloccata, vedere §4.C.

### Opzioni e precauzioni

| Comando | Funzione |
| --- | --- |
| moto-android NewUI -BuildOnly | Test e build senza installare. |
| moto-esp32 NewUI -BuildOnly | Build senza flash. |
| moto-android NewUI -Serial SERIAL | Seleziona un altro telefono ADB. |
| moto-esp32 NewUI COM4 | Seleziona COM4 (PC di casa); COM3 resta il default. |
| moto-esp32 NewUI -Port COM5 | Sintassi alternativa per selezionare un'altra porta seriale. |
| moto-motopack NewUI -Resume | Riprende senza aggiornare il checkout WSL. |
| moto-motopack NewUI -CopyOnly | Verifica e copia il pacchetto già completato. |
| moto-motopack NewUI -Threads 4 | Usa quattro thread invece di due. |
| moto-simulator NewUI -BuildOnly | Compila WASM senza avviare il server. |

Se il solo file versionato modificato è `platforms/esp32/sdkconfig` (modifica non in staging), i launcher Android ed ESP32 ne salvano una copia verificata in `%LOCALAPPDATA%\MotoGPS\sdkconfig-backups` e ripristinano la versione Git prima dell'aggiornamento. La build ESP32 parte dalla configurazione del branch e riapplica i default prestazionali; eventuali personalizzazioni locali sono conservate nel backup, non riapplicate automaticamente. Modifiche in staging, conflitti, cancellazioni o altri file versionati bloccano ancora l'operazione. Se il pull manuale per aggiornare l'installer è bloccato, salvare sdkconfig con un nome univoco e usare `git restore --worktree -- platforms/esp32/sdkconfig` solo dopo aver verificato che sia una semplice modifica non in staging.

Per ESP32, il launcher verifica che la porta selezionata esista prima di aggiornare o compilare (salvo `-BuildOnly`); non verifica che sia libera o che appartenga alla scheda. Per elencare le porte da PowerShell: `[System.IO.Ports.SerialPort]::GetPortNames()`. Se la porta è occupata, chiudere il monitor precedente. Se la build è già riuscita e il solo flash è fallito, aprire CMD ESP-IDF con il comando `moto`, entrare in `C:\MotoGPS\moto-gps-waveshare\platforms\esp32` ed eseguire `idf.py -p COM4 flash monitor`.

Collegare il dispositivo corretto prima di installare/flashare; chiudere eventuali monitor seriali. Non avviare Android ed ESP32 contemporaneamente sullo stesso checkout. Non avviare un secondo MotoPack né aggiornare il checkout WSL durante la generazione. Il pacchetto finale viene copiato in C:\MotoGPS\MotoChatGPS-Italia.motopack dopo verifica e confronto dei byte; l'importazione sul telefono rimane manuale (§2). Conservare il vecchio pacchetto se serve un rollback.

### Dove trovare le procedure di recupero

- §1: repository, ambienti e preparazione WSL; §2: MotoPack, ripresa e importazione.
- §3: Android, GitHub Actions, compilazione manuale e audit 16 KiB.
- §4–5: ESP-IDF, configurazione, flash manuale.
- §6–8: diagnostica, simulatore e GPS; §9: radio; §10: navigazione e tour; §11: riferimenti rapidi.

Su PC nuovo installare prima Git, Android SDK/JDK, ESP-IDF o WSL secondo l'operazione. **CMD e WSL restano strumenti di recupero e diagnostica**, non il punto di partenza quotidiano. Se il prompt è CMD, digitare exit per tornare a PowerShell; non eseguire comandi PowerShell in CMD. Non forzare Git, cancellare i dati Android o eseguire erase-flash per risolvere errori di build.

Stato e collaudi: [PROJECT_STATE.md](../PROJECT_STATE.md), [ROADMAP.md](ROADMAP.md). Per istruzioni precedenti consultare lo [snapshot del manuale prima di questa riorganizzazione](archive/MANUALE_OPERATIVO_PRE_AUTOMAZIONE_2026-11-24.md).

## 1. Repository e ambienti

**Sorgenti originali delle mappe (Windows):** `C:\MotoGPS\PBF`. **Repository usato in WSL:** `/home/mod/MotoGPS/moto-gps-waveshare`, ossia `~/MotoGPS/moto-gps-waveshare`. **Distribuzione WSL verificata sul PC:** `Ubuntu-24.04` (versione WSL 2). Il checkout Windows `C:\MotoGPS\moto-gps-waveshare`, quando presente, è una **copia distinta**: aggiornare e compilare dalla copia che si sta realmente usando.

Per **aggiornare il branch stabile in WSL** (terminale Ubuntu):

```bash
cd ~/MotoGPS/moto-gps-waveshare
git status --short
git fetch origin --prune
git switch valhalla
git pull --ff-only origin valhalla
git submodule update --init --recursive
git branch --show-current
```

Se `git switch` indica che si è già sul branch ma un nuovo script non esiste, fare `git pull --ff-only`: il branch locale può essere indietro rispetto a GitHub. Controllare `pwd` e `git branch --show-current`. Se `git status --short` mostra modifiche locali, valutarle prima del cambio branch; **non usare `reset --hard` o `git clean` per forzare l'aggiornamento**.

Per aggiornare invece la **versione integrata stabile** in **PowerShell Windows**:

```powershell
Set-Location "C:\MotoGPS\moto-gps-waveshare"
git status --short
git fetch origin --prune
git switch valhalla
git pull --ff-only origin valhalla
git submodule update --init --recursive
```

Il repository non usa più `main`. Il branch `test/*` resta separato da `valhalla` fino a collaudo e approvazione del merge. Un `git pull` non aggiorna automaticamente l'APK, il firmware o le mappe sul telefono.

## 1.1 Preparare WSL Ubuntu e l'ambiente `valhalla-env` da zero

Questa procedura si usa **solo quando si prepara un nuovo PC o si ricrea l'ambiente WSL**. Sul PC attuale, dove `~/valhalla-env` è già disponibile, **non cancellarlo e non ricrearlo**: passare direttamente a «Riattivare e verificare l'ambiente». Tutti i comandi Linux vanno eseguiti in **Ubuntu WSL**, non in PowerShell/CMD Windows. I comandi di installazione richiedono internet.

### A. Installare WSL 2 e Ubuntu 24.04 (una sola volta)

Da **PowerShell Windows come amministratore**, solo se Ubuntu WSL non è ancora presente:

```powershell
wsl --install -d Ubuntu-24.04
```

Se Windows richiede il riavvio, riavviare e completare la creazione dell'utente Linux. Prima di tentare una nuova installazione controllare:

```powershell
wsl --list --verbose
wsl --set-default-version 2
wsl -d Ubuntu-24.04
```

Nel tuo ambiente già configurato non serve ripetere `wsl --install`. I file originali di Windows sono raggiungibili in WSL da `/mnt/c/MotoGPS/PBF`; non spostare i grandi file temporanei di build su `/mnt/c`.

### B. Dipendenze di sistema in Ubuntu

Aprire il terminale **Ubuntu-24.04**:

```bash
sudo apt update
sudo apt install -y \
  python3 python3-venv python3-pip python3-dev \
  build-essential cmake pkg-config git zstd \
  libffi-dev libssl-dev
```

`zstd` serve per leggere `photon-italy.jsonl.zst` e comprimere `valhalla_tiles.tar.zst`. Il compilatore e gli header Python servono come fallback se qualche pacchetto Python non dispone di una wheel precompilata. Il progetto usa inoltre `python3`, `bash`, `git` e gli eseguibili Valhalla. **Non** installare pacchetti Python nel Python di sistema con `sudo pip` o `--break-system-packages`.

### C. Creare l'ambiente Python dedicato

Eseguire **solo se `~/valhalla-env` non esiste già**:

```bash
python3 --version
if [ -e "$HOME/valhalla-env" ]; then
  echo "valhalla-env esiste già: non lo sovrascrivo"
else
  python3 -m venv "$HOME/valhalla-env"
fi
source "$HOME/valhalla-env/bin/activate"
python -m pip install --upgrade pip wheel
python -m pip install "pyvalhalla==3.9.1" osmium shapely
```

`pyvalhalla==3.9.1` è la **versione utilizzata dal progetto** per generare Valhalla V5; non aggiornarla automaticamente a versioni più recenti senza verificare compatibilità e formato dei grafi. Il pacchetto PyPI `osmium` fornisce le API Python di **pyosmium** (`import osmium`), mentre `shapely` serve al convertitore MotoMap. Con il Python standard di Ubuntu 24.04 (normalmente 3.12) sono disponibili pacchetti wheel Linux per queste dipendenze; se un'installazione prova a compilare codice nativo, leggere l'errore prima di cambiare versione Python o toolchain.

### D. Riattivare e verificare l'ambiente ogni volta

In **ogni nuova sessione Ubuntu WSL** prima di generare le mappe:

```bash
source "$HOME/valhalla-env/bin/activate"
which python3
python3 -m pip show pyvalhalla osmium shapely
python3 -c "import osmium, shapely, valhalla; print('Valhalla / osmium / Shapely: import OK')"
```

Verificare che `which python3` punti a `~/valhalla-env/bin/python3` e che `pip show pyvalhalla` riporti **3.9.1**. Per gli strumenti effettivamente utilizzati dagli script di costruzione:

```bash
for tool in valhalla_build_config valhalla_build_tiles \
            valhalla_build_extract zstd; do
  command -v "$tool" || echo "MANCANTE: $tool"
done
python3 -c "import osmium, shapely; print('osmium OK; Shapely', shapely.__version__)"
```

Se non compare un `valhalla_build_*`, non assumere che la build sia pronta: controllare che l'ambiente sia attivo, ripetere `python -m pip show pyvalhalla` e usare la diagnostica ufficiale `python -m valhalla valhalla_build_tiles -h`. **I nostri script richiedono comunque che i comandi `valhalla_build_*` siano risolvibili sul `PATH`**; il modulo Python da solo non sostituisce tale requisito.

Se `valhalla-env` **esiste già** ma mancano solo i nuovi moduli POI:

```bash
source "$HOME/valhalla-env/bin/activate"
python -m pip install osmium shapely
python3 -c "import osmium, shapely; print('POI: dipendenze OK')"
```

Non reinstallare pyvalhalla né cancellare `valhalla-env` se la build V5 precedente funziona.

### E. Preparare il repository e verificare gli input

In Ubuntu WSL, con ambiente attivo:

```bash
cd ~/MotoGPS/moto-gps-waveshare
git status --short
git fetch origin --prune
git switch valhalla
git pull --ff-only origin valhalla

ls -lh /mnt/c/MotoGPS/PBF/italy-latest.osm.pbf \
       /mnt/c/MotoGPS/PBF/italy.poly \
       /mnt/c/MotoGPS/PBF/photon-italy.jsonl.zst
df -h "$HOME"
```

Il PBF deve corrispondere alla regione del file `.poly` e il dump Photon deve essere in formato JSONL Nominatim/Photon 0.1.x, non un indice Lucene già compilato. Controllare lo spazio disponibile: la rigenerazione mantiene temporaneamente **vecchia e nuova build** e crea grafi intermedi di dimensioni notevoli.

### F. Test rapido prima della rigenerazione (facoltativo)

Dalla radice del repository, nell'ambiente attivo:

```bash
python3 -m unittest discover \
  -s scripts/offline_map -p 'test_add_poi_to_photon.py' -v
```

Questo controlla lo script POI con dati sintetici; **non** è una prova di generazione completa sul PBF Italia. Per avviare la ricostruzione reale seguire la sezione 2. In caso di errori fermarsi prima di sostituire i dati esistenti. Gli strumenti POI e la procedura integrata sono ancora **da collaudare con l'intero dataset italiano** nel branch sperimentale.

### Riconnessione BLE automatica dopo allontanamento (20 novembre 2026 — collaudata)

Se la connessione tra telefono ed ESP32 si interrompe **involontariamente** (es. allontanamento con errore GATT 19, oppure altra perdita link), Android riprova la scansione del servizio BLE automaticamente. Il recupero applica scansioni di **12 secondi**, separate da pause crescenti di **1,5 / 3 / 6 / 12 / 30 secondi** (poi 30 secondi). Quando il collegamento torna disponibile, il normale handshake riattiva il precedente ripristino della rotta (geometria e snapshot) senza chiedere di avviare nuovamente la navigazione. La pressione volontaria di **Disconnetti** e la chiusura dell'app interrompono i tentativi; non invia richieste ripetute di avvio radio all'ESP32.

**Test richiesto:** avviare una navigazione, allontanarsi finché BLE si disconnette, tornare entro il raggio BLE senza premere Connetti e controllare se si ristabilisce automaticamente la sessione. La diagnostica riporta `BLE_RECONNECT phase=lost`, `phase=scheduled delay_ms=...`, `phase=scan`, `phase=scan_timeout` o `phase=scan_failed`. Verificare anche che la disconnessione manuale **non** riavvii la scansione e che, al rientro, rotta e navigazione siano immediatamente disponibili. **Confermata dall'utente al primo tentativo su dispositivo**, senza necessità di ricollegarsi manualmente.

### Pausa caffè — collaudata (21/11/2026)

**Branch di riferimento:** `valhalla` (collaudo in `test/coffee-pause-20261120`). L'utente conferma spegnimento alla scadenza, riconnessione automatica e risveglio prima del limite anche con schermo del telefono spento. Il fix Android alterna `autoConnect` nativo per 30 s verso l'ultimo dispositivo noto e scansione filtrata di 12 s; handshake incompleto chiuso dopo massimo 20 s dalla connessione e ritentato con backoff. Eventi aggiuntivi `BLE_RECONNECT phase=system_connect/connect_timeout/connect_failed/ready`.

- Dall'app Android: **⋮ → ☕ Pausa caffè**. Selezionare un limite di **1–30 minuti** (predefinito **10 minuti**) e premere **Salva**. L'impostazione rimane nelle preferenze Android; viene inviata all'ESP32 mediante un messaggio BLE `PowerPolicy (0x23)` **dopo ogni handshake riuscito** e anche quando la si modifica durante una connessione. L'ESP trattiene il valore in RAM durante l'allontanamento, senza dipendere dal telefono; a ogni nuova accensione viene reimpostato a 10 minuti fino al primo collegamento Android.
- Dopo **30 secondi senza connessione BLE** e dopo che c'è stata almeno una connessione: luminosità del CO5300 a zero (OLED nero), rendering IMU e campionamento software ridotti. **NimBLE resta attivo in advertising**, quindi Android può rilevare e riconnettere l'ESP. Non è deep sleep in questa fase; TE e controller display restano inizializzati per un risveglio rapido.
- Se il BLE ritorna prima della scadenza: luminosità a 100, campionamento IMU regolare e ri-sincronizzazione navigazione dal telefono **senza riavviare la rotta**.
- Se l'assenza BLE dura per il limite configurato, misurato **dall'inizio dell'assenza** (non da 30 secondi dopo), il dispositivo esegue lo **spegnimento AXP2101**, con fallback deep sleep come prima. Dopo lo spegnimento il BLE **non può riaccendere** il dispositivo: usare il pulsante fisico.
- **Chiusura volontaria (NewUI, prova precedente positiva; correzione pausa successiva da collaudare):** Disconnetti o chiusura normale dell'app inviano `Closing` prima di interrompere BLE; ESP32 resta sveglio e si spegne dopo 120 s, salvo riconnessione. Se Android termina forzatamente il processo o il messaggio non arriva, resta la pausa caffè. Verificare con APK e firmware aggiornati: log Android `BLE_POWER_POLICY phase=voluntary_closing`, seriale ESP32 `Voluntary phone closing received`.
- **Arrivo a destinazione:** conserva il precedente autospegnimento dopo **120 secondi** dall'arrivo, anche in caso di successiva disconnessione. Il pulsante PWR con pressione di **3 secondi** resta invariato.
- Non viene inviato un comando ripetuto per avviare la radio. Un ESP32 con firmware precedente ignora il nuovo controllo opzionale e conserva il suo comportamento precedente.

**Test consigliati:** aggiornare **sia APK sia firmware ESP32**, aprire la voce Pausa caffè e selezionare per esempio **1 minuto** per una prova rapida; con navigazione attiva allontanarsi, verificare schermo nero a ~30 s, rientrare prima di 1 min e verificare risveglio e rotta già pronta. Ripetere lasciando scadere 1 min e controllare lo spegnimento (senza aspettarsi risveglio BLE). Impostare 10/30 min per verificare la sincronizzazione configurazione. Riprovare anche arrivo a destinazione e pulsante manuale. Leggere log seriale ESP32 `COFFEE_PAUSE state=entered/resumed`, `Coffee pause auto-off timeout: N min`, `Power off requested: coffee pause timeout...` e diagnostica Android `BLE_POWER_POLICY`.

**Correzione NewUI del 24/11:** un link fisico o Starting non azzera più la scadenza; serve Phone Ready valido. Closing sopravvive ai tentativi incompleti. La correzione è implementata ma non ancora collaudata su ESP32.

**Nota consumi:** schermo nero e polling IMU più lento dovrebbero ridurre il consumo; **l'autonomia effettiva non è misurata** e va verificata con batteria/USB nelle diverse condizioni.

### Road DNA Explorer (sperimentale, NewUI)

Con il MotoPack già installato, aprire **Crea tour**, lasciare attivo **Explorer**, scegliere 30–300 km, direzione N/E/S/O e profilo Curve, Panorami o Touring. Aggiungere eventuali tappe obbligatorie nell'ordine desiderato. Toccare **Genera itinerari**: vengono calcolati fino a otto candidati sul grafo locale, senza inviare richieste online. Nell'anteprima scegliere una delle massimo tre varianti distinte per vedere il percorso e le sue tappe; MotoScore, curve, tornanti e ripetizioni sono stime, dislivello e luoghi possono essere non disponibili. La distanza è indicativa; verificare sempre il giro reale. Con più tappe obbligatorie può risultare una sola variante. È possibile salvare o avviare il tour selezionato; l'avvio ricalcola con le tappe scelte dalla posizione corrente. Disattivare Explorer per usare il normale tour singolo anche online. Non serve rigenerare il MotoPack o aggiornare ESP32 per questa funzione. Prima prova: `moto-android NewUI`, poi modalità aereo con GPS e grafo locale disponibile. Funzione non ancora collaudata.

### DNA stradale Italia — indice sperimentale separato (NewUI)

Non rigenerare il MotoPack. In Ubuntu WSL, nella copia Git già configurata:

```bash
cd ~/MotoGPS/moto-gps-waveshare
git fetch origin
git switch NewUI
git pull --ff-only origin NewUI
source ~/valhalla-env/bin/activate
python3 -m pip install osmium
mkdir -p ~/motogps-build/RoadDNA-Italia
python3 scripts/offline_map/road_dna_to_sqlite.py \
  /mnt/c/MotoGPS/PBF/italy-latest.osm.pbf \
  --output ~/motogps-build/RoadDNA-Italia/road-dna-italy.sqlite
cp ~/motogps-build/RoadDNA-Italia/road-dna-italy.sqlite /mnt/c/MotoGPS/road-dna-italy.sqlite
```

Se disponibile, aggiungere al comando Python **--elevation /percorso/esatto/elevation-italy.zip** prima di avviarlo (usa il DEM già generato, non lo scarica). Senza DEM l'indice funziona con rilievo non disponibile. Il generatore rifiuta di sovrascrivere un indice esistente: usare un altro nome o spostare il precedente dopo averlo salvato. Non modifica PBF, DEM o MotoPack. Usare lo stesso PBF del grafo installato per ridurre incongruenze.

Da PowerShell: `moto-android NewUI`. Copiare il file SQLite nella cartella Download del telefono (USB/MTP), aprire **Mappe offline → Opzioni avanzate → Importa DNA stradale SQLite** e selezionarlo. In **Crea tour**, con Explorer attivo e senza tappe obbligatorie, si può attivare/disattivare **DNA stradale · giri mirati**. Gli otto candidati originali rimangono disponibili; fino a quattro aggiuntivi mirano a tratti indicizzati, le schede selezionate di questo tipo hanno il suffisso Mirato. La geometria e il percorso sono comunque calcolati da Valhalla. Con tappe obbligatorie il generatore resta quello precedente. Non occorre flash ESP32. Verificare tempi, dimensione SQLite e risultato sulla mappa; funzione non ancora collaudata. Dati OSM © OpenStreetMap contributors, ODbL.

## 1.2. Road DNA online — chiavi Geoapify e OpenTripMap

**Non serve alcuna chiave per la navigazione o per Road DNA offline.** Le funzioni online sono integrate in `valhalla`; il funzionamento dei singoli provider e la completezza delle schede dipendono dalle credenziali, dalla rete e dai dati disponibili.

1. Ottenere **chiavi personali** sui portali https://myprojects.geoapify.com e https://dev.opentripmap.org. Non inviarle in chat, non salvarle nel repository e non inserirle negli screenshot diagnostici.
2. Nella nuova APK Android aprire **⋮ → API Road DNA**. Inserire la chiave Geoapify e premere **Salva**; ripetere per OpenTripMap. Lo stato conferma se ciascun provider e' configurato; usare **Verifica** per provare una richiesta HTTPS minima o **Elimina** per rimuovere la chiave. I campi sono mascherati, il valore non viene ripresentato dopo il salvataggio.
3. Pianificare un itinerario, aprire **Road DNA · Guida e luoghi da scoprire** e leggere l'analisi locale. Solo con il tasto **Cerca online (su richiesta)** vengono inviate coordinate ai provider: a Geoapify un corridoio di 2 km attorno alla geometria Valhalla, a OpenTripMap fino a cinque punti campione lungo la rotta. Selezionare **Dettagli OpenTripMap** per richiedere la descrizione di un luogo specifico; per un risultato Geoapify e' disponibile **Cerca scheda su OpenTripMap**, con abbinamento conservativo per nome e posizione entro 200 m (nessuna scheda se non e' verificabile). Senza chiavi la ricerca non parte; con una sola chiave funziona solo il relativo provider.
4. Controllare risultati, indicazione di copertura parziale e distanze in linea d'aria: non equivalgono a accessi stradali, deviazioni o visibilita' dei luoghi in moto. Non aggiungere ancora questi POI automaticamente come tappe.

**Protezione delle credenziali:** Android Keystore + AES-GCM, file `noBackupFilesDir` esclusi dai backup Android, niente chiavi nel MotoPack, nei log o nel codice. Geoapify riceve `x-api-key` via HTTPS, OpenTripMap richiede `apikey` come parametro URL HTTPS. Evitare di registrare traffico completo o incollare link con chiavi. Nessuna chiamata periodica, nessun accesso a POI durante navigazione, nessun cambiamento ESP32/BLE.

**Controlli utili dopo aggiornamenti:** test JUnit `RoadDnaOnlineTest.kt`; test manuale `Verifica` per entrambi i provider con le proprie credenziali, ricerca su itinerario reale (Sestriere/Lago Maggiore), cambio rotta durante caricamento, assenza rete, errore 401/429 e controllo che dati e URL con chiavi non entrino nella diagnostica. Una CI finale soltanto quando autorizzata.

## 2. Rigenerazione completa MotoPack — procedura consigliata

### MotoPack v4 e routing motorcycle nativo (NewUI, 25/11/2026)

Il nuovo pacchetto v4 aggiunge l'indice DNA stradale al MotoPack v3 e ricrea il grafo V6 con pyvalhalla 3.9.3. Valhalla Mobile 0.6.6 resta sul motore 3.9.2; compatibilità di lettura e risultati sul telefono da verificare. Il nuovo importatore accetta v4; non è necessario disinstallare Android, cancellare mappe o flashare ESP32. I profili Touring/Panoramico usano costing motorcycle; il livello curve 0–3 vale 0.5/0.6/0.8/1.0, Panoramico 0.8, uso trails 0, exclude_unpaved true. Le preferenze vengono applicate anche a tappe e ricalcoli. Rapido, breve e secondarie mantengono auto. Il server online potrebbe non supportare la preferenza curve.

**Sul Ryzen, Ubuntu WSL** (non durante una generazione in corso):

```bash
cd ~/MotoGPS/moto-gps-waveshare
git fetch origin
git switch NewUI
git pull --ff-only origin NewUI
source ~/valhalla-env/bin/activate
python -m pip install --upgrade "pyvalhalla==3.9.3"
python -m pip show pyvalhalla
bash scripts/offline_map/rebuild_motopack_wsl.sh --threads 2 --out "$HOME/motogps-build/ItaliaUnificata-RoadDNA-v4"
cp "$HOME/motogps-build/ItaliaUnificata-RoadDNA-v4/MotoChatGPS-Italia.motopack" /mnt/c/MotoGPS/MotoChatGPS-Italia.motopack
```

Il builder riutilizza la cache DEM e ricrea gli indici dal PBF. Per ripresa usare lo stesso comando con --resume, senza aggiornare branch o ambiente. Per usare moto-motopack NewUI occorre reinstallare i launcher aggiornati dal repo Windows, come in apertura. Aggiornare Android con moto-android NewUI, poi importare v4 in Mappe offline. Il pack precedente su WSL rimane separato. Prova necessaria: percorsi locali Touring con 0 e 3 curve, Panoramico, Explorer con tappe, ricalcolo, itinerario lungo e controllo sterrati noti. Costing motorcycle è ancora beta; non garantire che strade o accessi OSM siano aggiornati.

### Nuovo MotoPack v3 con quote DEM (branch `NewUI`, 23/11/2026 — **sperimentale, non ancora collaudato**)

Questa estensione **non è ancora parte del branch stabile `valhalla`**. In `NewUI` il pacchetto unificato diventa **MotoPack v3**: MotoMap v4, Photon v2, Tourist/Road DNA v1, grafo Valhalla V5 e archivio DEM Skadi. **Un MotoPack v2 precedente non è accettato dal nuovo importatore v3**; l'utente ha autorizzato questa incompatibilità per poter ripartire con dati aggiornati. Il firmware ESP32 e il protocollo BLE non cambiano.

**Prima di lanciare la rigenerazione**, in **WSL Ubuntu** (non PowerShell) controllare che il progetto sia davvero sul branch di prova e che siano disponibili gli strumenti:

```bash
cd ~/MotoGPS/moto-gps-waveshare
git switch NewUI
git pull --ff-only origin NewUI
source ~/valhalla-env/bin/activate
command -v valhalla_build_config
command -v valhalla_build_elevation
command -v valhalla_build_tiles
command -v valhalla_build_extract
command -v zstd
ls -lh /mnt/c/MotoGPS/PBF/italy-latest.osm.pbf /mnt/c/MotoGPS/PBF/italy.poly /mnt/c/MotoGPS/PBF/photon-italy.jsonl.zst
```

Se `valhalla_build_elevation` non viene trovato, **fermarsi**: occorre rendere disponibile lo script di download dell'installazione Valhalla compatibile con `valhalla-env`; non modificare il PBF né cancellare il vecchio pacchetto per tentare di risolvere. Il downloader Tilezen richiede **internet e spazio aggiuntivo su disco**, perché scarica i DEM di Italia e zone limitrofe; la generazione e l'importazione dell'APK non sono state ancora verificate sul PC/telefono.

**Procedura ordinaria:** usare moto-motopack NewUI da PowerShell (opzioni e installazione in apertura). Il launcher usa Ubuntu-24.04, valhalla-env e due thread per default, sceglie output v2/v3 dal generatore e verifica il risultato prima della copia Windows. Il lock protegge i launcher concorrenti; non garantisce la protezione da avvii manuali simultanei. Per recupero manuale usare le istruzioni seguenti, senza aggiornare il checkout durante la generazione.

**Alternativa manuale WSL — rigenerazione v3** (dopo un controllo dello spazio libero):

```bash
bash scripts/offline_map/rebuild_motopack_wsl.sh --threads 2 --out "$HOME/motogps-build/ItaliaUnificata-RoadDNA-v3"
```

Il sistema mantiene i tasselli SRTMv3 nella cache persistente `~/motogps-build/DEM-Italia`, **fuori** dalla cartella di output che lo script sostituisce dopo la verifica. Un download successivo può riutilizzare i file già presenti. La cartella può essere cambiata senza spostarla nel PBF:

```bash
MOTOGPS_ELEVATION_CACHE="$HOME/motogps-build/DEM-Italia" \
  bash scripts/offline_map/rebuild_motopack_wsl.sh --threads 2 --out "$HOME/motogps-build/ItaliaUnificata-RoadDNA-v3"
```

Il nuovo componente è `elevation-italy.zip` all'interno di `MotoChatGPS-Italia.motopack`. La verifica Python controlla tipo, SHA-256, lunghezza e presenza minima dei tasselli. L'importatore Android verifica l'intero contenuto prima di installare e copia le quote in `Android/data/com.motogps.android/files/routing/elevation`. Valhalla riceve quel percorso nella configurazione locale. Il grafico delle quote compare **solo nella finestra Road DNA del percorso Android**, aprendo il pannello **Profilo altimetrico**. Se i campioni sono assenti, l'app espone uno stato chiaro e non inventa altitudini.

**Profilo altimetrico in Road DNA:** pianificare un itinerario, scegliere la variante e aprire **Road DNA** dalla scheda della rotta. Sotto l'analisi curve, toccare **Profilo altimetrico — Mostra ▼** per quota minima/massima, salita/discesa e grafico. Toccando **Nascondi ▲** si richiude; il contenuto della finestra scorre indipendentemente dall'anteprima, che non contiene più il pannello. Il pulsante Chiudi resta fuori dal contenuto scorrevole. La resa orizzontale va collaudata. Il pannello non compare durante la guida e l'ESP32 non riceve quote né grafici.

**Quote online quando mancano DEM:** se il percorso non include campioni di quota validi, **l'apertura del solo profilo altimetrico** avvia una singola richiesta al servizio pubblico Valhalla `/height` con la geometria della rotta selezionata (max 4.500 punti, campionamento non più fitto di 100 m). Appare **Recupero online delle quote DEM…**, quindi il grafico viene aggiornato solo con dati validi. Il servizio non è interpellato all'avvio della guida o a ogni ricalcolo. Cambiare variante, generare una nuova anteprima o avviare la navigazione annulla la richiesta e azzera il profilo precedente.

**Road DNA · Guida e luoghi** riunisce curve, profilo DEM espandibile e ricerca turistica offline/online. Il recupero altimetrico resta separato e parte solo aprendo il profilo.

Il recupero online **richiede Internet** e che il server abbia DEM disponibili; senza rete, su HTTP 429/503 o con risposta senza quote, il percorso e l'analisi delle curve restano funzionanti. **Non è prevista alcuna chiamata automatica durante la navigazione o i ricalcoli BLE.** I valori di dislivello ricavati dal server sono comunque indicativi.

**Font del design Dark Touring:** Space Grotesk (titoli e etichette) e Manrope (testo) sono risorse incluse nell'APK `NewUI` e **non richiedono Internet**. I testi SIL Open Font License 1.1 sono nel repository in `docs/licenses/MANROPE_OFL.txt` e `docs/licenses/SPACE_GROTESK_OFL.txt`, con attribuzione nella schermata Informazioni. L'aspetto del font sul telefono resta da validare con la prima build autorizzata.

**Attenzione:** le quote sono DEM, quindi indicative; tornanti e classificazione delle curve sono stimati geometricamente, non misure di aderenza o di velocità consigliata. Il nuovo layout Android è stato compilato e provato con esito positivo secondo l'utente; la generazione/importazione DEM offline resta da collaudare.



### Alternativa manuale WSL — MotoPack v2 (branch valhalla)

**Un unico comando in WSL** ricrea **MotoMap v4** (strade e copertura), **Photon SQLite v2 con POI OSM** (ricerca indirizzi, carburante, ristorazione e alloggi), **Tourist SQLite v1** (Road DNA turistico) e **Valhalla V5** (grafo moto dal PBF aggiornato), quindi assembla e verifica il pacchetto `MotoChatGPS-Italia.motopack`. Usa lo script `scripts/offline_map/rebuild_motopack_wsl.sh`, presente nel branch stabile `valhalla`.

**Sorgenti attuali in `C:\MotoGPS\PBF`:**

- `italy-latest.osm.pbf`
- `italy.poly` (deve corrispondere all'estratto PBF)
- `photon-italy.jsonl.zst`

Il file sorgente eventualmente presente `valhalla_italia_v5.tar.zst` **non è usato dalla rigenerazione completa**: il grafo viene ricostruito dal PBF. I file di input non vengono sovrascritti.

**Da WSL Ubuntu-24.04** (prima configurare e verificare `valhalla-env` con la sezione 1.1; richiesti `pyvalhalla==3.9.1`, `osmium`, `shapely`, `zstd`):

```bash
cd ~/MotoGPS/moto-gps-waveshare
source ~/valhalla-env/bin/activate
ls scripts/offline_map/rebuild_motopack_wsl.sh
df -h ~
bash scripts/offline_map/rebuild_motopack_wsl.sh --threads 2
```

Se `ls` non trova lo script, aggiornare il branch come nella sezione 1. Per il builder servono `valhalla_build_config`, `valhalla_build_tiles`, `valhalla_build_extract`; verificare con `command -v`. **Non lanciare separatamente i tre convertitori**: lo script li esegue già. Il PC Ryzen ha usato normalmente **due thread**, per evitare di saturare RAM e swap.

**Cartella di output v2:**

```text
/home/mod/motogps-build/ItaliaUnificata-RoadDNA-v2/
├── italy-v4.motomap.zip
├── photon-italy-v2.sqlite
├── tourist-italy-v1.sqlite
├── ValhallaItaliaV5/
│   └── valhalla_tiles.tar.zst
└── MotoChatGPS-Italia.motopack
```

Lo script scrive prima su filesystem **Linux WSL**, in una cartella temporanea; **solo dopo verifica SHA-256** sostituisce la cartella finale esistente. In caso di errore preserva sia l'output precedente sia lo staging interrotto per il successivo `--resume`; se trova file estranei fra gli output, si ferma senza cancellarli. Richiede spazio per vecchia e nuova generazione contemporaneamente, oltre ai grafi Valhalla temporanei. Non usare `sudo` o `rm -rf` per ripartire. Per un'altra destinazione: `--out "$HOME/motogps-build/ItaliaUnificata-Nuova" --threads 2`.

**Verifica al termine** (non affermare che la nuova generazione sia valida solo perché MotoMap ha stampato il numero di tile):

```bash
python3 scripts/offline_map/motopack.py verify \
  "$HOME/motogps-build/ItaliaUnificata-RoadDNA-v2/MotoChatGPS-Italia.motopack"
ls -lh "$HOME/motogps-build/ItaliaUnificata-RoadDNA-v2/"
```

### Ripresa dopo un errore durante Road DNA (21/11/2026)

Se il terminale si arresta con `relation_missing_member_way` dopo avere risolto milioni di nodi, alcune relation OSM hanno membri assenti dall'estratto PBF. Il generatore aggiornato **salta solo quelle relazioni**, non pubblica un poligono incompleto e registra `relation_coverage=partial`, `relation_missing_member_way` e i primi ID saltati nella tabella `tourist_metadata`. Controllare poi laghi e aree protette sui percorsi di interesse; non dichiarare copertura turistica assoluta.

Aggiornare prima il branch stabile `valhalla`; dal repository WSL con `valhalla-env` attivo:

```bash
git switch valhalla
git pull --ff-only origin valhalla
PYTHONPATH=scripts/offline_map python3 -m unittest discover -s scripts/offline_map -p 'test_tourist_to_sqlite.py' -v
bash scripts/offline_map/rebuild_motopack_wsl.sh --threads 2 --resume
```

Il wrapper individua la cartella di staging della destinazione (se presente), verifica l'identità dei file sorgenti e riutilizza i componenti completi. Quando `tourist-italy-v1.sqlite.part` contiene un checkpoint nuovo e valido, la ripresa ricostruisce **soltanto i poligoni**, senza rieseguire i tre passaggi PBF. Se la generazione fallita è stata eseguita con la *vecchia* versione dello script, il `.part` e lo staging potrebbero essere stati cancellati automaticamente: in questo caso `--resume` avvia una nuova generazione o riutilizza solamente i file finali ancora esistenti. Non è possibile recuperare dal terminale le coordinate temporanee già eliminate.

Per il builder diretto su una cartella di output permanente, ripetere **gli stessi** `--pbf`, `--poly`, `--photon`, `--out` e aggiungere `--resume` a `build_motopack_wsl.sh`. Non rinominare un vecchio `.part` per aggirare il controllo di provenienza. In caso di checkpoint parziale (prima della fine della terza passata), va ispezionato e va iniziata una nuova generazione in una directory diversa.

A pacchetto generato, verificare la copertura registrata:

```bash
python3 - "$HOME/motogps-build/ItaliaUnificata-RoadDNA-v2/tourist-italy-v1.sqlite" <<'PY'
import sqlite3, sys
with sqlite3.connect(sys.argv[1]) as db:
    print(dict(db.execute("SELECT key,value FROM tourist_metadata")))
    print("integrity:", db.execute("PRAGMA integrity_check").fetchone()[0])
PY
```

La verifica attesa è `OK: MotoMap v4 + Valhalla V5 + Photon v2`, accompagnata dall'esito finale `PRONTO` dello script.

### Accedere alla cartella WSL da Windows

**Metodo immediato dal terminale WSL:**

```bash
explorer.exe "$(wslpath -w "$HOME/motogps-build/ItaliaUnificata-RoadDNA-v2")"
```

**Metodo diretto da Windows:** premere **Win+R** e incollare il percorso della tua distribuzione (non `Ubuntu` generico):

```text
\\wsl.localhost\Ubuntu-24.04\home\mod\motogps-build\ItaliaUnificata-RoadDNA-v2
```

Anche `\\wsl$\Ubuntu-24.04\home\mod\motogps-build\ItaliaUnificata-RoadDNA-v2` è utilizzabile. Se la distribuzione cambia, verificare con `wsl -l -v` da PowerShell. Non spostare la build su `/mnt/c` durante la compilazione.

**Copia finale in Windows:** il launcher la esegue automaticamente. Se il pacchetto è già pronto, usare moto-motopack NewUI -CopyOnly (per v2, il branch corrispondente) senza aggiornare il checkout WSL. Per recupero manuale, da WSL con valhalla-env attivo, scegliere v3 oppure v2 secondo il pacchetto effettivo:

~~~bash
PACK="$HOME/motogps-build/ItaliaUnificata-RoadDNA-v3/MotoChatGPS-Italia.motopack"
DEST=/mnt/c/MotoGPS/MotoChatGPS-Italia.motopack
python3 scripts/offline_map/motopack.py verify "$PACK" &&
  cp -- "$PACK" "$DEST.part" &&
  cmp -- "$PACK" "$DEST.part" &&
  mv -fT -- "$DEST.part" "$DEST"
~~~

Non utilizzare un file .part in Android. Per una build v2 sostituire v3 con v2 nel percorso.

In Windows sarà `C:\MotoGPS\MotoChatGPS-Italia.motopack`. Per aggiornare il telefono, copiare il file `.motopack` nella cartella **Download** del Samsung (per esempio collegando il telefono via USB, selezionando **Trasferimento file** e copiando il file con Esplora file). Poi interrompere la navigazione e aprire **Moto Chat GPS → Mappe offline → Importa MotoPack (.motopack)**, selezionando il file copiato. Non disinstallare l'app per importare le mappe; servono spazio e alimentazione sufficienti durante l'operazione.

**Dopo l'import:** controllare separatamente MotoMap v4, Photon v2, Valhalla V5, Road DNA e (v3) DEM. Verifica integrità del pacchetto e installazione dei componenti sono operazioni distinte: conservare un backup valido.



### Interfaccia Android riorganizzata (branch di integrazione)

Nella schermata iniziale la ricerca destinazione è l'azione principale, seguita da **Crea tour**, **Tour salvati**, collegamento ESP32 e **Mappe offline**. Il tema giorno/notte resta nella barra superiore; **Altre opzioni** ospita diagnostica e registrazione replay GPS, esportazione log e informazioni/licenze. Non sono cambiati algoritmi GNSS/IMU, routing, BLE né firmware.

**Mappe offline:** il pulsante principale **Importa MotoPack (.motopack)** aggiorna MotoMap v4, Valhalla V5 e Photon v2 insieme. Le importazioni singole MotoMap ZIP, indice Photon SQLite e grafo Valhalla, oltre alle opzioni di eliminazione, restano disponibili tramite **Opzioni avanzate · componenti separati**. Controllare lo stato dei tre componenti prima di navigare; non disinstallare l'app per aggiornare le mappe.

**Salta prossima tappa:** disponibile durante **qualsiasi** navigazione con almeno una tappa intermedia ancora pendente, non soltanto nei tour circolari. Toccare il pulsante e confermare da fermi: la tappa corrente viene esclusa solo dopo che Valhalla ha restituito una nuova rotta valida; tappe successive e **destinazione finale** vengono conservate. Sui tour circolari si aggiorna anche il checkpoint di ripresa. Sui percorsi ordinari non si crea un checkpoint circolare. Se ci si dirige direttamente all'arrivo senza tappe intermedie, il pulsante non compare. Sul branch attuale di test è presente anche il pulsante ESP32 **blu `>>`**, con doppio tocco di conferma, visibile quando ci sono tappe residue; il salto tappa di una revisione precedente è stato provato dall'utente. La validazione completa dopo l'ultimo hardening resta pendente. Non attribuire automaticamente questo comportamento a un firmware `valhalla` più vecchio.

### Ricerca indirizzi e POI (offline prima, online come fallback)

**Nella baseline `valhalla`:** cercare una destinazione o una tappa interroga prima Photon SQLite v2 italiano; **se non restituisce risultati**, usa Photon online quando disponibile, anche per luoghi fuori Italia. I risultati offline sono ordinati con preferenza alle corrispondenze pertinenti vicine al GPS; quelli online richiedono fino a 30 candidati e applicano una preferenza geografica analoga, mostrando al massimo otto risultati. Quando il GPS è disponibile, la distanza mostrata è **in linea d'aria**, non chilometri di guida. Una città espressamente indicata (es. `Via Roma, Torino`) prevale sulla vicinanza.

**Verifica ancora richiesta su APK finale:** `Via Roma` vicino, `Via Roma, Torino`, località estera, GPS assente e rete assente. Il database Photon non è garantito come indice esaustivo di categorie POI: trovare un marchio di distributore non dimostra la ricerca di tutti i distributori entro un raggio. Questa modifica non richiede rigenerare MotoPack.

### Strade di contesto quando si naviga online senza MotoPack

Valhalla **online** calcola il percorso ma **non fornisce da solo** le strade grigie dell'ESP32. Nella schermata di anteprima, dopo il calcolo, premere **Avvia navigazione**: se mancano tile del corridoio, l'app propone **Prepara strade OpenFreeMap** e permette **Avvia e scarica** oppure **Avvia senza download**. Lo scaricamento è un'azione esplicita, non parte automaticamente dal GPS; i file restano in cache per gli utilizzi successivi. La rotta verde può partire subito, mentre le strade grigie compaiono dopo la disponibilità dei dati. Il download ha limiti documentati di **3.000 tile / 50 MB**.

**Bug riscontrato durante il test del 20/11 (senza MotoPack):** `MainActivity` non collegava i callback di conteggio tile e download, quindi il dialogo non compariva e il contesto restava vuoto. Corretto e integrato in `valhalla`; per verificare il caso specifico sul dispositivo Per una nuova prova controllare se la UI propone il corridoio e se la diagnostica registra `OFM_COVERAGE` e `OFM_CORRIDOR`; `OSM_CONTEXT segments=0` da solo non certifica un errore del routing.

**Larghezza del corridoio (integrata):** la fascia OpenFreeMap segue la geometria della rotta ed è stata ampliata a **sette tile z14 in totale**: la tile attraversata e tre tile adiacenti per lato, per mantenere strade grigie disponibili anche in caso di piccole deviazioni. Non è una modifica alla zona coperta dal MotoMap italiano. Il comportamento con MotoPack grandi e itinerari estesi resta da verificare sul campo. **Limiti invariati:** massimo 3.000 tile pianificate e 50 MiB scaricabili; una rotta troppo estesa può produrre l'avviso di corridoio troppo grande, senza download automatico in tranche. Il limite di pianificazione si applica prima della sottrazione delle tile già presenti, comportamento da migliorare solo dopo verifica del caso pratico.

**Rotonde con OpenFreeMap (fallback geometrico integrato, da collaudare):** il decoder MVT restituisce geometria reale ma non gli ID OSM originali; per questo il riconoscimento topologico di MotoMap continua a prevalere, mentre in assenza del suo focus viene tentato un riconoscimento geometrico conservativo di circuiti OpenFreeMap con estremi coincidenti e almeno tre bracci. Non si inventano nodi OSM e non si presume connettività quando due linee si incrociano. La diagnostica aggiunge l'evento `OFM_RING` con `edges`, `loops`, `rejected`, `focus`, `exits`. **Per il collaudo online:** installare il nuovo APK, partire senza MotoPack italiano (oppure fuori copertura), autorizzare il corridoio OpenFreeMap, registrare la diagnostica completa durante l'avvicinamento e il passaggio in una rotonda con più ingressi; controllare `OFM_RING focus=1`, l'anello intero e la presenza delle uscite. Eventi `focus=0` non garantiscono un bug: il candidato può essere ambiguo o non chiuso. La selezione delle strade non appartenenti all'anello può ancora variare durante il movimento; **non dichiarare raggiunta la parità online/offline finché la prova non è positiva**. Questa modifica è solo Android, non richiede rigenerazione MotoPack e non modifica il firmware ESP32.

**Copertura mista Italia/Francia:** se hai MotoMap Italia installato e calcoli un itinerario oltre confine, la logica del corridoio verifica **tutta la rotta**, esclude dal conteggio le tile già coperte dal MotoMap e propone di scaricare solo quelle ancora mancanti, anche francesi. È presente un test specifico; verificare comunque il comportamento su tratte reali. **Attenzione:** il download OpenFreeMap aggiunge solo strade di contesto grigie: non estende Valhalla V5 Italia, quindi i percorsi francesi necessitano di routing online salvo disponibilità di un grafo locale adeguato. Se il piano supera 3.000 tile o 50 MB, attualmente non esiste download automatico a tranche; suddividere il viaggio o predisporre dati offline appropriati. Il conteggio viene effettuato in anteprima e va verificata la fluidità su MotoMap grandi.

**Ricerca POI online per categoria (integrata, copertura provider da verificare):** quando la query coincide con una delle parole generiche documentate, Android cerca prima per categoria nel nuovo SQLite POI OSM; se indisponibile o privo di risultati, interroga Photon online con `include=osm.amenity.*` / `osm.tourism.*`, **senza `q` testuale** e con `bbox` limitata alla zona GPS (raggi progressivi 15, 50, 150 km; fino a otto risultati ordinati per distanza). Il risultato viene verificato nuovamente tramite `osm_key`/`osm_value` e distanza. Se non c'è fix GPS, o il server non supporta i filtri, la ricerca generica restituisce nessun risultato: **non vengono mostrate attività lontane perché contengono 'trattoria' o 'hotel' nel nome**. Query di indirizzo o nome proprio (es. `Trattoria da Mario`, `Via Roma, Torino`) restano ricerche testuali normali. Nessun Overpass, nessuna rigenerazione MotoPack richiesta per questa correzione: serve nuovo APK. **Compatibilità effettiva del filtro con il server pubblico Photon e test Android non ancora verificati su dispositivo.**

### POI offline: carburante, ristoranti, bar e alloggi

Nella versione integrata la procedura **unica** di rigenerazione crea ora un indice di punti di interesse direttamente dentro `photon-italy-v2.sqlite`, usando **`italy-latest.osm.pbf`**: nodi e perimetri OSM con tag `amenity=fuel/restaurant/fast_food/cafe/bar/pub` e `tourism=hotel/motel/guest_house/hostel/apartment/chalet`. L'indice contiene categoria, sottotipo, nome (o un'etichetta generica) e coordinate; **non richiede un quarto file nel MotoPack né cambia lo schema principale Photon v2**. Il generatore usa `pyosmium` già previsto negli strumenti WSL e un indice su disco per i nodi delle geometrie.

**Comando WSL (ambiente e PBF già configurati):**

```bash
cd ~/MotoGPS/moto-gps-waveshare
git switch valhalla
git pull --ff-only origin valhalla
source ~/valhalla-env/bin/activate
bash scripts/offline_map/rebuild_motopack_wsl.sh --threads 2
```

Lo script genera normalmente MotoMap, Photon + POI e Valhalla in staging, verifica il bundle e **sostituisce la cartella di output solo a lavoro completato**. Senza modifica del formato del MotoPack, `motopack.py verify` mantiene il controllo dei tre componenti. Per aggiungere i soli POI a **una nuova copia** di Photon v2 già pronta, senza ricostruire il resto, si può usare in alternativa:

```bash
python3 scripts/offline_map/add_poi_to_photon.py \
  --pbf /mnt/c/MotoGPS/PBF/italy-latest.osm.pbf \
  --sqlite /percorso/alla/copia/photon-italy-v2.sqlite
```

**Non puntare questo comando al Photon attualmente installato sul telefono o a un file di produzione senza backup**: lo script modifica il file SQLite indicato e rifiuta di eseguire una seconda aggiunta allo stesso indice.

### Parole chiave per la ricerca POI offline

Inserire **una delle parole o frasi riportate esattamente nella tabella** nel campo di ricerca destinazione o tappa. Maiuscole e accenti vengono normalizzati. Le parole sono riconosciute come categoria solo quando la query contiene l'alias, **senza aggiungere città o altre parole** (esempio: `bar` è una ricerca di categoria, `Bar Centrale` è una ricerca per nome).

| Categoria | Parole chiave riconosciute nell'APK | Tipi OSM cercati |
| --- | --- | --- |
| **Carburante** | `distributore`, `distributori`, `benzinaio`, `benzinai`, `distributore benzina`, `distributori benzina`, `distributore carburante`, `distributori carburante`, `stazione di servizio`, `stazioni di servizio` | `amenity=fuel` |
| **Ristoranti** | `ristorante`, `ristoranti`, `trattoria`, `trattorie`, `osteria`, `osterie` | `amenity=restaurant`, `amenity=fast_food` |
| **Bar e caffetterie** | `bar`, `caffe`, `caffè`, `caffetteria`, `caffetterie`, `pub` | `amenity=bar`, `amenity=cafe`, `amenity=pub` |
| **Pernottamento** | `albergo`, `alberghi`, `hotel`, `motel`, `pensione`, `pensioni`, `affittacamere`, `ostello`, `ostelli` | `tourism=hotel`, `tourism=motel`, `tourism=guest_house`, `tourism=hostel`, `tourism=apartment`, `tourism=chalet` |

**Comportamento:** con **MotoPack rigenerato con l'indice POI**, l'APK aggiornato e un **fix GPS valido**, l'app cerca i POI della categoria nelle vicinanze e mostra fino a otto risultati per distanza geografica approssimata. Per esempio, cercare `distributore` restituisce tutti i `amenity=fuel` disponibili nell'indice, anche **senza marchio**, non un elenco fisso di Eni/IP/Q8. Cercare invece `Eni` o `Q8` resta una normale ricerca Photon per nome. I risultati possono essere scelti come destinazione o tappa; le distanze mostrate sono **in linea d'aria**.

**Compatibilità:** con il vecchio Photon SQLite v2 senza `poi_places`, o senza GPS valido, la ricerca torna al normale Photon per testo; **non** esegue più interrogazioni speciali ai marchi e non garantisce un elenco completo dei distributori. Se il motore locale non trova risultati, l'app può utilizzare il Photon pubblico quando è disponibile una connessione. La ricerca per categoria copre i POI OSM presenti nel **PBF Italia**; non è un elenco di attività verificate come aperte né una ricerca lungo il percorso. `trattoria` cerca tutti i ristoranti del gruppo, `hotel` include altre tipologie di alloggio. La nuova funzione richiede ancora collaudo di APK, MotoPack e tempi di query sul dataset italiano completo.

## 3. GitHub Actions e Android

### Uso quotidiano

Usare i comandi brevi in apertura. Android usa JAVA_HOME valido o JBR nella posizione standard di Android Studio; non occorre avviare l'IDE. Gli script Windows rifiutano modifiche locali tracciate e revisioni divergenti, aggiornano i submodule e interrompono installazione/flash se la build fallisce. Per procedure manuali vedere sotto.

[GitHub Actions — Project Build](https://github.com/thriller47/moto-gps-waveshare/actions/workflows/project-build.yml). I job `native`, `android` ed `esp32` sono selettivi; `skipped` non è equivalente a un test superato. Per avviare un solo job: pagina Project Build → **Run workflow** → branch `valhalla` → attivare solo la casella desiderata → **Run workflow**. **Non avviare workflow per questa revisione documentale.** Per il codice avviare **una sola CI consolidata alla fine**; una modifica al solo manuale non richiede build.

Gli artifact della run corretta possono includere il firmware `ESP32-Firmware` (`moto-gps-waveshare.bin`, bootloader e partizioni) e l'APK `Glimpse-MOTO-GPS-Android-Final.apk` (in alcuni casi dentro un archivio da estrarre). Controllare sempre **branch, commit e firma**: non installare a caso un APK di un branch diverso.

**Da PowerShell Windows, con GitHub CLI installata e autenticata** (sostituire `ID_RUN` con il numero della run mostrato nel sito):

```powershell
gh run list -R thriller47/moto-gps-waveshare -b valhalla -w project-build.yml -L 10
gh run download ID_RUN -R thriller47/moto-gps-waveshare -n ESP32-Firmware -D C:\MotoGPS\ESP32-Firmware
```

**Prima installazione Android Studio (solo nuovo PC):** scaricare Android Studio dal sito ufficiale Android Developers, completare la procedura iniziale, aprire **SDK Manager** e installare **Android SDK Platform 37 (pacchetto 37.0)**, **Platform-Tools** (include `adb`), **Build-Tools** e **NDK (Side by side) 29.0.14206865**. Aprire come progetto **`platforms/android`**, attendere la sincronizzazione Gradle e autorizzare eventuali download. Se `gradlew.bat` non parte, verificare di essere nella cartella `platforms/android`. Per il debug USB: sul telefono attivare *Opzioni sviluppatore → Debug USB*, collegare un cavo dati e confermare la finestra di autorizzazione RSA; `& $adb devices` deve mostrare il seriale con stato `device`, non `unauthorized`. Se il seriale cambia, usare quello visualizzato invece dell'esempio.

**Build Android manuale di recupero (PowerShell):** se si usa Android Studio, aprire `C:\MotoGPS\moto-gps-waveshare\platforms\android` (non la root). Versioni di riferimento per `valhalla`: JDK/JBR 21, Android SDK Platform **37**, Android Gradle Plugin **9.1.1**, Gradle wrapper **9.3.1** e CMake 3.22.1. Android Studio deve supportare AGP 9.1.1 (almeno Panda 3 Patch 1); in CI rimane JDK 17. **Su `valhalla` il NDK richiesto è `29.0.14206865`** per il supporto alle pagine da 16 KB; il vecchio NDK 27 rimane solo nei checkout non aggiornati.

### Alternativa manuale — aggiornare, testare, compilare e installare Android

Aprire PowerShell Windows; in alternativa usare il terminale PowerShell di Android Studio (Alt+F12). Collegare il Samsung via USB e autorizzare il debug. Copiare l'intero blocco seguente in PowerShell Windows, non in WSL. Si può lasciare in esecuzione la rigenerazione MotoPack in WSL: utilizzare il checkout Windows separato.

Il branch attivo al 24/11/2026 è `NewUI`; dopo il merge autorizzato sostituirlo con `valhalla`. Il blocco si interrompe in caso di errore Git, test/build o installazione, evitando di installare un APK precedente dopo una build fallita.

```powershell
& {
    $branch = "NewUI"
    Set-Location "C:\MotoGPS\moto-gps-waveshare" -ErrorAction Stop
    git switch $branch
    if ($LASTEXITCODE -ne 0) { throw "Cambio branch fallito" }
    git pull --ff-only origin $branch
    if ($LASTEXITCODE -ne 0) { throw "Aggiornamento Git fallito" }
    git log -1 --oneline

    Set-Location "C:\MotoGPS\moto-gps-waveshare\platforms\android" -ErrorAction Stop
    .\gradlew.bat testDebugUnitTest assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "Build fallita: APK non installato" }

    $adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
    if (-not (Test-Path $adb)) { throw "ADB non trovato: verificare Android SDK" }
    & $adb devices
    if ($LASTEXITCODE -ne 0) { throw "Errore ADB" }
    & $adb -s R5CXA3SF4QT install -r ".\app\build\outputs\apk\debug\app-debug.apk"
    if ($LASTEXITCODE -ne 0) { throw "Installazione APK fallita" }
}
```

Risultati attesi: commit corretto in Git, `BUILD SUCCESSFUL` da Gradle e `Success` da ADB. Se Git segnala modifiche locali in conflitto, fermarsi senza forzare il checkout. Se Android Studio richiede la sincronizzazione dopo il pull, usare **File → Sync Project with Gradle Files**. Non serve eseguire `clean` di routine. La procedura consolidata non è stata ancora verificata sulla nuova revisione NewUI.

**Installazione sul Samsung in uso (PowerShell):** il seriale ADB attuale è `R5CXA3SF4QT`. Il comando sopra è quello utilizzato dall’utente per aggiornare l’APK debug, dalla cartella `platforms/android`. La variabile `$adb` indica l’eseguibile ADB dell’Android SDK locale; se è già definita nella sessione PowerShell, non occorre ridefinirla. `-s` evita l’errore «more than one device/emulator» selezionando il telefono corretto; `-r` mantiene i dati dell’app quando firma e versionCode consentono l’aggiornamento. Controllare il seriale con `& $adb devices` se si cambia telefono. **Build Android e audit ELF 16 KiB:** confermati dall'utente il 22/11/2026 (sei librerie PASS, zero segmenti LOAD non validi); installazione e funzionamento su Samsung confermati dall'utente. I job native e Android della CI sono stati riferiti verdi dopo i fix del 22/11; il job ESP32 non è stato eseguito in questo ciclo. La verifica ZIP locale non è stata registrata separatamente.

### Verifica librerie Android a 16 KB (integrata in `valhalla`)

Se Android Studio segnala `Controllo allineamento ELF non riuscito` per `libmotogps_jni.so`, `libzstd-jni-*.so` o `libvalhalla-wrapper.so`, **non è un problema dei MotoPack**. Il linker del codice C++ deve produrre segmenti ELF `LOAD` allineati a 16 KB e anche le librerie precompilate devono essere compatibili. Nel branch di sviluppo sono configurati: NDK `29.0.14206865`; linker flags `-Wl,-z,max-page-size=16384` e `-Wl,-z,common-page-size=16384` per `motogps_jni`; `zstd-jni` 1.5.7-13 e `valhalla-mobile` 0.6.6. **Il fatto che il Gradle compili non prova che tutti i .so precompilati siano già conformi.**

In **Android Studio → Settings → Languages & Frameworks → Android SDK** installare **SDK Platform 37** e, nella scheda **SDK Tools**, il componente **NDK (Side by side)** versione `29.0.14206865` (eventualmente tramite *Show Package Details*). La libreria `zstd-jni` 1.5.7-13 richiede API 37 per compilare: `compileSdk` è 37, ma **`targetSdk` resta 35** e `minSdk` resta 26. AGP 9.1.1 usa Kotlin integrato; non riapplicare `org.jetbrains.kotlin.android`. Oppure installare dal terminale PowerShell con SDK command line tools:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat" "platforms;android-37.0" "ndk;29.0.14206865"
Set-Location "C:\MotoGPS\moto-gps-waveshare\platforms\android"
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Dopo la compilazione, con Python 3 installato, controllare **tutte le librerie ELF 64-bit nel nuovo APK**:

```powershell
Set-Location "C:\MotoGPS\moto-gps-waveshare"
py -3 scripts\diagnostics\check_android_16k_elf.py platforms\android\app\build\outputs\apk\debug\app-debug.apk
```

**Verifica ZIP 16 KB (PowerShell, dopo la build):** individuare il proprio `zipalign.exe` in `$env:LOCALAPPDATA\Android\Sdk\build-tools\` e sostituire la versione del percorso se diversa:

```powershell
Set-Location "C:\MotoGPS\moto-gps-waveshare\platforms\android"
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\zipalign.exe" -c -P 16 -v 4 ".\app\build\outputs\apk\debug\app-debug.apk"
```

Risultato atteso: righe `PASS` per ogni `lib/arm64-v8a/*.so` e `0 invalid LOAD segments`; un `FAIL` identifica la libreria da correggere nel progetto o presso il suo distributore. Per la separata verifica ZIP/APK usare `zipalign.exe -c -P 16 -v 4` della cartella *build-tools* di Android SDK (versione 35+). La CI `Project Build` esegue **entrambe** le verifiche dopo la build; controllare che il job Android non sia saltato (`skipped`) e che la verifica sia `PASS`.

**Prova finale sul dispositivo:** eseguire `adb shell getconf PAGE_SIZE` (16384 su kernel con pagine da 16 KB), quindi testare avvio, caricamento MotoPack `.tar.zst`, ricalcolo offline Valhalla e BLE. Il componente Valhalla Mobile 0.6.6 usa un engine 3.9.2 anziché 3.9.1: controllare l'apertura dei grafi V5 precedenti prima di considerarlo validato. Non rigenerare il MotoPack come prima risposta a un semplice problema di allineamento ELF.

`adb install -r` aggiorna l'app soltanto con firma e versionCode compatibili. **Non disinstallare l'app per tentativi:** rischi di perdere mappe, database e checkpoint dei tour. Vedi [guida alla firma APK](android-apk-signing.md). Dopo un aggiornamento APK, ricontrollare lo stato delle tre mappe: installare codice nuovo non equivale a rigenerarle.



## 4. Ambiente ESP-IDF Windows, comando `moto`, compilazione locale

**Configurazione di riferimento del PC Windows:**
- Framework: `C:\Espressif\frameworks\esp-idf-v5.5.6`
- Strumenti e virtualenv: `C:\Espressif`
- Python usato da IDF: `C:\Espressif\python_env\idf5.5_py3.11_env\Scripts\python.exe` (Python 3.11.2)
- Repository: `C:\MotoGPS\moto-gps-waveshare`
- Firmware: `C:\MotoGPS\moto-gps-waveshare\platforms\esp32`

Usare **PowerShell** salvo dove indicato CMD. I percorsi degli strumenti possono variare se l'installer è stato configurato diversamente.

### A. Installazione e recupero degli strumenti

Installare Git for Windows e **ESP-IDF 5.5.6** con installer Windows Espressif (https://dl.espressif.com/dl/esp-idf/), abilitando ESP32-S3. Dopo l'installazione controllare da **PowerShell**:

~~~powershell
Test-Path "C:\Espressif\frameworks\esp-idf-v5.5.6\export.bat"
Test-Path "C:\Espressif\python_env\idf5.5_py3.11_env\Scripts\python.exe"
& "C:\Espressif\python_env\idf5.5_py3.11_env\Scripts\python.exe" --version
~~~

**Se `install.bat` non trova Python:** `.\install.bat esp32s3` rispondeva `The following tools are not installed in your environment: python`, anche se Python 3.11 esisteva. La **soluzione verificata** è aggiungere la cartella dell'ambiente virtuale al PATH **della stessa sessione PowerShell** e impostare `IDF_TOOLS_PATH`:

~~~powershell
$env:IDF_TOOLS_PATH = "C:\Espressif"
$env:Path = "C:\Espressif\python_env\idf5.5_py3.11_env\Scripts;$env:Path"
python --version
cd C:\Espressif\frameworks\esp-idf-v5.5.6
.\install.bat esp32s3
~~~

Risultato ottenuto: `All done! You can now run: export.bat`. Non occorre ripetere `install.bat` a ogni avvio. Se il comando `python --version` fallisce, verificare innanzitutto il percorso dell'eseguibile; **non cancellare le toolchain**.

### B. Attivazione manuale e verifica

Sempre nella finestra PowerShell dove sono impostati **entrambi** `IDF_TOOLS_PATH` e `Path`:

~~~powershell
cmd.exe /k 'call "C:\Espressif\frameworks\esp-idf-v5.5.6\export.bat" && cd /d "C:\MotoGPS\moto-gps-waveshare\platforms\esp32"'
~~~

Il log di avvio riuscito conteneva:
- `Checking python version ... 3.11.2`
- `Checking python dependencies ... OK`
- `Establishing a new ESP-IDF environment ... OK`
- `Done! You can now compile ESP-IDF projects.`

Se dopo l'attivazione appare `Impossibile trovare il percorso specificato`, verificare la cartella del repository: **non è necessariamente un errore di ESP-IDF**. Se in CMD appare `"'call" non è riconosciuto`, il comando `cmd.exe /k '...'` è stato incollato **in CMD invece che in PowerShell**. In CMD, dopo l'attivazione, usare solo i comandi CMD indicati.

Nella finestra **CMD** aperta da `cmd.exe /k`:

~~~cmd
cd
idf.py --version
~~~

Il nome della versione atteso è `ESP-IDF v5.5.6`; il suffisso opzionale `-dirty` indica modifiche nel checkout Git del framework, non un errore dell'ambiente.

### C. Ricreare il comando breve moto dopo la reinstallazione

In una **nuova PowerShell** (non CMD), creare un backup del profilo e aggiungere la funzione. Il blocco seguente è quello **usato con successo**, aggiornato per Python 3.11 e la cartella MotoGPS. Se ripetuto, aggiunge una seconda definizione `moto`: per mantenerne una sola, usare una volta soltanto e controllare il backup prima di ripeterlo.

~~~powershell
New-Item -ItemType Directory -Force -Path (Split-Path $PROFILE) | Out-Null
if (Test-Path $PROFILE) {
    Copy-Item $PROFILE "$PROFILE.bak" -Force
}

@'
function moto {
    $env:IDF_TOOLS_PATH = "C:\Espressif"
    $env:Path = "C:\Espressif\python_env\idf5.5_py3.11_env\Scripts;$env:Path"
    cmd.exe /k 'call "C:\Espressif\frameworks\esp-idf-v5.5.6\export.bat" && cd /d "C:\MotoGPS\moto-gps-waveshare\platforms\esp32"'
}
'@ | Add-Content -Path $PROFILE -Encoding UTF8
~~~

**Se PowerShell blocca il profilo:** `. $PROFILE` restituiva `PSSecurityException: L'esecuzione di script è disabilitata`. Per consentire l'esecuzione degli script locali **solo per l'utente corrente**:

~~~powershell
Get-ExecutionPolicy -List
Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned
. $PROFILE
Get-Command moto
moto
~~~

Confermare con `S` quando richiesto. La policy `RemoteSigned` consente gli script locali; quelli scaricati da Internet contrassegnati come remoti devono essere firmati o esplicitamente sbloccati dopo verifica. Se una policy aziendale o di gruppo prevale, **non forzarla**: usare l'attivazione manuale della sezione B.

Ora `moto` va digitato **in PowerShell** e apre CMD con IDF attivo nella cartella ESP32. Verificare con `idf.py --version`. L'attivazione dell'ambiente non equivale a un flash o a un test del dispositivo.

### D. Compilazione e flash manuali di recupero

Dopo `moto`, nella finestra CMD:

~~~cmd
idf.py --version
idf.py -DSDKCONFIG_DEFAULTS="sdkconfig.defaults;sdkconfig.perf.defaults" build
~~~

Solo in caso di necessità di pulizia della build (verificare di essere nella cartella del firmware):

~~~cmd
idf.py fullclean
idf.py -DSDKCONFIG_DEFAULTS="sdkconfig.defaults;sdkconfig.perf.defaults" build
~~~

Con scheda Waveshare connessa a COM3 e monitor seriale chiuso:

~~~cmd
idf.py -p COM3 flash monitor
~~~

**Nel firmware di riferimento sono abilitate le opzioni ESP32 CURVE_ZOOM, SUBPIXEL, MAP_DIRTY_TILES e PERF_TELEMETRY.** Anche le build CI di valhalla usano questi quattro flag e `sdkconfig.perf.defaults`. Per una build locale precedentemente configurata senza statistiche FreeRTOS, verificare le righe `CONFIG_FREERTOS_GENERATE_RUN_TIME_STATS` in `platforms\\esp32\\sdkconfig`: i nuovi defaults non sovrascrivono un sdkconfig già esistente. Se la configurazione rimane precedente, rigenerarla con cura (salvando eventuali impostazioni personalizzate), senza cancellare partizioni flash, mappe o NVS. La telemetria periodica aiuta la diagnosi LVGL ma non è di per sé una garanzia di fluidità.

**Recupero errore telemetria FreeRTOS / xCoreID:** verificare le quattro voci richieste in sdkconfig.perf.defaults. Da CMD ESP-IDF nella cartella firmware:

~~~cmd
findstr /B /C:"CONFIG_FREERTOS_GENERATE_RUN_TIME_STATS=" /C:"CONFIG_FREERTOS_RUN_TIME_STATS_USING_ESP_TIMER=" /C:"CONFIG_FREERTOS_RUN_TIME_COUNTER_TYPE_U64=" /C:"CONFIG_FREERTOS_VTASKLIST_INCLUDE_COREID=" sdkconfig
~~~

Devono essere tutte y. Il launcher tenta la migrazione mirata con backup datato; se fallisce usare menuconfig o confrontare il backup prima di rigenerare la configurazione. Non cancellare sdkconfig senza backup, non flashare dopo una build fallita.

**Versioni da non confondere:** il PC Windows usa ESP-IDF **5.5.6**; la job ESP32 GitHub Actions del repository utilizza l'immagine **5.5.5**. Non modificare il progetto per far coincidere automaticamente i due ambienti.

La riuscita del comando `moto` **non dimostra da sola** che compilazione, flash o prova su strada siano passati.

---

**Test sperimentale predizione curva ESP32 (19/11/2026, branch di test):** l'opzione CMake `MOTO_ESP_TURN_ASSIST_EXPERIMENT` è **ON per default** dopo il collaudo e l'integrazione in `valhalla`. La versione precedente A resta ottenibile esplicitamente con `OFF`. Per provare l'assistenza hardware curva mantenendo inalterato Android e il filtro IMU, da **PowerShell con ESP-IDF già attivo** e cartella `platforms\\esp32` lanciare `idf.py -DMOTO_ESP_TURN_ASSIST_EXPERIMENT=ON -DSDKCONFIG_DEFAULTS="sdkconfig.defaults;sdkconfig.perf.defaults" build`, verificando che la configurazione CMake utilizzi realmente `ON`. Per tornare al firmware A, eseguire `idf.py -DMOTO_ESP_TURN_ASSIST_EXPERIMENT=OFF -DSDKCONFIG_DEFAULTS="sdkconfig.defaults;sdkconfig.perf.defaults" build` e flashare la build precedente o la nuova build OFF. Nella CI standard di `valhalla` il flag è ON; per il rollback usare `-DMOTO_ESP_TURN_ASSIST_EXPERIMENT=OFF`. Non fare flash prima che il build ON sia verde. La modifica non replica C2 identico: non ricevendo nuovi fix GPS ad ogni render, corregge solo con nuovi ancoraggi BLE reali e mantiene uno scostamento eventualmente residuo durante la sosta fino al successivo anchor. Raccogliere il log diagnostico completo nel giro di prova, e confrontare il comportamento rispetto al firmware A precedente.

**Nota storica:** artifact Turn Assist del 19/11 e dettagli di confronto sono conservati nello [snapshot precedente](archive/MANUALE_OPERATIVO_PRE_AUTOMAZIONE_2026-11-24.md).

**Salto tappa nei percorsi non circolari e marcatori ESP32 (integrati in `valhalla`):** nel percorso normale con almeno una tappa intermedia, una volta avviata la navigazione, Android mostra **Salta prossima tappa**. Confermare per chiedere a Valhalla una rotta verso le tappe restanti o direttamente la destinazione finale. La destinazione non si può saltare e, se il calcolo fallisce, il percorso rimane invariato. L'ESP32 mostra **cerchi gialli numerati 1–8** sulla linea verde, solo per le tappe intermedie ancora da visitare nella finestra visibile; un marker fuori mappa non viene visualizzato. Il marker della tappa raggiunta/saltata scompare dopo la prossima finestra BLE, e la bandiera di arrivo resta separata. **Per testare** installare APK e firmware entrambi del branch di prova, perché il suffisso BLE opzionale con coordinate delle tappe non è gestito dalla vecchia versione ESP32. Verificare con diagnostica completa: salto prima tappa, salto ultima tappa intermedia, ricalcolo, BLE e arrivo finale; test su strada da eseguire prima del merge.

**Pulsante fisso Salta tappa >> su ESP32 (integrato in `valhalla`):** in navigazione con almeno una tappa intermedia ancora da visitare appare un badge rotondo `>>` sul lato sinistro, **speculare e della stessa dimensione del badge limite velocità** sulla destra. Il pulsante è presente anche quando il numero della tappa è ancora fuori dalla mappa visibile; i marcatori sulla linea verde restano solo informativi. Primo tocco: `TOCCA ANCORA >>`; secondo tocco entro **5 secondi**: invio `SALTO TAPPA RICHIESTO`. Solo Android ricalcola con Valhalla e preserva la destinazione finale; il messaggio conferma la richiesta, non ancora il successo del ricalcolo. Per evitare interazioni accidentali, effettuare i primi test da fermi. Il pulsante sparisce automaticamente quando non rimangono tappe intermedie. Installare APK e firmware dello stesso commit, poiché usano il nuovo bit `NavigationHasPendingWaypoints` nei flag BLE. **Dopo la correzione layout del 19/11:** il bordo del badge è blu (`#2D8CF0`); la distanza alla prossima svolta è sempre centrata sotto la distanza all'arrivo, anche senza tappe, per evitare sovrapposizioni. La distanza all'arrivo e il badge limite velocità non sono stati spostati.

**Arrivo sulla mappa ESP32 (integrato in `valhalla`):** il traguardo viene ora rappresentato da un **cerchio blu con A bianca** sulla linea verde, con le stesse regole di visibilità dei marker intermedi gialli: compare quando la destinazione finale entra nella finestra locale trasmessa da Android, anche su una rotta senza tappe. Non è interattivo e non sostituisce la destinazione finale del navigatore. La bandiera a scacchi laterale nella schermata di arrivo è ridotta del 20% (da 116 a 93 unità di layout) e mantiene il proprio centro. Richiesti APK e firmware del medesimo commit del branch per vedere il marker blu; collaudo da eseguire prima del merge.

## 5. Flash ESP32 — COM3 d'esempio

Scollegare monitor seriali/Arduino IDE prima di usare esptool: COM3 deve essere libera. Installare esptool, se necessario, oppure usare l'esptool.exe già disponibile.

**Flash completo di bootloader + tabella partizioni + applicazione** (prima installazione o aggiornamento partizioni):

~~~cmd
cd /d C:\MotoGPS\ESP32-Firmware
esptool.exe --chip esp32s3 -p COM3 -b 460800 write-flash 0x0 bootloader\bootloader.bin 0x8000 partition_table\partition-table.bin 0x20000 moto-gps-waveshare.bin
~~~

**Aggiornare solo l'applicazione** (se le partizioni sono già corrette):

~~~cmd
cd /d C:\MotoGPS\ESP32-Firmware
esptool.exe --chip esp32s3 -p COM3 -b 460800 write-flash 0x20000 moto-gps-waveshare.bin
~~~

Questi esempi usano i sottocomandi **esptool 5.x** (con trattino). Con **esptool 4.x**, sostituire **write-flash** con **write_flash**. Per verificare: esptool.exe version oppure esptool.exe --help.

Indirizzi di questo progetto (confermati in platforms/esp32/partitions.csv):

| Indirizzo flash | Contenuto |
| --- | --- |
| 0x0000 | Bootloader |
| 0x8000 | Tabella partizioni |
| 0x20000 | Applicazione ESP32 |
| 0x820000 | Storage |
| 0xC20000 | Partizione diagnostica da 256 KiB (storica; logger attualmente disattivato) |

**Mai usare erase-flash per un aggiornamento normale.** Cancellerebbe anche dati e configurazioni. In caso di difficoltà nel flash, verificare porta COM, cavo dati e modalità download/BOOT della scheda. Non modificare gli offset.

## 6. Diagnostica Android e monitor seriale ESP32

**Android — diagnostica unificata:** connettere ESP32 via BLE, aprire **Altre opzioni → Diagnostica e replay GPS**, scegliere **BASE** oppure **DEBUG** e toccare **Avvia diagnostica completa**. La stessa sessione acquisisce il replay GPS Android e gli eventi ESP32 via BLE. Senza ESP32 collegato la registrazione completa non parte. Se il BLE si interrompe, il GPS prosegue; l'app tenta di ripristinare i log ESP32 dopo la riconnessione, ma non può recuperare gli eventi persi durante la disconnessione. Ogni 45 minuti rinnova il livello ESP32, perché il firmware lo disabilita automaticamente dopo circa un'ora.

Al termine scegliere **Ferma diagnostica completa** oppure **Ferma ed esporta tutto (ZIP)**. L'unico file esportato è `motogps-diagnostica-completa.zip`, con le sottocartelle `logs/` e `gps/`:

```text
motogps-diagnostica-completa.zip
├── README.txt
├── logs/
│   ├── current.log              (Android, se presente)
│   ├── previous.log             (Android, se presente)
│   ├── esp32-current.log        (BLE, se presente)
│   └── esp32-previous.log       (BLE, se presente)
└── gps/
    ├── motogps-replay-part-00001.jsonl
    └── ...                     (oppure motogps-replay.jsonl)
```

**Privacy:** contiene coordinate, velocità, percorso e telemetria; condividere solo esplicitamente e non caricarlo su GitHub. I file GPS sono segmentati per sessioni lunghe: estrarre lo ZIP e riprodurre i `.jsonl` in ordine. Un nuovo **Avvia** sovrascrive la precedente sessione GPS non esportata; salvare lo ZIP prima del giro successivo. I log Android/ESP32 ruotano per limitare lo spazio. I vecchi replay individuali restano compatibili con gli strumenti di analisi.
**ESP32 sul PC:** per vedere la telemetria `LVGL_ROADS`, `display perf` e le statistiche di frame è utile il **log seriale USB**; il precedente logger nella partizione flash `diag` non è la fonte attuale. Con scheda collegata a COM3, **finestra CMD del comando `moto` / ESP-IDF attivo**:

```cmd
idf.py -p COM3 monitor
```

Oppure da **PowerShell Windows** con Python Launcher e PySerial:

```powershell
py -m pip install pyserial
py -m serial.tools.miniterm COM3 115200 --eol LF
```

Aprire **un solo monitor** per volta. Per il test in movimento, se il dispositivo è collegato al telefono e non al computer, non dare per scontato di poter acquisire contemporaneamente la seriale: usare la configurazione fisica consentita dall'hardware o affidarsi alla diagnostica Android via BLE. Non maneggiare cablaggi e telefono durante la guida.

## 7. Simulatore LVGL/WASM e server web in WSL

**Uso quotidiano da PowerShell Windows:** `moto-simulator NewUI`. Aggiorna il checkout WSL e i submodule, attiva emsdk, esegue il test Node delle fixture sintetiche se presente e Node disponibile, compila WASM, avvia HTTP locale sulla porta 4173 e apre il browser predefinito. Se Node manca il test viene dichiarato saltato. Con `-BuildOnly` non avvia il server. Per riavviare, fermare il launcher con Ctrl+C e ripetere il comando; Ctrl+F5 nel browser. Non termina server estranei sulla porta 4173. Il lock condiviso con moto-motopack resta attivo durante il server: fermarlo prima di rigenerare il pacchetto. Non avviare il simulatore mentre la rigenerazione manuale è in corso. L'avvio con apertura browser è confermato dall'utente. Ctrl+C interrompe il server; il launcher tratta il codice 130 come uscita volontaria, non come errore (nuova gestione da collaudare).

**Scenari PR #13 (NewUI):** nel menu Scenario sintetico selezionare la scena e premere Esempio sintetico: guida normale, deviazione/cambio token, gap GPS di 8 s, inversione, percorso lungo, perdita/ritorno link, oltre alla rotatoria originale. Fixture visuali, non test live BLE/GNSS/IMU/Valhalla. Test sorgente: `node --test platforms/web/simulator/synthetic_scenarios.test.cjs`. Dettagli nel [README del simulatore](../platforms/web/simulator/README.md).

### Alternativa manuale WSL

Il simulatore usa Emscripten già installato in `~/emsdk`. Lavorare nello **stesso checkout WSL** sia per build sia per avvio server, evitando il vecchio percorso misto `/mnt/c/...`. **Da WSL Ubuntu-24.04:**

```bash
cd ~/MotoGPS/moto-gps-waveshare
source ~/emsdk/emsdk_env.sh
command -v emcmake
./scripts/build_web.sh
```

Gli output reali LVGL vengono generati in `platforms/web/shell/runtime/moto-gps-device.js` e `moto-gps-device.wasm`. La CI standard non compila automaticamente questa variante Emscripten.

**Avvio / riavvio del server**: in un **secondo terminale WSL** (non chiudere quello del server durante le prove):

```bash
cd ~/MotoGPS/moto-gps-waveshare
python3 -m http.server 4173 --directory .
```

Aprire **http://127.0.0.1:4173/platforms/web/simulator/index.html** da Chrome/Edge Windows. Dopo modifica e ricompilazione premere **Ctrl+F5**. Per riavviare il server fermarlo nel suo terminale con **Ctrl+C** e ripetere il comando: **non** lanciare un secondo server sulla stessa porta. Se sono cambiati solo HTML/JS, non serve ricompilare il WASM; dopo modifiche C++/LVGL/Navigator sì.

**Replay GPS:** estrarre `gps/` da `motogps-diagnostica-completa.zip` e caricare i JSONL singoli in ordine cronologico; non caricare direttamente lo ZIP. Il browser non emula l'intera catena Android/GNSS/BLE/QMI8658; i numeri RAF/WASM/flush non sono misure dirette degli FPS fisici dell'ESP32.

**Confronto A/B/C/C2 predizione nelle rotonde (19/11/2026, solo simulatore):** il selettore `Predizione posizione` offre **A** (predittore originale ESP32), **B** (posizione GPS snapshot, OFF) e **C** (prototipo WASM con attenuazione del movimento in curva e riallineamento più pronto al GPS), più **C2** (ingresso curva anticipato, riallineamento all’uscita, reset durante sosta). Aprire un JSONL estratto dalla cartella `gps/` dello ZIP diagnostico completo, usare **Heading Originale**, riproduzione **1× dall'inizio** e confrontare lo stesso tratto in A/B/C. Il pannello mostra scarti in metri rispetto al GPS registrato e il CSV esporta anche scarti laterali/longitudinali di C. **Non** sono errori certificati rispetto alla strada: nessun campione QMI8658 raw né timing BLE effettivo è disponibile. Per attivare C è indispensabile ricompilare in WSL con `source ~/emsdk/emsdk_env.sh && ./scripts/build_web.sh` e aggiornare Chrome con Ctrl+F5. Nessuna modifica o nuovo flash ESP32; istruzioni dettagliate in `platforms/web/simulator/README.md`.

**Confronto di regressione A/B (solo se serve diagnosi):** `bash scripts/diagnostics/prepare_20261115_replay_ab.sh` prepara due simulatori isolati, baseline del 15/11 e branch test, su porte `4174` e `4175`. I dettagli delle scene e dei singoli bug sono in [diagnostica regressione](diagnostics/REGRESSION_BASELINE_20261115_VS_20261117.md), non in questa procedura quotidiana. Per verificare lo zoom sperimentale riprodurre lo stesso JSONL nelle due versioni e controllare gli interruttori A/B, normalmente OFF. Non confondere i due server A/B con quello quotidiano in porta 4173.

## 8. Registrazione GPS e analisi della progressione

**App Android su `valhalla`:** seguire **Altre opzioni → Diagnostica e replay GPS → BASE/DEBUG → Avvia diagnostica completa** prima del giro. Il comando unifica registrazione GPS e diagnostica ESP32; l'esportazione è unica, come nella sezione 6. Non si avvia automaticamente e richiede il BLE connesso. Non maneggiare il telefono durante la guida.

I replay sono estratti dalla sottocartella `gps/` dello ZIP completo. Se vengono registrate più parti, caricarle in ordine numerico. Una nuova sessione sostituisce le vecchie parti GPS locali: **esportare prima di ripartire**.
**Per verificare blocchi della progressione e manovre**, l'APK test recente può includere eventi `route_full` e `progress_debug`. I replay vecchi privi di tali eventi non si possono completare retroattivamente. In WSL, dopo aver copiato il proprio JSONL o ZIP in un percorso privato:

```bash
cd ~/MotoGPS/moto-gps-waveshare
python3 scripts/diagnostics/analyze_progress_replay.py \
  "$HOME/motogps-replay.jsonl" \
  --csv "$HOME/motogps-progress-analysis.csv"
```

Il parser accetta anche un ZIP multipart come input. I campi `match_mode`, `matched_progress_m`, `raw_remaining_m`, `guarded_remaining_m`, `next_type` e `turn_m` aiutano a distinguere stallo della progressione da scostamento della mappa. Conservare replay, ZIP e CSV privati, **non caricarli su GitHub**.



## 9. Radio Wi-Fi sperimentale (modalità isolata)

I comandi sono previsti da `platforms/esp32/main/signal_wifi_console.cpp` e `signal_radio_stream.cpp`, ma la radio resta **sperimentale**: la presenza del parser **non garantisce** che audio MP3, connettività Wi-Fi, touch e stabilità della riproduzione siano stati collaudati sul firmware corrente.

### Come entrare nella modalità radio

Il firmware di prova attuale definisce `MOTO_SIGNAL_MODE_SWITCH` in `platforms/esp32/main/CMakeLists.txt`. Nella pagina GPS, il pulsante rotondo con simbolo Wi-Fi a circa **ore 3** richiede un **riavvio software controllato in modalità radio**. GPS e BLE non lavorano contemporaneamente alla radio: durante il test il normale sistema di navigazione è disabilitato fino al ritorno alla modalità GPS. Non usare questa funzione durante la guida. La configurazione isolata `MOTO_SIGNAL_WIFI_LAB_BOOT` è una variante da laboratorio: non attivarla senza ricompilare intenzionalmente un firmware di test.

Per tornare alla navigazione usare il controllo **GPS** dell'interfaccia radio, se disponibile e funzionante. Altrimenti, interrompere il test e riavviare normalmente il dispositivo: un'accensione da spento torna alla modalità GPS predefinita. Non presumere che una semplice riconnessione BLE basti mentre è attiva la radio.

### Monitor seriale su Windows

Collegare la scheda via USB e identificare la porta corretta nel Gestione dispositivi (negli esempi **COM3**, che può variare). Il parser riceve righe sul terminale seriale USB ESP-IDF. Aprire **un solo programma seriale alla volta**, a **115200 baud** con fine riga **LF/Newline** (in genere è accettato anche CRLF). **Esempio da CMD**, con Python già installato:

~~~cmd
py -m pip install pyserial
py -m serial.tools.miniterm COM3 115200 --eol LF
~~~

Nel caso di monitor che non supportano input bidirezionale usare un terminale interattivo (ad esempio Arduino IDE Serial Monitor, con **Newline**). Per uscire da miniterm: **Ctrl+]**. I comandi qui sotto vanno digitati **dentro il monitor seriale, non nel CMD o PowerShell**.

### Configurare hotspot e avviare radio

Accendere un hotspot di prova **Wi-Fi 2,4 GHz** e annotarne nome e password. Nel monitor seriale, una riga alla volta:

~~~text
wifi help
wifi status
wifi set MotoGPS_Test|PasswordDiProva123
wifi test
wifi status
radio list
radio play 1
radio stop
wifi off
~~~

Il separatore `|` mantiene gli spazi nel nome rete: ad esempio `wifi set Hotspot di prova|PasswordDiProva123`. Sono accettate anche le varianti `wifi set SSID PASSWORD` e `wifi set "SSID con spazi" PASSWORD`. **SSID massimo 32 caratteri**; la password WPA2 deve avere normalmente **8–63 caratteri**. Le credenziali vengono salvate nella NVS e riutilizzate da `wifi test`, ma il salvataggio non dimostra la riuscita della connessione.

| Comando da digitare | Significato / limitazione |
| --- | --- |
| `wifi help` | Elenca la sintassi disponibile |
| `wifi set SSID`&#124;`PASSWORD` | Memorizza le credenziali (scrivere senza backtick né spazi attorno a `|`) |
| `wifi test` | Tenta connessione alla rete salvata; controllare `ESP_OK` nei log |
| `wifi status` | Mostra stato connessione |
| `wifi off` | Disconnette il Wi-Fi |
| `radio list` | Elenca le stazioni definite nel firmware |
| `radio play` / `radio play 1` | Avvia lo streaming della prima stazione, solo con Wi-Fi connesso |
| `radio play 2` | Avvia la stazione numero 2, se esiste |
| `radio stop` | Ferma la riproduzione **mentre è in esecuzione**; a riproduzione inattiva segnala che non sta suonando |
| `radio volume 20` | Durante lo streaming imposta il volume (range 0–100); non è comando principale del prompt inattivo |

**Diagnosi:** se `wifi status` non risponde, controllare la modalità radio attiva, la porta COM e il terminatore di linea. Se il Wi-Fi non si connette, verificare frequenza 2,4 GHz, password, copertura e messaggio `Wi-Fi connect result: ...`. Se `radio play` non produce suono, verificare i messaggi `HTTP status`, `MP3 decoder`, `Speaker opened` e memoria disponibile: il percorso decoder/audio è sperimentale e potrebbe non funzionare anche con risposta HTTP 200. Non cancellare NVS per tentativi, perché contiene credenziali e altre configurazioni.

**Sicurezza:** la console può mostrare in chiaro la password digitata, e le credenziali NVS potrebbero non essere cifrate. Per prove e screenshot/log usare solo una rete di test; non pubblicare password, SSID personali o log con dati sensibili. **Non eseguire streaming radio in parallelo alla navigazione.**

## 10. Funzioni Android di navigazione e tour

Le funzioni elencate sono integrate nel branch `valhalla`; la loro disponibilità sul telefono dipende dalla versione APK effettivamente installata. Alcune funzioni restano da validare in ogni scenario reale.

- **Panoramico / Touring moto:** in **Opzioni percorso** scegliere il profilo; Panoramico preferisce un itinerario relativamente più tortuoso, ma non certifica il paesaggio né garantisce una distanza precisa.
- **Tour circolare:** dalla ricerca aprire **Crea tour circolare**, impostare circa **30–300 km** e N/E/S/O, aggiungere eventuali luoghi obbligatori trovati con Photon (massimo otto), quindi **Calcola tour**. Con zero punti obbligatori il pianificatore propone tappe automatiche; con uno può aggiungere un punto laterale, con più punti rispetta il loro ordine. Verificare sempre la preview di Valhalla, il ritorno e le tappe prima di partire.
- **Salvare un tour:** nell'anteprima usare **Salva questo tour**. L'archivio locale **I miei tour salvati** permette **Partenza salvata** (pianificazione dall'origine originale) oppure **Da qui** (origine GPS attuale). La geometria viene ricalcolata, non conservata immutabile; i tour non sono sincronizzati sul cloud.
- **Pausa/ripresa:** durante un tour avviato usare **Pausa tour (riprendi più tardi)**, non **Termina navigazione**. Al ritorno aprire **Tour in pausa → Riprendi da qui**, verificare l'anteprima della tratta residua e avviare manualmente. L'app salva checkpoint locali; eliminarne i dati può farli perdere.
- **Salta prossima tappa:** nell'app, durante una navigazione con waypoint intermedi, scegliere **Salta prossima tappa** e confermare **solo da fermi e in sicurezza**. Ricalcola verso le tappe residue; non consente di saltare la destinazione finale. Nel firmware `valhalla` il pulsante touch ESP32 `>>` è implementato e richiede doppio tocco; collaudare la combinazione firmware/APK più recente prima di considerarla definitiva.

Le prove di routing, checkpoint e BLE/IMU non coincidono con un collaudo dell'intero nuovo script MotoPack del 18/11. Evitare di trasformare richieste future o esperimenti A/B in istruzioni della versione stabile; consultare `PROJECT_STATE.md` per test e limiti aperti.

### Audit IMU per uso motociclistico — da preparare, non eseguito

Il Waveshare è previsto **centrato sul manubrio e quasi orizzontale**; può essere acceso con la moto inclinata sul cavalletto laterale. L'attuale QMI8658 stima la verticale usando l'accelerometro e proietta il giroscopio su tale riferimento, ma il comportamento su una moto in piega **non è ancora validato**. Il simulatore GPS/LVGL esistente **non riproduce i sei assi raw della QMI8658**.

La prossima verifica consiste in **test nativi C++ con segnali sintetici coerenti accel/gyro sui tre assi**: cavalletto inclinato → raddrizzamento → rettilineo con ricalibrazione prudente → piega 15°/30°/45° → uscita dalla curva → frenata e vibrazioni → GNSS assente/ripristinato. Registrare errori di heading e bias prima di modificare la fusione. Solo se serve, estendere successivamente il replay WASM alla telemetria IMU reale. Dettagli, vincoli e criteri sono nella [roadmap dedicata](ROADMAP.md#priorità-1--audit-imu-qmi8658-per-uso-in-moto-da-testare).

**Stato:** audit proposto e documentato; test non implementati/eseguiti, nessuna modifica alla fusione e **nessuna build richiesta per ora**. Non fare prove di inclinazione/manipolazione del dispositivo mentre si guida.

## 11. Operazioni manuali eccezionali e riferimento rapido

**La rigenerazione ordinaria è quella unica della sezione 2.** Le utility separate servono solo per una riparazione mirata o per assemblare **componenti già pronti**, senza ricostruire i tre dataset. Non usare file MotoMap v2/v3, Photon v1 o grafi Valhalla di generazioni non coerenti.

- `scripts/offline_map/pbf_to_motomap.py`: converte `italy-latest.osm.pbf` + `italy.poly` in MotoMap v4; usare un nome output nuovo per evitare di sostituire un file valido.
- `scripts/offline_map/photon_to_sqlite.py`: converte il dump `photon-italy.jsonl.zst` in database SQLite v2; **non** usare il vecchio indice Photon/Lucene al posto del dump JSONL.
- `scripts/offline_map/build_valhalla_moto_v5.sh`: ricostruisce solo il grafo V5 da PBF; la directory destinazione deve essere nuova.
- `scripts/offline_map/build_motopack_wsl.sh`: builder integrale per una cartella **nuova**, con input e output espliciti; `--resume` riusa intermedi completi e quindi **non** è adatto a forzare un aggiornamento degli stessi componenti.
- `scripts/offline_map/motopack.py build/verify`: assembla e verifica un pacchetto con MotoMap, Photon e Valhalla già generati.

**Stato del pacchetto:** il `.motopack` ZIP64 contiene MotoMap, Photon, Valhalla e Tourist SQLite (Road DNA), più `manifest.json` con dimensioni e SHA-256. Il collaudo del pacchetto precedente del 15/11 è positivo secondo i test riportati; ciò non certifica ancora la nuova rigenerazione del 18/11.

| Operazione frequente | Dove | Comando o percorso |
| --- | --- | --- |
| Aggiornare e compilare Android | PowerShell | `moto-android NewUI` |
| Aggiornare e compilare ESP32 | PowerShell | `moto-esp32 NewUI` |
| Rigenerare MotoPack | PowerShell | `moto-motopack NewUI` |
| Copiare MotoPack pronto | PowerShell | `moto-motopack NewUI -CopyOnly` |
| Pacchetto Windows | Esplora file | `C:\MotoGPS\MotoChatGPS-Italia.motopack` |
| Flash sola app ESP32 (recupero) | CMD/esptool | offset `0x20000` (non `0x10000`) |
| Ricompilare e aprire simulatore | PowerShell | `moto-simulator NewUI` |
| Avviare / riavviare server | WSL | `python3 -m http.server 4173 --directory .` (fermare il precedente con Ctrl+C) |
| Avviare diagnostica unificata | Android | Altre opzioni → Diagnostica e replay GPS → BASE/DEBUG → Avvia diagnostica completa |
| Esportare GPS + ESP32 + Android | Android | Ferma ed esporta tutto (ZIP): `motogps-diagnostica-completa.zip` |

**Conservare come storico**, non come procedura corrente: [vecchio manuale](archive/MANUALE_OPERATIVO_PRE_CLEANUP_2026-11-15.md), [cronologia tecnica completa al 19/11](archive/PROJECT_STATE_PRE_CLEANUP_2026-11-19.md). Stato e attività aperte: [PROJECT_STATE.md](../PROJECT_STATE.md) e [ROADMAP.md](ROADMAP.md). Non cancellare source PBF o archivi validi per fare spazio senza averne un backup.

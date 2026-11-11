# Moto GPS — Manuale operativo (Windows CMD e PowerShell)

**Riferimento:** repository thriller47/moto-gps-waveshare, branch principale **valhalla**. 
**Scheda:** Waveshare ESP32-S3-Touch-AMOLED-1.75C, 32 MB flash, 8 MB PSRAM.
**Porta negli esempi:** **COM3**. I blocchi sono etichettati **PowerShell** o **CMD**: usare la shell indicata. 
**Aggiornato:** 10 novembre 2026 (matcher validato su strada e strumenti diagnostici integrati in `valhalla`). Controllare prima PROJECT_STATE.md e AGENTS.md.

> **Attenzione:** il branch main è stato eliminato; signal resta solo come storico OSM online. Non usare né main né NoOta come base. Effettuare il flash soltanto con immagini della scheda 1.75C.

## 0. Ripristino Windows verificato: ESP-IDF 5.5.6, Python 3.11 e comando moto

**Configurazione attualmente funzionante (31/10/2026):**
- Framework: `C:\Espressif\frameworks\esp-idf-v5.5.6`
- Strumenti e virtualenv: `C:\Espressif`
- Python usato da IDF: `C:\Espressif\python_env\idf5.5_py3.11_env\Scripts\python.exe` (Python 3.11.2)
- Repository: `C:\MotoGPS\moto-gps-waveshare`
- Firmware: `C:\MotoGPS\moto-gps-waveshare\platforms\esp32`

**Attenzione:** sul vecchio Windows Python era 3.10 e il repository era in `C:\moto-gps-waveshare`. Quei percorsi sono **storici**, non usarli nella nuova installazione. Per le istruzioni di seguito usare **PowerShell** salvo dove indicato CMD. La presenza degli eseguibili dipende dai percorsi scelti nell'installer: adattarli se diversi.

### A. Installazione e recupero degli strumenti

Installare Git for Windows e **ESP-IDF 5.5.6** con installer Windows Espressif (https://dl.espressif.com/dl/esp-idf/), abilitando ESP32-S3. Dopo l'installazione controllare da **PowerShell**:

~~~powershell
Test-Path "C:\Espressif\frameworks\esp-idf-v5.5.6\export.bat"
Test-Path "C:\Espressif\python_env\idf5.5_py3.11_env\Scripts\python.exe"
& "C:\Espressif\python_env\idf5.5_py3.11_env\Scripts\python.exe" --version
~~~

**Problema incontrato dopo la reinstallazione:** `.\install.bat esp32s3` rispondeva `The following tools are not installed in your environment: python`, anche se Python 3.11 esisteva. La **soluzione verificata** è aggiungere la cartella dell'ambiente virtuale al PATH **della stessa sessione PowerShell** e impostare `IDF_TOOLS_PATH`:

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

**Altro errore reale dopo la reinstallazione:** `. $PROFILE` restituiva `PSSecurityException: L'esecuzione di script è disabilitata`. Per consentire l'esecuzione degli script locali **solo per l'utente corrente**:

~~~powershell
Get-ExecutionPolicy -List
Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned
. $PROFILE
Get-Command moto
moto
~~~

Confermare con `S` quando richiesto. La policy `RemoteSigned` consente gli script locali; quelli scaricati da Internet contrassegnati come remoti devono essere firmati o esplicitamente sbloccati dopo verifica. Se una policy aziendale o di gruppo prevale, **non forzarla**: usare l'attivazione manuale della sezione B.

Ora `moto` va digitato **in PowerShell** e apre CMD con IDF attivo nella cartella ESP32. Verificare con `idf.py --version`. **La sessione confermata dall'utente è funzionante**; compilazione e flash dopo la reinstallazione restano controlli separati.

### D. Compilazione e flash solo dopo l'attivazione

Dopo `moto`, nella finestra CMD:

~~~cmd
idf.py --version
idf.py build
~~~

Solo in caso di necessità di pulizia della build (verificare di essere nella cartella del firmware):

~~~cmd
idf.py fullclean
idf.py build
~~~

Con scheda Waveshare connessa a COM3 e monitor seriale chiuso:

~~~cmd
idf.py -p COM3 flash monitor
~~~

La riuscita del comando `moto` **non dimostra da sola** che compilazione, flash o prova su strada siano passati.

---

## 1. Installare o aggiornare il repository sul PC (nuova cartella)

**Percorso operativo attuale:** `C:\MotoGPS\moto-gps-waveshare`. La cartella `C:\moto-gps-waveshare` era quella del vecchio Windows: **non usarla**. Branch primario `valhalla`, non `main`.

**Prima installazione — da PowerShell** (Git for Windows già installato):

~~~powershell
New-Item -ItemType Directory -Force "C:\MotoGPS" | Out-Null
Set-Location "C:\MotoGPS"
git clone --branch valhalla --recurse-submodules https://github.com/thriller47/moto-gps-waveshare.git
Set-Location "C:\MotoGPS\moto-gps-waveshare"
git status -sb
~~~

Se la cartella del repository **esiste già**, non ripetere `git clone`: seguire l'aggiornamento.

**Aggiornamento quotidiano — da PowerShell** (oppure nella shell CMD aperta con `moto`, usando `cd /d` al posto di `Set-Location`):

~~~powershell
Set-Location "C:\MotoGPS\moto-gps-waveshare"
git status --short
git fetch origin --prune
git switch valhalla
git pull --ff-only origin valhalla
git submodule update --init --recursive
git log -1 --oneline
~~~

Se `git status --short` mostra modifiche locali, **fermarsi prima di fare pull** e salvare o valutare i file: non usare `git reset --hard` o `git clean -fd` alla cieca. Il repository deve esistere prima che il comando `moto` possa aprire automaticamente `platforms\esp32`.

**Verifica della struttura:**

~~~powershell
Test-Path "C:\MotoGPS\moto-gps-waveshare\platforms\esp32"
~~~

Deve risultare `True`. `git pull` aggiorna **il codice**, non scarica gli artifact binari delle Actions.

## 2. Scaricare le build già compilate da GitHub

Le build sono in **GitHub → Actions → Project Build**, selezionando una run del branch valhalla che abbia il job **esp32 verde**. La pagina è:
https://github.com/thriller47/moto-gps-waveshare/actions/workflows/project-build.yml

Da interfaccia web: aprire la run → **Artifacts** → scaricare **ESP32-Firmware** → estrarre lo ZIP in **C:\MotoGPS\ESP32-Firmware**.

Se si usa GitHub CLI (gh, con autenticazione già configurata):

~~~cmd
gh run list -R thriller47/moto-gps-waveshare -b valhalla -w project-build.yml -L 10
mkdir C:\MotoGPS\ESP32-Firmware
gh run download ID_RUN -R thriller47/moto-gps-waveshare -n ESP32-Firmware -D C:\MotoGPS\ESP32-Firmware
~~~

**Sostituire ID_RUN** con il numero della run scelta, verificando che contenga l'artifact ESP32. Le Actions pubblicano l'artifact ESP32 per **3 giorni**; quello Android per **7 giorni**.

**Struttura delle cartelle dopo l'estrazione** (così è configurato project-build.yml):

~~~text
C:\MotoGPS\ESP32-Firmware\
    moto-gps-waveshare.bin
    bootloader\
        bootloader.bin
    partition_table\
        partition-table.bin
~~~

Se lo ZIP è stato estratto in una cartella aggiuntiva, aprire quella che contiene i tre percorsi qui sopra. **Non rinominare né appiattire le sottocartelle**.

## 3. Flash ESP32 su COM3 — dall'artifact GitHub

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

## 4. Scaricare Geofabrik e convertire PBF → MotoMap ZIP

MotoMap serve **solo per le strade grigie sul display ESP32**. Il grafo Valhalla (sezione 5) serve **a calcolare gli itinerari**, ed è un file diverso.

Fonte Geofabrik: https://download.geofabrik.de/europe/italy.html

Per esempio scaricare **italy-latest.osm.pbf**, oppure una sottoregione come **nord-ovest-latest.osm.pbf** (meno memoria e tempo). Conservare il PBF sotto **C:\MotoGPS\PBF**.

~~~cmd
mkdir C:\MotoGPS\PBF
cd /d C:\MotoGPS\moto-gps-waveshare
python -m pip install osmium
python scripts\offline_map\pbf_to_motomap.py C:\MotoGPS\PBF\italy-latest.osm.pbf -o C:\MotoGPS\PBF\italia-v4.motomap.zip
~~~

L'ultimo comando genera **italia-v4.motomap.zip**, leggibile direttamente dall'app: **Gestione mappe → Importa mappa ZIP**. Non decomprimere il pacchetto sul telefono.

Per una sottoregione, sostituire il nome del PBF e usare un output differente:

~~~cmd
python scripts\offline_map\pbf_to_motomap.py C:\MotoGPS\PBF\nord-ovest-latest.osm.pbf -o C:\MotoGPS\PBF\nord-ovest.motomap.zip
~~~

Il convertitore Python è in memoria e l'intera Italia può usare diversi GB di RAM: chiudere programmi pesanti e controllare spazio disco. I PBF di Geofabrik **non sono** direttamente importabili come MotoMap.

### MotoMap v4: rotatorie OSM esplicite (branch test, 11 novembre 2026)

**MotoMap v4 sostituisce v3, senza retrocompatibilità.** L'APK sperimentale legge solo i pacchetti con manifest `motomap_v4.json`, `version=4`, `junction=osm-junction-way-v4`. Gli ZIP v3 precedenti non vengono più caricati. L'importazione rifiuta i pacchetti che non rispettano il nuovo formato. Conservare un backup del vecchio ZIP su PC per sicurezza, poi rimuoverlo dall'elenco mappe Android e importare il nuovo ZIP v4.

Ogni riga di strada ha intestazione obbligatoria `classe,velocita,layer,osmWayId,junctionKind|bbox|latE6,lonE6,nodeIdBase36;...`:
`junctionKind=0` è una strada ordinaria, `1` corrisponde a `junction=roundabout` OSM, `2` a `junction=circular`. Rimangono gli ID dei nodi OSM originali, anche quando più nodi condividono coordinate E6 arrotondate. Il lettore non accetta le righe v3 senza il nuovo campo. Nel riconoscimento Android gli archi OSM esplicitamente taggati hanno precedenza entro il budget esistente di 90 archi e possono comporre anelli fino a 18 way, mentre il DFS geometrico ordinario conserva gli otto way precedenti. **Non cambia il routing Valhalla, né il formato BLE o i 20/160 punti della scena.** Le `highway=mini_roundabout` rappresentate come soli nodi non vengono ancora sintetizzate come cerchi; richiederanno un indice dedicato in una fase successiva.

In **Windows PowerShell**, dopo aver aggiornato il branch sperimentale e installato `osmium` (e `shapely` soltanto con `--coverage-poly`):

~~~powershell
cd C:\MotoGPS\moto-gps-waveshare
git fetch origin
git switch test/roundabout-topology-20261110
git pull --ff-only origin test/roundabout-topology-20261110
python -m pip install osmium shapely
python scripts\offline_map\pbf_to_motomap.py C:\MotoGPS\PBF\italy-261101.osm.pbf -o C:\MotoGPS\PBF\italy-v4.motomap.zip --coverage-poly C:\MotoGPS\PBF\italy.poly
~~~

Sostituire il PBF con il nome effettivamente presente in `C:\MotoGPS\PBF` e assicurarsi che `italy.poly` descriva **lo stesso estratto**. Per verificare prima dell'importazione:

~~~powershell
python -c "import zipfile,json; z=zipfile.ZipFile(r'C:\MotoGPS\PBF\italy-v4.motomap.zip'); print(json.dumps(json.loads(z.read('motomap_v4.json')),indent=2))"
~~~

La conversione dell'intera Italia richiede diversi GB di RAM e spazio su disco, come in v3. Per provare rapidamente usare prima un PBF Geofabrik piccolo, con output `test-v4.motomap.zip` e senza `--coverage-poly`, poi rigenerare Italia solo dopo il test sulla rotatoria problematica. Controllare nel log Android `OSM_RING` il nuovo campo `osm_tagged=true` quando viene selezionato un anello davvero marcato in OSM. `OSM_RING_SEARCH` include la quantità di `osm_ways` disponibili.

**Stato:** convertitore e lettore v4 sono sperimentali; CI/test Android e test reale devono risultare positivi prima di sostituire la mappa utilizzata per navigare. Non modificare né rigenerare `valhalla_tiles.tar.zst`.

---
 
## 5. Creare o importare i grafi Valhalla

### A. Importare un grafo già pronto

Scaricare, se disponibile per la zona e compatibile, un archivio **.tar.zst** (per esempio **Italy.tar.zst** da un distributore di grafi Valhalla) o **.tar**. Sul telefono:
**Gestione mappe → Importa grafo TAR / ZST**.

L'app decomprime autonomamente uno ZST in un TAR con tile **.gph**, verifica l'archivio e salva il file in area privata. Non occorre decomprimere lo ZST sul PC. **Verificare il routing effettivo** dopo l'importazione: alcuni grafi sono incompatibili con la versione del motore nell'APK.

### B. Compilare il grafo Italia su AMD Ryzen 5 3600 / WSL2 (procedura verificata)

**Configurazione riuscita il 2 novembre 2026:** Windows con WSL2 Ubuntu 24.04, Ryzen 5 3600 (16 GB RAM fisica), `pyvalhalla==3.9.1`, **due thread di compilazione**, PBF `italy-261101.osm.pbf`, workspace nel filesystem Linux `/home/mod/motogps-build`. Risultato: **1.591 tile .gph**, TAR di circa 2,3 GB e `valhalla_tiles.tar.zst` di **886 MiB**. Il fatto che la build sia riuscita non dimostra, isolatamente, che la riduzione a due thread abbia risolto i crash precedenti: nel frattempo sono cambiati anche l'installazione Ubuntu e il filesystem usato.

**1. Installare Ubuntu e WSL2** (PowerShell amministratore; solo se non installato):

~~~powershell
wsl --install -d Ubuntu-24.04
wsl --update
wsl --list --verbose
~~~

Se WSL2 è già configurato, **non reinstallarlo**. Non eseguire `wsl --unregister` senza backup: elimina tutto il filesystem Linux della distribuzione selezionata. Creare l'utente Ubuntu al primo avvio.

**2. Configurare memoria WSL2** (PowerShell; file `%USERPROFILE%\.wslconfig`):

~~~powershell
notepad $env:USERPROFILE\.wslconfig
~~~

Contenuto adottato sul PC Ryzen:

~~~ini
[wsl2]
memory=12GB
processors=4
swap=8GB
~~~

Salvare e applicare:

~~~powershell
wsl --shutdown
~~~

Con 16 GB fisici, chiudere altre applicazioni pesanti e verificare lo spazio disponibile sul disco Windows che ospita WSL. Memoria e swap sono **limiti/configurazioni**, non prova che il processo non possa esaurirli.

**3. Preparare Ubuntu e pyvalhalla** (terminale Ubuntu):

~~~bash
sudo apt update && sudo apt upgrade -y
sudo apt install -y python3 python3-pip python3-venv git curl wget zstd build-essential
python3 -m venv ~/valhalla-env
source ~/valhalla-env/bin/activate
python -m pip install --upgrade pip
python -m pip install pyvalhalla==3.9.1
python -m pip show pyvalhalla
command -v valhalla_build_tiles
command -v valhalla_build_config
command -v valhalla_build_extract
~~~

`valhalla_build_admins` e `valhalla_build_timezones` sono facoltativi nello script. Se manca il generatore timezone, possono essere ridotte le funzioni di routing dipendenti dall'orario.

**4. Copiare il PBF dentro il filesystem Linux**, anziché costruire direttamente sotto `/mnt/c`. Esempio con i percorsi effettivi dell'installazione:

~~~bash
mkdir -p ~/motogps-build
cp /mnt/c/MotoGPS/PBF/italy-261101.osm.pbf ~/motogps-build/
# Se disponibile, conservare anche il perimetro usato da MotoMap v2/v3:
cp /mnt/c/MotoGPS/PBF/italy.poly ~/motogps-build/
sha256sum /mnt/c/MotoGPS/PBF/italy-261101.osm.pbf ~/motogps-build/italy-261101.osm.pbf
df -h ~
free -h
~~~

I due SHA256 del PBF devono corrispondere. `italy.poly` serve al convertitore MotoMap v2/v3 e **non** alla compilazione Valhalla. Prevedere preferibilmente 30–40 GB di spazio libero per tile temporanee, TAR e compressione.

**5. Costruire il grafo con due thread.** Lo script aggiornato usa **2 thread per default** e consente `--threads N`; lavorare in una directory nuova:

~~~bash
source ~/valhalla-env/bin/activate
cd /mnt/c/MotoGPS/moto-gps-waveshare
git switch test/turn-heading-render-20261102
git pull --ff-only origin test/turn-heading-render-20261102
bash scripts/offline_map/build_valhalla_graph.sh \
  ~/motogps-build/italy-261101.osm.pbf \
  ~/motogps-build/ValhallaItaliaV4 --zstd --threads 2
~~~

Non usare una cartella output già popolata: lo script rifiuta il riutilizzo dei file del grafo precedente. La vecchia build con parametri impliciti aveva prodotto errori `EdgeInfo offsets incorrect` e poi `SIGKILL 9`; l'ambiente pulito e i due thread sono la configurazione osservata riuscire.

**6. Verificare l'archivio e copiarlo su Windows:**

~~~bash
ls -lh ~/motogps-build/ValhallaItaliaV4/
tar -tf ~/motogps-build/ValhallaItaliaV4/valhalla_tiles.tar | grep -c '\.gph$'
python3 scripts/offline_map/analyze_valhalla_graph.py \
  ~/motogps-build/ValhallaItaliaV4/valhalla_tiles.tar.zst \
  --json ~/motogps-build/grafo-italia.json
cp ~/motogps-build/ValhallaItaliaV4/valhalla_tiles.tar.zst \
  /mnt/c/MotoGPS/valhalla_italia_ryzen.tar.zst
~~~

La build riuscita aveva **1.591 tile**, **11.457.028 nodi**, **26.093.538 archi orientati**, `Unverified: 0` (header compatibili). I conteggi non attestano da soli la qualità o la compatibilità di routing JNI Android: importare lo ZST in **Gestione mappe → Importa grafo TAR / ZST** e verificare itinerari senza connessione dati.

### C. Confrontare il grafo locale con Routing.earth

L'analizzatore accetta archivi `.tar` e `.tar.zst` e versioni Valhalla v3 con suffissi di build, senza estrarre tutto l'archivio su disco:

~~~bash
python3 scripts/offline_map/analyze_valhalla_graph.py \
  ~/motogps-build/ValhallaItaliaV4/valhalla_tiles.tar.zst \
  /mnt/c/MotoGPS/Italy.tar.zst \
  --json ~/motogps-build/confronto_valhalla.json
~~~

**Risultati rilevati il 2 novembre 2026:**

| Parametro | Locale 3.9.1 | Routing.earth 3.9.0 (varie revisioni) |
| --- | ---: | ---: |
| Tile | 1.591 | 1.202 |
| Nodi | 11.457.028 | 12.939.572 |
| Archi orientati | 26.093.538 | 29.484.433 |
| Header non verificati | 0 | 0 |
| Archivio compresso | 886 MiB | circa 1,42 GB |

Routing.earth contiene circa il 13% di nodi/archi orientati in più, **non necessariamente il 13% di chilometri di strada in più**. Le tile non sono direttamente confrontabili come indicatore di copertura; cambiano versione, estratto e configurazione di costruzione. Nessuna conclusione sulla qualità degli itinerari prima di una prova funzionale a parità di impostazioni.

## 6. Diagnostica attuale ESP32 (PSRAM/BLE) e Android

**Valhalla, dal 2026-11-01:** il vecchio logger flash ESP32 è
disattivato. La nuova diagnostica è registrata in PSRAM, trasmessa
via BLE e salvata nell'app Android. Non sono necessarie letture flash
né cavo USB per recuperare i nuovi log.

1. Connettere Android all'ESP32 via BLE.
2. Toccare **LOG**, scegliere **BASE** per eventi critici o **DEBUG**
   per il dettaglio di navigazione e rendering. L'impostazione
   predefinita è **OFF**.
3. Il timeout è di **60 minuti**, con disattivazione anticipata
   su disconnessione BLE o riavvio. Per interrompere manualmente scegliere
   **LOG > OFF**.
4. **Prima di disconnettere**, premere **Condividi / Esporta diagnostica**
   nella barra dell'app. Salvare `motogps-android-diagnostics.zip`,
   che comprende i file Android `current.log`/`previous.log` e,
   se sono arrivati record ESP32, `esp32-current.log`/
   `esp32-previous.log`.

**Spazio:** Android registra automaticamente (indipendentemente da LOG).
Entrambe le sorgenti usano due file a rotazione: fino a **2 × 1 MiB**
per Android e **2 × 2 MiB** per ESP32, circa 6 MiB in totale più
l'ultimo ZIP esportato. Nessun pulsante di cancellazione; i file più
vecchi vengono sovrascritti. Il buffer ESP32 PSRAM contiene fino a
2048 eventi e trasferisce al massimo 4 eventi/s: possono andare persi
eventi in presenza di intenso DEBUG o di un collegamento BLE interrotto.
Per il funzionamento ordinario, lasciare LOG su OFF.

**Solo per estrarre i log storici del vecchio firmware ESP32:**
la partizione flash `0xC20000` rimane presente, ma non viene più
aggiornata. Con la scheda su COM3:

~~~cmd
cd /d C:\MotoGPS\moto-gps-waveshare
esptool.exe --chip esp32s3 -p COM3 -b 460800 read-flash 0xC20000 0x40000 esp-diag.bin
python scripts\diagnostics\decode_esp32_trace.py esp-diag.bin
~~~

Il decoder produce `esp-diag.csv` con **solo** vecchi eventi.
Con esptool 4.x usare `read_flash` al posto di `read-flash`.

## 7. Radio Wi-Fi — console seriale di configurazione e prova (modalità isolata)

**Sezione ripristinata dalle istruzioni del 30 ottobre 2026 e verificata contro il codice presente il 5 novembre.** I comandi sono realmente previsti da `platforms/esp32/main/signal_wifi_console.cpp` e `signal_radio_stream.cpp`, ma la radio resta **sperimentale**: la presenza del parser **non garantisce** che audio MP3, connettività Wi-Fi, touch e stabilità della riproduzione siano stati collaudati sul firmware corrente.

### Come entrare nella modalità radio

Il firmware di prova attuale definisce `MOTO_SIGNAL_MODE_SWITCH` in `platforms/esp32/main/CMakeLists.txt`. Nella pagina GPS, il pulsante rotondo con simbolo Wi-Fi a circa **ore 3** richiede un **riavvio software controllato in modalità radio**. GPS e BLE non lavorano contemporaneamente alla radio: durante il test il normale sistema di navigazione è disabilitato fino al ritorno alla modalità GPS. Non usare questa funzione durante la guida. La configurazione isolata `MOTO_SIGNAL_WIFI_LAB_BOOT` è una variante da laboratorio: non attivarla senza ricompilare intenzionalmente un firmware di test.

Per tornare alla navigazione usare il controllo **GPS** dell'interfaccia radio, se disponibile e funzionante. Altrimenti, interrompere il test e riavviare normalmente il dispositivo: un'accensione da spento torna alla modalità GPS predefinita. Non presumere che una semplice riconnessione BLE basti mentre è attiva la radio.

### Monitor seriale su Windows

Collegare la scheda via USB e identificare la porta corretta nel Gestione dispositivi (negli esempi **COM3**, che può variare). Il parser riceve righe sul terminale seriale USB ESP-IDF. Aprire **un solo programma seriale alla volta**, a **115200 baud** con fine riga **LF/Newline** (in genere è accettato anche CRLF). **Esempio da CMD**, con Python già installato:

~~~cmd
python -m pip install pyserial
python -m serial.tools.miniterm COM3 115200 --eol LF
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

## 8. Simulatore browser Windows e registrazione GPS Android

### Avviso tappa intermedia raggiunta (sperimentale, 10 novembre 2026)

Sul branch `test/roundabout-topology-20261110`, il raggiungimento di una tappa programmata è confermato dal **display ESP32** con `TAPPA 1/3 RAGGIUNTA` per **7 secondi**. La notifica è sovrapposta senza nascondere mappa, linea verde, distanza all'arrivo e limite di velocità; non equivale all'arrivo finale. Per verificare la funzione è necessario **installare sia il nuovo APK Android sia il nuovo firmware ESP32** generati dalla CI del branch test, altrimenti il vecchio firmware ignora il segnale. Impostare due tappe, avviare navigazione e attraversarle: il testo deve apparire **una sola volta per tappa** e svanire automaticamente. Il log Android conserva `WAYPOINT_REACHED`; non è previsto un nuovo campo BLE. Il marker geografico sulla linea verde resta una funzione futura distinta perché richiede l'effettiva coordinata della tappa, non presente nello snapshot. **Non usare su strada prima di CI verde e verifica funzionale**.



**Stato attuale (10 novembre 2026):** simulatore, registrazione GPS senza limite complessivo e correzioni matcher/ricalcolo verificati su strada sono integrati in **`valhalla`** tramite PR #73 e PR #74. Non utilizzare i branch di test completati o eliminati per il normale utilizzo. Le prove sulla rotatoria multi-way disallineata di ~11 m rimangono aperte.

### Avviare o aggiornare il simulatore — WSL Ubuntu (procedura attuale)


Con Emscripten/emcmake installati in `~/emsdk` e il repository già presente in WSL, eseguire da **WSL Ubuntu** (non da PowerShell):

```bash
cd ~/MotoGPS/moto-gps-waveshare
git switch valhalla
git pull --ff-only origin valhalla
git submodule update --init --recursive
source ~/emsdk/emsdk_env.sh
command -v emcmake
./scripts/build_web.sh
```

Lo script compila il runtime C++/LVGL in `platforms/web/shell/runtime/moto-gps-device.js` e `moto-gps-device.wasm`. **Non serve ripetere l'installazione di Emscripten** a ogni avvio; `source ~/emsdk/emsdk_env.sh` attiva la toolchain nella shell WSL corrente.

**Server HTTP locale:** se è già in esecuzione, lasciarlo attivo. Altrimenti, in **un secondo terminale WSL**, dalla radice dello stesso checkout:

```bash
cd ~/MotoGPS/moto-gps-waveshare
python3 -m http.server 4173 --directory .
```

Aprire in Chrome/Edge su Windows: **http://127.0.0.1:4173/platforms/web/simulator/index.html**. Dopo un aggiornamento o una build, premere **Ctrl+F5** per evitare file JavaScript/WASM in cache. Il server si arresta con `Ctrl+C` nel terminale in cui gira. Se la porta 4173 risulta occupata, verificare prima che il server precedente sia ancora attivo, invece di avviarne un secondo.

**Quando ricompilare:** se sono cambiati file C++/LVGL/NavPresenter oppure manca il runtime, eseguire `source ~/emsdk/emsdk_env.sh` e `./scripts/build_web.sh` dopo il pull. Per modifiche **solo JavaScript/HTML** del simulatore basta `git pull --ff-only origin valhalla` e `Ctrl+F5` (non è necessario ricompilare WASM).

**Anteprima senza compilatore:** su Windows si può aprire direttamente `platforms\web\simulator\index.html`. Senza runtime comparirà `ANTEPRIMA 2D · NON LVGL`: mostra percorsi, scene e zoom, ma non riproduce esattamente il rendering dell'ESP32. Per utilizzare **LVGL reale** aprire l'URL HTTP sopra; `file://` non è sufficiente per caricare correttamente il runtime WASM.

**Alternativa PowerShell nativa (non verificata sul PC):** con Emscripten Windows attivo è possibile tentare `emcmake cmake -S . -B build/web -DMOTO_BUILD_WEB=ON -DMOTO_BUILD_TESTS=OFF`, `cmake --build build/web --parallel` e `py -m http.server 4173`. La procedura verificata dall'utente resta **WSL Ubuntu**.


### 8.1. Ricostruire il simulatore su un altro PC Windows/WSL (PC lavoro da 8 GB)

**Stato della prova 10 novembre 2026:** sul PC del lavoro Emscripten `emcc 7.0.0` è stato verificato dall'utente; la prima compilazione WebAssembly si è fermata al **53%** con processi `Killed` ed `Error 137`. L'esaurimento della memoria WSL/Linux è la spiegazione più probabile, **da confermare con la memoria e i log OOM**. Il completamento della build con un solo job e l'eventuale modifica dello swap **non sono ancora stati confermati dall'utente**. Non confondere queste istruzioni con la compilazione del grafo Valhalla Italia (sezione 5): per il replay del simulatore il grafo non serve.

**1. Verificare WSL** (PowerShell Windows, non terminale Ubuntu):

```powershell
wsl -l -v
```

Se Ubuntu 24.04 non compare ed è necessario installarlo, usare **PowerShell come amministratore**: `wsl --install -d Ubuntu-24.04`. Se è già presente, avviarlo con `wsl -d Ubuntu-24.04` (oppure usare il nome esatto mostrato da `wsl -l -v`). **Non usare `wsl --unregister`**: cancella dati e configurazione Linux.

**2. Dipendenze, repository nel filesystem Linux** (da qui tutti comandi nel terminale Ubuntu; non usare `/mnt/c` come cartella di build):

```bash
sudo apt update
sudo apt install -y git python3 cmake ninja-build build-essential curl ca-certificates
mkdir -p ~/MotoGPS
cd ~/MotoGPS
git clone --branch test/esp32-map-dirty-tiles-20261109 --recurse-submodules https://github.com/thriller47/moto-gps-waveshare.git
cd ~/MotoGPS/moto-gps-waveshare
git branch --show-current
```

Se `~/MotoGPS/moto-gps-waveshare` **esiste già**, non ripetere il clone né cancellare i file: entra nella cartella, controlla `git status`, poi aggiorna con `git pull --ff-only origin test/esp32-map-dirty-tiles-20261109` e `git submodule update --init --recursive` se il checkout è pulito. Per lavorare sulla versione stabile usa esplicitamente il branch `valhalla`; non fare merge tra i due per avviare il simulatore.

**3. Installare Emscripten con emsdk** (solo se `~/emsdk` non esiste):

```bash
cd ~
git clone https://github.com/emscripten-core/emsdk.git
cd ~/emsdk
./emsdk install latest
./emsdk activate latest
source ./emsdk_env.sh
emcc --version
emcmake --version
```

`emcc 7.0.0` è la versione osservata sul PC del lavoro nel test citato; `latest` potrebbe installarne una diversa. **In ogni nuovo terminale WSL** eseguire `source ~/emsdk/emsdk_env.sh` prima della compilazione.

**4. Configurare e compilare con un solo job sul PC da 8 GB** (terminale Ubuntu):

```bash
cd ~/MotoGPS/moto-gps-waveshare
source ~/emsdk/emsdk_env.sh
emcmake cmake -S . -B build/web -DMOTO_BUILD_WEB=ON -DMOTO_BUILD_TESTS=OFF -DCMAKE_BUILD_TYPE=Release
cmake --build build/web --parallel 1
ls -lh platforms/web/shell/runtime/moto-gps-device.*
```

Verificare la presenza di `moto-gps-device.js` e `moto-gps-device.wasm`. Lo script `./scripts/build_web.sh` è la scorciatoia per macchine con più risorse, ma sul PC da 8 GB è preferibile il comando esplicito `--parallel 1`: lo script invoca `cmake --build ... --parallel` senza indicare direttamente il numero di job.

**5. Recupero dopo `Killed` / `Error 137` / arresto al 53%:** non cancellare `build/web`, perché i file già compilati possono essere riutilizzati. Verificare prima RAM e swap:

```bash
free -h
swapon --show
dmesg | grep -i -E 'out of memory|killed process' | tail -n 15
```

`dmesg` può richiedere privilegi (`sudo dmesg`). Riprendere direttamente con:

```bash
cd ~/MotoGPS/moto-gps-waveshare
source ~/emsdk/emsdk_env.sh
cmake --build build/web --parallel 1
```

**Solo se anche il singolo job viene terminato per memoria**, considerare la configurazione WSL2 seguente, tenendo presente che riguarda **tutte le distribuzioni WSL2** e richiede spazio libero sul disco. Da **PowerShell Windows** eseguire `notepad $env:USERPROFILE\.wslconfig`; creare o modificare la sezione mantenendo le eventuali altre impostazioni già presenti:

```ini
[wsl2]
memory=5GB
processors=2
swap=8GB
```

Salvare, eseguire in PowerShell `wsl --shutdown`, riaprire Ubuntu, ricontrollare `free -h` / `swapon --show` e ripetere `source ~/emsdk/emsdk_env.sh` seguito da `cmake --build build/web --parallel 1`. **Questa modifica è un suggerimento di recupero, non una configurazione già validata** sul PC del lavoro.

**6. Avviare/restart server e simulatore:** in un terminale WSL dedicato, dalla radice del repository:

```bash
cd ~/MotoGPS/moto-gps-waveshare
python3 -m http.server 4173 --directory .
```

Lasciare il server acceso e aprire da Chrome/Edge Windows **http://127.0.0.1:4173/platforms/web/simulator/index.html**. Dopo una nuova build WASM aggiornare con `Ctrl+F5`; per **fermare** il server usare `Ctrl+C`, per **riavviarlo** ripetere il comando `python3 -m http.server`. Se il server è già in esecuzione non lanciarne un secondo sulla stessa porta. Il doppio clic su `index.html` può mostrare solo l'anteprima 2D senza il runtime WASM. Emscripten va usato nel terminale WSL; i comandi nativi PowerShell `emcmake` non sono verificati.

**Avvio rapido su un PC già configurato:** `cd ~/MotoGPS/moto-gps-waveshare`, scegliere e aggiornare il branch desiderato, `git submodule update --init --recursive`, `source ~/emsdk/emsdk_env.sh`, quindi compilare con `cmake --build build/web --parallel 1` (se CMake è già configurato) oppure `./scripts/build_web.sh` su un PC con memoria sufficiente.

**Diagnostica anello–rotta (10 novembre 2026, solo browser):** sul branch sperimentale, il simulatore indica lo scarto tra la prima rotatoria chiusa selezionata da MotoMap e la **finestra verde BLE** del frame. Il valore **non prova** che la rotta completa attraversi o non attraversi l'anello, perché la finestra è parziale; non cambia né la rotta Valhalla né la visualizzazione ESP32. Nel replay 10/11 la prima rotatoria alle 13:35:14 è a circa 10,8 m dalla linea verde, mentre la seconda alle 13:35:46 coincide (0 m). Sul PC del lavoro, dopo `git pull --ff-only origin test/esp32-map-dirty-tiles-20261109`, basta **Ctrl+F5** nel browser e ricaricare il replay: per questo aggiornamento JavaScript **non serve una nuova build Emscripten**.

### Registrare il GPS senza durata massima — APK sperimentale

La registrazione del trace GPS rimane **OFF per impostazione predefinita** e si attiva manualmente dal pannello **LOG → Avvia registrazione GPS**. Non si interrompe automaticamente dopo 60 minuti; salva file consecutivi `replay-current-part-00001.jsonl`, `00002.jsonl`, ecc. La rotazione avviene ogni **30 minuti o circa 12 MiB per singolo file**, senza un limite fissato al numero di file o alla durata totale della sessione (resta il limite fisico dello spazio disponibile sul telefono). La scrittura resta su thread privato, senza carico di I/O nel callback GPS/BLE.

Al termine premere **Ferma registrazione GPS → Esporta tracciato simulatore**: con una sola parte si condivide `motogps-replay.jsonl`; con più parti, `motogps-replay-parts.zip` che contiene **tutti i .jsonl numerati** e un README. Su Windows, estrarre lo ZIP e caricare i file `.jsonl` nel simulatore **uno alla volta, in ordine numerico**: ogni parte è autonoma e ricomincia da `t_ms=0`, ma contiene `part_index`, `session_elapsed_ms` e data iniziale per riallineare le prove. Non passare direttamente lo ZIP al selettore `.jsonl` del simulatore.

**Attenzione a sicurezza e dati:** il file GPS contiene coordinate, posizioni previste, velocità, geometrie della rotta verde e delle strade MotoMap e manovre. Rimane privato finché non viene esportato volontariamente. Lo ZIP ordinario di **diagnostica Android non include i replay né coordinate**. L'avvio di una **nuova** registrazione sostituisce le parti locali della sessione precedente: esportare la sessione precedente prima di riavviare la registrazione. Se la memoria del telefono si esaurisce, la scrittura fallirà e la registrazione si interromperà; non esiste spazio di archiviazione infinito. Non maneggiare il telefono mentre si guida, né usare DEBUG ESP32 approfondito solo per registrare il GPS.

**Zoom tornanti (prototipo WASM):** sul branch di test aggiornato, dopo `source ~/emsdk/emsdk_env.sh` e `./scripts/build_web.sh` ricaricare il browser con Ctrl+F5. Il checkbox **Zoom tornanti sperimentale** funziona soltanto con renderer LVGL reale: OFF mantiene lo zoom originale; ON prova a ingrandire fino a 3× le curvature marcate della linea verde. La rotatoria conserva il suo zoom fino a 4×. Controllare i frame dopo **12:10:50**. Il firmware ESP32 reale non cambia.

**Confronto tornanti (8 novembre, correzione scatti):** sul replay reale delle 12:10:50, il rilevatore geometrico raggiunge 3× ma l'utente ha osservato una riduzione prematura a metà tornante e movimento a scatti. In questa versione sperimentale la finestra geometrica dopo il vertice è estesa da 40 a 95 metri e il player limita a circa 8 aggiornamenti al secondo il **solo grafico velocità/cronologia secondaria**, lasciando la mappa LVGL aggiornata a ogni frame GPS. Confrontare OFF/ON sullo stesso tratto, e controllare la rotatoria 4×. I miglioramenti visivi sono **ancora da verificare nel browser**; non sono abilitati nel firmware ESP32.

**Ripartenza WSL (attuale):** utilizzare i comandi aggiornati all'inizio del capitolo 8 sul branch `valhalla`, quindi `Ctrl+F5` nel browser. Se il server è spento, avviarlo in una seconda finestra WSL; per arrestarlo usare `Ctrl+C`. Le descrizioni sotto sono note storiche delle prove del 6–8 novembre, non istruzioni di checkout dei branch eliminati.

**Secondo intervento (8 novembre, da collaudare):** dopo la seconda prova l'utente riferisce ancora mappa a scatti e zoom terminato prima della fine del tornante. La sola estensione della finestra 40→95 m non ha risolto. La nuova sperimentazione conserva il valore zoom riconosciuto durante l'attraversamento e lo rilascia in base alla **distanza effettivamente percorsa** (prima 55 m di mantenimento, poi 80 m di riduzione) anche se la finestra GPS/rotta verde cambia. Il player Web interpola il movimento fra snapshot registrati compatibili a non più di 25 Hz; evita interpolazioni per salto GPS >35 m, intervallo >1,5 s, cambio rotta/scena o stato. Le funzioni sono **solo per il simulatore** e la riproduzione frame per frame conserva il dato originale. Non confondere la fluidità del replay interpolato con una misurazione degli FPS hardware. Ripetere il medesimo confronto ON/OFF alle 12:10:50–12:12:30 e verificare che la rotatoria raggiunga ancora 4×. Ricompilazione WASM e ricaricamento Ctrl+F5 necessari.

**Diagnostica fluidità replay (8 novembre, sperimentale):** dopo il feedback dell'utente lo zoom tornanti è considerato visivamente coerente (primo tornante 1,96×; successivi fino a 2,78×), ma la mappa WASM scatta ancora. Il parser ora riutilizza le route e le scene *identiche* ritrasmesse da Android invece di ricostruirle a ogni ripetizione; conserva invece i cambi effettivi di geometria, token, generazione o revisione. Durante Play, nella scheda «Diagnostica replay WASM» sono visibili ogni secondo **RAF/s** (callback del browser), **WASM/s** (chiamate replay), costo medio/massimo della chiamata e **ritardi** (intervalli RAF oltre 50 ms). Questi dati non equivalgono agli FPS ESP32 e non includono necessariamente tutto il tempo di rendering SDL/LVGL. Per capire se gli scatti dipendono dal PC/browser, eseguire il tratto alle 12:10:50 in modalità 1×, annotare le cifre e confrontarle con l'anteprima 2D, sempre distinguendola dal renderer reale. Nessuna modifica allo zoom 3× dei tornanti né alle rotatorie 4×.

**Misura reale del simulatore (8 novembre):** l'utente ha rilevato `60 RAF/s · 25 WASM/s · 0,1 ms medi · 0,2 ms max · 0 ritardi`. Il ciclo browser è regolare e la chiamata C++ è leggera, ma il limite artificiale dell'interpolazione GPS a 40 ms forniva solo 25 nuove posizioni al secondo. Il limite è stato abbassato a 14 ms per seguire tipicamente il display del browser (fino a circa 60 aggiornamenti/s su pannello 60 Hz), **senza modificare il rilevatore geometrico** o la semantica dei ricalcoli. Eseguire `git pull --ff-only` sul branch test, Ctrl+F5 (nessuna ricompilazione WASM necessaria perché questa modifica è solo in `player.js`), provare ancora lo stesso replay alle 12:10:50 a velocità 1× e confrontare fluidità e diagnostica. Se persiste lo scatto anche con circa 60 WASM/s e 0 ritardi, indagare il tempo di flush LVGL/SDL e la presentazione dei frame separatamente: le attuali metriche **non** misurano quel lavoro.

**Nuova diagnostica ciclo LVGL (8 novembre, solo browser):** l'utente riferisce ancora scatti con replay `48–64 WASM/s` dopo l'eliminazione del limite 25 Hz. Viene aggiunto sotto i numeri JS un secondo riquadro «Diagnostica ciclo LVGL», che mostra `handler/s`, durata media e massima di `lv_timer_handler()` in millisecondi e numero di intervalli del ciclo oltre 50 ms. È **il ciclo LVGL**, non il numero effettivo di flush SDL/immagini mostrate a schermo; un valore vicino a 60 handler/s non prova che la GPU presenti 60 immagini distinte. Poiché la modifica include C++ WebAssembly, per vedere il riquadro con dati validi **ricompilare**:
```bash
cd ~/MotoGPS/moto-gps-waveshare
git switch valhalla
git pull --ff-only origin valhalla
source ~/emsdk/emsdk_env.sh
./scripts/build_web.sh
```
Lasciare in esecuzione il server `python3 -m http.server 4173 --directory .` se è già attivo e premere `Ctrl+F5` in Chrome. Ripetere il percorso alle 12:10:50 a 1×, riportando sia diagnostica replay WASM sia diagnostica ciclo LVGL. Modifica solo per browser, senza cambiare zoom, APK o firmware. Test visivo e nuova compilazione WSL da convalidare.

**Diagnostica render/flush LVGL (8 novembre):** con la versione precedente il test utente ha mostrato `60 RAF/s · 47 WASM/s · 0,1 ms medi · 0,2 ms max · 0 ritardi` e `60 handler/s · 0,6 ms medi · 1,5 ms max · 0 ritardi`, ma la mappa continuava a scattare. Il ciclo LVGL è regolare e leggero, però questo non dimostra che venga effettuato un render a ogni handler. La diagnostica LVGL ora conta **render/s** (evento `LV_EVENT_RENDER_START`) e **flush/s** (evento `LV_EVENT_FLUSH_START`) in aggiunta a handler/s, costi e ritardi. Il numero di flush può differire dai frame per aggiornamenti parziali; nessuna delle due misure conta necessariamente le immagini effettivamente presentate dalla GPU del browser. **Serve ricompilare WASM** dopo il pull: `source ~/emsdk/emsdk_env.sh && ./scripts/build_web.sh`, quindi `Ctrl+F5`. Riportare entrambi i riquadri sul solito tratto a 1× per decidere se il disegno LVGL è meno frequente rispetto agli aggiornamenti replay. Non variare ulteriormente lo zoom già approvato; nessuna modifica a ESP32, Android o Valhalla.

**Refresh WebAssembly 60 Hz (8 novembre, sperimentale):** l'utente ha registrato `60 RAF/s · 64 WASM/s · 0,1 ms medi · 0,2 ms max · 0 ritardi` ma solo `60 handler/s · 30 render/s · 30 flush/s · 0,7 ms medi · 1,7 ms max · 0 ritardi`, con render/s talvolta 23–25 e mappa visivamente scattosa. Il valore condiviso `LV_DEF_REFR_PERIOD=25 ms` fa scadere il timer display meno spesso del browser a 60 Hz (~16,7 ms/frame); se il timer viene controllato soltanto una volta per frame, la scadenza può essere osservata ogni due callback, producendo circa 30 disegni/s. **Intervento mirato solo WebAssembly:** in `platforms/web/wasm/main_web.cpp` il timer del display SDL ottenuto da `lv_display_get_refr_timer(display)` viene configurato a **15 ms** con `lv_timer_set_period`. **Nessun cambio a `config/lv_conf.h` o ESP32**; zoom geometrico e routing invariati. Ricompilare in WSL dopo pull (`source ~/emsdk/emsdk_env.sh && ./scripts/build_web.sh`), poi Ctrl+F5. Verificare il numero di `render/s` e `flush/s` e la fluidità reale alle 12:10:50. I render LVGL possono comunque essere inferiori a RAF se non esistono aree invalidate; per questo test la mappa è in movimento. Risultato **ancora da collaudare**, non dichiarare 60 fps confermati.

**Correzione degli scatti GPS del replay (8 novembre, JavaScript):** nel video sul PC appaiono circa 60 RAF/s, 50 WASM/s e 60 handler LVGL/s con 4.732 snapshot ma solo 1.178 fix GPS. Il player precedentemente interpolava i soli snapshot immediatamente successivi: molte coordinate erano identiche e la mappa avanzava per salti. Ora, durante Play, sono interpolati i fix GPS *distinti*, con salvaguardie su ricalcoli e buchi GPS; Step/Seek restano fedeli al log. Per provare la nuova correzione, nella shell WSL dentro `~/MotoGPS/moto-gps-waveshare` sul branch `test/road-contrast-cleanup-20261104` eseguire `git pull --ff-only`; lasciare il server HTTP già avviato e premere `Ctrl+F5` in Chrome. **Nessuna ricompilazione WASM è necessaria per questa sola modifica JS**, se il runtime era già aggiornato al refresh LVGL di 15 ms. Verificare lo stesso tratto a 1× e annotare fluidità, WASM/s, render/s e flush/s. Test visivo della correzione ancora da confermare; zoom tornanti non modificato.

**Seconda correzione refresh WebAssembly (8 novembre):** con timer display a 15 ms il test utente ha registrato `60 RAF/s · 62 WASM/s · 0,1 ms medi · 0,9 ms max · 0 ritardi` e `60 handler/s · 45 render/s · 45 flush/s · 0,9 ms medi · 1,4 ms max · 0 ritardi`, con cali render/flush anche a ~25. Quindi il refresh a 15 ms ha migliorato 30→45 render/s ma rimangono occasionali scadenze perse fra il timer LVGL e `requestAnimationFrame` del browser. **Nuovo esperimento, solo simulatore:** la `frame()` in `platforms/web/wasm/main_web.cpp` marca esplicitamente pronto (`lv_timer_ready`) il timer di refresh del display ottenuto da `lv_display_get_refr_timer` prima di `lv_timer_handler`, una volta per RAF. Ciò sincronizza la richiesta di refresh al browser, senza toccare la configurazione condivisa di ESP32 e senza garantire un flush quando la scena non è invalidata. **Ricompilare WASM** dopo `git pull`: `source ~/emsdk/emsdk_env.sh && ./scripts/build_web.sh`; `Ctrl+F5` nel browser, riprovare alle 12:10:50 a 1× e riportare diagnosi e sensazione visiva. Non modificato l'algoritmo dei tornanti. Test visuale ancora da eseguire.

**Eliminazione doppio orologio di interpolazione (8 novembre):** feedback dopo `lv_timer_ready`: scatti ridotti ma persistenti; `60 RAF/s · 33 WASM/s · 0,1 ms medi · 0,4 ms max · 0 ritardi` e `60 handler/s · 31 render/s · 31 flush/s · 0,7 ms medi · 1,7 ms max · 0 ritardi`, con render e WASM che oscillano da ~33 a 60. Il simulatore usava ancora un limitatore separato `now-lastMotionPaint<14` per interpolare il GPS: il secondo orologio poteva tagliare interi RAF quando la cadenza del browser non rispettava quella soglia. Rimosso il filtro: durante Play ora si tenta una interpolazione per ogni RAF. Nella diagnostica replay compaiono `interp/s` (interpolazioni valide) e `senza interp` (RAF senza arco GPS interpolabile); se il secondo resta alto significa che il registrato non consente una posizione nuova senza inventarla, non un problema di flush. Nessuna modifica a C++/WASM, zoom o firmware in questa iterazione: **basta `git switch valhalla
git pull --ff-only origin valhalla` e `Ctrl+F5`**, mantenendo aperto il server locale. Ripetere a 1× il tratto 12:10:50–12:12:30. Non dichiarare risolti gli scatti prima del test visivo.

**Prova fluidità movimento mappa (8 novembre, sperimentale):** l'ultimo replay è risultato `60 RAF/s · 65 WASM/s · 0,1 ms medi · 0,2 ms max · 0 ritardi · 61 interp/s · 0 senza interp`, ma il ciclo grafico ha mostrato `60 handler/s · 45 render/s · 45 flush/s · 1,0 ms medi · 1,6 ms max · 0 ritardi`. Una registrazione video di 39 secondi mostra che i cambi di posizione della mappa non sono sempre regolari. Nel codice della UI condivisa è presente un **secondo timer di movimento** `route_motion_tick` a 25 ms, distinto dal refresh del display già allineato a RAF. Sulla compilazione WebAssembly del solo branch test quel timer è ora impostato a 1 ms, perché viene esaminato una volta per callback LVGL (~60 Hz, non genera 1000 esecuzioni/s), e il fattore di interpolazione passa da `0,39` a `0,28` per conservare circa la stessa costante di smorzamento nel tempo. Sull'ESP32 restano **25 ms e 0,39**, senza cambiare alcuna firma API, BLE, routing o zoom. I vertici grafici sono tuttora quantizzati a pixel interi, quindi `render/s` può rimanere inferiore a 60 anche con un movimento regolare; non usare i soli FPS come prova di fluidità. **Ricompilare WebAssembly** dopo l'aggiornamento del branch e ricaricare Chrome (`Ctrl+F5`):
```bash
cd ~/MotoGPS/moto-gps-waveshare
git switch valhalla
git pull --ff-only origin valhalla
source ~/emsdk/emsdk_env.sh
./scripts/build_web.sh
```
Verificare alle 12:10:50–12:12:30 a 1×, osservando soprattutto i salti delle linee verdi e grigie, oltre a render/s, flush/s e interpolazioni valide. Compilazione e test visivo successivi ancora da confermare.

**Replay, diagnostica avanzata e correzione del doppio bersaglio — 8 novembre 2026 (sperimentale):** durante Play, quando il cursore attraversava uno snapshot registrato, il simulatore chiamava WASM due volte nello stesso RAF: prima con la posizione grezza, poi con quella interpolata. Il player ora invia **una sola posizione per RAF** (interpolata, oppure grezza se l'intervallo non è interpolabile). I comandi Pausa, Step e Seek continuano a visualizzare fedelmente gli snapshot originali. `trace_model.js` distingue le ragioni del mancato movimento: `reroute` (cambio percorso), `gap` (intervallo GPS fuori 70–3000 ms), `jump` (salto oltre 100 m), `state` (non navigazione), `no-route`, `boundary` ed `end`; nessuna estrapolazione attraverso ricalcoli o salti anomali.

La nuova riga **Movimento mappa (solo WASM)** confronta `bersagli/s` (variazioni delle coordinate richieste della prima linea di percorso), `movimenti pixel/s` (tick che hanno realmente spostato i pixel della rotta o delle strade di contesto) e `px ritardo` (distanza del primo vertice verde dal suo bersaglio, in pixel). Non interpretare `movimenti pixel/s` come FPS GPU né richiedere 60 aggiornamenti quando lo spostamento è subpixel. I contatori C++ sono confinati in `#ifdef __EMSCRIPTEN__`, senza modificare le build ESP32.

**Ricompilazione obbligatoria** per le nuove funzioni di diagnostica C++/WASM:
```bash
cd ~/MotoGPS/moto-gps-waveshare
git switch valhalla
git pull --ff-only origin valhalla
source ~/emsdk/emsdk_env.sh
./scripts/build_web.sh
```
Lasciare avviato il server `python3 -m http.server 4173 --directory .` dalla radice del repository, aprire `http://127.0.0.1:4173/platforms/web/simulator/index.html`, ricaricare con `Ctrl+F5`, importare la registrazione privata e riprodurre i tornanti alle 12:10:50 a 1×. Confrontare replay WASM, ciclo LVGL, movimento mappa e sensazione visiva. La modifica è **da verificare sul PC utente**; i 13 test del parser sono stati eseguiti in V8 con adattatore minimo (non ancora con `node --test`), nessuna compilazione WSL o CI confermata.

**Esperimento precisione subpixel solo simulatore (8 novembre 2026):** nella compilazione Emscripten il presenter conserva in `moto_ui_state_t` due buffer supplementari di punti `float` (rotta selezionata e contesto), generati dallo stesso calcolo geografico che alimenta i punti `int16_t` di produzione. La UI browser usa i `float` come bersagli di movimento e li passa al disegno LVGL senza arrotondare prima a pixel interi. Sul firmware ESP32 i buffer aggiuntivi non sono compilati (`__EMSCRIPTEN__`), la UI continua a usare esclusivamente punti interi e il timer collaudato 25 ms/0,39. **Non è stata attivata una nuova funzionalità sull'ESP32:** questa prova misura se mantenere le frazioni nel simulatore riduce i salti; solo dopo un riscontro visivo e una verifica di RAM/FPS sul dispositivo si valuterà il trasferimento.

Ricompilare WASM perché cambiano C++ e struct della build web:
```bash
cd ~/MotoGPS/moto-gps-waveshare
git switch valhalla
git pull --ff-only origin valhalla
source ~/emsdk/emsdk_env.sh
./scripts/build_web.sh
```
Dopo `Ctrl+F5`, ripetere lo stesso tratto a 1×. Nella riga `Movimento mappa` i `bersagli/s` misurano ora anche le variazioni subpixel del primo vertice verde; `movimenti pixel/s` indica i tick di modifica dei punti grafici, **non** presentazioni reali GPU né cambi di pixel interi. Registrare una nuova prova per confrontare fluidità e tempi LVGL prima di accettare la modifica. Il test native protegge la precedente quantizzazione a pixel interi e l'allineamento percorso/contesto su ESP32.

**Firmware ESP32 — zoom/subpixel validati su strada, 9 novembre 2026:** le due opzioni `MOTO_ESP_CURVE_ZOOM_EXPERIMENT` e `MOTO_ESP_SUBPIXEL_EXPERIMENT` sono disponibili in `valhalla` ma restano **OFF di default**. L'ESP32 provato il 9 novembre (zoom fino a 4× e nessun rallentamento percepito) aveva entrambe ON; le due opzioni non modificano BLE, routing o timer del movimento UI a 25 ms.

**Nuovo esperimento di ottimizzazione (solo `test/esp32-map-dirty-tiles-20261109`):** il terzo flag `MOTO_ESP_MAP_DIRTY_TILES_EXPERIMENT` (OFF per impostazione predefinita) limita le invalidazioni LVGL **durante il movimento interpolato** alle celle della mappa attraversate dalle vecchie e nuove linee, con margine per antialiasing e ombra. Se almeno metà delle 16 celle cambia, il codice usa la stessa invalidazione completa precedente. Le modifiche alle scene/ai percorsi rimangono sul percorso originale. Esperimento A/B: non è ancora validato su hardware e non va confuso con la precedente prova positiva.

**PowerShell Windows** (ESP-IDF 5.5.6 già attivo con `moto`, dal checkout nella radice del repository):
```powershell
git fetch origin
git switch test/esp32-map-dirty-tiles-20261109
git pull --ff-only origin test/esp32-map-dirty-tiles-20261109
cd platforms/esp32
idf.py -DMOTO_ESP_CURVE_ZOOM_EXPERIMENT=ON -DMOTO_ESP_SUBPIXEL_EXPERIMENT=ON -DMOTO_ESP_MAP_DIRTY_TILES_EXPERIMENT=ON build
```
### Nuova telemetria CPU0 / CPU1 per il prossimo giro (9 novembre, solo branch test)

L'artifact `ESP32-Firmware-EXPERIMENTAL` di questo branch, dopo la compilazione della nuova versione, include un campionamento seriale **ogni 5 secondi**: `CORE_PERF CPU0 CPU1 UI LVGL BLE sample_us` e `TOP1/TOP2/TOP3`. I due carichi CPU sono stimati dal tempo di inattività dei rispettivi task IDLE; la quota degli altri task è riferita ai 5 secondi di osservazione, non all'intera capacità dei due core. `aff-1` significa che un task non è fissato a un core, **non** che il carico sia negativo. La durata `sample_us` misura quanto la lettura dello stato FreeRTOS sospende brevemente lo scheduler; intervalli anomali o peggioramenti visibili devono far interrompere la prova del logger. ISR e trasferimenti DMA non vengono isolati da questa statistica.

La telemetria è **disattivata nelle build standard**. Per compilarla manualmente su Windows PowerShell, con ambiente ESP-IDF 5.5.6 già attivato dal comando `moto` e dopo il `git pull` del branch sperimentale:

```powershell
cd C:\MotoGPS\moto-gps-waveshare\platforms\esp32
idf.py '-DSDKCONFIG_DEFAULTS=sdkconfig.defaults;sdkconfig.perf.defaults' set-target esp32s3
idf.py '-DSDKCONFIG_DEFAULTS=sdkconfig.defaults;sdkconfig.perf.defaults' -DMOTO_ESP_CURVE_ZOOM_EXPERIMENT=ON -DMOTO_ESP_SUBPIXEL_EXPERIMENT=ON -DMOTO_ESP_MAP_DIRTY_TILES_EXPERIMENT=ON -DMOTO_ESP_PERF_TELEMETRY_EXPERIMENT=ON build
```

Il file `sdkconfig.perf.defaults` integra, **senza sostituirlo**, `sdkconfig.defaults`; abilita le statistiche FreeRTOS con orologio ESP_TIMER a 1 MHz e contatore uint64. Abilita anche `CONFIG_FREERTOS_VTASKLIST_INCLUDE_COREID=y`, necessario su ESP-IDF 5.5 per includere l'affinità `xCoreID` nelle informazioni dei task. La [run ESP32 42604538068](https://github.com/thriller47/moto-gps-waveshare/actions/runs/42604538068) ha verificato questa configurazione con esito verde; artifact `ESP32-Firmware-EXPERIMENTAL` disponibile fino al 12 novembre. `set-target` ricrea `sdkconfig`: fare attenzione a eventuali modifiche locali non salvate. Per ripristinare una build davvero standard bisogna rimuovere le opzioni runtime stats dalla precedente configurazione (reconfigure pulito / fullclean), usare soltanto `sdkconfig.defaults` e disattivare i flag CMake.

**Raccolta durante il test:** collegare USB Terminal o `idf.py monitor`, salvare l'intero log dall'accensione al termine del percorso, conservando insieme le righe `CORE_PERF`, `NAV_EXP` (proiezione/lock/zoom), `display perf` (FPS/render/flush) e, separatamente, il replay GPS Android `.jsonl` e lo ZIP diagnostico. **Prima fare 2–3 minuti da fermo**: se si notano scatti, disconnessioni o valori `sample_us` insolitamente elevati, interrompere il test sperimentale e tornare al firmware già validato. Non interagire con il telefono mentre si guida; lasciare OFF la diagnostica persistente, che aveva causato freeze in passato.

Per confrontare **la stessa configurazione del 9 novembre**, mantenere zoom e subpixel ON e cambiare soltanto `-DMOTO_ESP_MAP_DIRTY_TILES_EXPERIMENT=OFF` (ricompilare). Confrontare sullo **stesso percorso** i valori seriali `board_waveshare: display perf` (FPS veri, rendering ms, flush/frame), non il solo `NAV_EXP hz`, che misura la task. Il flusso USB Terminal e la raccolta replay Android restano invariati. Evitare l'uso del telefono durante la guida.

I valori delle opzioni restano nella cache CMake: per ritornare alla build **standard valhalla** impostare esplicitamente tutti e tre i flag a OFF (oppure pulire la configurazione) e ricompilare. Nella CI del branch test tutte e tre sono ON e l'artifact è `ESP32-Firmware-EXPERIMENTAL`; nella CI di `valhalla` restano OFF e l'artifact si chiama `ESP32-Firmware`. Verificare prima la CI e poi l'hardware, senza effettuare merge automatico.

Nel monitor seriale `idf.py -p COM3 monitor` l'intestazione `ESP NAV EXPERIMENT curve=1 subpixel=1` conferma i primi due flag, **non il terzo**: per verificarlo controllare i parametri di build. La riga `NAV_EXP render=... hz=... projection_avg=...us max=...us lock_max=...us zoom_x100=...` misura la task e non i frame effettivamente visualizzati.
**Riprodurre:** caricare il file JSONL, usare play/pausa, frame precedente/successivo, cursore, velocità 0,25–4×, gli **eventi cliccabili** e il grafico di velocità per raggiungere subito tappa, rotatorie, cambi rotta e arrivo. Lo zoom mostrato accanto alla manovra riproduce il calcolo attuale del presenter. "Esempio sintetico" serve soltanto a verificare l'interfaccia: non è una mappa OSM reale. La funzione PNG può dipendere dal framebuffer WebGL. Il player non ricalcola Valhalla o il selettore MotoMap: per cambiare i punteggi delle strade servirà una seconda modalità con anche le candidate non selezionate. I precedenti ZIP senza geometrie non si possono riprodurre come mappe fedeli.

Guida completa: platforms/web/simulator/README.md. La vecchia platforms/web/shell/index.html resta la demo storica e non il nuovo banco prova.


## 9. Promemoria rapido

| Operazione | File / comando |
| --- | --- |
| Aggiornare il codice stabile | `git pull --ff-only origin valhalla` |
| Firmware ESP32 GitHub | Artifact **ESP32-Firmware** della Project Build |
| Flash completo | bootloader `0x0`, partizioni `0x8000`, app `0x20000` |
| Flash solo app | `0x20000` |
| MotoMap offline | `pbf_to_motomap.py` → **.motomap.zip**; `italy.poly` per copertura v2/v3 |
| Grafo Valhalla | WSL2/Ubuntu, `pyvalhalla==3.9.1`, 2 thread → **.tar.zst** |
| Log diagnostici attuali | Android → Condividi diagnostica → **.zip** |
| Replay GPS riservato | Android → LOG → Avvia/Ferma → Esporta JSONL; Windows → platforms/web/simulator/ |
| Log ESP32 storici | `read-flash` e decoder; soltanto firmware precedenti |
| Console radio sperimentale | Modalità isolata con pulsante Wi-Fi; COM3 (se corrisponde alla porta USB), 115200, `wifi help`, `wifi set`, `wifi test`, `radio list`, `radio play` |

Il vecchio backend/gateway è fuori uso. La **radio seriale di test è ancora presente nel codice firmware**, ma si usa esclusivamente dopo il passaggio alla **modalità radio isolata** (sezione 7), mai nella normale schermata GPS. Non considerarla una funzione audio stabile o contemporanea alla navigazione.

**Prima del flash:** leggere `PROJECT_STATE.md`, controllare commit e run degli artifact. CI verde non equivale a test su strada.

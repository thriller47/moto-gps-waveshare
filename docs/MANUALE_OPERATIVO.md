# Moto GPS — Manuale operativo (Windows CMD e PowerShell)

**Riferimento:** repository thriller47/moto-gps-waveshare, branch principale **valhalla**. 
**Scheda:** Waveshare ESP32-S3-Touch-AMOLED-1.75C, 32 MB flash, 8 MB PSRAM.
**Porta negli esempi:** **COM3**. I blocchi sono etichettati **PowerShell** o **CMD**: usare la shell indicata. 
**Aggiornato:** 2 novembre 2026. Controllare prima PROJECT_STATE.md e AGENTS.md.

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
python scripts\offline_map\pbf_to_motomap.py C:\MotoGPS\PBF\italy-latest.osm.pbf -o C:\MotoGPS\PBF\italia.motomap.zip
~~~

L'ultimo comando genera **italia.motomap.zip**, leggibile direttamente dall'app: **Gestione mappe → Importa mappa ZIP**. Non decomprimere il pacchetto sul telefono.

Per una sottoregione, sostituire il nome del PBF e usare un output differente:

~~~cmd
python scripts\offline_map\pbf_to_motomap.py C:\MotoGPS\PBF\nord-ovest-latest.osm.pbf -o C:\MotoGPS\PBF\nord-ovest.motomap.zip
~~~

Il convertitore Python è in memoria e l'intera Italia può usare diversi GB di RAM: chiudere programmi pesanti e controllare spazio disco. I PBF di Geofabrik **non sono** direttamente importabili come MotoMap.

### MotoMap v2: indice di copertura e poligono Geofabrik

Per il file Italia `italy.poly` usare **la fonte ufficiale Geofabrik**:
https://download.geofabrik.de/europe/italy.poly .
È il poligono di ritaglio della regione, non un grafo Valhalla. Controllare che il PBF provenga dallo stesso estratto (Italia, non Nord-Ovest o altro).

**PowerShell:**

~~~powershell
New-Item -ItemType Directory -Force C:\MotoGPS\PBF | Out-Null
Invoke-WebRequest "https://download.geofabrik.de/europe/italy.poly" -OutFile "C:\MotoGPS\PBF\italy.poly"
Set-Location "C:\MotoGPS\moto-gps-waveshare"
python -m pip install osmium shapely
python scripts\offline_map\pbf_to_motomap.py C:\MotoGPS\PBF\italy-261026.osm.pbf -o C:\MotoGPS\PBF\italy-v2.motomap.zip --coverage-poly C:\MotoGPS\PBF\italy.poly
~~~

Se si utilizza un PBF nuovo, sostituire il percorso. **Non rinominare un PBF regionale come Italia:** il poligono certifica solo celle z14 interamente interne all'estratto dichiarato. Se non esiste un `.poly` affidabile, omettere `--coverage-poly`: il pacchetto v2 funzionerà, ma non certificherà tile vuote. Con il file v2 Android può riconoscere aree elaborate prive di strade senza riscaricarle da OpenFreeMap. Conservare il precedente ZIP fino all'importazione e alla verifica della copertura.

## 5. Creare o importare i grafi Valhalla

### A. Metodo più semplice: grafo già pronto

Scaricare, se disponibile per la zona e compatibile, un archivio **.tar.zst** (per esempio **Italy.tar.zst** da un distributore di grafi Valhalla) o **.tar**. Sul telefono:
**Gestione mappe → Importa grafo TAR / ZST**.

L'app decomprime autonomamente uno ZST in un TAR con tile **.gph**, verifica l'archivio e salva il file in area privata. Non occorre decomprimere lo ZST sul PC. **Verificare il routing effettivo** dopo l'importazione: alcuni grafi sono incompatibili con la versione del motore nell'APK.

### B. Creare autonomamente il grafo dal PBF (Docker Desktop, Windows)

Richiede **Docker Desktop avviato**, container Linux e spazio libero su SSD. Il container ufficiale Valhalla scripted genera tile e archivio TAR indicizzato. Prima provare con **un estratto regionale**. Con 16 GB di RAM sul PC, il build dell'Italia intera può esaurire la memoria.

~~~cmd
docker --version
mkdir C:\MotoGPS\ValhallaData
copy C:\MotoGPS\PBF\nord-ovest-latest.osm.pbf C:\MotoGPS\ValhallaData\
docker run --rm --name motogps-valhalla-build -v "C:\MotoGPS\ValhallaData:/custom_files" -e serve_tiles=False -e build_tar=True -e server_threads=2 ghcr.io/valhalla/valhalla-scripted:latest
~~~

Il risultato previsto è **C:\MotoGPS\ValhallaData\valhalla_tiles.tar**. Il container costruisce le tile stradali dal PBF e l'archivio TAR; l'opzione server_threads=2 limita il parallelismo. Se Docker restituisce errori, consultare i log e verificare RAM/spazio disponibile; non interpretare la sola creazione di un file come prova di compatibilità.

**Controllo rapido dell'archivio** tramite tar integrato in Windows:

~~~cmd
dir C:\MotoGPS\ValhallaData\valhalla_tiles.tar
tar -tf C:\MotoGPS\ValhallaData\valhalla_tiles.tar | findstr /i ".gph" | more
~~~

Se compaiono tile **.gph**, importare **valhalla_tiles.tar** nell'app Android, usando **Importa grafo TAR / ZST**. L'app supporta direttamente il TAR: **comprimerlo non è obbligatorio**.

**Compressione opzionale** (solo se è installato il programma zstd):

~~~cmd
cd /d C:\MotoGPS\ValhallaData
zstd -T2 -6 valhalla_tiles.tar -o valhalla_tiles.tar.zst
~~~

Per aggiornare un grafo, lavorare preferibilmente con una **nuova cartella ValhallaData** contenente il nuovo PBF, così un eventuale TAR precedente non viene riutilizzato silenziosamente. La dimensione espansa deve restare entro il limite dell'importatore Android (20 GiB). Per l'intera Italia, valutare RAM, tempi e SSD: con 16 GB di RAM il build potrebbe non riuscire.

Fonti: https://github.com/valhalla/valhalla/blob/master/docker/README.md e https://valhalla.github.io/valhalla/start/building/

### C. Alternativa indipendente: build locale Geofabrik PBF → grafo TAR (Linux/WSL2)

Se routing.earth o altri distributori diventano indisponibili, il repository contiene `scripts/offline_map/build_valhalla_graph.sh`, basato sugli strumenti ufficiali Valhalla. Produce `valhalla_tiles.tar`, importabile direttamente nell'app, senza dipendere da server di grafi pronti. **Non è un convertitore Python puro:** occorrono gli eseguibili Valhalla installati nel sistema Linux (o in WSL2), spazio SSD e molta RAM. È un'alternativa al Docker della sezione B.

Su **Windows PowerShell**, installare WSL2 se necessario (`wsl --install -d Ubuntu`, con riavvio se richiesto), poi lavorare nel terminale Ubuntu. Verificare che gli strumenti `valhalla_build_config`, `valhalla_build_tiles`, `valhalla_build_extract`, `valhalla_build_admins`, `valhalla_build_timezones` siano disponibili tramite pacchetti della distribuzione o una build Valhalla compilata seguendo le istruzioni ufficiali: https://valhalla.github.io/valhalla/start/building/ . La disponibilità esatta dei pacchetti varia in base alla distribuzione.

**Ubuntu/WSL2 (Bash):**

~~~bash
command -v valhalla_build_config
command -v valhalla_build_tiles
command -v valhalla_build_extract
# Opzionale, per comprimere in tar.zst:
sudo apt-get update
sudo apt-get install -y zstd
cd /mnt/c/MotoGPS/moto-gps-waveshare
bash scripts/offline_map/build_valhalla_graph.sh \
  /mnt/c/MotoGPS/PBF/italy-261026.osm.pbf \
  /mnt/c/MotoGPS/ValhallaItaliaV2 --zstd
~~~

La cartella `ValhallaItaliaV2` deve essere **nuova e vuota**: lo script si rifiuta di sovrascrivere un grafo precedente. Crea configurazione e directory delle tile, costruisce timezone/admin database quando gli strumenti sono presenti, genera il grafo e il TAR indicizzato, poi controlla che contenga file `.gph`. L'output compresso sarà `C:\MotoGPS\ValhallaItaliaV2\valhalla_tiles.tar.zst`; senza `--zstd` importare `valhalla_tiles.tar`.

Per provare prima su una zona piccola usare `nord-ovest-latest.osm.pbf` e una cartella differente. Con un PBF Italia completo il consumo di RAM e disco può essere notevole; WSL2 deve avere memoria disponibile e lo spazio deve comprendere tile temporanee, TAR e TAR compresso. Se la build non riesce per memoria, usare una sottoregione o un PC più capiente. Su WSL2 può essere più veloce lavorare nel filesystem Linux anziché direttamente in `/mnt/c`.

**Verifica PowerShell dell'archivio:**

~~~powershell
tar -tf C:\MotoGPS\ValhallaItaliaV2\valhalla_tiles.tar | Select-String '\.gph (PSRAM/BLE) e Android

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

## 7. Console seriale Wi-Fi della radio (COM3)

La modalità radio può essere avviata separatamente da GPS/BLE tramite il controllo **RADIO** nel firmware che implementa la commutazione. I comandi seguenti sono verificati nel parser del repository (**signal_wifi_console.cpp**). **Non inviarli nella normale schermata GPS**, né durante la guida.

Usare un terminale **bidirezionale** a **115200 baud**, terminatore **LF/Newline** (per esempio monitor Arduino IDE con Newline), oppure Python + pyserial:

~~~cmd
python -m pip install pyserial
python -m serial.tools.miniterm COM3 115200 --eol LF
~~~

Digitare i comandi che seguono **dentro il terminale seriale**, non nel CMD. In particolare, il carattere **|** nel comando wifi set è un separatore della console radio: nella shell CMD sarebbe invece un operatore di pipe!

~~~text
wifi help
wifi status
wifi set NomeRete|PasswordTemporanea
wifi test
wifi status
wifi off
~~~

| Comando nel monitor seriale | Funzione |
| --- | --- |
| wifi help | Mostra i comandi previsti |
| wifi set NomeRete&#124;PasswordTemporanea | Salva SSID e password in NVS; supporta spazi nell'SSID |
| wifi test | Connette al Wi-Fi **2,4 GHz** memorizzato |
| wifi status | Indica se è connesso |
| wifi off | Disconnette il Wi-Fi |
| radio list | Elenca le stazioni MP3 disponibili |
| radio play | Avvia la prima stazione |
| radio play 2 | Avvia la stazione n. 2, se presente |
| radio stop | **Durante il playback**, ferma la riproduzione |
| radio volume 20 | **Durante il playback**, imposta volume al 20% (range 0–100) |

Per uscire da miniterm: **Ctrl+]**. Per evitare errori, prima digitare **wifi status**: se non arriva alcuna risposta, verificare che sia attiva la modalità radio e che il terminale non sia su un'altra porta.

**Sicurezza:** la console di prova non maschera la password; il terminale può conservarla nella cronologia e la memorizzazione NVS potrebbe non essere cifrata. Usare un hotspot di prova, non inserire password reali in schermate/log pubblici.

## 8. Promemoria rapido

| Operazione | File / comando essenziale |
| --- | --- |
| Aggiornare il codice | git pull --ff-only origin valhalla |
| Firmware ESP32 GitHub | Artifact **ESP32-Firmware** della Project Build |
| Flash completo | bootloader a 0x0; partition-table a 0x8000; app a 0x20000 |
| Flash solo aggiornamento | app a 0x20000 |
| MotoMap offline | pbf_to_motomap.py → **.motomap.zip** |
| Routing offline | Docker Valhalla o grafo esterno → **.tar** / **.tar.zst** |
| Log ESP32 storico | read-flash 0xC20000 0x40000 → **.bin** → decoder → **.csv** |
| Log Android | Condividi diagnostica → **.zip** |
| Wi-Fi radio | COM3, 115200, terminale con Newline; wifi set SSID&#124;PASSWORD |

**In caso di dubbi prima di flashare:** leggere PROJECT_STATE.md e controllare il commit e la run GitHub da cui provengono i binari. CI verde non equivale a stabilità verificata su strada.
 | Select-Object -First 5
~~~

Copiare TAR o TAR.ZST sul telefono e selezionare **Gestione mappe → Importa grafo TAR / ZST**. Disattivare temporaneamente la rete e provare una rotta all'interno della copertura: i log devono riportare il provider **Valhalla offline**. L'esistenza del TAR, il controllo `.gph` e una build riuscita **non garantiscono** da soli che il motore JNI Android possa leggerlo: la compatibilità dei grafi binari va verificata sul dispositivo.

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

## 7. Console seriale Wi-Fi della radio (COM3)

La modalità radio può essere avviata separatamente da GPS/BLE tramite il controllo **RADIO** nel firmware che implementa la commutazione. I comandi seguenti sono verificati nel parser del repository (**signal_wifi_console.cpp**). **Non inviarli nella normale schermata GPS**, né durante la guida.

Usare un terminale **bidirezionale** a **115200 baud**, terminatore **LF/Newline** (per esempio monitor Arduino IDE con Newline), oppure Python + pyserial:

~~~cmd
python -m pip install pyserial
python -m serial.tools.miniterm COM3 115200 --eol LF
~~~

Digitare i comandi che seguono **dentro il terminale seriale**, non nel CMD. In particolare, il carattere **|** nel comando wifi set è un separatore della console radio: nella shell CMD sarebbe invece un operatore di pipe!

~~~text
wifi help
wifi status
wifi set NomeRete|PasswordTemporanea
wifi test
wifi status
wifi off
~~~

| Comando nel monitor seriale | Funzione |
| --- | --- |
| wifi help | Mostra i comandi previsti |
| wifi set NomeRete&#124;PasswordTemporanea | Salva SSID e password in NVS; supporta spazi nell'SSID |
| wifi test | Connette al Wi-Fi **2,4 GHz** memorizzato |
| wifi status | Indica se è connesso |
| wifi off | Disconnette il Wi-Fi |
| radio list | Elenca le stazioni MP3 disponibili |
| radio play | Avvia la prima stazione |
| radio play 2 | Avvia la stazione n. 2, se presente |
| radio stop | **Durante il playback**, ferma la riproduzione |
| radio volume 20 | **Durante il playback**, imposta volume al 20% (range 0–100) |

Per uscire da miniterm: **Ctrl+]**. Per evitare errori, prima digitare **wifi status**: se non arriva alcuna risposta, verificare che sia attiva la modalità radio e che il terminale non sia su un'altra porta.

**Sicurezza:** la console di prova non maschera la password; il terminale può conservarla nella cronologia e la memorizzazione NVS potrebbe non essere cifrata. Usare un hotspot di prova, non inserire password reali in schermate/log pubblici.

## 8. Promemoria rapido

| Operazione | File / comando essenziale |
| --- | --- |
| Aggiornare il codice | git pull --ff-only origin valhalla |
| Firmware ESP32 GitHub | Artifact **ESP32-Firmware** della Project Build |
| Flash completo | bootloader a 0x0; partition-table a 0x8000; app a 0x20000 |
| Flash solo aggiornamento | app a 0x20000 |
| MotoMap offline | pbf_to_motomap.py → **.motomap.zip** |
| Routing offline | Docker Valhalla o grafo esterno → **.tar** / **.tar.zst** |
| Log ESP32 storico | read-flash 0xC20000 0x40000 → **.bin** → decoder → **.csv** |
| Log Android | Condividi diagnostica → **.zip** |
| Wi-Fi radio | COM3, 115200, terminale con Newline; wifi set SSID&#124;PASSWORD |

**In caso di dubbi prima di flashare:** leggere PROJECT_STATE.md e controllare il commit e la run GitHub da cui provengono i binari. CI verde non equivale a stabilità verificata su strada.

# Moto GPS — Manuale operativo (Windows CMD e PowerShell)

**Riferimento:** repository thriller47/moto-gps-waveshare, branch principale **valhalla**. 
**Scheda:** Waveshare ESP32-S3-Touch-AMOLED-1.75C, 32 MB flash, 8 MB PSRAM.
**Porta negli esempi:** **COM3**. I blocchi sono etichettati **PowerShell** o **CMD**: usare la shell indicata. 
**Aggiornato:** 31 ottobre 2026. Controllare prima PROJECT_STATE.md e AGENTS.md.

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

## 6. Recuperare e convertire i log diagnostici ESP32

**Stato attuale (2026-10-30):** la registrazione continua sulla flash ESP32 è **disattivata di default** dalla build **4c363b8f**, perché sospettata di causare blocchi LVGL. La partizione diagnostica rimane e conserva i **vecchi** log. Non usare questi vecchi dati come se fossero stati registrati con una build nuova.

**Leggere i 256 KiB della partizione** (ESP32 collegato a COM3; nessun altro programma deve avere aperto la porta):

~~~cmd
cd /d C:\MotoGPS\moto-gps-waveshare
esptool.exe --chip esp32s3 -p COM3 -b 460800 read-flash 0xC20000 0x40000 esp-diag.bin
~~~

**Convertire il dump binario in CSV:**

~~~cmd
python scripts\diagnostics\decode_esp32_trace.py esp-diag.bin
~~~

Il risultato è **esp-diag.csv** nella cartella corrente, con numero di sequenza, uptime ESP32, evento e cinque campi numerici. Possibili eventi: BOOT, LINK, NAV_SNAPSHOT, MAP_SCENE, ESP_RENDER, RENDER_PHASE, DISPLAY_LOCK_TIMEOUT. Il decoder scarta record corrotti; il dump è in lettura, **non cancella** la flash.

Se si usa esptool **4.x**: read_flash al posto di read-flash.

**Android:** dall'app Moto GPS usare l'icona **Condividi / Esporta diagnostica** e salvare il file **motogps-android-diagnostics.zip**. Per analizzare una prova, conservare ZIP Android e CSV ESP32. **Attenzione:** nella build attuale il CSV ESP32 contiene soltanto log storici.

**Diagnostica futura v2 (ancora da implementare):** registro circolare PSRAM → trasmissione BLE su richiesta → salvataggio permanente nell'APK Android. Quando sarà disponibile, il manuale dovrà riportare il nuovo comando/pulsante di esportazione e l'eventuale conversione. Finché non viene sviluppato, **non esiste un comando BLE per estrarre i log ESP32**. Evitare scritture flash durante la guida.

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

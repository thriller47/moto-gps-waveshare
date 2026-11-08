# Moto GPS — Manuale operativo (Windows CMD e PowerShell)

**Riferimento:** repository thriller47/moto-gps-waveshare, branch principale **valhalla**. 
**Scheda:** Waveshare ESP32-S3-Touch-AMOLED-1.75C, 32 MB flash, 8 MB PSRAM.
**Porta negli esempi:** **COM3**. I blocchi sono etichettati **PowerShell** o **CMD**: usare la shell indicata. 
**Aggiornato:** 6 novembre 2026 (primo simulatore browser e registratore GPS privato sperimentali). Controllare prima PROJECT_STATE.md e AGENTS.md.

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

### MotoMap v3: poligono di copertura e topologia dei cavalcavia (branch test)

**Stato al 4 novembre 2026:** nel branch sperimentale `test/road-contrast-cleanup-20261104`, il convertitore genera **MotoMap v3** (`motomap_v3.json`). Oltre alle tile z15, conserva gli ID dei nodi OSM (base36) e il livello strada/ponte/galleria, disponibili per l'analisi topologica delle connessioni; **il filtro di livello/cavalcavia è attualmente disabilitato** dopo regressioni su strada, quindi questi metadati non vengono usati per nascondere strade. Il file `coverage_z14.txt` continua a certificare solo le celle interamente comprese nel perimetro Geofabrik. I pacchetti v2 restano supportati dall'app; `valhalla` rimane separato fino alla prova completa.

Conservare PBF e `.poly` in `C:\MotoGPS\PBF` e usare lo **script del branch test**, non quello di `valhalla` finché il merge non sarà approvato. Esempio PowerShell:

~~~powershell
cd C:\MotoGPS\moto-gps-waveshare
git switch test/road-contrast-cleanup-20261104
python -m pip install osmium shapely
python scripts\offline_map\pbf_to_motomap.py C:\MotoGPS\PBF\italy-261101.osm.pbf -o C:\MotoGPS\PBF\italy-v3.motomap.zip --coverage-poly C:\MotoGPS\PBF\italy.poly
~~~

Sostituire il nome del PBF con quello reale: lo script non richiede una cartella diversa da `C:\MotoGPS\PBF`. **Non rigenerare** un pacchetto v3 già valido soltanto perché il file si chiama `italy-v2.motomap.zip`: la versione effettiva dipende dal metadata interno, non dal nome ZIP. Per controllare il file già prodotto:

~~~powershell
python -c "import zipfile,json; p=r'C:\MotoGPS\PBF\italy-v2.motomap.zip'; z=zipfile.ZipFile(p); print(json.dumps(json.loads(z.read('motomap_v3.json')),indent=2)); print('Coverage:', 'coverage_z14.txt' in z.namelist())"
~~~

**Pacchetto verificato dall'utente:** `version=3`, `zoom=15`, `topology=osm-node-ids-base36`, `level=osm-layer-or-bridge-tunnel`, 278.048 tile stradali e 214.320 celle z14 certificate. Dopo la correzione Android del controllo di copertura (commit `35a5e02`), sul percorso Cuneo → Reggio Calabria il conteggio è passato da **667 a 0 tile mancanti**. Nel successivo test su strada l'autostrada sotto un cavalcavia è stata correttamente esclusa dalla scena di contesto. Per sfruttare la v3 serve **un APK compilato dal branch test**; lo ZIP da solo non aggiorna Android.

**Esperimento successivo (solo Android, ancora da collaudare):** commit `985700e` alza il limite della scena da 12/96 a **16 strade / 128 punti**, senza modificare firmware ESP32 o dati MotoMap. Resta sotto il massimo firmware di 24/192. Non è necessario rigenerare il PBF, reinstallare il grafo Valhalla o riflashare l'ESP32; compilare/installare Android dal branch test. Verificare sul campo la continuità delle strade grigie e l'assenza di congestione BLE prima del merge.

**Attenzione:** il poligono `italy.poly` deve corrispondere all'estratto Italia. Non rinominare un PBF di sottoregione come Italia, né usarlo con la certificazione dell'intero Paese. Senza `--coverage-poly` l'archivio resta leggibile ma non certifica le aree prive di strade. Conservare lo ZIP precedente finché l'importazione e un percorso di prova non sono riusciti.

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

**Sperimentale, branch test/road-contrast-cleanup-20261104, 6 novembre 2026.** Sono disponibili una **anteprima 2D browser senza compilatore** (non è la UI LVGL) e il renderer vero **LVGL + NavPresenter** tramite WebAssembly quando compilato. Non serve un ESP32 fisico. La compilazione Emscripten e la prova del vero renderer nel browser NON sono ancora state convalidate.

**Prima prova veloce Windows:** nel checkout del branch di test aprire `platforms\\web\\simulator\\index.html` con Chrome/Edge, caricare il file `.jsonl` registrato ed esaminare la cronologia degli eventi. Senza runtime appare chiaramente `ANTEPRIMA 2D · NON LVGL`; questo permette di esaminare percorso, scene e zoom prima di compilare Emscripten.

**WSL Ubuntu**, dalla radice del checkout del branch di test, con Git, CMake, emcmake/em++ già installati e attivati:

~~~bash
git submodule update --init --recursive
command -v emcmake
./scripts/build_web.sh
python3 -m http.server 4173 --directory .
~~~

Da Windows aprire nel browser: http://127.0.0.1:4173/platforms/web/simulator/index.html

Con Emscripten attivo in PowerShell Windows si può compilare anche con emcmake cmake -S . -B build/web -DMOTO_BUILD_WEB=ON -DMOTO_BUILD_TESTS=OFF, poi cmake --build build/web --parallel e avviare il server con py -m http.server 4173; questa variante non è ancora stata provata sul PC. Per il renderer LVGL usare il server HTTP: `file://` è ammesso soltanto per la modalità di anteprima 2D. Il runtime viene compilato nella cartella platforms/web/shell/runtime/.

**Registrare (APK di test):** nell'app aprire LOG, premere "Avvia registrazione GPS", compiere il tratto da testare, premere "Ferma registrazione GPS" e "Esporta tracciato simulatore (.jsonl)". Il file contiene coordinate e velocità GPS, geometrie della rotta e delle strade MotoMap inviate su BLE, snapshot di manovra/distanza e waypoint raggiunti. È separato dal normale ZIP diagnostico, che continua a NON contenere coordinate; l'opzione è OFF di default e dura al massimo 60 minuti / 24 MiB. Il registratore non richiede di accendere DEBUG ESP32.

**Privacy:** non condividere file GPS pubblicamente: rivelano tragitti, orari e luoghi personali. Il browser legge il file localmente senza upload. Il trace documenta l'invio BLE tentato, non il ricevimento radio sull'ESP32.

**Zoom tornanti (prototipo WASM):** sul branch di test aggiornato, dopo `source ~/emsdk/emsdk_env.sh` e `./scripts/build_web.sh` ricaricare il browser con Ctrl+F5. Il checkbox **Zoom tornanti sperimentale** funziona soltanto con renderer LVGL reale: OFF mantiene lo zoom originale; ON prova a ingrandire fino a 3× le curvature marcate della linea verde. La rotatoria conserva il suo zoom fino a 4×. Controllare i frame dopo **12:10:50**. Il firmware ESP32 reale non cambia.

**Confronto tornanti (8 novembre, correzione scatti):** sul replay reale delle 12:10:50, il rilevatore geometrico raggiunge 3× ma l'utente ha osservato una riduzione prematura a metà tornante e movimento a scatti. In questa versione sperimentale la finestra geometrica dopo il vertice è estesa da 40 a 95 metri e il player limita a circa 8 aggiornamenti al secondo il **solo grafico velocità/cronologia secondaria**, lasciando la mappa LVGL aggiornata a ogni frame GPS. Confrontare OFF/ON sullo stesso tratto, e controllare la rotatoria 4×. I miglioramenti visivi sono **ancora da verificare nel browser**; non sono abilitati nel firmware ESP32.

**Ripartenza WSL verificabile:** dalla cartella `~/MotoGPS/moto-gps-waveshare` eseguire `git switch test/road-contrast-cleanup-20261104`, `git pull --ff-only origin test/road-contrast-cleanup-20261104`, `source ~/emsdk/emsdk_env.sh`, `./scripts/build_web.sh`. Se il server è già attivo non riavviarlo: fare `Ctrl+F5` nel browser dopo la build. Se è spento, avviarlo con `python3 -m http.server 4173 --directory .`; per arrestarlo usare `Ctrl+C` nella finestra WSL che lo ospita. URL Windows: `http://127.0.0.1:4173/platforms/web/simulator/index.html`. Se la porta è occupata verificare prima che non sia rimasto un server attivo, invece di aprirne un secondo.

**Secondo intervento (8 novembre, da collaudare):** dopo la seconda prova l'utente riferisce ancora mappa a scatti e zoom terminato prima della fine del tornante. La sola estensione della finestra 40→95 m non ha risolto. La nuova sperimentazione conserva il valore zoom riconosciuto durante l'attraversamento e lo rilascia in base alla **distanza effettivamente percorsa** (prima 55 m di mantenimento, poi 80 m di riduzione) anche se la finestra GPS/rotta verde cambia. Il player Web interpola il movimento fra snapshot registrati compatibili a non più di 25 Hz; evita interpolazioni per salto GPS >35 m, intervallo >1,5 s, cambio rotta/scena o stato. Le funzioni sono **solo per il simulatore** e la riproduzione frame per frame conserva il dato originale. Non confondere la fluidità del replay interpolato con una misurazione degli FPS hardware. Ripetere il medesimo confronto ON/OFF alle 12:10:50–12:12:30 e verificare che la rotatoria raggiunga ancora 4×. Ricompilazione WASM e ricaricamento Ctrl+F5 necessari.

**Diagnostica fluidità replay (8 novembre, sperimentale):** dopo il feedback dell'utente lo zoom tornanti è considerato visivamente coerente (primo tornante 1,96×; successivi fino a 2,78×), ma la mappa WASM scatta ancora. Il parser ora riutilizza le route e le scene *identiche* ritrasmesse da Android invece di ricostruirle a ogni ripetizione; conserva invece i cambi effettivi di geometria, token, generazione o revisione. Durante Play, nella scheda «Diagnostica replay WASM» sono visibili ogni secondo **RAF/s** (callback del browser), **WASM/s** (chiamate replay), costo medio/massimo della chiamata e **ritardi** (intervalli RAF oltre 50 ms). Questi dati non equivalgono agli FPS ESP32 e non includono necessariamente tutto il tempo di rendering SDL/LVGL. Per capire se gli scatti dipendono dal PC/browser, eseguire il tratto alle 12:10:50 in modalità 1×, annotare le cifre e confrontarle con l'anteprima 2D, sempre distinguendola dal renderer reale. Nessuna modifica allo zoom 3× dei tornanti né alle rotatorie 4×.

**Misura reale del simulatore (8 novembre):** l'utente ha rilevato `60 RAF/s · 25 WASM/s · 0,1 ms medi · 0,2 ms max · 0 ritardi`. Il ciclo browser è regolare e la chiamata C++ è leggera, ma il limite artificiale dell'interpolazione GPS a 40 ms forniva solo 25 nuove posizioni al secondo. Il limite è stato abbassato a 14 ms per seguire tipicamente il display del browser (fino a circa 60 aggiornamenti/s su pannello 60 Hz), **senza modificare il rilevatore geometrico** o la semantica dei ricalcoli. Eseguire `git pull --ff-only` sul branch test, Ctrl+F5 (nessuna ricompilazione WASM necessaria perché questa modifica è solo in `player.js`), provare ancora lo stesso replay alle 12:10:50 a velocità 1× e confrontare fluidità e diagnostica. Se persiste lo scatto anche con circa 60 WASM/s e 0 ritardi, indagare il tempo di flush LVGL/SDL e la presentazione dei frame separatamente: le attuali metriche **non** misurano quel lavoro.

**Nuova diagnostica ciclo LVGL (8 novembre, solo browser):** l'utente riferisce ancora scatti con replay `48–64 WASM/s` dopo l'eliminazione del limite 25 Hz. Viene aggiunto sotto i numeri JS un secondo riquadro «Diagnostica ciclo LVGL», che mostra `handler/s`, durata media e massima di `lv_timer_handler()` in millisecondi e numero di intervalli del ciclo oltre 50 ms. È **il ciclo LVGL**, non il numero effettivo di flush SDL/immagini mostrate a schermo; un valore vicino a 60 handler/s non prova che la GPU presenti 60 immagini distinte. Poiché la modifica include C++ WebAssembly, per vedere il riquadro con dati validi **ricompilare**:
```bash
cd ~/MotoGPS/moto-gps-waveshare
git pull --ff-only origin test/road-contrast-cleanup-20261104
source ~/emsdk/emsdk_env.sh
./scripts/build_web.sh
```
Lasciare in esecuzione il server `python3 -m http.server 4173 --directory .` se è già attivo e premere `Ctrl+F5` in Chrome. Ripetere il percorso alle 12:10:50 a 1×, riportando sia diagnostica replay WASM sia diagnostica ciclo LVGL. Modifica solo per browser, senza cambiare zoom, APK o firmware. Test visivo e nuova compilazione WSL da convalidare.

**Diagnostica render/flush LVGL (8 novembre):** con la versione precedente il test utente ha mostrato `60 RAF/s · 47 WASM/s · 0,1 ms medi · 0,2 ms max · 0 ritardi` e `60 handler/s · 0,6 ms medi · 1,5 ms max · 0 ritardi`, ma la mappa continuava a scattare. Il ciclo LVGL è regolare e leggero, però questo non dimostra che venga effettuato un render a ogni handler. La diagnostica LVGL ora conta **render/s** (evento `LV_EVENT_RENDER_START`) e **flush/s** (evento `LV_EVENT_FLUSH_START`) in aggiunta a handler/s, costi e ritardi. Il numero di flush può differire dai frame per aggiornamenti parziali; nessuna delle due misure conta necessariamente le immagini effettivamente presentate dalla GPU del browser. **Serve ricompilare WASM** dopo il pull: `source ~/emsdk/emsdk_env.sh && ./scripts/build_web.sh`, quindi `Ctrl+F5`. Riportare entrambi i riquadri sul solito tratto a 1× per decidere se il disegno LVGL è meno frequente rispetto agli aggiornamenti replay. Non variare ulteriormente lo zoom già approvato; nessuna modifica a ESP32, Android o Valhalla.

**Refresh WebAssembly 60 Hz (8 novembre, sperimentale):** l'utente ha registrato `60 RAF/s · 64 WASM/s · 0,1 ms medi · 0,2 ms max · 0 ritardi` ma solo `60 handler/s · 30 render/s · 30 flush/s · 0,7 ms medi · 1,7 ms max · 0 ritardi`, con render/s talvolta 23–25 e mappa visivamente scattosa. Il valore condiviso `LV_DEF_REFR_PERIOD=25 ms` fa scadere il timer display meno spesso del browser a 60 Hz (~16,7 ms/frame); se il timer viene controllato soltanto una volta per frame, la scadenza può essere osservata ogni due callback, producendo circa 30 disegni/s. **Intervento mirato solo WebAssembly:** in `platforms/web/wasm/main_web.cpp` il timer del display SDL ottenuto da `lv_display_get_refr_timer(display)` viene configurato a **15 ms** con `lv_timer_set_period`. **Nessun cambio a `config/lv_conf.h` o ESP32**; zoom geometrico e routing invariati. Ricompilare in WSL dopo pull (`source ~/emsdk/emsdk_env.sh && ./scripts/build_web.sh`), poi Ctrl+F5. Verificare il numero di `render/s` e `flush/s` e la fluidità reale alle 12:10:50. I render LVGL possono comunque essere inferiori a RAF se non esistono aree invalidate; per questo test la mappa è in movimento. Risultato **ancora da collaudare**, non dichiarare 60 fps confermati.

**Correzione degli scatti GPS del replay (8 novembre, JavaScript):** nel video sul PC appaiono circa 60 RAF/s, 50 WASM/s e 60 handler LVGL/s con 4.732 snapshot ma solo 1.178 fix GPS. Il player precedentemente interpolava i soli snapshot immediatamente successivi: molte coordinate erano identiche e la mappa avanzava per salti. Ora, durante Play, sono interpolati i fix GPS *distinti*, con salvaguardie su ricalcoli e buchi GPS; Step/Seek restano fedeli al log. Per provare la nuova correzione, nella shell WSL dentro `~/MotoGPS/moto-gps-waveshare` sul branch `test/road-contrast-cleanup-20261104` eseguire `git pull --ff-only`; lasciare il server HTTP già avviato e premere `Ctrl+F5` in Chrome. **Nessuna ricompilazione WASM è necessaria per questa sola modifica JS**, se il runtime era già aggiornato al refresh LVGL di 15 ms. Verificare lo stesso tratto a 1× e annotare fluidità, WASM/s, render/s e flush/s. Test visivo della correzione ancora da confermare; zoom tornanti non modificato.

**Seconda correzione refresh WebAssembly (8 novembre):** con timer display a 15 ms il test utente ha registrato `60 RAF/s · 62 WASM/s · 0,1 ms medi · 0,9 ms max · 0 ritardi` e `60 handler/s · 45 render/s · 45 flush/s · 0,9 ms medi · 1,4 ms max · 0 ritardi`, con cali render/flush anche a ~25. Quindi il refresh a 15 ms ha migliorato 30→45 render/s ma rimangono occasionali scadenze perse fra il timer LVGL e `requestAnimationFrame` del browser. **Nuovo esperimento, solo simulatore:** la `frame()` in `platforms/web/wasm/main_web.cpp` marca esplicitamente pronto (`lv_timer_ready`) il timer di refresh del display ottenuto da `lv_display_get_refr_timer` prima di `lv_timer_handler`, una volta per RAF. Ciò sincronizza la richiesta di refresh al browser, senza toccare la configurazione condivisa di ESP32 e senza garantire un flush quando la scena non è invalidata. **Ricompilare WASM** dopo `git pull`: `source ~/emsdk/emsdk_env.sh && ./scripts/build_web.sh`; `Ctrl+F5` nel browser, riprovare alle 12:10:50 a 1× e riportare diagnosi e sensazione visiva. Non modificato l'algoritmo dei tornanti. Test visuale ancora da eseguire.

**Eliminazione doppio orologio di interpolazione (8 novembre):** feedback dopo `lv_timer_ready`: scatti ridotti ma persistenti; `60 RAF/s · 33 WASM/s · 0,1 ms medi · 0,4 ms max · 0 ritardi` e `60 handler/s · 31 render/s · 31 flush/s · 0,7 ms medi · 1,7 ms max · 0 ritardi`, con render e WASM che oscillano da ~33 a 60. Il simulatore usava ancora un limitatore separato `now-lastMotionPaint<14` per interpolare il GPS: il secondo orologio poteva tagliare interi RAF quando la cadenza del browser non rispettava quella soglia. Rimosso il filtro: durante Play ora si tenta una interpolazione per ogni RAF. Nella diagnostica replay compaiono `interp/s` (interpolazioni valide) e `senza interp` (RAF senza arco GPS interpolabile); se il secondo resta alto significa che il registrato non consente una posizione nuova senza inventarla, non un problema di flush. Nessuna modifica a C++/WASM, zoom o firmware in questa iterazione: **basta `git pull --ff-only origin test/road-contrast-cleanup-20261104` e `Ctrl+F5`**, mantenendo aperto il server locale. Ripetere a 1× il tratto 12:10:50–12:12:30. Non dichiarare risolti gli scatti prima del test visivo.

**Prova fluidità movimento mappa (8 novembre, sperimentale):** l'ultimo replay è risultato `60 RAF/s · 65 WASM/s · 0,1 ms medi · 0,2 ms max · 0 ritardi · 61 interp/s · 0 senza interp`, ma il ciclo grafico ha mostrato `60 handler/s · 45 render/s · 45 flush/s · 1,0 ms medi · 1,6 ms max · 0 ritardi`. Una registrazione video di 39 secondi mostra che i cambi di posizione della mappa non sono sempre regolari. Nel codice della UI condivisa è presente un **secondo timer di movimento** `route_motion_tick` a 25 ms, distinto dal refresh del display già allineato a RAF. Sulla compilazione WebAssembly del solo branch test quel timer è ora impostato a 1 ms, perché viene esaminato una volta per callback LVGL (~60 Hz, non genera 1000 esecuzioni/s), e il fattore di interpolazione passa da `0,39` a `0,28` per conservare circa la stessa costante di smorzamento nel tempo. Sull'ESP32 restano **25 ms e 0,39**, senza cambiare alcuna firma API, BLE, routing o zoom. I vertici grafici sono tuttora quantizzati a pixel interi, quindi `render/s` può rimanere inferiore a 60 anche con un movimento regolare; non usare i soli FPS come prova di fluidità. **Ricompilare WebAssembly** dopo l'aggiornamento del branch e ricaricare Chrome (`Ctrl+F5`):
```bash
cd ~/MotoGPS/moto-gps-waveshare
git pull --ff-only origin test/road-contrast-cleanup-20261104
source ~/emsdk/emsdk_env.sh
./scripts/build_web.sh
```
Verificare alle 12:10:50–12:12:30 a 1×, osservando soprattutto i salti delle linee verdi e grigie, oltre a render/s, flush/s e interpolazioni valide. Compilazione e test visivo successivi ancora da confermare.

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

# Moto Chat GPS — Manuale operativo

**Aggiornato:** 15 novembre 2026. Repository `thriller47/moto-gps-waveshare`, branch stabile **`valhalla`**. Scheda Waveshare ESP32-S3-Touch-AMOLED-1.75C (466×466, 8 MB PSRAM, 32 MB flash). Nei comandi Windows la porta **COM3 è un esempio**, sostituirla con quella effettiva. Usare **PowerShell**, **CMD** o **WSL Ubuntu** come indicato.

**Questa è la procedura corrente.** Il manuale precedente, con vecchi branch, Photon v1, MotoMap v2/v3, proposte non ancora implementate e note di debug ripetute, è conservato in [archive/MANUALE_OPERATIVO_PRE_CLEANUP_2026-11-15.md](archive/MANUALE_OPERATIVO_PRE_CLEANUP_2026-11-15.md) **solo per riferimento storico**. Lo stato del progetto e le prove ancora aperte sono in [../PROJECT_STATE.md](../PROJECT_STATE.md). Non usare i comandi di checkout dei rami di test dell'archivio.

**Stato delle prove:** Photon SQLite v2 e grafo Valhalla Italia V5 sono stati **collaudati dall'utente sul telefono**. Il **clipping LVGL per singoli segmenti grigi** è già in `valhalla`, ma la prova stradale prevista il 14 novembre è stata rinviata per maltempo; non dichiararlo ancora validato.

## 1. Aggiornare il repository (Windows PowerShell)

```powershell
Set-Location "C:\MotoGPS\moto-gps-waveshare"
git status --short
git fetch origin --prune
git switch valhalla
git pull --ff-only origin valhalla
git submodule update --init --recursive
git status --short
git log -1 --oneline
```

Se il primo `git status --short` mostra modifiche locali, **fermarsi e valutarle** prima di `switch/pull`: non eseguire `reset --hard` o `clean` senza sapere cosa si perderebbe. Se non esiste ancora il repository: `git clone --branch valhalla --recurse-submodules https://github.com/thriller47/moto-gps-waveshare.git` da `C:\MotoGPS`. Il comando `git pull` aggiorna i sorgenti, **non** reinstalla app, firmware o mappe già presenti sul telefono.

**Regola del progetto:** lavorare su `valhalla` per le versioni integrate; `main` è stato eliminato. Il ramo `test/lvgl-single-road-renderer-20261113` è conservato temporaneamente per la verifica del clipping, **non** come branch principale.

## 2. CI, artifact e APK Android

Pagina [GitHub Actions — Project Build](https://github.com/thriller47/moto-gps-waveshare/actions/workflows/project-build.yml). Le job `native`, `android` ed `esp32` sono **selettive in base ai file modificati**: `skipped` non significa test verde. Non riavviare build per sole modifiche al manuale. Rispettare una **CI consolidata** alla fine di un lavoro sul codice.

- Artifact **ESP32-Firmware** (se job ESP32 eseguita, conservazione 3 giorni): applicazione `moto-gps-waveshare.bin`, `bootloader/bootloader.bin`, `partition_table/partition-table.bin`.
- Artifact **Glimpse-MOTO-GPS-Android-Final.apk** (se job Android eseguita, conservazione 7 giorni): contiene `app-debug.apk`. L'artifact può essere un file ZIP da estrarre; controllare contenuti e commit della run.
- La firma dell'APK è importante. **Non disinstallare l'app per tentativi**: potrebbero andare perse le mappe, il grafo Valhalla, Photon e altri dati privati. Vedi `docs/android-apk-signing.md`.

Da PowerShell, con `gh` già configurato:

```powershell
gh run list -R thriller47/moto-gps-waveshare -b valhalla -w project-build.yml -L 10
gh run download ID_RUN -R thriller47/moto-gps-waveshare -n ESP32-Firmware -D C:\MotoGPS\ESP32-Firmware
```

Sostituire `ID_RUN`; scegliere **la run giusta del branch valhalla** e verificare che contenga il relativo artifact.

### Compilare Android in locale (Windows PowerShell)

Aprire **Android Studio → Open** su `C:\MotoGPS\moto-gps-waveshare\platforms\android`, non sulla root del repository. Usare il Gradle Wrapper del progetto, JDK 21/JBR 21, SDK Platform 36, NDK 27 e CMake 3.22.1 se richiesti dalla sincronizzazione. Per verificare versioni effettive consultare i Gradle del checkout.

```powershell
Set-Location "C:\MotoGPS\moto-gps-waveshare\platforms\android"
.\gradlew.bat testDebugUnitTest assembleDebug
```

APK locale: `platforms\android\app\build\outputs\apk\debug\app-debug.apk`. Per installare con ADB, dopo `adb devices`:

```powershell
adb install -r "C:\MotoGPS\moto-gps-waveshare\platforms\android\app\build\outputs\apk\debug\app-debug.apk"
```

`-r` funziona solo se **firma e versionCode** sono compatibili; non forzare disinstallazioni. Per un APK GitHub preferire l'artifact con la firma già utilizzata dall'app installata. Successivamente controllare in Gestione mappe MotoMap v4, Photon v2 e Valhalla V5: aggiornare il codice non rigenera quei file.



## 3. Ambiente ESP-IDF Windows, comando `moto`, compilazione locale

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

**Su valhalla sono abilitate di default le opzioni ESP32 CURVE_ZOOM, SUBPIXEL, MAP_DIRTY_TILES e PERF_TELEMETRY.** Anche le build CI di valhalla usano questi quattro flag e `sdkconfig.perf.defaults`. Per una build locale precedentemente configurata senza statistiche FreeRTOS, verificare le righe `CONFIG_FREERTOS_GENERATE_RUN_TIME_STATS` in `platforms\\esp32\\sdkconfig`: i nuovi defaults non sovrascrivono un sdkconfig già esistente. Se la configurazione rimane precedente, rigenerarla con cura (salvando eventuali impostazioni personalizzate), senza cancellare partizioni flash, mappe o NVS. La telemetria periodica aiuta la diagnosi LVGL ma non è di per sé una garanzia di fluidità.

**Versioni da non confondere:** il PC Windows usa ESP-IDF **5.5.6**; la job ESP32 GitHub Actions del repository utilizza l'immagine **5.5.5**. Non modificare il progetto per far coincidere automaticamente i due ambienti.

La riuscita del comando `moto` **non dimostra da sola** che compilazione, flash o prova su strada siano passati.

---

## 4. Flash ESP32 — COM3 d'esempio

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

## 5. Generare MotoMap **v4** (strade grigie ESP32)

MotoMap e Valhalla **non sono intercambiabili**. MotoMap v4 include ID dei nodi OSM e tag `junction=roundabout/circular`; i vecchi pacchetti v2/v3 non sono supportati dall'app attuale. Sorgenti PBF Geofabrik in `C:\MotoGPS\PBF`. Non caricare il PBF o lo ZIP generato nel repository.

Da **PowerShell Windows** con `py` funzionante e dipendenze Python `osmium` (e `shapely` per `--coverage-poly`):

```powershell
Set-Location "C:\MotoGPS\moto-gps-waveshare"
py -m pip install osmium shapely
py .\scripts\offline_map\pbf_to_motomap.py "C:\MotoGPS\PBF\italy-261101.osm.pbf" -o "C:\MotoGPS\PBF\italy-v4.motomap.zip" --coverage-poly "C:\MotoGPS\PBF\italy.poly"
```

**Adattare il nome del PBF al file effettivo**. Il `.poly` deve riferirsi allo **stesso estratto geografico**; se non disponibile, omettere `--coverage-poly`, ma la copertura certificata potrebbe mancare. Importare lo ZIP in **Moto Chat GPS → Gestione mappe → Importa mappa ZIP**. Non decomprimere: viene letto direttamente come archivio. La generazione Italia richiede diversi GB di RAM. Non confondere con i file `.tar.zst` di Valhalla.

Confermare il formato v4 (Windows PowerShell):

```powershell
py -c "import zipfile,json; z=zipfile.ZipFile(r'C:\MotoGPS\PBF\italy-v4.motomap.zip'); print(json.loads(z.read('motomap_v4.json')))"
```

Fuori dalla copertura, la funzione **Scarica strade del percorso** può usare OpenFreeMap su richiesta esplicita. I vecchi comandi di download per regione/8 km e le istruzioni Overpass non sono procedure attive.

## 6. Valhalla Italia **V5** — grafo offline moto in WSL Ubuntu

**Procedura V5 già eseguita dall'utente su Ryzen/WSL, grafo testato positivamente su Android.** Compilatore Valhalla via `pyvalhalla==3.9.1`, script `scripts/offline_map/build_valhalla_moto_v5.sh`, impostazioni Mjolnir:
`include_driving=true`, `include_bicycle=false`, `include_pedestrian=false`. Filtra i percorsi esclusivamente pedonali/ciclabili, **non elimina a priori ogni `highway=track` o `highway=path`**. Conservare V4 come backup.

### Ambiente WSL (Ubuntu 24.04)

Da **WSL Ubuntu** (non PowerShell), se l'ambiente virtuale esiste già, **non reinstallarlo**:

```bash
source ~/valhalla-env/bin/activate
command -v valhalla_build_config
command -v valhalla_build_tiles
command -v valhalla_build_extract
zstd --version
```

Se l'ambiente ancora non esiste, configurarlo **una sola volta** in WSL Ubuntu (non PowerShell):

```bash
sudo apt update
sudo apt install -y python3 python3-venv python3-pip zstd git build-essential
python3 -m venv ~/valhalla-env
source ~/valhalla-env/bin/activate
python -m pip install --upgrade pip
python -m pip install pyvalhalla==3.9.1
command -v valhalla_build_tiles
```

Per Italia, usare **due thread** ed eseguire i lavori nel filesystem Linux, non direttamente sotto `/mnt/c`. Il PBF di riferimento V4/V5 è `~/motogps-build/italy-261101.osm.pbf` (sostituire se diverso). Se manca in Linux ma è già su Windows, da WSL:

```bash
mkdir -p ~/motogps-build
cp /mnt/c/MotoGPS/PBF/italy-261101.osm.pbf ~/motogps-build/
sha256sum /mnt/c/MotoGPS/PBF/italy-261101.osm.pbf ~/motogps-build/italy-261101.osm.pbf
df -h ~
free -h
```

I due hash SHA-256 devono essere uguali. Per una nuova build Italia prevedere **diverse decine di GB** temporanei. Sul PC Ryzen la configurazione WSL usata nei collaudi è 12 GB RAM massimi, 4 processori, 8 GB swap in `%USERPROFILE%\.wslconfig` (Windows PowerShell) e `wsl --shutdown` dopo eventuali modifiche. Non cambiare questi limiti se la build locale è già funzionante.

### Build V5 senza sovrascrivere V4

Da **WSL Ubuntu**, il checkout Windows aggiornato può essere aperto in `/mnt/c/MotoGPS/moto-gps-waveshare`:

```bash
source ~/valhalla-env/bin/activate
cd /mnt/c/MotoGPS/moto-gps-waveshare
git switch valhalla
git pull --ff-only origin valhalla

# Opzionale: preview config in cartella DISTINTA, senza build.
bash scripts/offline_map/build_valhalla_moto_v5.sh \
  ~/motogps-build/italy-261101.osm.pbf \
  ~/motogps-build/ValhallaItaliaV5-preview --config-only --threads 2

# Build vera: crea una NUOVA cartella di output.
bash scripts/offline_map/build_valhalla_moto_v5.sh \
  ~/motogps-build/italy-261101.osm.pbf \
  ~/motogps-build/ValhallaItaliaV5 --zstd --threads 2
```

**Attenzione:** lo script si ferma se la directory output **esiste già**, anche se generata solo per la preview. La cartella V5 attuale è già stata usata con successo: **non rilanciare su di essa**. Per una ricostruzione scegliere per esempio `ValhallaItaliaV5b` e relativo nome di preview. Non cancellare V4 per liberare spazio senza backup.

**File V5 risultato:**

```text
/home/mod/motogps-build/ValhallaItaliaV5/valhalla_tiles.tar.zst
```

Per portarlo su Windows, da **WSL Ubuntu**:

```bash
cp ~/motogps-build/ValhallaItaliaV5/valhalla_tiles.tar.zst \
   /mnt/c/MotoGPS/valhalla_italia_v5.tar.zst
```

Su Windows sarà `C:\MotoGPS\valhalla_italia_v5.tar.zst`. Copiare il file in **Download** sul telefono e importare da **Moto Chat GPS → Gestione mappe → Importa grafo Valhalla TAR / ZST**. L'app decomprime il file; non farlo manualmente. La sostituzione conserva un solo grafo attivo sul telefono, quindi conservare V4 sul PC.

**Confronto V4/V5** (WSL Ubuntu, dallo stesso checkout):

```bash
python3 scripts/offline_map/analyze_valhalla_graph.py \
  ~/motogps-build/ValhallaItaliaV4/valhalla_tiles.tar.zst \
  ~/motogps-build/ValhallaItaliaV5/valhalla_tiles.tar.zst
ls -lh ~/motogps-build/ValhallaItaliaV4/valhalla_tiles.tar* \
       ~/motogps-build/ValhallaItaliaV5/valhalla_tiles.tar*
```

**Valori effettivamente riferiti dall'utente il 14/11/2026:** V4 `886M` ZST e `2.3G` TAR, V5 `661M` ZST e `1.7G` TAR; nodi 11.457.028 → 8.757.819 e archi orientati 26.093.538 → 18.799.618. Versione tile 3.9.1 per entrambi; nessun header non verificato. I valori di `ls -lh` sono arrotondati. La prova Android di V5 è stata riportata come positiva; non dimostra però ogni possibile strada secondaria.



### Produzione completa in WSL da PBF + POLY + Photon (branch di test)

**Procedura sperimentale, non ancora eseguita sull'Italia completa.** Il generatore `scripts/offline_map/build_motopack_wsl.sh` svolge tutte le fasi in WSL Ubuntu: PBF+POLY → MotoMap v4; dump Photon JSONL (anche Zstandard) → Photon SQLite v2; PBF → grafo Valhalla moto V5; quindi assemblaggio e verifica SHA-256 del `.motopack`. Non è richiesto PowerShell per nessuna conversione.

**Prerequisiti WSL**: ambiente Valhalla 3.9.1 attivato con `valhalla_build_config`, `valhalla_build_tiles` e `valhalla_build_extract` nel PATH; `zstd` installato; Python 3 con `osmium` e `shapely` (`python3 -m pip install osmium shapely` nell'ambiente virtuale appropriato). Il dump Photon deve essere **JSONL/Nominatim 0.1.x** (`.jsonl` o `.jsonl.zst`), non un ZIP indice Photon/Lucene o il vecchio SQLite. Il file `.poly` deve corrispondere allo stesso estratto geografico del PBF. Usare preferibilmente una cartella di output nel filesystem Linux WSL, non in `/mnt/c`, per le conversioni pesanti.

**Da terminale WSL Ubuntu** (sostituire i nomi sorgente con quelli effettivamente presenti in `C:\MotoGPS\PBF`):

```bash
cd ~/MotoGPS/moto-gps-waveshare
source ~/valhalla-env/bin/activate
bash scripts/offline_map/build_motopack_wsl.sh \
  --pbf /mnt/c/MotoGPS/PBF/italy-latest.osm.pbf \
  --poly /mnt/c/MotoGPS/PBF/italy.poly \
  --photon /mnt/c/MotoGPS/PBF/photon-italy.jsonl.zst \
  --out ~/motogps-build/ItaliaUnificata \
  --threads 2
```

Il risultato atteso è `~/motogps-build/ItaliaUnificata/MotoChatGPS-Italia.motopack`. Gli intermedi restano nella stessa directory. I comandi rifiutano di sovrascrivere una directory o un pacchetto già esistente. In caso di conversione interrotta, verificare i file presenti; **`--resume`** riutilizza i componenti già completi, ma non sovrascrive un grafo incompleto. Non eliminare cartelle o vecchi grafi automaticamente.

Per trasferire **solo il pacchetto verificato** in Windows, da WSL:

```bash
cp ~/motogps-build/ItaliaUnificata/MotoChatGPS-Italia.motopack /mnt/c/MotoGPS/PBF/
```

**Attenzione:** l'Italia completa richiede molto spazio libero **su disco WSL**, oltre a quello del telefono. Anche i file ZIP intermedi e il TAR Valhalla non compresso occupano spazio; la stima del requisito non è ancora misurata in questa pipeline. Prima di avviare, controllare `df -h ~`. Evitare di ricostruire i dataset finché il pacchetto attuale funziona, salvo voler riprodurre tutta la filiera da nuove sorgenti OSM.

### Pacchetto offline unificato sperimentale (.motopack) — branch test

**Non ancora collaudato sul telefono.** Nel branch `test/unified-offline-map-20261115` l'app aggiunge **Gestione mappe → Importa pacchetto unico Moto Chat GPS**. Questa procedura non è disponibile nell'APK stabile `valhalla` finché non viene autorizzato il merge. Non disinstallare l'app attuale solo per testarlo.

Il contenitore ZIP64 `.motopack` comprende tre componenti e un `manifest.json` con le versioni, le dimensioni e gli SHA-256 dei file. Contiene **MotoMap v4 ZIP**, **Valhalla Italia V5 TAR.ZST** e **Photon SQLite v2** senza convertirne ulteriormente i dati. È possibile usare gli artefatti già generati e collaudati, senza rifare l'importazione PBF.

**Da Windows PowerShell**, adattare i tre percorsi ai file effettivamente presenti:

```powershell
Set-Location "C:\MotoGPS\moto-gps-waveshare"
py scripts/offline_map/motopack.py build `
  --motomap "C:\MotoGPS\italy-v4.motomap.zip" `
  --valhalla "C:\MotoGPS\valhalla_italia_v5.tar.zst" `
  --photon "C:\MotoGPS\photon-italy-v2.sqlite" `
  -o "C:\MotoGPS\MotoChatGPS-Italia.motopack"
py scripts/offline_map/motopack.py verify "C:\MotoGPS\MotoChatGPS-Italia.motopack"
```

**Nota:** i nomi di input sono esempi. Il generatore non sovrascrive un pacchetto esistente. Copiare il file `.motopack` in Download sul telefono, aprire l'APK sperimentale del branch di test, interrompere la navigazione e scegliere **Gestione mappe → Importa pacchetto unico Moto Chat GPS**. Lasciare il telefono alimentato e con molto spazio libero: durante l'importazione coesistono copie temporanee dei tre componenti e il TAR Valhalla decompresso. Il formato non migliora direttamente lo spazio occupato dal grafo installato.

**Garanzie e limiti:** Android verifica il manifest e SHA-256 di tutti e tre i componenti **prima di modificare** le mappe installate. Dopodiché riusa gli importatori attuali, ciascuno con il proprio meccanismo di sostituzione e backup. L'aggiornamento dei tre componenti **non è ancora una transazione atomica globale**: se una delle tre installazioni fallisce, alcuni componenti potrebbero risultare aggiornati e altri no. Verificare manualmente lo stato di MotoMap, Photon e Valhalla dopo il test; mantenere una copia degli originali sul PC. Non interrompere il processo deliberatamente durante il collaudo iniziale.

**Test automatici del contenitore:** `python3 -m unittest discover -s scripts/offline_map -p 'test_motopack.py'`. Il test con un archivio fittizio non sostituisce la prova sul dispositivo con gli archivi reali.

## 7. Diagnostica Android, seriale ESP32 e prossimo test clipping

**Android:** collegare ESP32 via BLE e aprire **LOG**; scegliere `BASE` per eventi essenziali o `DEBUG` per dettagli (default `OFF`). Sessione DEBUG fino a circa 60 minuti, interrotta da disconnessione/riavvio o da `LOG → OFF`. **Prima di staccare il BLE**, usare **Condividi / Esporta diagnostica**: si genera `motogps-android-diagnostics.zip` con log Android e, quando presenti, eventi ESP32 ricevuti via BLE. La diagnostica Android usa file a rotazione; il pacchetto ZIP **non** equivale al replay GPS separato.

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

**Test clipping ancora aperto (15/11):** il renderer precedente è stato giudicato positivo, ma il culling per singolo segmento inserito il 14/11 non è stato provato per maltempo. Durante il giro con rotatorie/tornanti e strade grigie dense, annotare FPS, eventuali salti, linee tronche e comportamento sui ricalcoli. Nei log `LVGL_ROADS` confrontare `seg`, `seg_skip`, `dirty`, `tiles`; `seg_skip` conta i segmenti scartati anticipatamente, non dimostra da solo un guadagno di FPS. Distinguere sosta e movimento. **Non modificare ora il clipping o il protocollo BLE.**



## 8. Radio Wi-Fi sperimentale (modalità isolata)

**Sezione ripristinata dalle istruzioni del 30 ottobre 2026 e verificata contro il codice presente il 5 novembre.** I comandi sono realmente previsti da `platforms/esp32/main/signal_wifi_console.cpp` e `signal_radio_stream.cpp`, ma la radio resta **sperimentale**: la presenza del parser **non garantisce** che audio MP3, connettività Wi-Fi, touch e stabilità della riproduzione siano stati collaudati sul firmware corrente.

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

## 9. Simulatore browser e replay GPS

### Avvio del simulatore LVGL in WSL Ubuntu


Con Emscripten/emcmake installati in `~/emsdk` e il repository già presente in WSL, eseguire da **WSL Ubuntu** (non da PowerShell):

```bash
cd /mnt/c/MotoGPS/moto-gps-waveshare
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
cd /mnt/c/MotoGPS/moto-gps-waveshare
python3 -m http.server 4173 --directory .
```

Aprire in Chrome/Edge su Windows: **http://127.0.0.1:4173/platforms/web/simulator/index.html**. Dopo un aggiornamento o una build, premere **Ctrl+F5** per evitare file JavaScript/WASM in cache. Il server si arresta con `Ctrl+C` nel terminale in cui gira. Se la porta 4173 risulta occupata, verificare prima che il server precedente sia ancora attivo, invece di avviarne un secondo.

**Quando ricompilare:** se sono cambiati file C++/LVGL/NavPresenter oppure manca il runtime, eseguire `source ~/emsdk/emsdk_env.sh` e `./scripts/build_web.sh` dopo il pull. Per modifiche **solo JavaScript/HTML** del simulatore basta `git pull --ff-only origin valhalla` e `Ctrl+F5` (non è necessario ricompilare WASM).

**Anteprima senza compilatore:** su Windows si può aprire direttamente `platforms\web\simulator\index.html`. Senza runtime comparirà `ANTEPRIMA 2D · NON LVGL`: mostra percorsi, scene e zoom, ma non riproduce esattamente il rendering dell'ESP32. Per utilizzare **LVGL reale** aprire l'URL HTTP sopra; `file://` non è sufficiente per caricare correttamente il runtime WASM.

**Alternativa PowerShell nativa (non verificata sul PC):** con Emscripten Windows attivo è possibile tentare `emcmake cmake -S . -B build/web -DMOTO_BUILD_WEB=ON -DMOTO_BUILD_TESTS=OFF`, `cmake --build build/web --parallel` e `py -m http.server 4173`. La procedura verificata dall'utente resta **WSL Ubuntu**.

**Nota PC Windows/WSL:** se il checkout per il simulatore si trova invece in `~/MotoGPS/moto-gps-waveshare`, usare **quel** percorso coerentemente sia per build sia per server. Su un PC con poca RAM limitare il parallelismo della compilazione dopo una diagnosi, non interpretare un processo `Killed` come bug di LVGL.

### Registrare un replay GPS dall'app Android

La registrazione del trace GPS rimane **OFF per impostazione predefinita** e si attiva manualmente dal pannello **LOG → Avvia registrazione GPS**. Non si interrompe automaticamente dopo 60 minuti; salva file consecutivi `replay-current-part-00001.jsonl`, `00002.jsonl`, ecc. La rotazione avviene ogni **30 minuti o circa 12 MiB per singolo file**, senza un limite fissato al numero di file o alla durata totale della sessione (resta il limite fisico dello spazio disponibile sul telefono). La scrittura resta su thread privato, senza carico di I/O nel callback GPS/BLE.

Al termine premere **Ferma registrazione GPS → Esporta tracciato simulatore**: con una sola parte si condivide `motogps-replay.jsonl`; con più parti, `motogps-replay-parts.zip` che contiene **tutti i .jsonl numerati** e un README. Su Windows, estrarre lo ZIP e caricare i file `.jsonl` nel simulatore **uno alla volta, in ordine numerico**: ogni parte è autonoma e ricomincia da `t_ms=0`, ma contiene `part_index`, `session_elapsed_ms` e data iniziale per riallineare le prove. Non passare direttamente lo ZIP al selettore `.jsonl` del simulatore.

**Attenzione a sicurezza e dati:** il file GPS contiene coordinate, posizioni previste, velocità, geometrie della rotta verde e delle strade MotoMap e manovre. Rimane privato finché non viene esportato volontariamente. Lo ZIP ordinario di **diagnostica Android non include i replay né coordinate**. L'avvio di una **nuova** registrazione sostituisce le parti locali della sessione precedente: esportare la sessione precedente prima di riavviare la registrazione. Se la memoria del telefono si esaurisce, la scrittura fallirà e la registrazione si interromperà; non esiste spazio di archiviazione infinito. Non maneggiare il telefono mentre si guida, né usare DEBUG ESP32 approfondito solo per registrare il GPS.

La telemetria browser (RAF, WASM, render/flush) misura il **simulatore**, non il display ESP32. Le vecchie prove/patch iterative del novembre 2026 sono nell'archivio storico; non usare i vecchi comandi su branch `test/*`.


## 10. Photon offline **v2** (ricerca indirizzi e civici)

**Valhalla e MotoMap non dipendono da Photon.** Il convertitore in `valhalla` produce **solo lo schema SQLite v2**: FTS4 `contentless` e dizionari per comuni/regioni. La precedente v1 da circa 2 GiB è **incompatibile con il lettore Android v2**; la vecchia istruzione di generare `photon-italy.sqlite` con schema v1 è obsoleta.

**Origine dump:** `photon-dump-italy-1.0-latest.jsonl.zst` (dump Photon/Nominatim italiano, non gli archivi server `photon-db*.tar.bz2`), disponibile dalla distribuzione pubblica GraphHopper:
https://download1.graphhopper.com/public/europe/italy/photon-dump-italy-1.0-latest.jsonl.zst

Conservare il dump in `C:\MotoGPS\PBF`, **fuori dal repository**. Se il comando `zstd` non è installato su Windows, da PowerShell è possibile usare `winget install --id Meta.Zstandard -e` e riaprire la finestra, verificando `zstd --version`. L'ultima conversione dell'utente ha già prodotto un database funzionante: **8.362.869 luoghi, 15.909 saltati, 1.074,0 MiB**; ricerca offline Android confermata per Courmayeur, Via Roma Torino e indirizzo con civico e accento. Non occorre rigenerarlo per aggiornare l'APK.

**Solo se serve rigenerare** (Windows PowerShell, Python Launcher **`py`** perché `python` non era disponibile nel PATH del PC; `zstd` installato e visibile nel PATH):

```powershell
Set-Location "C:\MotoGPS\moto-gps-waveshare"
git switch valhalla
git pull --ff-only origin valhalla
py --version
zstd --version
py scripts/offline_map/photon_to_sqlite.py --input "C:\MotoGPS\PBF\photon-dump-italy-1.0-latest.jsonl.zst" --output "C:\MotoGPS\PBF\photon-italy-v2.sqlite"
```

Il convertitore accetta `.jsonl` o `.jsonl.zst` e non sovrascrive un output esistente. Se `photon-italy-v2.sqlite` esiste già, **non cancellarlo** per prova: usare un nuovo nome output (es. `photon-italy-v2-new.sqlite`). Finalizzazione con FTS optimize e `VACUUM`: serve spazio temporaneo aggiuntivo sul disco. Sul PC Windows `py` è il launcher ufficiale e va bene anche quando il comando `python` non è disponibile.

```powershell
Get-Item "C:\MotoGPS\PBF\photon-italy-v2.sqlite" |
    Select-Object Name, @{Name="MiB";Expression={[math]::Round($_.Length / 1MB, 1)}}
```

Per installarlo: copiare il database `.sqlite` (non lo `.zst` dump) in **Download** sul telefono → **Moto Chat GPS → Gestione mappe → Ricerca indirizzi offline — Photon → Importa indice Photon SQLite**. La copia è atomica con backup temporaneo, richiede spazio libero ulteriore; non reimportare per il semplice aggiornamento del codice.

**Verifica offline:** disattivare rete/dati e cercare Courmayeur, Via Roma Torino, una strada con numero civico e un accento. Se l'indice manca o è incompatibile, l'app tenta Photon online e può mostrare errore di rete; la ricerca con indice valido resta offline, anche con zero risultati. Le coordinate inserite manualmente restano disponibili offline. Mantenere attribuzione OpenStreetMap/ODbL e Photon per qualsiasi distribuzione.

## 11. Riepilogo delle operazioni quotidiane

| Scopo | Azione corretta |
| --- | --- |
| Aggiornare i sorgenti | `git switch valhalla` + `git pull --ff-only origin valhalla` |
| Compilare firmware ESP32 locale | PowerShell `moto` → CMD `idf.py … build` |
| Flash app ESP32 | offset **`0x20000`**, non `0x10000` |
| Installare APK | Artifact Android del branch `valhalla` o `gradlew.bat assembleDebug`; controllare firma |
| Rigenerare strade grigie | `pbf_to_motomap.py` → **MotoMap v4 ZIP** |
| Rigenerare indirizzi offline | Windows **`py`** + `photon_to_sqlite.py` → **Photon v2 SQLite** |
| Rigenerare grafi moto | WSL `build_valhalla_moto_v5.sh --zstd --threads 2` |
| Controllare grafi | WSL `analyze_valhalla_graph.py` V4/V5 |
| Replay | WSL `./scripts/build_web.sh` + `python3 -m http.server 4173 --directory .` |
| Diagnostica | Android LOG/ZIP e, per FPS/segmenti, seriale ESP32 |
| Test rimasto aperto | **Clipping LVGL per segmento**: prova su strada, senza modifiche prima del feedback |

**Materiale storico:** [archive/MANUALE_OPERATIVO_PRE_CLEANUP_2026-11-15.md](archive/MANUALE_OPERATIVO_PRE_CLEANUP_2026-11-15.md); non usarlo per comandi correnti.

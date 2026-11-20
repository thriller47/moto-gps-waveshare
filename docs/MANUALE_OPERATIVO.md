# Moto Chat GPS — Manuale operativo

**Aggiornato:** 20 novembre 2026 (diagnosi corridoio OpenFreeMap online, ricerca Photon, GPS/IMU e MotoPack) · **Repository:** `thriller47/moto-gps-waveshare` · **Branch stabile di riferimento:** `valhalla`.

Queste sono le **procedure operative correnti**, per Waveshare ESP32-S3-Touch-AMOLED-1.75C e app Android. Le istruzioni distinguono **Windows PowerShell**, **CMD ESP-IDF** e **WSL Ubuntu-24.04**: i rispettivi comandi non sono intercambiabili. La porta USB `COM3` è un esempio da verificare sul PC.

**Dove cercare il resto:** [PROJECT_STATE.md](../PROJECT_STATE.md) è lo stato sintetico corrente; [ROADMAP.md](ROADMAP.md) distingue lavoro da testare da funzionalità già disponibili; la [cronologia storica completa](archive/PROJECT_STATE_PRE_CLEANUP_2026-11-19.md) conserva diagnosi e prove precedenti, e l'[archivio del vecchio manuale](archive/MANUALE_OPERATIVO_PRE_CLEANUP_2026-11-15.md) conserva le procedure superate. Il MotoPack v4/V5/v2 installato il **15/11** è stato collaudato; la **nuova rigenerazione completa del 18/11** con lo script di sostituzione è ancora da confermare su dataset reale e su Android.

## 1. Repository e ambienti

**Sorgenti originali delle mappe (Windows):** `C:\MotoGPS\PBF`. **Repository usato in WSL:** `/home/mod/MotoGPS/moto-gps-waveshare`, ossia `~/MotoGPS/moto-gps-waveshare`. **Distribuzione WSL verificata sul PC:** `Ubuntu-24.04` (versione WSL 2). Il checkout Windows `C:\MotoGPS\moto-gps-waveshare`, quando presente, è una **copia distinta**: aggiornare e compilare dalla copia che si sta realmente usando.

Per **lavorare nel branch sperimentale in WSL**:

```bash
cd ~/MotoGPS/moto-gps-waveshare
git status --short
git fetch origin --prune
git switch test/waypoint-markers-20261119
git pull --ff-only origin test/waypoint-markers-20261119
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

## 2. Rigenerazione completa MotoPack — procedura consigliata

**Un unico comando in WSL** ricrea **MotoMap v4** (strade e copertura), **Photon SQLite v2** (ricerca indirizzi), **Valhalla V5** (grafo moto dal PBF aggiornato), quindi assembla e verifica il pacchetto `MotoChatGPS-Italia.motopack`. Usa lo script `scripts/offline_map/rebuild_motopack_wsl.sh`, presente nel branch sperimentale.

**Sorgenti attuali in `C:\MotoGPS\PBF`:**

- `italy-latest.osm.pbf`
- `italy.poly` (deve corrispondere all'estratto PBF)
- `photon-italy.jsonl.zst`

Il file sorgente eventualmente presente `valhalla_italia_v5.tar.zst` **non è usato dalla rigenerazione completa**: il grafo viene ricostruito dal PBF. I file di input non vengono sovrascritti.

**Da WSL Ubuntu-24.04** (ambiente `valhalla-env` già configurato; richiesti `pyvalhalla==3.9.1`, `osmium`, `shapely`, `zstd`):

```bash
cd ~/MotoGPS/moto-gps-waveshare
source ~/valhalla-env/bin/activate
ls scripts/offline_map/rebuild_motopack_wsl.sh
df -h ~
bash scripts/offline_map/rebuild_motopack_wsl.sh --threads 2
```

Se `ls` non trova lo script, aggiornare il branch come nella sezione 1. Per il builder servono `valhalla_build_config`, `valhalla_build_tiles`, `valhalla_build_extract`; verificare con `command -v`. **Non lanciare separatamente i tre convertitori**: lo script li esegue già. Il PC Ryzen ha usato normalmente **due thread**, per evitare di saturare RAM e swap.

**Cartella di output predefinita:**

```text
/home/mod/motogps-build/ItaliaUnificata-20261118/
├── italy-v4.motomap.zip
├── photon-italy-v2.sqlite
├── ValhallaItaliaV5/
│   └── valhalla_tiles.tar.zst
└── MotoChatGPS-Italia.motopack
```

Lo script scrive prima su filesystem **Linux WSL**, in una cartella temporanea; **solo dopo verifica SHA-256** sostituisce la cartella finale esistente. In caso di errore ordinario preserva quella precedente; se trova file estranei fra gli output, si ferma senza cancellarli. Richiede spazio per vecchia e nuova generazione contemporaneamente, oltre ai grafi Valhalla temporanei. Non usare `sudo`, `rm -rf` o `--resume` al posto di questa procedura. Per un'altra destinazione: `--out "$HOME/motogps-build/ItaliaUnificata-Nuova" --threads 2`.

**Verifica al termine** (non affermare che la nuova generazione sia valida solo perché MotoMap ha stampato il numero di tile):

```bash
python3 scripts/offline_map/motopack.py verify \
  "$HOME/motogps-build/ItaliaUnificata-20261118/MotoChatGPS-Italia.motopack"
ls -lh "$HOME/motogps-build/ItaliaUnificata-20261118/"
```

La verifica attesa è `OK: MotoMap v4 + Valhalla V5 + Photon v2`, accompagnata dall'esito finale `PRONTO` dello script.

### Accedere alla cartella WSL da Windows

**Metodo immediato dal terminale WSL:**

```bash
explorer.exe "$(wslpath -w "$HOME/motogps-build/ItaliaUnificata-20261118")"
```

**Metodo diretto da Windows:** premere **Win+R** e incollare il percorso della tua distribuzione (non `Ubuntu` generico):

```text
\\wsl.localhost\Ubuntu-24.04\home\mod\motogps-build\ItaliaUnificata-20261118
```

Anche `\\wsl$\Ubuntu-24.04\home\mod\motogps-build\ItaliaUnificata-20261118` è utilizzabile. Se la distribuzione cambia, verificare con `wsl -l -v` da PowerShell. Non spostare la build su `/mnt/c` durante la compilazione.

**Copiare il pacchetto finale in Windows** (da WSL, una volta verificato):

```bash
cp "$HOME/motogps-build/ItaliaUnificata-20261118/MotoChatGPS-Italia.motopack" \
   /mnt/c/MotoGPS/MotoChatGPS-Italia.motopack
```

In Windows sarà `C:\MotoGPS\MotoChatGPS-Italia.motopack`. Per aggiornare il telefono copiare il file in Download, interrompere la navigazione, quindi aprire **Moto Chat GPS → Gestione mappe → Importa pacchetto unico Moto Chat GPS**. Non disinstallare l'app per importare le mappe; servono spazio e alimentazione sufficienti durante l'operazione.

**Limite di atomicità:** Android verifica i tre hash prima dell'importazione, ma installa i tre componenti in fasi successive. Dopo l'import controllare separatamente MotoMap v4, Photon v2 e Valhalla V5 e tenere il vecchio pacchetto come backup.



### Interfaccia Android riorganizzata (branch di integrazione)

Nella schermata iniziale la ricerca destinazione è l'azione principale, seguita da **Crea tour**, **Tour salvati**, collegamento ESP32 e **Mappe offline**. Il tema giorno/notte resta nella barra superiore; **Altre opzioni** ospita diagnostica e registrazione replay GPS, esportazione log e informazioni/licenze. Non sono cambiati algoritmi GNSS/IMU, routing, BLE né firmware.

**Mappe offline:** il pulsante principale **Importa MotoPack (.motopack)** aggiorna MotoMap v4, Valhalla V5 e Photon v2 insieme. Le importazioni singole MotoMap ZIP, indice Photon SQLite e grafo Valhalla, oltre alle opzioni di eliminazione, restano disponibili tramite **Opzioni avanzate · componenti separati**. Controllare lo stato dei tre componenti prima di navigare; non disinstallare l'app per aggiornare le mappe.

**Salta prossima tappa:** disponibile durante **qualsiasi** navigazione con almeno una tappa intermedia ancora pendente, non soltanto nei tour circolari. Toccare il pulsante e confermare da fermi: la tappa corrente viene esclusa solo dopo che Valhalla ha restituito una nuova rotta valida; tappe successive e **destinazione finale** vengono conservate. Sui tour circolari si aggiorna anche il checkpoint di ripresa. Sui percorsi ordinari non si crea un checkpoint circolare. Se ci si dirige direttamente all'arrivo senza tappe intermedie, il pulsante non compare. Sul branch attuale di test è presente anche il pulsante ESP32 **blu `>>`**, con doppio tocco di conferma, visibile quando ci sono tappe residue; il salto tappa di una revisione precedente è stato provato dall'utente. La validazione completa dopo l'ultimo hardening resta pendente. Non attribuire automaticamente questo comportamento a un firmware `valhalla` più vecchio.

### Ricerca indirizzi e POI (offline prima, online come fallback)

**Nel branch `test/waypoint-markers-20261119`, non ancora nella baseline `valhalla`:** cercare una destinazione o una tappa interroga prima Photon SQLite v2 italiano; **se non restituisce risultati**, usa Photon online quando disponibile, anche per luoghi fuori Italia. I risultati offline sono ordinati con preferenza alle corrispondenze pertinenti vicine al GPS; quelli online richiedono fino a 30 candidati e applicano una preferenza geografica analoga, mostrando al massimo otto risultati. Quando il GPS è disponibile, la distanza mostrata è **in linea d'aria**, non chilometri di guida. Una città espressamente indicata (es. `Via Roma, Torino`) prevale sulla vicinanza.

**Verifica ancora richiesta su APK finale:** `Via Roma` vicino, `Via Roma, Torino`, località estera, GPS assente e rete assente. Il database Photon non è garantito come indice esaustivo di categorie POI: trovare un marchio di distributore non dimostra la ricerca di tutti i distributori entro un raggio. Questa modifica non richiede rigenerare MotoPack.

### Strade di contesto quando si naviga online senza MotoPack — branch test

Valhalla **online** calcola il percorso ma **non fornisce da solo** le strade grigie dell'ESP32. Nella schermata di anteprima, dopo il calcolo, premere **Avvia navigazione**: se mancano tile del corridoio, l'app propone **Prepara strade OpenFreeMap** e permette **Avvia e scarica** oppure **Avvia senza download**. Lo scaricamento è un'azione esplicita, non parte automaticamente dal GPS; i file restano in cache per gli utilizzi successivi. La rotta verde può partire subito, mentre le strade grigie compaiono dopo la disponibilità dei dati. Il download ha limiti documentati di **3.000 tile / 50 MB**.

**Bug riscontrato durante il test del 20/11 (senza MotoPack):** `MainActivity` non collegava i callback di conteggio tile e download, quindi il dialogo non compariva e il contesto restava vuoto. Corretto nel branch `test/waypoint-markers-20261119`, **da compilare e verificare sul dispositivo**. Per una nuova prova controllare se la UI propone il corridoio e se la diagnostica registra `OFM_COVERAGE` e `OFM_CORRIDOR`; `OSM_CONTEXT segments=0` da solo non certifica un errore del routing.

**Larghezza del corridoio (branch test, aggiornamento 20/11):** la fascia OpenFreeMap segue la geometria della rotta ed è stata ampliata a **sette tile z14 in totale**: la tile attraversata e tre tile adiacenti per lato, per mantenere strade grigie disponibili anche in caso di piccole deviazioni. Non è una modifica alla zona coperta dal MotoMap italiano. La modifica non è stata ancora compilata o provata su strada. **Limiti invariati:** massimo 3.000 tile pianificate e 50 MiB scaricabili; una rotta troppo estesa può produrre l'avviso di corridoio troppo grande, senza download automatico in tranche. Il limite di pianificazione si applica prima della sottrazione delle tile già presenti, comportamento da migliorare solo dopo verifica del caso pratico.

**Copertura mista Italia/Francia:** se hai MotoMap Italia installato e calcoli un itinerario oltre confine, la logica del corridoio verifica **tutta la rotta**, esclude dal conteggio le tile già coperte dal MotoMap e propone di scaricare solo quelle ancora mancanti, anche francesi. Un test specifico è stato aggiunto nel branch sperimentale ma non ancora eseguito. **Attenzione:** il download OpenFreeMap aggiunge solo strade di contesto grigie: non estende Valhalla V5 Italia, quindi i percorsi francesi necessitano di routing online salvo disponibilità di un grafo locale adeguato. Se il piano supera 3.000 tile o 50 MB, attualmente non esiste download automatico a tranche; suddividere il viaggio o predisporre dati offline appropriati. Il conteggio viene effettuato in anteprima e va verificata la fluidità su MotoMap grandi.

### POI offline: carburante, ristoranti, bar e alloggi — nuova rigenerazione

Nel branch di test la procedura **unica** di rigenerazione crea ora un indice di punti di interesse direttamente dentro `photon-italy-v2.sqlite`, usando **`italy-latest.osm.pbf`**: nodi e perimetri OSM con tag `amenity=fuel/restaurant/fast_food/cafe/bar/pub` e `tourism=hotel/motel/guest_house/hostel/apartment/chalet`. L'indice contiene categoria, sottotipo, nome (o un'etichetta generica) e coordinate; **non richiede un quarto file nel MotoPack né cambia lo schema principale Photon v2**. Il generatore usa `pyosmium` già previsto negli strumenti WSL e un indice su disco per i nodi delle geometrie.

**Comando WSL (ambiente e PBF già configurati):**

```bash
cd ~/MotoGPS/moto-gps-waveshare
git switch test/waypoint-markers-20261119
git pull --ff-only origin test/waypoint-markers-20261119
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

[GitHub Actions — Project Build](https://github.com/thriller47/moto-gps-waveshare/actions/workflows/project-build.yml). Le job `native`, `android` ed `esp32` sono selettive; `skipped` non è equivalente a un test superato. Per il codice avviare **una sola CI consolidata alla fine**; una modifica al solo manuale non richiede build.

Gli artifact della run corretta possono includere il firmware `ESP32-Firmware` (`moto-gps-waveshare.bin`, bootloader e partizioni) e l'APK `Glimpse-MOTO-GPS-Android-Final.apk` (in alcuni casi dentro un archivio da estrarre). Controllare sempre **branch, commit e firma**: non installare a caso un APK di un branch diverso.

**Da PowerShell Windows, con GitHub CLI configurata** (esempio per il branch test, cambiare `-b` quando si usa `valhalla`):

```powershell
gh run list -R thriller47/moto-gps-waveshare -b test/waypoint-markers-20261119 -w project-build.yml -L 10
gh run download ID_RUN -R thriller47/moto-gps-waveshare -n ESP32-Firmware -D C:\MotoGPS\ESP32-Firmware
```

**Build Android locale (PowerShell):** in Android Studio aprire `C:\MotoGPS\moto-gps-waveshare\platforms\android` (non la root). Versioni di riferimento: JDK/JBR 21, Android SDK Platform 36, NDK 27 e CMake 3.22.1 quando richiesti dal Gradle presente nel checkout.

```powershell
Set-Location "C:\MotoGPS\moto-gps-waveshare\platforms\android"
.\gradlew.bat testDebugUnitTest assembleDebug
adb devices
adb install -r "C:\MotoGPS\moto-gps-waveshare\platforms\android\app\build\outputs\apk\debug\app-debug.apk"
```

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

**Nel firmware di riferimento sono abilitate le opzioni ESP32 CURVE_ZOOM, SUBPIXEL, MAP_DIRTY_TILES e PERF_TELEMETRY.** Anche le build CI di valhalla usano questi quattro flag e `sdkconfig.perf.defaults`. Per una build locale precedentemente configurata senza statistiche FreeRTOS, verificare le righe `CONFIG_FREERTOS_GENERATE_RUN_TIME_STATS` in `platforms\\esp32\\sdkconfig`: i nuovi defaults non sovrascrivono un sdkconfig già esistente. Se la configurazione rimane precedente, rigenerarla con cura (salvando eventuali impostazioni personalizzate), senza cancellare partizioni flash, mappe o NVS. La telemetria periodica aiuta la diagnosi LVGL ma non è di per sé una garanzia di fluidità.

**Versioni da non confondere:** il PC Windows usa ESP-IDF **5.5.6**; la job ESP32 GitHub Actions del repository utilizza l'immagine **5.5.5**. Non modificare il progetto per far coincidere automaticamente i due ambienti.

La riuscita del comando `moto` **non dimostra da sola** che compilazione, flash o prova su strada siano passati.

---

**Test sperimentale predizione curva ESP32 (19/11/2026, branch di test):** l'opzione CMake `MOTO_ESP_TURN_ASSIST_EXPERIMENT` è **ON per default** dopo il collaudo e l'integrazione in `valhalla`. La versione precedente A resta ottenibile esplicitamente con `OFF`. Per provare l'assistenza hardware curva mantenendo inalterato Android e il filtro IMU, da **PowerShell con ESP-IDF già attivo** e cartella `platforms\\esp32` lanciare `idf.py -DMOTO_ESP_TURN_ASSIST_EXPERIMENT=ON -DSDKCONFIG_DEFAULTS="sdkconfig.defaults;sdkconfig.perf.defaults" build`, verificando che la configurazione CMake utilizzi realmente `ON`. Per tornare al firmware A, eseguire `idf.py -DMOTO_ESP_TURN_ASSIST_EXPERIMENT=OFF -DSDKCONFIG_DEFAULTS="sdkconfig.defaults;sdkconfig.perf.defaults" build` e flashare la build precedente o la nuova build OFF. Nella CI standard di `valhalla` il flag è ON; per il rollback usare `-DMOTO_ESP_TURN_ASSIST_EXPERIMENT=OFF`. Non fare flash prima che il build ON sia verde. La modifica non replica C2 identico: non ricevendo nuovi fix GPS ad ogni render, corregge solo con nuovi ancoraggi BLE reali e mantiene uno scostamento eventualmente residuo durante la sosta fino al successivo anchor. Raccogliere il log diagnostico completo nel giro di prova, e confrontare il comportamento rispetto al firmware A precedente.

**Firmware sperimentale Turn Assist da GitHub (19/11/2026, branch `test/waypoint-markers-20261119`):** la run `Project Build` di questo branch produce il firmware ESP32 con `MOTO_ESP_TURN_ASSIST_EXPERIMENT=ON`, dopo controllo esplicito del flag. Aprire la run, confermare `esp32 = success`, scaricare l'artifact `ESP32-Firmware-TURN-ASSIST-ON` e controllare il file `TURN-ASSIST-ON.txt` (flag e commit); nello ZIP sono inclusi binario applicazione, bootloader e partizioni. Eseguire il flash seguendo la sezione 5. Conservare il firmware precedente OFF per ripristino. Dopo il merge la funzione è presente anche negli artifact standard di `valhalla`; l'APK non richiede ulteriori aggiornamenti rispetto alla versione già collaudata. Dopo prova rotonde/rettilinei/ricalcoli condividere la diagnostica completa.

**Salto tappa nei percorsi non circolari e marcatori ESP32 (branch di prova `test/waypoint-markers-20261119`):** nel percorso normale con almeno una tappa intermedia, una volta avviata la navigazione, Android mostra **Salta prossima tappa**. Confermare per chiedere a Valhalla una rotta verso le tappe restanti o direttamente la destinazione finale. La destinazione non si può saltare e, se il calcolo fallisce, il percorso rimane invariato. L'ESP32 mostra **cerchi gialli numerati 1–8** sulla linea verde, solo per le tappe intermedie ancora da visitare nella finestra visibile; un marker fuori mappa non viene visualizzato. Il marker della tappa raggiunta/saltata scompare dopo la prossima finestra BLE, e la bandiera di arrivo resta separata. **Per testare** installare APK e firmware entrambi del branch di prova, perché il suffisso BLE opzionale con coordinate delle tappe non è gestito dalla vecchia versione ESP32. Verificare con diagnostica completa: salto prima tappa, salto ultima tappa intermedia, ricalcolo, BLE e arrivo finale; test su strada da eseguire prima del merge.

**Nota storica (precedente versione di test):** il doppio long-press sul segnalino 1 è stato sostituito dal pulsante fisso `>>` speculare al badge limite di velocità. I segnalini sulla mappa non sono più interattivi.

**Pulsante fisso Salta tappa >> su ESP32 (19/11/2026; branch sperimentale `test/waypoint-markers-20261119`):** in navigazione con almeno una tappa intermedia ancora da visitare appare un badge rotondo `>>` sul lato sinistro, **speculare e della stessa dimensione del badge limite velocità** sulla destra. Il pulsante è presente anche quando il numero della tappa è ancora fuori dalla mappa visibile; i marcatori sulla linea verde restano solo informativi. Primo tocco: `TOCCA ANCORA >>`; secondo tocco entro **5 secondi**: invio `SALTO TAPPA RICHIESTO`. Solo Android ricalcola con Valhalla e preserva la destinazione finale; il messaggio conferma la richiesta, non ancora il successo del ricalcolo. Per evitare interazioni accidentali, effettuare i primi test da fermi. Il pulsante sparisce automaticamente quando non rimangono tappe intermedie. Installare APK e firmware dello stesso commit, poiché usano il nuovo bit `NavigationHasPendingWaypoints` nei flag BLE. **Dopo la correzione layout del 19/11:** il bordo del badge è blu (`#2D8CF0`); la distanza alla prossima svolta è sempre centrata sotto la distanza all'arrivo, anche senza tappe, per evitare sovrapposizioni. La distanza all'arrivo e il badge limite velocità non sono stati spostati.

**Arrivo sulla mappa ESP32 (19/11/2026, branch `test/waypoint-markers-20261119`):** il traguardo viene ora rappresentato da un **cerchio blu con A bianca** sulla linea verde, con le stesse regole di visibilità dei marker intermedi gialli: compare quando la destinazione finale entra nella finestra locale trasmessa da Android, anche su una rotta senza tappe. Non è interattivo e non sostituisce la destinazione finale del navigatore. La bandiera a scacchi laterale nella schermata di arrivo è ridotta del 20% (da 116 a 93 unità di layout) e mantiene il proprio centro. Richiesti APK e firmware del medesimo commit del branch per vedere il marker blu; collaudo da eseguire prima del merge.

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

**App Android, branch `test/waypoint-markers-20261119`:** seguire **Altre opzioni → Diagnostica e replay GPS → BASE/DEBUG → Avvia diagnostica completa** prima del giro. Il comando unifica registrazione GPS e diagnostica ESP32; l'esportazione è unica, come nella sezione 6. Non si avvia automaticamente e richiede il BLE connesso. Non maneggiare il telefono durante la guida.

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

## 10. Funzioni Android sperimentali (branch test)

Le funzioni elencate sono presenti nel codice del branch `test/waypoint-markers-20261119` e **non vanno attribuite automaticamente alla versione stabile** `valhalla`. Alcune possono essere già in `valhalla`, altre no: verificare il firmware/APK realmente installato. La disponibilità effettiva dipende dall'APK installato.

- **Panoramico / Touring moto:** in **Opzioni percorso** scegliere il profilo; Panoramico preferisce un itinerario relativamente più tortuoso, ma non certifica il paesaggio né garantisce una distanza precisa.
- **Tour circolare:** dalla ricerca aprire **Crea tour circolare**, impostare circa **30–300 km** e N/E/S/O, aggiungere eventuali luoghi obbligatori trovati con Photon (massimo otto), quindi **Calcola tour**. Con zero punti obbligatori il pianificatore propone tappe automatiche; con uno può aggiungere un punto laterale, con più punti rispetta il loro ordine. Verificare sempre la preview di Valhalla, il ritorno e le tappe prima di partire.
- **Salvare un tour:** nell'anteprima usare **Salva questo tour**. L'archivio locale **I miei tour salvati** permette **Partenza salvata** (pianificazione dall'origine originale) oppure **Da qui** (origine GPS attuale). La geometria viene ricalcolata, non conservata immutabile; i tour non sono sincronizzati sul cloud.
- **Pausa/ripresa:** durante un tour avviato usare **Pausa tour (riprendi più tardi)**, non **Termina navigazione**. Al ritorno aprire **Tour in pausa → Riprendi da qui**, verificare l'anteprima della tratta residua e avviare manualmente. L'app salva checkpoint locali; eliminarne i dati può farli perdere.
- **Salta prossima tappa:** nell'app, durante una navigazione con waypoint intermedi, scegliere **Salta prossima tappa** e confermare **solo da fermi e in sicurezza**. Ricalcola verso le tappe residue; non consente di saltare la destinazione finale. Nel branch di test il pulsante touch ESP32 `>>` è implementato e richiede doppio tocco; collaudare la combinazione firmware/APK più recente prima di considerarla definitiva.

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

**Stato del pacchetto:** il `.motopack` ZIP64 contiene i tre archivi e `manifest.json` con dimensioni e SHA-256. Il collaudo del pacchetto precedente del 15/11 è positivo secondo i test riportati; ciò non certifica ancora la nuova rigenerazione del 18/11.

| Operazione frequente | Dove | Comando o percorso |
| --- | --- | --- |
| Aggiornare branch test | WSL | `git pull --ff-only origin test/waypoint-markers-20261119` |
| Ricostruire tutto il MotoPack | WSL | `bash scripts/offline_map/rebuild_motopack_wsl.sh --threads 2` |
| Aprire risultati da Windows | Win + R | `\\wsl.localhost\Ubuntu-24.04\home\mod\motogps-build\ItaliaUnificata-20261118` |
| Compilare ESP32 | CMD attivato con `moto` | `idf.py -DSDKCONFIG_DEFAULTS="sdkconfig.defaults;sdkconfig.perf.defaults" build` |
| Flash sola app ESP32 | CMD/esptool | offset `0x20000` (non `0x10000`) |
| Compilare Android | PowerShell | `.\gradlew.bat testDebugUnitTest assembleDebug` |
| Ricompilare simulatore | WSL + emsdk | `./scripts/build_web.sh` |
| Avviare / riavviare server | WSL | `python3 -m http.server 4173 --directory .` (fermare il precedente con Ctrl+C) |
| Avviare diagnostica unificata | Android | Altre opzioni → Diagnostica e replay GPS → BASE/DEBUG → Avvia diagnostica completa |
| Esportare GPS + ESP32 + Android | Android | Ferma ed esporta tutto (ZIP): `motogps-diagnostica-completa.zip` |

**Conservare come storico**, non come procedura corrente: [vecchio manuale](archive/MANUALE_OPERATIVO_PRE_CLEANUP_2026-11-15.md), [cronologia tecnica completa al 19/11](archive/PROJECT_STATE_PRE_CLEANUP_2026-11-19.md). Stato e attività aperte: [PROJECT_STATE.md](../PROJECT_STATE.md) e [ROADMAP.md](ROADMAP.md). Non cancellare source PBF o archivi validi per fare spazio senza averne un backup.

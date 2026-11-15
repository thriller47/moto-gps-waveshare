<p align="center">
  <img src="assets/brand/moto-chat-gps-splash.webp" alt="Moto Chat GPS — Powered by Valhalla, OSM and Photon" width="900">
</p>

# Moto Chat GPS

Navigatore motociclistico con sorgenti pubblici in sviluppo per **Waveshare ESP32-S3-Touch-AMOLED-1.75C** (display AMOLED 466×466) e app Android. Il telefono gestisce GPS, ricerca destinazioni, calcolo itinerari e mappe offline; il dispositivo ESP32 riceve i dati via **Bluetooth LE** e visualizza la rotta verde, le strade di contesto grigie, distanze e indicazioni di prossimità.

**Branch di riferimento: [valhalla](https://github.com/thriller47/moto-gps-waveshare/tree/valhalla)**. `main` non esiste più. Il lavoro sul renderer LVGL è integrato, ma **l'ultima ottimizzazione di clipping dei segmenti grigi è ancora da collaudare su strada**.

## Componenti

| Componente | Funzione | Stato |
| --- | --- | --- |
| Android | Companion Kotlin, navigazione, BLE, importazione file e diagnostica | In uso |
| ESP32-S3 | Firmware ESP-IDF/LVGL, visualizzazione moto e radio separata sperimentale | In uso |
| **Valhalla Italia V5** | Routing offline per moto (escluse vie esclusivamente bici/pedoni) | Testato dall'utente |
| **MotoMap v4** | Rete stradale di contesto OSM e geometrie rotatorie | In uso |
| **Photon offline v2** | Ricerca località, strade, civici e accenti via SQLite FTS4 | Testato dall'utente |
| Simulatore web | Replay GPS JSONL, anteprima 2D e renderer LVGL via WASM | Disponibile |

MotoMap, Valhalla e Photon sono **archivi distinti**: un grafo Valhalla non sostituisce le strade MotoMap e Photon non calcola le rotte. I servizi online Valhalla/Photon sono fallback per gli scenari previsti dall'app. Non è necessario un gateway Render né OSRM.

## Per iniziare

- **[Manuale operativo in italiano](docs/MANUALE_OPERATIVO.md)** — installazione e aggiornamento del repository, build APK, flash ESP32, generazione/importazione MotoMap v4, Photon v2 e Valhalla V5, logging e simulatore.
- **[Stato corrente del progetto](PROJECT_STATE.md)** — funzionalità verificate, limiti, prove aperte e prossima azione.
- **[Simulatore browser](platforms/web/simulator/README.md)** — riproduzione dei tracciati GPS.
- **[Firma APK Android](docs/android-apk-signing.md)** — aggiornare l'app senza perdere dati privati.
- **[Diagnostica ESP32 in RAM](docs/ESP32_DIAGNOSTICA_RAM.md)** — log BLE e limiti operativi.
- **[Supporto MotoNav Fusion 360 v0.15](hardware/MotoNavFusion_v015.zip)** — unico archivio conservato in `hardware/`, contenente script, manifest e istruzioni; prototipo da verificare fisicamente prima dell'uso in moto.
- **[Contribuire](CONTRIBUTING.md)** — test, sicurezza e regole per le modifiche.

Da PowerShell sul PC Windows:

```powershell
Set-Location "C:\MotoGPS\moto-gps-waveshare"
git status --short
git fetch origin --prune
git switch valhalla
git pull --ff-only origin valhalla
git submodule update --init --recursive
```

Per compilazione/flash e installazione consultare il manuale: i dettagli su ESP-IDF, COM e firma Android dipendono dall'ambiente.

## Navigazione e sicurezza

La linea verde e lo **zoom contestuale** sostituiscono le vecchie frecce di svolta. La navigazione conserva distanza alla manovra e all'arrivo, limite di velocità, tappe intermedie, rotatorie e ricalcoli. La modalità radio Wi-Fi è separata dalla navigazione ed è ancora sperimentale. Si tratta di un progetto personale sperimentale: verificare sempre indicazioni, limiti e divieti reali; **non utilizzare il telefono durante la guida**.

## Origini, licenze e riconoscimenti

Il lavoro trae origine dal progetto [moto-gps-waveshare di mx3353672833-debug](https://github.com/mx3353672833-debug/moto-gps-waveshare) e ne conserva la tracciabilità nella cronologia Git. Crediti agli autori originari e ai contributori successivi. Il software originale coperto dalla licenza principale resta soggetto a **[PolyForm Noncommercial 1.0.0](LICENSE.md)**; questa dicitura **non modifica né restringe le licenze dei componenti di terzi**.

- **[Valhalla](https://github.com/valhalla/valhalla)** — routing, licenza MIT.
- **[Photon](https://github.com/komoot/photon)** — ricerca geocodificata, licenza Apache-2.0.
- **[OpenStreetMap](https://www.openstreetmap.org/copyright)** — © OpenStreetMap contributors, dati ODbL 1.0.
- **[LVGL](https://github.com/lvgl/lvgl)** — libreria grafica MIT.
- **Waveshare** — componenti/driver sotto le rispettive licenze, compresi Apache-2.0 dove applicabile.
- **ESP-IDF, NimBLE, font e altre dipendenze** — ciascuno mantiene le proprie condizioni e avvisi.

Consultare **[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)** e la directory **[LICENSES/](LICENSES/)**. Gli archivi locali `.sqlite`, `.motomap.zip`, `.tar.zst` non fanno parte dei sorgenti Git.

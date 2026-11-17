# Regressioni Moto Chat GPS — confronto 15 vs 17 novembre 2026

## Scopo e versioni
- **Versione di riferimento sul campo:** commit `e1cc5cdf7695f22b0b8485d91572ea5864dfc6a7` di `valhalla` (15 novembre, 16:56 UTC), fissato in `diagnostics/baseline-20261115`. L'utente riferisce navigazione corretta prima delle modifiche successive.
- **Versione sperimentale corrente:** `test/unified-offline-map-20261115` (17 novembre). Mantenere la cronologia, i nuovi tour e MotoPack; non usare reset hard né merge sul branch stabile.
- **Riproduzione:** stessa registrazione Android del 17 novembre nei due simulatori LVGL. Il parser `trace_model.js` è identico in entrambe le revisioni.

## Fatti verificati sui sorgenti
| File | Differenza tra baseline e sperimentale | Conseguenza per il test |
| --- | --- | --- |
| `ValhallaTurnGuidance.kt` | Identico | La politica di selezione delle manovre non è nuova. Può ricevere però progressi diversi. |
| `RouteProgressMatcher.kt` | Identico | Verificare come è chiamato da ViewModel e quali course riceve, non accusarlo senza prova. |
| `platforms/web/simulator/trace_model.js` | Identico | Il formato JSONL è comune ai due simulatori. |
| `NavigationViewModel.kt` / `NavigationHeadingPolicy.kt` | Modificati | Posizione, course e refresh delle finestre di rotta potrebbero differire. |
| `RerouteStabilityPolicy.kt` / `RouteGeometryWindow.kt` | Modificati | Cambiano rispettivamente la decisione di ricalcolo e la scelta della finestra BLE. |
| `motion_heading_fusion.hpp` / `map_origin_predictor.hpp` / `phone_nav_bridge.cpp` | Modificati | Sospetti forti per direzione e latenza della posizione sul dispositivo reale. |
| `moto_nav_presenter.cpp` | Modificato | Verificare visualizzazione e zoom, separati dai messaggi Android. |

## Cronologia dei cambi sospetti
1. **15 novembre sera**, `dfef49f081`: rimozione dipendenze demo Jinan dal bridge ESP32; da non confondere con la precedente inizializzazione corretta del QMI8658, già nella baseline.
2. **17 novembre mattina**, `f8ade120f9`: heading Android, re-anchor BLE, fusione IMU e predittore modificati insieme. Primo gruppo da isolare, dopo il confronto dei renderer.
3. **17 novembre pomeriggio**, `a6c88cad31` e `349e67dc31`: ulteriore taratura della fusione gyro/GNSS.
4. **17 novembre sera**, `fb6ded9b4a` e `187f4fec58`: gestione zoom geometrico, nuovi criteri di ricalcolo e rollback selettivo dello zoom automatico. Non correggono retroattivamente la rotta già registrata.

## Procedura A/B ripetibile in WSL
Da checkout sperimentale **già presente** in `~/MotoGPS/moto-gps-waveshare`:
```bash
cd ~/MotoGPS/moto-gps-waveshare
git fetch origin --prune
git switch test/unified-offline-map-20261115
git pull --ff-only origin test/unified-offline-map-20261115
bash scripts/diagnostics/prepare_20261115_replay_ab.sh
```

Lo script crea due *worktree detached* separati in `~/MotoGPS/motogps-regression-20261117/{baseline,current}` e compila due WebAssembly indipendenti. **Non cambia il checkout attivo, non elimina né sovrascrive worktree preesistenti e blocca versioni inattese.** I server sono da avviare separatamente: baseline sulla porta 4174, current sulla 4175. La vecchia porta 4173 resta libera/non modificata.

Aprire il medesimo file di replay delle 17:40–17:58 in entrambe le schede. Confrontare **17:44:35 (piega inesistente)**, **17:45:20 (zoom spurio)** e **17:45:43–17:46:07 (zoom rotatoria)**. Impostare 1×, heading originale, esperimenti opzionali disattivati; le interfacce delle due versioni non sono identiche. Annotare per ogni istante: distanza alla manovra, tipo manovra, fattore zoom, forma della polilinea, posizione della moto.

**Interpretazione rigorosa:** gli snapshot JSONL registrati il 17 novembre includono già coordinate, geometria verde, manovra e sua distanza calcolati dall'Android sperimentale. Se entrambi i renderer mostrano il medesimo errore, la causa può essere a monte, ma non è una prova contro il vecchio Android. Se differiscono con identico input, indagare renderer/JS/WebAssembly. Nessuna versione WASM riproduce per intero BLE, IMU fisica, ritardo GATT, la fase Valhalla Android o il percorso di un altro giorno. I falsi ricalcoli delle 17:37 non sono contenuti nel replay iniziato alle 17:40.

## Esperimenti successivi — una sola variabile alla volta
- **A:** confrontare le due visualizzazioni (questa preparazione; nessun codice navigazione cambiato).
- **B:** a parità di APK e di percorso, eventualmente confrontare ESP32 con fusione/predittore del 15 vs corrente; usare log reali, firmware e BLE compatibili. Non dedurre conclusioni da due tragitti con precisione GNSS differente.
- **C:** se la regressione si manifesta già nei messaggi Android, isolare prima la politica heading/aggiornamento route progress, poi il filtro ricalcoli, mantenendo fisso il resto. Serve confronto con log GNSS grezzo e accuratezza; i replay non permettono di ricalcolare con certezza una rotta nuova.

## Stato
Branch baseline creato su GitHub; script e istruzioni preparati sul branch sperimentale. Non sono stati eseguiti qui due build WASM, non è stato riprodotto localmente il video e non è stata verificata la correzione su moto. Una CI nativa può validare la sintassi dello script ma **non** certificare il confronto visuale o l'IMU. Non fare merge in `valhalla` senza collaudo. Prossima azione: preparare due runtime WSL, confrontare la stessa finestra temporale, decidere quale singolo componente isolare.

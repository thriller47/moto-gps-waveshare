# Audit predittore mappa — uscita rotonda, 17 novembre 2026

**Progetto:** Moto Chat GPS. **Branch sperimentale:** `test/unified-offline-map-20261115`. **Baseline stabile:** `valhalla`, non modificata.

## Evidenza

Segnalazione dell'utente: «uscendo da una rotonda ... mi spostava parecchio verso sinistra».

Fonti private esaminate localmente (non incluse nel repository):
- `motogps-replay(20261117-072914).jsonl`: eventi `route`, `fix` e `snapshot`;
- `moto-heading-diagnostics(2).csv`: output dell'analisi WASM del medesimo replay.

L'evento di maggiore spostamento a sinistra nel CSV è a **t=1.121.857 ms** (frame 4503, manovra `roundabout`): scarto ON **28,02 m**, proiezione **−27,99 m verso sinistra**, longitudinale **−1,22 m**, heading snapshot **19°**, speed **9,86 m/s**. Scarto rispetto allo snapshot Android, non rispetto alla corsia reale.

## Cronologia sincronizzata

| t (ms) | Evento | Heading | Speed (m/s) | Scarto ON / laterale |
| ---: | --- | ---: | ---: | --- |
| 1.117.743 | Nuova finestra route | — | — | — |
| 1.118.784 | Nuova finestra route (geometria distinta) | — | — | — |
| 1.118.991 | Primo snapshot con ultima finestra | 340,59° | 17,67 | 2,86 / −1,96 m |
| 1.120.024 | Snapshot in curva | 349,20° | 13,50 | 9,81 / −7,74 m |
| 1.121.078 | Snapshot in curva | 3,20° | 12,31 | 19,97 / −18,29 m |
| 1.121.857 | Ultimo snapshot prima di route nuova | 19,00° | 9,86 | **28,02 / −27,99 m** |
| 1.121.859 | Nuova finestra route, 2 ms dopo il picco | — | — | — |
| 1.122.117 | Snapshot con finestra nuova | 19,00° | 9,86 | 17,45 / −17,43 m |
| 1.122.374 | Snapshot, correzione in corso | 19,00° | 9,86 | 11,10 / −10,91 m |

Il gap reale fra le ultime due **finestre distinte** è **3.075 ms**. Le finestre in questo tratto **non sono identiche**: cambiano origine e lista di punti, benché token e generazione della rotta rimangano uguali. Correzione esplicita della precedente ipotesi secondo cui sarebbero stati trasmessi soltanto pacchetti identici. Il parser `trace_model.js` continua comunque a deduplicare correttamente eventuali pacchetti identici in altri tratti, per la riproduzione visiva.

## Analisi del codice C++

In `platforms/esp32/main/map_origin_predictor.hpp`, `MapOriginPredictor::advance`:
1. Reinizializza con nuovo `route_id`, nuova `route_generation` o gap dei tick maggiore di 1 s.
2. Integra il movimento soltanto con speed 1,5–35 m/s, heading valido, età geometria non oltre 3,4 s, differenza heading rispetto all'ultima ancora non oltre **15°** e speed almeno **75%** di quella dell'ancora.
3. Al nuovo `geometry_sequence`, calcola la correzione tra origine nuova e posizione predetta, con possibile attenuazione solo **retrograda** su rettilineo stabile; applica correzione progressiva con costante 0,55 s. Un errore superiore a 75 m forza lo snap.

Al primo snapshot con finestra corrente l'heading/velocità d'ancora sono circa **340,59° / 17,67 m/s**. A t=1.121.078 ms:
- variazione angolare breve rispetto all'ancora: **22,61°**, superiore a 15°;
- rapporto velocità **12,31/17,67 = 69,7%**, inferiore a 75%.

**Entrambe le condizioni inibiscono l'integrazione rettilinea**, mentre la posizione Android continua lungo la curva. A t=1.121.857 il delta heading sale a 38,41° e il rapporto speed scende a 55,8%. Con assenza di una nuova finestra per ~3 s, la posizione predetta può restare arretrata lungo la traiettoria precedente: proiettando rispetto al nuovo heading, la differenza appare quasi tutta **a sinistra**. Quando arriva la finestra a t=1.121.859, la correzione progressiva spiega il rapido rientro nei successivi snapshot.

**Importante:** questo spiega meccanicamente il *modello di replay* e la correlazione osservata; non dimostra che la stessa temporizzazione o lo stesso problema si verifichino nell'ESP32 reale. Il replay usa tempi Android e l'arrivo di eventi `route` registrati, non veri timestamp di consegna BLE, e omette yaw QMI8658, eventuale map matching e stato del collegamento. L'heading non è il dato GNSS grezzo.

## Ipotesi verificabili / prossimo passo

- Test nativo deterministico: usare una sequenza sintetica di finestre con gap 3,075 s e curva che supera progressivamente 15°/25% di frenata; verificare congelamento extrapolazione e rientro dopo nuova ancora, senza GPS privato nel repository.
- Per distinguere la causa pratica: strumentare in simulatore **motivo di blocco dell'extrapolazione** e distanza fra posizione predetta e ancora, confrontando gap normale e caso rotonda. Non abbassare alla cieca soglie 15° e 25%: servono a evitare estrapolazione fuori strada su tornanti e svincoli.
- Eventuale correzione dovrà essere A/B reversibile sul banco prova, con guardrail per rotatorie e test di regressione, prima di qualsiasi proposta di patch firmware.

**Stato:** audit completato; **nessuna modifica di firmware/Android/BLE/routing**, nessuna build Emscripten/CI avviata. La diagnosi del comportamento reale rimane aperta.


## Regressione sintetica aggiunta (17/11)

Il test `test_roundabout_exit_three_second_geometry_gap` in `tests/native/map_origin_predictor_tests.cpp` riproduce le **condizioni meccaniche**, non le coordinate personali: finestre distinte a distanza di 3.075 ms, curva e frenata oltre le due soglie, stop della previsione rettilinea e rientro progressivo al successivo anchor. Questa verifica protegge il comportamento baseline attuale; **non è una patch del predittore** né una prova dell'hardware. La suite è già registrata come `map_origin_predictor_tests` in CTest. Build/CTests da verificare prima di estendere l'esperimento A/B.

# Audit heading su rettilineo — 17 novembre 2026, 07:49:53–07:50:03

**Ramo:** `test/unified-offline-map-20261115`; `valhalla` invariato.

**Osservazione:** freccia/mappa disallineate dalla linea verde di almeno ~30° indipendentemente da Originale, Bloccato, Stabilizzato, GPS verificato.

**Fonti private analizzate localmente e non pubblicate:** `motogps-replay(20261117-072914).jsonl`; `moto-heading-diagnostics(2).csv`; `motogps-android-diagnostics(20261117-072912).zip` (`previous.log`).

## Cronologia riprodotta

L'evento `meta` fissa l'inizio del tracciato alle 07:36:01.580 CET. Intervallo richiesto: `t_ms=831420..841420`.

| Ora CET | Heading snapshot | Direzione segmento verde più vicino | Differenza |
| --- | ---: | ---: | ---: |
| 07:49:53.150 | 219,50° | 212,80° | 6,70° |
| 07:49:53.416 | **253,20°** | **212,80°** | **40,40°** |
| 07:49:54.476 | 251,79° | 212,80° | 38,99° |
| 07:49:55.560 | 238,20° | 213,15° | 25,05° |
| 07:49:56.603 | 232,20° | 213,15° | 19,05° |
| 07:49:57.416 | 215,50° | 213,15° | 2,35° |
| 07:50:00.609 | 211,09° | 213,11° | 2,02° |

Le ore di alcune righe sono ricavate dalla coppia `captured_at_ms + t_ms` e arrotondate; nei file diagnostici Android la stessa sequenza è visibile con timestamp propri. Su 40 snapshot nel segmento, 8 hanno differenza >30°, 12 >20°. La direzione del segmento più vicino resta tra 212,80° e 213,17°: la geometria di riferimento non mostra la stessa rotazione.

## Corroborazione indipendente con GNSS

Nel log Android del 17/11 alle 07:49:54.330:
`GPS_FIX provider_course=251.8 speed_mps=8.13 heading_cdeg=25179 source=movement`.
Il replay `fix` riporta heading grezzo 251,8°; fra quel fix e il precedente lo **spostamento reale fra coordinate** punta circa a 216,0° su ~12 m. La divergenza è ~35,8°. Nei campioni successivi il bearing del provider 238,2°/232,2° torna gradualmente verso 215,5°, mentre le direzioni di spostamento restano grossolanamente tra 195° e 220° in brevi intervalli; una linea su 3–6 secondi si mantiene ~208–223°. Attenzione: il singolo intervallo GNSS appena prima, 07:49:52.384→07:49:53.329, punta ~246,5° su 9 m: **anche le posizioni GNSS possono oscillare**. Non utilizzare una sola coppia di fix come verità assoluta.

## Causa software identificata, limiti

In `NavigationHeadingPolicy.movingHeading`, Android sceglie per primo `gpsBearingDeg` quando è 0..360°, speed >=3 m/s e `bearingAccuracyDeg` è assente oppure <=25°. Il corso di spostamento è soltanto fallback quando quello del provider non è disponibile. **Manca un controllo temporale di coerenza del bearing valido con direzione consolidata su più fix**. `NavigationViewModel.updateAcceptedDeviceLocation` copia quel corso nel `headingCentiDeg`, quindi nel BLE e nel replay. Il NavPresenter ruota strade e linea verde direttamente con `snapshot.heading_deg`. Ciò spiega perché un heading assoluto errato produca un disallineamento visivo in tutte le modalità: Bloccato può congelare proprio un valore errato, mentre i filtri sperimentali partono dalla medesima sorgente e potrebbero classificare erroneamente come curve due salti di coordinate.

**Non è dimostrato** quale causa fisica generi il `provider_course` errato (rumore GNSS, inaccuratezza del fix, altri effetti); il replay non conserva `bearingAccuracyDegrees`. Non attribuire causalità al giro IMU ESP32: il replay non ne simula la fusione. Il segmento verde più vicino non è map matching garantito ma è coerente con lo spostamento globale in questa finestra.

## Decisione e test proposto

**Non toccare ancora la produzione.** Prima costruire in simulatore un esperimento A/B basato su course di più fix distinti, filtro di robustezza e isteresi, con rilascio rapido su curvatura GPS sostenuta (non su un solo segmento rumoroso). Test deterministici con: picco isolato 40°, rettilineo con jitter laterale di 3–5m, vera rotonda, curva lenta, buchi GNSS, angolo 359/0. Considerare l'uso del segmento verde solo come diagnostica/indizio, non come riferimento assoluto in cavalcavia o ricalcoli.

**Stato:** audit concluso; nessuna patch di filtri, ESP32, Android, BLE o motore di routing. Nessuna build richiesta per questo aggiornamento documentale.

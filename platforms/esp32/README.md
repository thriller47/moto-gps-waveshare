# ESP32 — Moto Chat GPS per Waveshare 1.75C

Firmware ESP-IDF e LVGL per la scheda **ESP32-S3-Touch-AMOLED-1.75C** (display CO5300 466×466, touch CST9217, 32 MB flash, 8 MB PSRAM).

**Manuale operativo:** [docs/MANUALE_OPERATIVO.md](../../docs/MANUALE_OPERATIVO.md). Il firmware riceve le informazioni da Android via BLE e visualizza rotta verde, strade grigie MotoMap, distanza di manovra/arrivo, limite e marcatori. Il clipping anticipato per segmenti grigi è integrato ma **ancora da provare su strada**.

## Compilazione e flash Windows

Ambiente locale confermato: **ESP-IDF 5.5.6** (la CI usa 5.5.5). Aprire **PowerShell** nella root del progetto e digitare `moto` per aprire il CMD predisposto per ESP-IDF. Poi:

```cmd
idf.py --version
idf.py -DSDKCONFIG_DEFAULTS="sdkconfig.defaults;sdkconfig.perf.defaults" build
idf.py -p COM3 flash monitor
```

COM3 è un esempio. Gli offset nell'attuale `partitions.csv`: bootloader `0x0`, tabella `0x8000`, applicazione `0x20000` (non `0x10000`). Il comando di flash app-only con artifact GitHub e la configurazione iniziale sono nel manuale. **Non cancellare la flash intera** durante gli aggiornamenti ordinari.

La diagnostica può essere trasferita via BLE all'app Android oppure letta via seriale (`LVGL_ROADS`, FPS e `display perf`). La partizione `diag` flash resta solo per la compatibilità con i vecchi log; la registrazione attuale usa RAM/PSRAM.

Il simbolo Wi-Fi attiva una modalità **radio separata e sperimentale**: nessuna navigazione contemporanea. I comandi seriali di laboratorio sono nel manuale, non nella normale UI GPS.

Licenze: [root LICENSE.md](../../LICENSE.md), [avvisi di terzi](../../THIRD_PARTY_NOTICES.md) e [LICENSES/](../../LICENSES/). Conservare i riferimenti ad Apache-2.0 di Waveshare dove applicabili.

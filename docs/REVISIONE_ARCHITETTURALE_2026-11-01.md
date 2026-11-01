# Moto GPS — Revisione architetturale (2026-11-01)

Stato: DA RIPRENDERE PIÙ AVANTI. Nessuna implementazione autorizzata ora.

## ESP32
- ESP-01: pulizia UI legacy (bassa priorità).
- ESP-02: conteggio perdite ring PSRAM diagnostico (alta).
- ESP-03: verificare limite estrapolazione MapOriginPredictor (alta).
- ESP-04: misurare copie NavSnapshot e lock LVGL (media).
- ESP-05: priorità al traffico BLE navigazione rispetto ai log (alta).

## Android
- AND-01: circuit-breaker Overpass e cache prudente (media).
- AND-02: test del contatore svolta prematuro (alta).
- AND-03: estrarre collaboratori da NavigationViewModel (media).
- AND-04: profilare logging asincrono già presente (bassa).
- AND-05: heading GPS stabile durante soste e partenze (alta).

## Regole di ripresa
Conservare valhalla stabile; test/openfreemap-mvt separato. Nessuna nuova freccia; zoom 3.4x e UI concordata; nessun logger flash; PSRAM/BLE 60 minuti; nessun tasto cancellazione. Test dedicati e verifica hardware prima di ogni merge.

Ordine suggerito: ESP-02/05, AND-02/05, ESP-03/04 e AND-01, infine ESP-01/AND-03/04. Verificare sempre codice aggiornato e log prima di intervenire.

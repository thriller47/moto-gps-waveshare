# Contribuire a Moto Chat GPS

Il progetto è sviluppato principalmente sul branch **`valhalla`**. Prima di proporre modifiche leggere [PROJECT_STATE.md](PROJECT_STATE.md), [AGENTS.md](AGENTS.md) e il [manuale operativo](docs/MANUALE_OPERATIVO.md). Il progetto è destinato allo sviluppo sperimentale e personale, senza promessa di assistenza o manutenzione commerciale.

## Proposte e modifiche

1. Descrivere problema, dispositivo, versione firmware/APK, passaggi per riprodurlo e comportamento atteso.
2. Lavorare su un ramo di prova separato quando il cambiamento comporta rischi. **Non reintrodurre** `main`, gateway OSRM/Render, vecchie pagine o formati delle mappe.
3. Mantenere la compatibilità Android↔BLE↔ESP32; se si modifica un payload, aggiornare codice, fixture e test coinvolti.
4. Verificare build/test effettivamente eseguiti e distinguerli dai test su strada. Una modifica non deve essere dichiarata collaudata soltanto perché compila.
5. Evitare commit di credenziali, API key, log GPS personali, dati di posizione privati, dump OSM, mappe compilate o certificati di firma.

## Licenze e attribuzioni

Leggere [LICENSE.md](LICENSE.md) e [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Conservare i riferimenti a OpenStreetMap, Valhalla, Photon, Waveshare, LVGL e alle licenze di font/componenti. Non copiare materiale proprietario o di terzi senza autorizzazione. Le eventuali modifiche a codice di terzi devono preservare licenza e avvisi originali.

Per le prove su strada non maneggiare telefono o cavi durante la guida; usare tracciati anonimizzati quando si condividono report.

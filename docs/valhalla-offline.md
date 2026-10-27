# Esperimento Valhalla offline (`valhalla`)

Questo branch nasce da `signal`. `signal`, `main`, `NoOta` e il
servizio Render di produzione **non devono essere aggiornati** finché
l'integrazione non è collaudata.

## Implementato nella prima fase

- Libreria Android `io.github.rallista:valhalla-mobile:0.6.4`.
- Motore locale `OfflineValhallaRouter` (Valhalla C++/JNI, non HTTP).
- Ricalcolo con direzione GPS recente, quando disponibile.
- Conversione di shape `polyline6` e manovre nel formato del gateway
  Moto-GPS che il firmware ESP32 già riceve via BLE.
- Anteprima del percorso e navigazione attraverso Valhalla se è presente
  un archivio di tile da routing locale.
- Ripiego temporaneo al gateway OSRM/Render **soltanto quando manca**
  l'archivio. Con l'archivio presente, gli errori di Valhalla vengono
  mostrati e non nascosti tramite un routing differente.

## Differenza tra OSM, OSRM, Valhalla e motomap

Valhalla è un motore di routing che usa normalmente i dati
OpenStreetMap. Non elimina il formato o la provenienza OSM delle strade.
La cache di visualizzazione `.motomap` non contiene il grafo di routing
necessario a Valhalla e non può essere rinominata in `.tar`.

Per l'uso offline sono necessari dati Valhalla generati in anticipo
(per esempio a partire da un extract OSM PBF) per la regione desiderata.
L'archivio previsto dall'app si chiama:

```text
Android/data/com.motogps.android/files/routing/valhalla_tiles.tar
```

Il path è relativo alla **memoria condivisa primaria**; in codice
viene calcolato tramite `getExternalFilesDir(null)`, con fallback alla
directory privata dell'app. L'archivio deve contenere vere tile di
routing Valhalla, non un file OSM `.pbf` e non una cache `.motomap`.

### Installazione temporanea per i test con ADB

1. Compilare la versione Android dal branch `valhalla` e installarla.
2. Ottenere un archivio `valhalla_tiles.tar` coerente con la versione
   del motore e con l'area del test.
3. Sul dispositivo di test (debug), trasferirlo, per esempio:

   ```sh
   adb shell mkdir -p /sdcard/Android/data/com.motogps.android/files/routing
   adb push valhalla_tiles.tar /sdcard/Android/data/com.motogps.android/files/routing/valhalla_tiles.tar
   ```

   Su dispositivi con restrizioni dello storage, questo metodo potrebbe
   essere bloccato. In quel caso usare un file picker/importer da
   implementare nella fase successiva.

4. Riaprire l'app e pianificare un itinerario **dentro i confini
   geografici delle tile installate**.

Non copiare l'archivio durante una navigazione in corso: il motore lo
tiene aperto e mappato in memoria.

## Non ancora completato

- Creazione, download e gestione in-app degli extract regionali.
- Importazione GPX, tappe intermedie e preferenze motociclistiche.
- Ricerca di luoghi/indirizzi offline (la ricerca attuale usa il gateway).
- Conversione del contesto stradale `.motomap` a una fonte interamente
  offline: la cache funziona ma il recupero delle tile mancanti usa
  ancora Internet.
- Eliminazione definitiva di Render/OSRM dal prodotto.
- Test su dispositivo con archivi reali.

L'ESP32 e il protocollo BLE restano invariati.

Riferimenti:
- https://github.com/Rallista/valhalla-mobile
- https://valhalla.github.io/valhalla/

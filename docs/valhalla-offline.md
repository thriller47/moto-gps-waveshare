# Moto-GPS Valhalla: routing ibrido e ricerca Photon

Branch sperimentale `valhalla`. Non modificare `signal`, `main`,
`NoOta` o il servizio Render fino al collaudo su telefono e ESP32.

## Routing Valhalla (mai OSRM)

Ogni operazione (anteprima, avvio e ricalcolo del percorso) usa:
1. **Valhalla locale (JNI)** se `valhalla_tiles.tar` è installato e il
   motore riesce a calcolare il percorso richiesto.
2. **Valhalla online**, quando il grafo locale non esiste o non fornisce
   un percorso. Viene chiamato direttamente
   `https://valhalla1.openstreetmap.de/route` via POST JSON.
3. Se entrambi falliscono, l'app mostra il motivo del mancato routing;
   non invia richieste ai vecchi endpoint OSRM.

La UI mostra **Percorso Valhalla offline** oppure **Percorso Valhalla
online**, e distingue il motore effettivo anche dopo l'avvio.
Il ricalcolo utilizza, quando disponibile, il bearing GPS recente,
non la bussola del dispositivo. Le geometrie e le istruzioni di
entrambi i motori passano dallo stesso `ValhallaRouteAdapter` e
raggiungono l'ESP32 nel formato BLE esistente.

**Il server FOSSGIS è pubblico e dimostrativo, non un backend
affidabile per produzione.** Il suo uso è soggetto a fair use,
disponibilità e possibili limitazioni; prima del rilascio su vasta scala
prevedere un servizio Valhalla dedicato e concordare l'uso della demo.
Il client usa un header identificativo `X-Client-Id`.

## Ricerca destinazioni senza Render

L'app interroga `https://photon.komoot.io/api/` direttamente tramite
una singola richiesta alla pressione del pulsante Cerca.
- Risposte Photon GeoJSON e coordinate WGS84 (ordine GeoJSON: lon, lat).
- Massimo otto risultati, priorità alla posizione GPS se disponibile,
  validazione degli intervalli di latitudine e longitudine.
- Nessun parametro `lang=it`: l'istanza pubblica Photon può rifiutare
  con HTTP 400 lingue non disponibili. La ricerca accetta comunque nomi
  italiani e i risultati usano i nomi OSM locali/di default.
- Su errori HTTP l'app mostra anche il messaggio restituito da Photon,
  se presente (ad esempio la lingua rifiutata).
- Nessun autocomplete continuo per non sovraccaricare il server demo.

Photon è online: offline si può comunque scegliere una destinazione
tramite pressione prolungata sulla mappa, mentre l'indice locale
degli indirizzi resta un lavoro futuro. Anche Photon demo può limitare
o rifiutare richieste: per produzione usare una propria istanza o
un fornitore con condizioni compatibili.

## Archivi di routing locali

**Gestione Mappe Offline → Routing Valhalla offline → Aggiorna regioni →
Scarica routing.** Il catalogo di prova proviene da
[Librescoot](https://github.com/librescoot/valhalla-tiles/releases/tag/latest).
L'archivio `.tar` viene verificato tramite dimensione, SHA-256 e
presenza di tile `.gph`. Percorso:
```
Android/data/com.motogps.android/files/routing/valhalla_tiles.tar
```

Il grafo di routing non è una cache di strade `.motomap`.
Con la versione attuale è possibile installare un solo grafo alla volta.
Un percorso che esce dalla zona scaricata può essere richiesto al servizio
Valhalla online; offline non è possibile navigare fuori copertura.

## Stato della dipendenza da Render

Il **calcolo itinerari**, **ricalcolo** e **ricerca testuale** non
dipendono più da Render. Il gateway è però ancora usato per
l'**aggiornamento automatico delle mappe stradali .motomap e del
contesto stradale inviato all'ESP32**, e per i vecchi download delle
mappe vettoriali gestiti da `HybridMapRepository`. OSMDroid visualizza
invece le tile raster OSM tramite la propria sorgente. Quindi **Render
non è ancora eliminabile** senza una sostituzione di quelle API.

## Test consigliati

1. Senza archivi e con rete attiva, cercare una città con Photon e
   verificare l'etichetta **Valhalla online**.
2. Scaricare e installare le tile, disattivare i dati, selezionare una
   destinazione nella stessa regione con pressione lunga: deve apparire
   **Valhalla offline** e funzionare il ricalcolo.
3. Riprovare senza dati e senza archivio: mostrare errore esplicito.
4. Con archivi limitati e rete attiva, chiedere un percorso fuori
   copertura locale: tentare Valhalla online.
5. Verificare l'assenza di regressioni BLE, geometrie e manovre con
   dispositivo fisico.

I test automatici coprono conversione dei percorsi, richiesta JSON,
coordinate GeoJSON e parsing Photon. Non equivalgono a test dal vero
su rete pubblica o su grafo nativo installato.

Dati: © OpenStreetMap contributors (ODbL). Valhalla demo:
FOSSGIS e.V.; Photon demo: komoot; tile di esempio: community Librescoot.

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

## Cartografia stradale indipendente da Render

Anche il contesto stradale dell'ESP32 viene ora acquisito da dati OSM
**direttamente tramite Overpass**. L'endpoint
`https://overpass-api.de/api/interpreter` restituisce vere geometrie
OSM per piccole bounding box intorno al GPS. Il convertitore preserva
il formato `.motomap` e il protocollo BLE esistente; nessuna richiesta
dell'APK raggiunge Render.

- La posizione corrente provoca una richiesta di **una sola tile**
  zoom 15 quando il contesto in cache è insufficiente. Il risultato
  viene conservato in `files/maps/osm_near_*.motomap` per 7 giorni.
- Il caricamento automatico di *tutto* il corridoio di un itinerario è
  disabilitato: interrogherebbe centinaia di tile del server pubblico.
- I download espliciti della zona e del corridoio restano disponibili,
  eseguono richieste distanziate e mantengono checkpoint locali.
  Non sono una soluzione per scaricare intere regioni: per quella
  occorre un archivio OSM regionalizzato.
- Le velocità dei tratti non sono fornite da Valhalla: si usa il tag
  OSM `maxspeed` numerico, ove presente, o il valore indicativo per
  classe di strada. **Non va interpretato come limite legale certo**.
- Dati già in cache `.motomap` rimangono leggibili.
- Photon e Valhalla online rimangono servizi distinti e indipendenti
  da Render; la visualizzazione raster OSMDroid usa la propria fonte.

**Limite importante:** il server Overpass pubblico è condiviso,
soggetto a code, rallentamenti e quote; questa integrazione è
sperimentale. Per offrire il caricamento automatico su larga scala
occorrono tile regionali offline o un servizio OSM/PMTiles dedicato.
Le richieste non garantiscono che tutte le strade vengano mostrate
immediatamente. Il sistema BLE e l'ESP32 non sono stati modificati.

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

## Diagnostica recupero strade OSM

Se le strade non compaiono sull'ESP32, aprire **Gestione Mappe Offline**:
la voce di stato segnala se la richiesta automatica alla tile OSM ha trovato
strade oppure se è fallita. Il log Android `GatewayRoadTiles` riporta
endpoint e dettaglio HTTP, mentre `NavigationVM` registra il risultato
complessivo. Il client prova, in sequenza e solo in caso di errore,
`overpass-api.de` e `overpass.kumi.systems`; non chiama Render.
Una risposta valida senza strade non viene convertita artificialmente
in geometrie. La presenza di un percorso Valhalla online non garantisce
che Overpass abbia risposto.

## Pacchetti stradali offline da .osm.pbf (prima versione)

Il file OSM PBF è un formato sorgente, non una mappa pronta per il rendering.
Preparare il pacchetto su PC Linux o Windows con Python e pyosmium:

```bash
python -m pip install osmium
python scripts/offline_map/pbf_to_motomap.py italy-nord-ovest-latest.osm.pbf -o nord-ovest.motomap.zip
```

Scaricare il `.osm.pbf` della regione desiderata dal catalogo
[Geofabrik](https://download.geofabrik.de/europe/italy.html).
Copiare il `.motomap.zip` sul telefono, aprire **Gestione Mappe Offline**
e premere **Importa pacchetto strade OSM (.zip)**.
Selezionare il file dal selettore Android: gli archivi vengono conservati
senza estrazione in `files/maps/.osm_packages` e le singole tile sono lette
direttamente dallo ZIP in base alla posizione GPS. Archivi con nomi diversi
possono coesistere; importare nuovamente lo stesso nome lo sostituisce.
Le vecchie tile estratte in `files/maps/.osm_import_parts` restano leggibili. Il contenuto è destinato al **contesto stradale ESP32**,
non sostituisce il grafo Valhalla `.tar` usato per il routing.

**Attenzione alle dimensioni.** Questa prima versione usa un convertitore
Python in memoria; su estratti PBF grandi (per esempio un'intera
macroregione) richiede molta RAM. Partire con un estratto locale piccolo
prima di tentare regioni complete. Una versione successiva dovrà
generare le tile con indicizzazione su disco e offrire il download di
pacchetti precompilati. L'importazione Android accetta fino a 500.000 tile per ZIP, con limite di
8 GiB per archivio e somma delle dimensioni estratte, e protegge il
precedente archivio con lo stesso nome durante l'aggiornamento.
Il pacchetto Italia da 278.028 tile rientra nel limite del numero di file;
restano da verificare spazio sul telefono, dimensioni e test reali.

Nessun pacchetto regionale precompilato è incluso nell'APK.
La disponibilità delle strade offline dipende dall'avvenuta
**conversione e importazione** del pacchetto; non basta installare
l'app senza scaricare dati.

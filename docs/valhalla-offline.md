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

La UI mostra semplicemente **Percorso Valhalla**, senza specificare
la modalità. Il provider effettivo resta disponibile nei dati tecnici
(`route.provider`) e nei log, senza alterare la scelta automatica del motore.
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
tramite pressione prolungata sulla mappa **oppure inserendo coordinate GPS**
nel formato `45.4642, 9.1900`. Le coordinate vengono riconosciute localmente,
senza chiamare Photon. L'indice locale degli indirizzi resta un lavoro futuro.
**Nota UI:** se Valhalla non ha prodotto alcuna geometria, l'anteprima
mostra soltanto marcatori di partenza e arrivo, mai una finta linea retta;
il motivo dell'errore rimane visibile e Avvia navigazione è disabilitato. Anche Photon demo può limitare
o rifiutare richieste: per produzione usare una propria istanza o
un fornitore con condizioni compatibili.

## Importazione di grafi esterni

Nel menu **Gestione mappe → Grafi Valhalla → Importa grafo TAR / ZST**
è possibile scegliere un archivio scaricato da un fornitore esterno,
ad esempio `Italy.tar.zst` da routing.earth, oppure un normale `.tar`.
L'app espande Zstandard in streaming nel file TAR usato dal motore
Valhalla (non occorre decomprimere sul PC). L'archivio deve contenere
le tile di routing `.gph`. La copia precedente rimane disponibile
durante la validazione e viene sostituita solo dopo la verifica del TAR.
Non importare mentre la navigazione è attiva.

Prima dell'operazione verificare **molto spazio libero** sul telefono:
l'archivio compresso resta nella cartella Download e l'app crea una
copia TAR non compressa prima di sostituire il grafo installato.
I file di routing sono molto grandi; l'app limita a 20 GiB il TAR
espanso e controlla periodicamente lo spazio residuo. La validazione
dell'archivio non garantisce la compatibilità del suo formato binario
con la versione Valhalla integrata: testare il calcolo di una rotta
dopo l'installazione.

## Archivi di routing locali

**Gestione mappe → Grafi Valhalla → Aggiorna elenco regioni →
Scarica grafo.** Il catalogo di prova proviene da
[Librescoot](https://github.com/librescoot/valhalla-tiles/releases/latest).
Il catalogo Android interroga la release piu recente tramite
`/releases/latest`, evitando il tag storico `/releases/tags/latest` che
non seguiva i nuovi rilasci mensili.
L'archivio `.tar` viene verificato tramite dimensione, SHA-256 e
presenza di tile `.gph`. Percorso:
```
Android/data/com.motogps.android/files/routing/valhalla_tiles.tar
```

Il grafo di routing non è una cache di strade `.motomap`.
Con la versione attuale è possibile installare un solo grafo alla volta.
Un percorso che esce dalla zona scaricata può essere richiesto al servizio
Valhalla online; offline non è possibile navigare fuori copertura.

## Cartografia stradale da pacchetti OSM

Le strade mostrate sull'ESP32 provengono dagli archivi `.motomap.zip` importati
nell'app. Le tile vengono lette direttamente dagli ZIP in base alla posizione GPS
e inviate tramite il protocollo BLE esistente, senza estrarre l'intero archivio.

La schermata **Gestione mappe** offre solo **Importa mappa ZIP** per le strade e
il download dei **Grafi Valhalla** per il routing. Il comando **Scarica strade del
percorso** e i precedenti download manuali della zona vicina (~8 km) e della
regione sono stati eliminati insieme ai relativi metodi di acquisizione.
Una mappa stradale .motomap e un grafo Valhalla .tar restano file distinti:
la prima disegna le strade, il secondo calcola svolte e itinerari.

Se non ci sono strade utilizzabili nella zona GPS, resta attivo il recupero
automatico e limitato di una tile OSM da Overpass. Non scarica corridoi o intere
regioni; è un fallback per le zone non coperte dai pacchetti, e può fallire per
limiti o indisponibilità del servizio pubblico. I file .motomap già salvati e
le cache legacy `*_parts` restano leggibili. Il valore `maxspeed` delle strade
non è una garanzia del limite legale vigente.

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

## Diagnostica delle strade

Se le strade non compaiono sull'ESP32, verificare che il pacchetto
`.motomap.zip` sia stato importato correttamente e contenga la zona GPS.
Il fallback automatico a una singola tile Overpass registra eventuali
errori con i tag Android `GatewayRoadTiles` e `NavigationVM`. Il suo
mancato funzionamento non impedisce di usare le strade già installate.

## Pacchetti stradali offline da .osm.pbf (prima versione)

Il file OSM PBF è un formato sorgente, non una mappa pronta per il rendering.
Preparare il pacchetto su PC Linux o Windows con Python e pyosmium:

```bash
python -m pip install osmium
python scripts/offline_map/pbf_to_motomap.py italy-nord-ovest-latest.osm.pbf -o nord-ovest.motomap.zip
```

Scaricare il `.osm.pbf` della regione desiderata dal catalogo
[Geofabrik](https://download.geofabrik.de/europe/italy.html).
Copiare il `.motomap.zip` sul telefono, aprire **Gestione mappe**
e premere **Importa mappa ZIP**.
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

## Opzioni di percorso

Nella schermata **Percorso** si apre **Opzioni percorso** per selezionare
**Rapido**, **Breve** o **Strade secondarie**. I profili usano il modello di costo
`auto`: Rapido mantiene le impostazioni precedenti, Breve imposta `shortest`
e Strade secondarie riduce la preferenza per autostrade e sterrati.
Sono disponibili anche le opzioni **Evita autostrade**, **Evita pedaggi**,
**Evita traghetti**. Valhalla può comunque attraversarli se necessario:
non si tratta di esclusioni assolute.

Nel profilo Breve, Valhalla ignora le penalizzazioni: i controlli Evita sono
temporaneamente disattivati. Le impostazioni sono persistenti sul telefono e
vengono usate nella preview, nel calcolo iniziale e nei ricalcoli del percorso.
Cambiare un'opzione ricalcola la rotta senza doverla cercare nuovamente,
annullando il precedente tentativo. Il motore continua a scegliere prima
Valhalla con grafo locale, poi quello di rete.

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

## Grafo di routing installato

**Gestione mappe → Grafo Valhalla → Importa grafo TAR / ZST.**
Il grafo deve essere stato scaricato sul telefono da una sorgente esterna.
L'app importa un file `.tar` o `.tar.zst`, decomprime quando necessario,
controlla la struttura TAR e cerca le tile `.gph`.
La vecchia lista delle regioni e il download diretto dal catalogo
sono stati rimossi. Percorso del grafo locale:
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

La schermata **Gestione mappe** offre **Importa mappa ZIP** per le strade e
**Importa grafo TAR / ZST** per il routing; non scarica grafi per regione. Il comando **Scarica strade del
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
2. Importare un grafo TAR / ZST, disattivare i dati, selezionare una
   destinazione nella zona coperta con pressione lunga: deve apparire
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

## Touring stradale e preferenza curve (prima versione)

Il profilo **Touring moto** usa ancora il modello Valhalla `auto`, compatibile
con il grafo Italia gia verificato: riduce la preferenza per autostrade
(`use_highways=0.15`), evita tracce sterrate (`use_tracks=0`) e imposta
`exclude_unpaved=true`. Le altre preferenze (pedaggi, traghetti, autostrade)
continuano ad applicarsi. Non e un profilo off-road e non promette una strada
interamente asfaltata se gli attributi OSM sono incompleti.

La nuova regolazione **Preferisci curve** (No, Leggero, Medio, Alto) chiede
fino a due alternative **nella stessa singola richiesta Valhalla** soltanto nel
profilo Touring e quando il valore e maggiore di zero. Il selettore
`RouteCurvatureSelector` misura la tortuosita effettiva delle geometrie
Valhalla `polyline6` e confronta eventuali alternative solo se rispettano
limiti di allungamento su tempo e distanza, piu permissivi con intensita alta.
Se il motore non propone alternative valide, rimane il percorso normale;
non vengono inventate strade, eseguite richieste aggiuntive o caricate mappe
panoramiche. L'opzione non e un algoritmo panoramico: per laghi, punti di vista
e paesaggi servira una futura analisi geografica.

La finestra delle opzioni ora e un **pannello inferiore** di altezza massima
320 dp, scorrevole, con profili su una riga orizzontale per **lasciare visibile
la mappa** nella parte alta. Il valore curve e salvato insieme agli altri
parametri, applicato alle anteprime e alle rotture/ricalcoli di percorso.

## Tappe intermedie ordinate

La schermata **Percorso → Tappe intermedie** mostra un pannello basso,
scorrevole (max 370dp) che lascia visibile la mappa. Si possono aggiungere
fino a otto tappe cercandole online con Photon, inserendo coordinate GPS
oppure premendo a lungo sulla mappa dell'anteprima. Le tappe possono
essere spostate su/giu o eliminate; le modifiche ricalcolano la rotta.

Il motore Valhalla locale (o l'eventuale fallback online) riceve
`locations` nell'ordine: origine GPS, tappe `type=break`, destinazione.
Le tappe `break` producono `legs` e istruzioni di arrivo e partenza:
il convertitore BLE esistente aggrega gia tutte le tratte, mantenendo
svolte e geometria stradale reale senza alcuna linea sintetica.

Durante la navigazione una tappa viene considerata raggiunta su una
posizione GPS filtrata entro 60 metri, una per aggiornamento. Quando
il navigatore ricalcola fuori percorso, invia soltanto le tappe non
ancora raggiunte e la destinazione. Tappe lontane non vengono
saltate automaticamente. Le tappe non sono riordinate dall'app.

Per itinerari multi-punto Valhalla non supporta il campo `alternates`:
si disabilita la ricerca delle due varianti in Touring, mantenendo
tutte le preferenze stradali attive. L'importazione del grafo,
il protocollo BLE e il firmware ESP32 non vengono toccati.

Il commit usa `[skip ci]` per non avviare alcuna build.

## Arrivo al punto finale (bandierina a scacchi)

Nella mappa Android la **destinazione finale** ha un indicatore
a scacchi distinto dalle tappe intermedie. Le tappe restano marcatori
di passaggio senza simbolo di fine viaggio.

Il nuovo `RouteArrivalDetector` accetta soltanto fix GPS gia filtrati,
a massimo 40m dalla destinazione, con distanza stradale residua non
superiore a 125m e senza tappe intermedie pendenti. Sono necessari
almeno due fix validi a distanza di 1,2 secondi; gli allontanamenti
e le interruzioni prolungate azzerano la conferma.

All'arrivo, Android mantiene aperta la navigazione e segnala
**Destinazione raggiunta** nella mappa; l'ESP32 riceve lo stato BLE
`arrived`, manovra `arrive`, zero metri residui e indicatore
d'arrivo a scacchi (rendering in LVGL). Il pulsante diventa
**Concludi percorso**. Il completamento non scatta sulle tappe
intermedie, non elimina automaticamente la rotta e non provoca
ricalcoli dopo l'arrivo. La grafica a scacchi su ESP32 richiede
il firmware aggiornato; il protocollo BLE resta invariato.

Sorgenti pubblicati con `[skip ci]`: nessuna build eseguita.

## Visibilita dei pacchetti stradali importati

I file `.motomap.zip` sono conservati **compressi** sotto
`files/maps/.osm_packages/`, mentre i vecchi file singoli
`.motomap` e `.json` si trovano sotto `files/maps/`.
La navigazione leggeva gia direttamente le tile negli ZIP, ma
`listSavedMaps()` controllava soltanto `files/maps/`: i pacchetti
venivano quindi nascosti dall'elenco, anche dopo l'importazione
corretta e a ogni riapertura dell'app.

Ora **Gestione mappe → Mappe stradali salvate** mostra entrambe
le categorie, con dimensione in MB e indicazione **Pacchetto ZIP
importato** o **File mappa singolo**. L'eliminazione di uno ZIP agisce
solo sull'archivio selezionato nella sottocartella; non elimina le
altre mappe ne i file Valhalla. I file temporanei `.part` e i
backup `.backup` non vengono presentati come mappe installate.

**Importante:** le mappe sono salvate nella memoria privata Android.
Una disinstallazione dell'app puo cancellarle fisicamente; una
semplice riapertura non le cancella. Prima di disinstallare,
conservare sempre una copia del pacchetto ZIP originale esterna
all'app, ad esempio nella cartella Download del telefono.

La correzione e pubblicata con `[skip ci]`, senza avviare alcuna build.

## Interfaccia senza download di grafi regionali

La sezione **Grafo Valhalla** mostra soltanto lo stato del grafo locale,
il comando **Importa grafo TAR / ZST**, l'eventuale avanzamento/
annullamento dell'importazione e **Rimuovi grafo installato**. Non chiama
più l'API del catalogo Librescoot né scarica archivi regionali.
La validazione TAR resta disponibile nel componente indipendente
`ValhallaTarValidator`, usato dall'importatore manuale.
Questa modifica non cancella o altera il grafo `Italy.tar.zst` già importato.

## Frecce di svolta e metri alla manovra (ESP32)

La vista navigazione del display 466×466 ripristina il simbolo di
direzione per svolta a sinistra/destra, deviazione leggera, inversione,
rotonda e bandiera a scacchi all'arrivo. Il codice delle frecce era
gia' presente ma non veniva mai associato a un oggetto LVGL.

Una nuova etichetta **Montserrat 28** in posizione centrata, appena
sotto il nome strada arcuato lungo il bordo superiore, mostra
`N m` oppure `N.N km` alla **prossima manovra**. La freccia
resta nella parte sinistra della mappa, lontana dal segnalino moto.
Il valore della **distanza residua alla destinazione** resta
invariato nell'area inferiore (y=250 in coordinate di progetto,
etichetta Montserrat 48 + unità).

Android ricava la prossima svolta dalla lista manovre Valhalla e
dai vertici reali `route_point_index`, misurando la distanza
**lungo la geometria**, anziché stimare l'angolo fra tre punti GPS.
Le manovre sono aggiornate all'avvio e dopo un ricalcolo di rotta;
non cambia il formato BLE. I display senza una rotta nascondono
freccia e distanza alla svolta. Test nativi e test Kotlin
aggiunti, da eseguire alla prossima build manuale.

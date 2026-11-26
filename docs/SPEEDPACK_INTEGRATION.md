# SpeedPack — integrazione Android / ESP32 (progetto, non protocollo attivo)

## Fase 1: importazione sperimentale
Android installa lo SQLite separato in filesDir/speedpack/speedpack-italy.sqlite,
con staging, limite 1 GiB, quick_check, controllo schema e sostituzione atomica.
MotoPack rimane a sei componenti. Nessun avviso attivato dall'importazione.

## Fase 2: associazione alla rotta (da implementare dopo analisi V1)
La sola vicinanza geometrica non basta. Valutare corrispondenza tra geometria
Valhalla e segmenti/way OSM, intersezioni, carreggiate parallele e livelli.
Separare affidabilità stradale, direzione e fonte. Non interpretare facingDeg
come senso di marcia. Direzione non nota non equivale a bidirezionalità
documentata. Non fondere postazioni in conflitto. Conservare solo candidati
davanti alla posizione sulla rotta, non la distanza in linea d'aria.
Per i Tutor occorre una tratta verificata, non un singolo punto.

Impostazione Android persistente, inizialmente disattivata, distinto consenso
per puntuali e futura velocità media. Nessun avviso quando disabilitati,
senza pack valido, fuori copertura o in giurisdizione non verificata.
Regole nazionali da verificare prima di attivare avvisi effettivi.
Cambio preferenza, arrivo, ricalcolo e perdita sessione invalidano lo stato.

## Fase 3: trasporto e rendering (proposta)
Il protocollo condiviso oggi ha NavigationSnapshot, RouteGeometry, MapScene e
capability fino al bit 8. Non riutilizzare i marcatori tappa: indice zero
significa arrivo e gli altri sono usati anche per skip. Proporre un messaggio
dedicato, con capability nuova, negoziata prima dell'invio; nessun cambio al
layout dei payload esistenti. Definire ID e codifica solo quando si implementa
l'intera catena Android/JNI/shared BLE/PhoneNavBridge.
Il messaggio deve identificare sessione, token e generazione rotta, contenere
stato esplicito vuoto/valido, distanza lungo rotta e coordinate nello stesso
riferimento geografico di RouteGeometry (conversione verificata), non pixel.
Scartare dati obsoleti, limitare numero marcatori e frequenza; snapshot
completo per riconnessione, invalidazione locale su link down e cambio rotta.
Nessun invio periodico di tutto lo SQLite all'ESP32.

PhoneNavBridge conserva lo stato sotto mutex; solo il render task passa i
dati a NavPresenter e LVGL. Il marcatore ambra è sulla linea verde, distinto
da tappe e arrivo; la proiezione deve seguire identico origin/zoom/heading
anche ad alta frequenza IMU. Riutilizzare lo spazio distanza manovra soltanto
quando non è già occupato da una manovra imminente. Non toccare badge limite,
distanza arrivo, zoom, skip, radio e spegnimento. Nessun suono in questa fase.
Soglie di distanza da definire con test, non introdotte nel firmware.

## Collaudo richiesto
Import valido/corrotto/annullato/sostituzione/riavvio; associazioni a strada
parallela, sovrapposta, incrocio, rotta ad anello e ricalcolo; direzione
sconosciuta; disattivazione immediata e riconnessione; compatibilità con
firmware/app precedenti, marker e priorità manovra nel simulatore,
performance/heap ESP32, verifica su strada in condizioni lecite.

## Implementazione diagnostica iniziale (26/11)
L'adapter Valhalla attuale converte la shape senza way ID. Implementato matcher geometrico locale, massimo 8 m e 20 gradi, segmenti fino a 250 m, vincoli direzionali conservativi, solo indizi di strada selezionata e non grade. Toggle separato, default off; log conteggi senza coordinate. Non è un sistema di avvisi: non stabilisce identità di way, non deduplica sorgenti, non applica ancora norme nazionali. Occorre un'associazione verificabile Valhalla/OSM prima del trasporto BLE.

## Riscontro way ID offline sperimentale
Valhalla Mobile 0.6.6 espone traceAttributesRaw. Implementato riscontro diagnostico edge_walk su finestre di massimo 8 segmenti/400 m, massimo 16 richieste per itinerario, actor esistente e stesso costing. La shape restituita deve corrispondere punto per punto (1e-6 gradi); gli span edge devono coprire i segmenti senza conflitto. Confronto way ID nel segmento candidato, non semplice appartenenza a un insieme. Se fallisce, resta non verificato; non effettuare map_snap. Rischio nativo da validare sul telefono; non eseguire trace dell'intero itinerario, nessun invio BLE. Way coincidente non dimostra lo stato della postazione e non è ancora sufficiente per un avviso.

## Anteprima Road DNA
Motore diagnostico condiviso con navigazione; stato per record/passaggio, sorgente e coordinate originali, limite dichiarato e chainage. Scheda e mappa separate dai POI turistici, toggle comune default off, filtri per stato, cap 250 marker per filtro. Cambi rotta/disattivazione invalidano la preview. Nessuna fusione né conferma di attività; nessun invio al firmware in questa fase. Collaudare way e geometrie su percorsi noti prima del trasporto.

## Diagnostica copertura
Anteprima soltanto: query di tutti gli spot originali OSM/MapAtlas, corridoio 50 m e marker blu per passaggi non rappresentati dal matcher selettivo (stessa fonte/ID entro 100 m di chainage). Sottodivisione spaziale dei segmenti lunghi senza modificare la shape. Nessun fallback nel motore di navigazione e nessuna promozione di vicinanza a identità stradale. Serve a distinguere copertura e filtri; non garantisce assenza di falsi positivi o negativi.

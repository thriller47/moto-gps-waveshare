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

Aggiornamento copertura sperimentale: raggio anteprima 500 m, proiezione minima per record sorgente sull'intera shape; il campo originalDistanceMeters è distinto dal crossTrack della proiezione stradale. Conteggi raw_scanned/raw_within_500m e paginazione 250. Nessun cambio al matcher stretto o alla navigazione; il raggio diagnostico non è una soglia di avviso.

## Revisione raw-first (26/11)
L'anteprima individua record originali entro 500 m; per quelli entro 50 m legge tutte le way in road_evidence e le confronta sul segmento della shape più vicino al record originale, senza prefiltrare road_state/grade/direzione. Il riepilogo è per record sorgente; tutte le alternative sono nel dettaglio. ID coincidente non è una decisione di avviso. Se un bucket supera 400 m prova singoli segmenti compatibili, sempre max 16 richieste e shape/span verificati. Non risolve ancora tutti i passaggi in tour o le ambiguità al nodo tra segmenti. Matcher selettivo attivo invariato; firmware e protocollo invariati. Test sintetici 1 m e più way aggiunti, non eseguiti; causa storica dello zero non provata senza shape e log.

## Piano ombra concordato
26/11: utente conferma Dalmazzi ambra e sceglie di non escludere way coincidenti per direzione dichiarata. Android usa raw-first in preview e navigazione, verifica way offline, deduplica solo coppie reciproche già presenti in cross_source_hints, stessa way e distanza originale/chainage <=20 m, senza alterare fonti. Piano di avvisi potenziali e log prossimo candidato; non è ancora un avviso operativo. Il limite di 16 trace e i passaggi ravvicinati restano limiti sperimentali. BLE/ESP32 e verifica territoriale prima di attivare avvisi effettivi ancora pendenti.

## Trasporto e UI sperimentali (26/11)
SpeedAlert 0x15 capability bit9, payload rev1 24 byte: session u32, route token u32, generation u32, valid u8, distance u16, lat/lon s32 E6; clear valid=0, distanza e coordinate zero. Android latest-only, refresh 750 ms, stessa sessione/route, clear su stop/disabilitazione, stale, reroute; ESP32 scadenza 2500 ms verificata su snapshot/heartbeat, render task unico, proiezione condivisa, marker autonomo e priorità manovra 200 m. Toggle display separato default off, esclusivamente admin IT nella finestra trace offline (richiede dati amministrativi). Geofence della posizione GPS non ancora implementato, attivare solo in Italia. Fonti statiche, non certifica attività; riferimento giuridico Italia Cassazione 3853/2014 e art.45 CdS: https://rivistagiuridica.aci.it/documento/uso-di-dispositivi-che-individuano-le-postazioni-di-controllo.html . Test su dispositivo e simulatore pendenti, Tutor escluso.


## Aggiornamento 27/11 — contesto mobile Android

Il percorso attivo non usa più l'analisi globale Road DNA. Cache statica geografica legata al file installato, finestra locale legata alla chainage Valhalla (2 km davanti, 25 m dietro), refresh ogni 400 m e reset immediato per nuova geometria; verifica immediata 850 m e poi estensione. Trace esatte memorizzate solo per la stessa istanza di rotta, fallback conservativo a singoli segmenti se la finestra multipla non verifica la shape. I candidati restano raw-first, way coincidente e admin IT per BLE, deduplica reciproca e 550 m invariati. Nessun nuovo tipo di messaggio o marker fuori rotta. Il limite di 16 tentativi per fase e il motore nativo sincronizzato sono ancora vincoli sperimentali. Log contesto con tempi e numero di chiamate native; test sintetici aggiunti ma non ancora eseguiti.

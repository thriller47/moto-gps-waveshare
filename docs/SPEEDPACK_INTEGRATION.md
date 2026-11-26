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

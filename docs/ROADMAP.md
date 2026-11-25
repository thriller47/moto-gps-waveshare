# Moto Chat GPS — Roadmap operativa

**Aggiornata:** 25 novembre 2026 · **Integrazione:** `valhalla` · **Branch attivo:** `NewUI`.

Stati distinti: implementato, collaudato dall'utente, da verificare, da sviluppare. Per i riferimenti CI e i dettagli correnti consultare [PROJECT_STATE.md](../PROJECT_STATE.md); per le procedure [MANUALE_OPERATIVO.md](MANUALE_OPERATIVO.md). La [roadmap del 21 novembre](archive/ROADMAP_2026-11-21.md) conserva le decisioni storiche, non rappresenta lo stato corrente.

## 1. Consolidamento NewUI — da verificare

- Utente conferma MotoPack v4 generato e importato (4,07 GB riferiti); sei componenti: MotoMap v4, Valhalla V6, Photon, DEM, DNA turistico e DNA stradale.
- Costing motorcycle nativo Touring/Panoramico, livelli curve 0–3: screenshot utente di giro 200 km mostrano geometrie differenti; qualità e tempi su strada da misurare. Verificare anche tappe e ricalcoli.
- Explorer: tre proposte differenziate confermate dall'utente; verificare salvataggio, avvio, tappe obbligatorie e assenza DEM.
- Nuova UI: ultimo commit efc4857 rimuove oscuramento anteprima, aggiorna descrizioni e permette importazione individuale di tutti i sei componenti. Verificare build, stati dopo riavvio, import DEM/turistico e annullamento.
- ESP32: centro freccia alla base collaudato positivamente dall'utente; ritaglio linea verde 7 px da verificare. Pausa caffè, spegnimento e riconnessione BLE già collaudati dall'utente; ripetere regressioni dopo pulizia firmware, inclusi radio, skip tappa, heap e warning.
- Ultima CI indipendentemente verificata: 72ce8d7 success; non valida i commit successivi. Una sola CI consolidata native/Android/ESP32 al termine, merge in valhalla solo con approvazione esplicita.

## 2. Prossimo sviluppo: autovelox — priorità alta

**Decisione utente 25/11:** gli avvisi autovelox devono apparire direttamente sul display ESP32. Non limitare la funzione al telefono. Nessuna implementazione ancora effettuata.

### Fase A — inventario PBF e qualità

- Analizzare localmente in WSL `C:\MotoGPS\PBF\italy-latest.osm.pbf`, senza Overpass: nodi `highway=speed_camera`, relazioni `type=enforcement`, `enforcement=maxspeed` e `enforcement=average_speed`, ruoli dei membri, direzione, limiti, riferimenti alle strade e copertura geografica.
- Contare oggetti, duplicati, attributi mancanti e campioni; stimare dimensione dell'indice. Distinguere dati realmente presenti da tag teorici. Non dedurre che una postazione sia attiva o completa solo perché mappata.
- Valutare indice spaziale offline leggero e importazione separata/MotoPack futuro dopo misure. Non modificare ancora il formato v4. Verificare normativa applicabile nei paesi interessati e prevedere disattivazione degli avvisi.

### Fase B — Android e protocollo

- Associare le postazioni alla geometria e alla progressione lungo rotta, direzione e carreggiata quando identificabili; evitare falsi avvisi da strade parallele, cavalcavia, senso opposto e postazioni già superate.
- Distinguere distanza lungo rotta da distanza in linea d'aria. Gestire ricalcoli, anelli, GPS incerto, deduplica, annullamento e scadenza evento.
- Progettare evento BLE compatto e versionato; verificare compatibilità Android/ESP32, budget e priorità rispetto a geometria, tappe e arrivo. Nessuna query continua esterna durante la guida.
- Tutor/velocità media in fase successiva solo con dati sufficienti per ingresso, uscita, direzione e tratto; non inventare segmenti o limiti.

### Fase C — avviso ESP32

- Requisito confermato: avviso visibile sul display. Proposta da concordare: simbolo riconoscibile, distanza progressiva, limite se noto, notifica non invasiva che non copra la linea verde, distanze o arrivo; non ripristinare frecce di svolta.
- Soglie, posizione, colori, durata e precedenza da decidere con utente e simulatore; nessuna soglia già approvata. Dati incerti non presentati come certezza, nessuna promessa di rilevamento completo o in tempo reale.
- Test sintetici e replay: direzioni opposte, strade parallele, passaggio, ricalcolo, rotonda, GPS perso, BLE riconnesso, sovrapposizione con tappa/arrivo, dati mancanti. Verifica su strada in sicurezza.

## 3. Audit IMU e zoom — priorità alta, in parallelo quando possibile

- QMI8658: montaggio centrale quasi orizzontale, accensione sul cavalletto laterale, raddrizzamento, rettilineo, pieghe 15/30/45°, transizioni, frenata, vibrazioni, perdita/ripresa GNSS. Test sintetici coerenti a sei assi e misure di yaw, verticale, bias, deriva e convergenza prima di cambiare la fusione. Non assumere difetto già provato; i collaudi dinamici sono stati soprattutto in auto.
- Riferimenti: motion_heading_sensor.cpp, motion_heading_axis.hpp, motion_heading_fusion.hpp, phone_nav_bridge.cpp e test nativi motion_heading_fusion_tests.cpp. Replay IMU grezzo nel simulatore solo se necessario; non è già garantito.
- Zoom predittivo: rotonde, tornanti, U-turn e svincoli; mantenere comportamento già validato, intervenire su casi riproducibili. Nessuna freccia di svolta.
- Autonomia: misurare navigazione, standby e pausa caffè (30 s, timeout 1–30 min); ottimizzare solo dopo misure.

## 4. Servizi lungo rotta — da sviluppare

- Estendere la ricerca per categoria già presente (carburante, ristorazione, bar, alloggi) a distributori lungo percorso, soste, officine moto, parcheggi, acqua/toilette.
- Distanza effettiva della deviazione sulla rete, direzione e accessibilità; scelta esplicita come tappa, niente icone superflue sul display ESP32.
- Verificare parità Photon offline/online, ricerca indirizzi omonimi, categorie/marchi, GPS assente, rete assente, destinazioni estere. Non reintrodurre Overpass.
- Inventario OSM aggiuntivo: surface, smoothness, tracktype, motorcycle e conditional, maxspeed conditional, incline, lit, hazard, traffic_calming, barriere e passaggi a livello. Attributi assenti restano sconosciuti.

## 5. Garage itinerari e GPX — da sviluppare

- Estendere salvataggio/ripresa tour esistente a raccolta di percorsi, preferiti, cronologia, classificazione Road DNA e riutilizzo.
- Import/export GPX, distinguendo itinerario pianificato, traccia realmente percorsa e replay diagnostico. Definire conservazione geometria, tappe, ricalcolo e comportamento offline.

## 6. Road DNA ed Explorer — evoluzione, non riscrittura

- DNA stradale, turistico, DEM e Explorer già implementati nel v4; collaudare qualità e prestazioni prima di ampliare.
- Migliorare selezione tratti, curve, fondo, passi, belvedere, soste e interesse naturale/storico. Verificare copertura reale PBF, deduplicazione e accessi stradali; non usare centroidi arbitrari come destinazioni.
- Interesse turistico non equivale a panorama visibile. Mantenere metriche con copertura/confidenza, distinguere stime da dati misurati. Valutare provider online opzionali solo se utili, non obbligatori per l'offline.
- Specifiche storiche: [ROAD_DNA_DESIGN.md](ROAD_DNA_DESIGN.md), [ROAD_DNA_DATA_SOURCES.md](ROAD_DNA_DATA_SOURCES.md), [PHOTON_ROAD_DNA_REVIEW.md](PHOTON_ROAD_DNA_REVIEW.md); le proposte pre-v4 non descrivono il codice attuale.

## 7. Navigazione internazionale — da verificare e migliorare

- Test Italia/Francia con copertura mista MotoMap/OpenFreeMap, routing online fuori grafo italiano; nessuna promessa di routing offline estero senza grafo.
- Verificare rotonde OpenFreeMap (fallback geometrico OFM_RING, replay a sei bracci), strade grigie e isteresi, download esplicito corridoio 7 tile e ricalcoli.
- Limiti esistenti 3000 tile/50 MiB: valutare pianificazione per sezioni o regioni, ripresa e stima spazio/rete; verificare tetto prima dell'esclusione tile già coperte.

## Già implementato — non riproporre come novità

Navigazione offline Italia, MotoPack v4, Valhalla V6 e costing motorcycle, quattro livelli curve, MotoMap, Photon e ricerca POI, DNA turistico/stradale e altimetria, Explorer, tour circolari, tappe e skip, salvataggio/ripresa tour, simulatore WASM, diagnostica ZIP, pausa caffè, spegnimento, BLE/radio, nuova UI Android e pulizia firmware. Lo stato implementato non equivale a collaudo conclusivo.

## Regole

Un solo ramo stabile valhalla; NewUI attivo fino a collaudo e autorizzazione al merge. Una sola CI consolidata per serie di modifiche; nessuna build per documentazione. Priorità proposta: consolidare NewUI, inventario e autovelox, servizi lungo rotta, garage/GPX, evoluzione Road DNA, internazionale; audit IMU/zoom/autonomia in parallelo. Il prossimo lavoro funzionale richiesto è autovelox su ESP32.

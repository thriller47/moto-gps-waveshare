# Moto Chat GPS — Roadmap operativa

**Aggiornata:** 21 novembre 2026 · **Integrazione:** `valhalla` · **Sviluppo e verifiche:** `test/coffee-pause-20261120`.

Questa roadmap distingue **da verificare**, **da sviluppare** e **già implementato**. Non equivale a un esito dei test. Lo stato corrente e i riferimenti CI sono in [PROJECT_STATE.md](../PROJECT_STATE.md); le procedure sono nel [manuale operativo](MANUALE_OPERATIVO.md).

## Modalità Pausa caffè — TIMER COLLAUDATO, RICONNESSIONE DA VERIFICARE

**21/11/2026:** primo collaudo utente positivo per spegnimento alla scadenza; prima della scadenza l'ESP32 torna operativo con connessione manuale da Android. Riconnessione automatica mancante. Introdotto recupero Android tramite `autoConnect` sul dispositivo già noto, con finestra di 30 s, scansione filtrata alternativa e watchdog handshake di 20 s. Nessuna modifica al firmware o alle soglie. Nuova prova richiesta (prima con app visibile, poi con telefono bloccato); se il problema persiste acquisire diagnostica Android/ESP32. Nessuna build avviata per questa correzione.

**20/11/2026:** riconnessione Android dopo allontanamento verificata con successo dall'utente. Aggiunta sull'ESP32 policy di risparmio dopo 30 s senza BLE (luminosità OLED a zero, task IMU e interpolazione meno frequenti, NimBLE resta connesso/advertising), risveglio al nuovo BLE e spegnimento dopo limite **configurabile 1–30 minuti da Android (10 default)**. Comando BLE opzionale `PowerPolicy (0x23)` senza modificare i frame Heartbeat e NavigationSnapshot; mantiene autospegnimento all'arrivo (120 s) e pulsante (3 s). Test di policy, codec e bridge aggiunti ma **non eseguiti in CI**, nessun merge autorizzato per questa fase. Verificare su ESP32 reale: OLED, BLE, risveglio, scadenza, consumi, eventuale USB alimentato, nuovi timeout trasmessi per ogni sessione.

## Prossimo sviluppo dopo Pausa caffè — inventario contenuti OSM del PBF italiano (DA FARE)

**Richiesta del 20/11/2026:** prima di introdurre nuove funzioni, inventariare **i dati effettivamente presenti** in `C:\MotoGPS\PBF\italy-latest.osm.pbf`, verificando tag, quantità e copertura geografica. Distinguere chiaramente ciò che il PBF contiene, ciò che gli strumenti di generazione esportano e ciò che MotoMap v4, Valhalla V5 e Photon v2 rendono già disponibile all'app. Non presumere che la presenza di un tag nel dizionario OSM garantisca copertura o qualità nel file italiano.

**Categorie da misurare** (con conteggio oggetti, esempi, frequenza degli attributi e dimensione dell'indice):
1. **Autovelox fissi e velocità media/Tutor:** `highway=speed_camera`, `enforcement=average_speed`, direzione, strade associate, limiti di velocità; attenzione a dati incompleti, postazioni non attive e controlli della carreggiata opposta.
2. **Fondo stradale e uso motociclistico:** `surface`, `smoothness`, `tracktype`, `motorcycle`, `motorcycle:conditional`, `maxspeed:conditional`, `incline`, `lit`; potenziale evoluzione dei profili Touring/Panoramico con classificazioni prudenti e senza inventare informazioni mancanti.
3. **Sicurezza e manovre:** `hazard`, `traffic_calming`, passaggi a livello, barriere, `turn:lanes`, restrizioni di svolta.
4. **Servizi moto e soste:** `amenity=fuel`, `shop=motorcycle`, `amenity=compressed_air`, `amenity=motorcycle_parking`, `amenity=cafe`, `amenity=restaurant`, `highway=rest_area`, acqua e toilette.
5. **Turismo motociclistico:** `mountain_pass=yes`, `tourism=viewpoint`, parcheggi e punti di sosta panoramici lungo la rotta.

**Metodo:** elaborazione locale del PBF in WSL tramite script riproducibile e report tabellare; nessuna chiamata Overpass. Prima inventario e valutazione di copertura/qualità, poi scelta di indici leggeri da aggiungere alla rigenerazione MotoPack. Sul telefono usare indici spaziali e filtro per **distanza dal percorso e direzione di marcia**; verso ESP32 trasmettere soltanto eventi/avvisi compatti, deduplicati e pertinenti, preservando budget BLE e leggibilità del display. Non trasformare dati statici OSM in falsi avvisi in tempo reale.

**DNA percorso / Road DNA — concetto da sviluppare dopo l'inventario:** impronta motociclistica **descrittiva** di ciascun itinerario, soprattutto per confrontare e classificare i tour salvati, non un nuovo motore di scelta della rotta né un sinonimo di curviness. Costruire dapprima indicatori dalla geometria disponibile (densità/intensità curve, continuità/scorrevolezza, ripartizione delle categorie stradali); arricchirli soltanto dove i dati risultano realmente presenti e attendibili (superficie e qualità del fondo, passi, punti panoramici, pendenze se dotate di dati altimetrici credibili, soste/servizi). Esplicitare per ciascuna metrica la **copertura dei dati**; i tratti non mappati restano sconosciuti, non diventano automaticamente asfaltati o panoramici. Presentazione compatta del tipo "Montagna / Touring / Rapido" come **esempio illustrativo, non classificazione implementata**. Prima verificare dati e definire formule verificabili; eventuale suggerimento di percorsi in base al DNA sarà una fase separata, non promessa dall'inventario. Nessuna nuova funzione o build autorizzata.

**Ordine preferito dopo l'inventario:** (1) autovelox fissi, poi Tutor se geometrie/direzioni sufficienti; (2) qualità del fondo e restrizioni moto per Touring; (3) distributori lungo rotta; (4) passi e belvedere; (5) parcheggi moto/officine. **Nessuna implementazione o build autorizzata da questa nota**: avviare dopo verifica della Pausa caffè e conferma dell'utente.

## Priorità 1 — Audit IMU QMI8658 per uso in moto (DA TESTARE)

**Motivazione:** i collaudi dinamici finora sono stati soprattutto in automobile. Sulla moto il Waveshare sarà **centrato sul manubrio, con display quasi orizzontale**; seguirà rollio e vibrazioni della moto. L'accensione potrebbe avvenire **sul cavalletto laterale**, con moto già inclinata. L'algoritmo attuale non deve confondere orientamento di montaggio, gravità e rotazione direzionale durante piega e uscita dalla curva.

**Stato reale al 19/11:** ispezione preliminare del codice effettuata, **nessuna suite motociclistica creata, nessuna simulazione IMU a sei assi eseguita e nessuna prova su moto**. Non dichiarare che la proiezione yaw corrente sia errata: serve misurarla.

**Codice da verificare:**
- `platforms/esp32/main/motion_heading_sensor.cpp`: campionamento QMI8658 a ~125 Hz, calibrazione all'avvio (~120 campioni), stima gravitazionale, aggiornamento lento condizionato da accelerazione/giroscopio e compensazione bias.
- `platforms/esp32/main/motion_heading_axis.hpp`: proiezione del vettore giroscopico su asse gravitazionale stimato; segno della direzione.
- `platforms/esp32/main/motion_heading_fusion.hpp`: integrazione heading, deadband, bias, ancoraggio con direzione GNSS e qualità del fix.
- `platforms/esp32/main/phone_nav_bridge.cpp`: consegna campioni IMU e aggiornamenti di heading alla UI.

**Sequenza dei test nativi, senza toccare il comportamento di navigazione:**
1. Avvio statico con moto inclinata sul cavalletto laterale (~10–20°) e successivo raddrizzamento; verificare che la verticale non resti ancorata alla postura di parcheggio.
2. Primo rettilineo stabile: verificare convergenza lenta della stima gravitazionale **solo quando dinamica e GNSS rendono attendibile la misura**; non assumere che rettilineo significhi rollio esattamente nullo (vento laterale, pendenza trasversale).
3. Piega 15°/30°/45°, ingresso/uscita e transizione destra-sinistra: controllare yaw vero vs yaw stimato, senza confondere rollio e beccheggio con svolte.
4. Curva a raggio costante, tornante, frenata/accelerazione, vibrazioni manubrio e asperità: misurare errori, eventuali falsi heading e apprendimento improprio del bias.
5. Perdita temporanea GNSS e ripristino: verificare deriva, correzione progressiva e assenza di salti della mappa.

**Misure e criteri di lavoro:** salvare per ogni scenario il riferimento teorico, rateo yaw grezzo/corretto, verticale stimata, bias, heading integrato e ancore GNSS; calcolare errore angolare medio/massimo e tempi di convergenza. Definire soglie di accettazione dopo i primi risultati, non inventarle a priori. Riprodurre segnali sintetici coerenti sui tre assi (`accelX/Y/Z`, `gyroX/Y/Z`), non soltanto angoli ideali; distinguere moto ferma sul cavalletto, andatura costante e accelerazioni dinamiche.

**Metodo:** prima aggiungere test nativi C++ mirati (estendere `tests/native/motion_heading_fusion_tests.cpp` e creare test sull'asse/sensore se necessario). **Solo se emerge un difetto grafico**, estendere il simulatore LVGL/WASM al replay IMU grezzo; quello attuale registra soprattutto GPS/heading e **non riproduce già la QMI8658 a sei assi**. Infine confrontare su strada **in moto, quando le condizioni lo permettono**, conservando il GPS come riferimento autorevole per routing e progressione. Eventuali correzioni al firmware saranno separate e giustificate dalle misure.

**Vincoli:** nessun nuovo sensore o modifica BLE/MotoPack per l'audit; non introdurre subito quaternion/filtro complesso senza evidenza; non modificare `valhalla`; **non avviare build finché non richiesto**.

**Prossima azione:** preparare i test nativi di montaggio/inclinazione e la strumentazione numerica, senza cambiare i guadagni dell'attuale fusione.

## Ricerca POI online allineata ai tag OSM offline — DA TESTARE

**20/11/2026:** la ricerca generica online ora usa Photon `/api` con filtri `include=osm.*` e bbox locale, senza ricerca testuale del nome (`q`); cerca 15/50/150 km e verifica categoria OSM nelle risposte. Il testo normale continua ad usare Photon FTS. Verificare su APK `trattoria`, `bar`, `hotel`, `distributore`, assenza GPS, dati fuori Italia e risposta server 400 per filtro non disponibile. Non ripristinare Overpass, non mostrare risultati fuori zona se il filtro online fallisce. Nessuna nuova build effettuata.

## Priorità 1 — Parità del contesto stradale OpenFreeMap / MotoMap (DA TESTARE)

**Motivazione:** prova online del 20/11: tile scaricate correttamente (99/99) ma rotonde spezzate e strade grigie che cambiano bruscamente. La versione MVT di OpenFreeMap non include identificativi OSM originali dei nodi, way o tag di giunzione; il selettore MotoMap non può comporre i suoi anelli topologici. **Intervento sperimentale:** `OpenFreeMapRoundaboutGeometry.kt` individua soltanto circuiti chiusi per coincidenza conservativa di estremi e con >=3 bracci; `NavigationViewModel` usa il fallback solo se il focus topologico originale è assente. Si riutilizza il normale budget BLE e si registra `OFM_RING`. Test sintetici aggiunti; **non ancora eseguiti**.

**Colloqui necessari:** compilazione/test Android; replay della rotonda a 6 bracci; prova con dati reali OpenFreeMap e cambi ingresso; verifica che non vengano riconosciuti come anelli isolati quadrati, strade a livelli diversi o svincoli. Il resto delle strade grigie non gode ancora di isteresi: affrontare comparsa/scomparsa solo con misure sul replay e senza toccare la qualità già collaudata del contesto MotoMap. Non promettere parità completa prima delle prove.

## Priorità 1 — Indice OSM POI integrato nel MotoPack (IMPLEMENTATO, DA VALIDARE)

**Aggiornamento ricerca carburanti 20/11:** rimossa la scorciatoia FTS a marchi `FuelStationSearch`; «distributore», «benzinaio» e «stazione di servizio» usano `poi_places.category=fuel` con il nuovo MotoPack e GPS disponibile. La ricerca di marchi singoli è normale ricerca Photon per nome, senza espansioni. Alias documentati nel manuale; test Android aggiornati ma non ancora eseguiti.


**Aggiornamento 20/11/2026:** implementato `scripts/offline_map/add_poi_to_photon.py`, richiamato dalla pipeline WSL esistente. Il PBF italiano viene letto una volta per nodi e way POI, e i risultati vengono memorizzati nella stessa `photon-italy-v2.sqlite` in `poi_places` (categoria, sottotipo, nome, marchio e posizione), indicizzata per categoria/latitudine/longitudine. Tre componenti e formato MotoPack invariati. `motopack.py` controlla integrità dell'indice opzionale; i vecchi pacchetti v2 non vengono rifiutati. Lato Android `OfflinePoiCategorySearch` interroga i POI vicini per parole generiche carburante, ristoranti/trattorie, bar/caffetterie e alberghi/hotel/motel; `NavigationViewModel` mantiene il comportamento della ricerca per nome. Test Python sintetici e JUnit aggiunti, **ancora da eseguire**.

**Validazione necessaria:** confermare pyosmium sul PC WSL; testare scansione del vero `italy-latest.osm.pbf` (dimensione e durata) con indice temporaneo su filesystem Linux, SQLite finale e verifica MotoPack; installare nuovo APK e nuovo pacchetto, testare `La Pergola` come ristorante cercando «ristorante», altri nomi non descrittivi, varianti, deduplica, tempo delle query e coordinate in città/montagna. **Non dichiarare completa l'implementazione prima di questo collaudo.** Il metodo per POI prevede ricerca *vicino alla posizione GPS*, non ricerca lungo tutta la rotta. Nessun bisogno di quarto file né di nuovo bundle; fuori Italia serve PBF/indice del Paese relativo.

## Priorità 2 — Miglioramenti POI da valutare

La ricerca per **categoria OSM** è già implementata nel branch test per distributori, ristorazione, bar e alloggi. Restano da validare dati reali e prestazioni su MotoPack rigenerato. Evoluzioni possibili, non ancora implementate: ricerca di POI lungo la rotta (non soltanto vicini al GPS), categorie più dettagliate e filtro per denominazione/città combinati. Non ripristinare liste statiche di marchi come sostituto di `amenity=fuel`.

## Priorità 1 — Ricerca Photon locale e fallback online (IMPLEMENTATA, DA RICOLLAUDARE)

Nel branch di test sono presenti: fallback a Photon online quando la ricerca SQLite v2 restituisce zero risultati; ranking offline con preferenza geografica; ricerca online fino a 30 candidati e ranking nell'app fino a 8 risultati; distanze in linea d'aria per risultati di destinazioni e tappe quando il GPS è disponibile.

**Verifiche pendenti:** nuova ricerca "Via Roma" dalla stessa posizione, "Via Roma, Torino", destinazione estera assente dal DB Italia, POI di marca vs parola generica, GPS non disponibile, assenza rete, consistenza fra classificazione Photon online/offline. Verificare compilazione e test del **commit finale di codice**; una CI verde su commit precedente non valida i successivi `[skip ci]`. La prova utente precedente conferma solo il miglioramento iniziale dell'offline, non il ranking online nuovo.

**Limiti da non mascherare:** massimo 30 candidati recuperati dall'endpoint online e 4.000 nella preselezione SQLite possono ancora perdere località se il servizio/indice non le restituisce. Non promettere ricerca geografica esaustiva dei distributori senza indice POI per categoria/prossimità.

## Priorità 1 — Strade grigie senza MotoPack (FIX APPLICATO, DA TESTARE)

**Test su strada 20/11, volutamente online senza MotoPack:** Valhalla online ha prodotto una rotta valida, ma il contesto è rimasto `OSM_CONTEXT segments=0` e il dialogo per scaricare il corridoio non è apparso. Nel codice `MainActivity` non passava `onCorridorTileCount` e `onDownloadCorridor` alla `NavigationScreen`: i default `{ 0 }` / `{ }` impedivano la proposta e l'azione. **Ripristinato il collegamento** nel commit `73b795c` del branch test.

**Verifica pendente:** con MotoPack assente e connessione disponibile, calcolare la rotta, premere Avvia, verificare il dialogo «Prepara strade OpenFreeMap» con il conteggio; scegliere «Avvia e scarica» e controllare eventi `OFM_COVERAGE`, `OFM_CORRIDOR` e comparsa delle strade grigie anche in movimento. Verificare anche «Avvia senza download», annullamento e route con tile tutte già in cache. Non scaricare implicitamente mentre si guida; serve azione esplicita. **Nessuna build lanciata.**

## Priorità 1 — Corridoio OpenFreeMap a 7 tile (IMPLEMENTATO, DA COLLAUDARE)

**Modifica 20/11 sul branch test:** allargata la fascia di raccolta tile z14 da **3 a 7 tile complessive** (tile centrale + 3 per lato) per lasciare margine in caso di deviazioni. La logica continua a contare le tile una sola volta, a sottrarre le tile MotoMap Italia/OpenFreeMap già presenti e a chiedere consenso prima del download. Restano **3.000 tile pianificate e 50 MiB massimi di download**, senza download automatico a tranche. Test JUnit specifico per impronta 7×7 aggiunto, **non eseguito**.

**Da verificare:** anteprima e tempi di calcolo con MotoMap Italia completo, itinerari curvilinei e misti Italia/Francia, disponibilità delle strade fuori rotta a seguito di ricalcolo, comportamento quando la nuova fascia supera 3.000 tile. Limite noto: il tetto tile è applicato durante la pianificazione **prima** di escludere la copertura già installata; verificare se provoca rifiuti inutili su rotte lunghe e affrontarlo con una modifica separata, senza eludere i limiti del servizio.

## Priorità 1 — Itinerari internazionali con copertura mista (LOGICA ESISTENTE, DA TESTARE)

**Scenario:** Italia con MotoMap v4 e Valhalla V5 installati → gita in Francia. La pianificazione della copertura del corridoio OpenFreeMap considera l'intera geometria della rotta, sottraendo solo le tile già in cache o coperte dal MotoMap; **non presume che una mappa italiana copra il tratto francese**. Aggiunto test JUnit `OpenFreeMapMixedCoverageTest.kt` per un itinerario rappresentativo, porzione coperta, porzione mancante e cache parziale; **test non eseguito**.

**Verifica pratica:** con MotoMap Italia installata, rotta con partenza italiana e destinazione francese; controllare che siano proposte soltanto tile mancanti oltre copertura, che le grigie continuino senza interruzione e che il routing ripieghi sul Valhalla online dove il grafo Italia non copre la Francia. Se internet assente e non esiste grafo Valhalla francese, non promettere routing offline oltre confine: OpenFreeMap non contiene il grafo Valhalla.

**Limiti da risolvere per lunghi viaggi:** `OpenFreeMapRouteCorridor.plan` rifiuta oltre 3.000 tile, download massimo 50 MB. Valutare scaricamento per sezioni o regione estera distinta, con preventivo spazio/rete e ripresa, **prima** di promettere una copertura fino a destinazioni molto lontane. Verificare anche la reattività del conteggio in anteprima: `missing()` legge gli indici degli archivi in modo sincrono.

## Priorità 1 — Ultima integrazione Android/ESP32 (DA COLLAUDARE)

Sul branch `test/waypoint-markers-20261119`: waypoint gialli numerati e arrivo `A` blu sulla rotta, flag a scacchi ridotta, pulsante ESP32 blu `>>` per saltare la prossima tappa con doppio tocco, distanza alla svolta sotto quella all'arrivo; hardening BLE/GATT, radio, IMU/bias e pacing display. Il salto tappa prima degli ultimi interventi è stato **collaudato positivamente dall'utente**; l'intero hardening e i commit Photon successivi **non hanno ancora un collaudo hardware complessivo confermato**.

**Verificare prima del merge:** un'unica CI consolidata finale native/Android/ESP32, quindi ESP32+telefono con BLE, comando radio, pressione PWR 3 s, heading/GPS, tappe/arrivo e distanze; controllo dei filtri workflow perché il job native non deve essere saltato. Conservare rollback e non dichiarare verde un job `skipped`.

## Priorità 2 — Estensioni da valutare, non già implementate

- Ricerca POI geografica lungo rotta (distributori e punti panoramici): verificare categorie disponibili in Photon v2 e dati OSM del PBF; non assumere che una ricerca di "Eni" provi un indice `amenity=fuel` completo.
- Modalità sosta/risveglio a basso consumo ("pausa caffè"): **implementata in test, da collaudare su hardware e misurare nei consumi** (vedi sezione iniziale).
- Esportazione/importazione GPX e traccia GPS realmente percorsa, se ancora richiesti, separati dai replay diagnostici.
- Raffinamenti di contesto rotonde/svincoli e performance **solo in presenza di casi riproducibili**, senza riaprire regressioni già risolte.

## Già disponibili — non riproporre come nuove funzioni

Valhalla V5 offline con preferenze Touring/Panoramico e tour circolari, tappe e ricalcoli; MotoMap v4 per contesto; Photon SQLite v2; MotoPack unificato; simulatore GPS/WASM e confronti A/B/C/C2; diagnostica Android/ESP32 unificata ZIP; salvataggio e ripresa tour; Turn Assist ON su `valhalla` dopo il collaudo riferito dall'utente. Per distinguere **funzione presente** da **prova conclusiva sul dispositivo**, vedere [PROJECT_STATE.md](../PROJECT_STATE.md).

## Regola di sviluppo

Una sola integrazione stabile (`valhalla`), sperimenti nel branch di test, una **sola CI consolidata al termine della serie di modifiche**, merge soltanto dopo approvazione esplicita. Non eseguire build per i soli aggiornamenti documentali.

# Moto Chat GPS — Roadmap operativa

**Aggiornata:** 20 novembre 2026 · **Integrazione:** `valhalla` · **Sviluppo e verifiche:** `test/waypoint-markers-20261119`.

Questa roadmap distingue **da verificare**, **da sviluppare** e **già implementato**. Non equivale a un esito dei test. Lo stato corrente e i riferimenti CI sono in [PROJECT_STATE.md](../PROJECT_STATE.md); le procedure sono nel [manuale operativo](MANUALE_OPERATIVO.md).

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

## Priorità 1 — Categorie POI ristorazione e alloggio (DA PROGETTARE/TESTARE)

**Richiesta 20/11:** cercando genericamente «ristorante», «trattoria», «bar», «albergo», «hotel» o «motel», restituire i POI reali vicini in un unico elenco, senza richiedere il nome dell'esercizio. Gruppi proposti: ristorazione (restaurant, trattorie/osterie dove i tag lo permettono), sosta (bar, cafe, pub) e alloggi (hotel, motel, guest_house e altre categorie esplicitamente concordate). Evitare di confondere bar con una strada o hotel con parole nel nome di una via; preservare la ricerca libera per nome.

**Diagnosi schema esistente:** `scripts/offline_map/photon_to_sqlite.py` salva `kind=address_type` nella tabella `places`, ma la FTS4 contentless `places_fts` indicizza **solo nome, strada, civico, città, regione e CAP**, non il tipo OSM. Una query FTS per «ristorante» non recupera un locale chiamato «La Pergola». Una scansione SQLite su `places.kind` senza un indice dedicato è potenzialmente molto pesante sui milioni di luoghi del database. Il metodo a marchi implementato per la benzina è un compromesso, **non** una ricerca esaustiva per categoria.

**Intervento consigliato (non ancora implementato):** verificare quali tag effettivi sono preservati nel `photon-italy.jsonl.zst` (specialmente `address_type`, eventuali classe/tipo) e quali POI sono disponibili nell'`italy-latest.osm.pbf` (`amenity=restaurant/cafe/bar/pub/fuel`, `tourism=hotel/motel/guest_house`). Predisporre un indice categoriale leggero e indicizzato per vicinanza (SQLite con categoria + lat/lon + nome/località), preferibilmente integrato nel processo MotoPack con versione e test di compatibilità espliciti, senza rallentare le ricerche per indirizzo già funzionanti. Non inventare copertura delle trattorie o affittacamere se la sorgente non le distingue. Preservare il fallback Photon online e usare filtri di categoria remoti solo se supportati dall'endpoint in uso.

**Test richiesti:** ricerca generica che recuperi un POI dal nome non descrittivo; casi con e senza fix GPS; disambiguazione con strade omonime; deduplica tra fonti e sotto-categorie; classifica vicino/lontano; ristoranti/bar/hotel in località montane e in città; query per nome che non venga trasformata automaticamente in categoria. Distinguere distanze in linea d'aria da deviazione di percorso. **Nessuna implementazione o build avviata per queste categorie.**

## Priorità 1 — Ricerca unificata distributori (IMPLEMENTATA IN PARTE, DA PROVARE)

**Richiesta:** «distributore» deve restituire in un'unica lista i punti carburante già indicizzati con nome del marchio, senza dover cercare Eni, Q8, IP ecc. separatamente. Implementata in `OfflinePhotonPlaceSearch.kt` una query FTS4 unica, con alias generici e marchi italiani comuni, filtri contro falsi positivi e ordinamento GPS. Funziona con l'indice SQLite Photon v2 esistente, **senza rigenerare MotoPack**. Test JUnit aggiunti, non eseguiti; APK da compilare e collaudare.

**Verifiche:** cerca «distributore», «benzinaio», «stazione di servizio», «Eni» e «Q8» con indice locale; controlla prossimità, duplicati, POI non carburante e risultati lontani. **Non è copertura completa:** stazioni anonime, marchi diversi e località non presenti nel DB potrebbero sfuggire. Per un catalogo esaustivo serve esaminare il dump e indicizzare l'attributo OSM `amenity=fuel` (potrebbe implicare rigenerazione v2 o migrazione), non moltiplicare richieste al Photon pubblico. La variante online rimane la ricerca normale, da studiare separatamente rispettando fair use.

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
- Modalità sosta/risveglio a basso consumo ("pausa caffè"): definire requisiti e comportamento BLE, separatamente dallo spegnimento automatico esistente.
- Esportazione/importazione GPX e traccia GPS realmente percorsa, se ancora richiesti, separati dai replay diagnostici.
- Raffinamenti di contesto rotonde/svincoli e performance **solo in presenza di casi riproducibili**, senza riaprire regressioni già risolte.

## Già disponibili — non riproporre come nuove funzioni

Valhalla V5 offline con preferenze Touring/Panoramico e tour circolari, tappe e ricalcoli; MotoMap v4 per contesto; Photon SQLite v2; MotoPack unificato; simulatore GPS/WASM e confronti A/B/C/C2; diagnostica Android/ESP32 unificata ZIP; salvataggio e ripresa tour; Turn Assist ON su `valhalla` dopo il collaudo riferito dall'utente. Per distinguere **funzione presente** da **prova conclusiva sul dispositivo**, vedere [PROJECT_STATE.md](../PROJECT_STATE.md).

## Regola di sviluppo

Una sola integrazione stabile (`valhalla`), sperimenti nel branch di test, una **sola CI consolidata al termine della serie di modifiche**, merge soltanto dopo approvazione esplicita. Non eseguire build per i soli aggiornamenti documentali.

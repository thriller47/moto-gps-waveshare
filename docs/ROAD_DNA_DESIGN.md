# Road DNA — progettazione tecnica (Android)

**Stato del branch sperimentale (21 novembre 2026):** codice Android offline in integrazione, **non compilato/testato in CI** · **Branch:** `feature/road-dna-motopack-v2`. Dopo il benchmark reale su quattro percorsi, l'utente ha scelto un nuovo MotoPack **senza vincolo di retrocompatibilità**, completo di POI turistici offline; quindi Road DNA locale prioritario, Geoapify e OpenTripMap opzionali. Non dichiarare pronto l'import Android prima del codice e dei test.

**Nome nell'app: sempre «Road DNA».** Nelle stringhe visibili NON usare «DNA percorso» o «RoadDNA». Nomi interni coerenti: `RoadDna...`; chiavi persistite `road_dna_...`. Testi e indicazioni illustrati qui sono proposte UI, non funzionalità esistenti.

## Integrazione sperimentale Geoapify + OpenTripMap (21/11/2026)

**Codice presente nel branch, non ancora compilato né provato con credenziali live:**
- Android: `viewmodel/RoadDnaOnline.kt` e `NavigationViewModel.kt`, `NavigationScreen.kt`, `MainActivity.kt`. Chiavi separate cifrate AES-256-GCM con Android Keystore e file privati sotto `noBackupFilesDir`; inserimento mascherato in `⋮ → API Road DNA`, salvataggio, eliminazione e verifica HTTPS manuale. Chiavi assenti per impostazione predefinita; nessuna chiave nel repo, log o backup.
- **Geoapify:** pulsante `Cerca online` nella scheda Road DNA in anteprima; `POST https://api.geoapify.com/v2/places` con header `x-api-key`, `filter.type=polyline`, geometria Valhalla `LineString` in coordinate [lon, lat], buffer 2000 m; un massimo di tre spezzoni da 800 vertici e prima pagina di 100 risultati per spezzone. Non semplifica i vertici né fabbrica linee tra pezzi. Percorsi oltre budget e pagine sature sono dichiarati parziali. Categorie iniziali turistiche Geoapify.
- **OpenTripMap:** fino a cinque ricerche `/0.1/it/places/radius` a 2 km su punti campionati della rotta, `limit=25`, `rate=2`. I risultati sono sempre indicati come **parziali** per il campionamento; un tasto `Dettagli OpenTripMap` per un `xid` avvia la seconda richiesta esplicita `/places/xid/{xid}`. Il provider richiede `apikey` nella query HTTPS, quindi non riportare URL completi nei log.
- Limiti e sicurezza: niente network quando si apre la scheda (analisi offline separata); mostrare informativa di invio coordinate vicino al comando; timeout 8 s connessione/12 s lettura; risultati max 60, visibili max 20; posizioni filtrate entro 2 km geometrici, **non** distanza stradale o accessibilità. Richieste residue e risultati vecchi sono annullati/ignorati al cambio rotta; nessuna scrittura MotoPack e nessuna trasmissione BLE. Errori del provider senza URL/chiavi.
- **Non fatto:** verifica live delle categorie e delle credenziali, licenze/quote dei propri piani, validazione di copertura su GPX reali, deduplicazione cross-provider basata su identità OSM verificabile, controllo accesso Valhalla per trasformare i POI in tappe. L'anteprima non deve trasformare automaticamente i centri di aree naturali in destinazioni.
- Test di parsing/preparazione richieste aggiunti in `RoadDnaOnlineTest.kt`; **non eseguiti** in questa sessione. Eseguire i test e una sola CI finale dopo consenso.

Riferimenti API ufficiali: https://apidocs.geoapify.com/how-to/place-discovery/places-in-custom-polygon/ e https://dev.opentripmap.org/product.

## Implementazione sperimentale Android nel branch Road DNA

L'anteprima dell'itinerario Valhalla ora presenta una scheda «Road DNA» con curva indicativa e riepilogo dei POI turistici offline nel corridoio 2 km. Il ranking di Photon privilegia i nomi esatti dei luoghi rispetto a vie parziali; la ricerca turistico-nominale integra i risultati Photon con posizioni indicative non navigabili fino alla verifica di accesso Valhalla. Il nuovo MotoPack v2 e il lettore Android richiedono il quarto componente turistico SQLite, con controllo SHA-256 e versione schema. Le modifiche sono sperimentali e non garantiscono ancora una build Android corretta né una rigenerazione italiana riuscita. Il progetto **non** include ancora API key settings, Geoapify/OpenTripMap online, punto di partenza virtuale o deviazione verificata per i POI.

## Decisione successiva al benchmark PBF e online (21/11/2026)

**Decisione utente:** rigenerare una sola volta un **MotoPack nuovo e completo**, senza dover conservare il formato bundle precedente, e **poi integrare il nuovo formato nell'app Android**. Il formato definitivo, nome/versioni dei componenti e procedura di import vanno definiti e testati prima della rigenerazione; la decisione non significa che un pacchetto nuovo sia già stato costruito.

**Evidenza misurata sul PBF italiano:** `tourist-pois-proxy.sqlite` autonomo da **27.529.216 byte** (26,3 MiB), 16 categorie turistiche; relazioni OSM non georeferenziate (**incluse 236 relazioni lago, 755 riserva e 2.172 parco**), nessuna way scartata nella scansione selettiva. I POI categorizzati possono ripetersi tra categorie, e il valore non misura ancora il costo dentro MotoPack. Benchmark su quattro rotte reali: Geoapify prima pagina fino a 100 risultati, OpenTripMap solo tre aree campione; **non confrontare i conteggi come inventari esaustivi**.

**Componenti proposti del nuovo MotoPack:**
- Motomap v4: tile cartografiche e visualizzazione, nessun cambiamento finché non necessario.
- Valhalla V5: routing moto, profili e grafo offline invariati se verificati.
- Photon: SQLite ricercabile per città/indirizzi; correggere precedenza dei nomi esatti di città e luoghi geografici sulle vie omonime, senza degradare la ricerca di indirizzi.
- **Tourist/Road DNA**: SQLite dedicato oppure tabelle integrate nel DB Photon (decisione dopo benchmark sul lettore Android), con ID e tipo OSM, categoria, sottotipo, nome multilingua dove presente, coordinate indicative, geometria/centroide quando applicabile, provenienza e segnalazione di affidabilità. Indici spaziali e antimeridiano/latitudine robusti. Non usare centri di aree vaste come destinazioni raggiungibili senza verifica Valhalla.

**Criteri per dichiararlo completo:**
1. Coprire nodi, way e relazioni multipoligonali rilevanti; test dedicati per laghi, aree protette e parchi, e controllo dei casi senza coordinate o relazione non montabile.
2. De-duplicare rappresentazioni node/way/relation della stessa attrazione senza cancellare entità davvero distinte; mantenere i riferimenti OSM per verifiche.
3. Validare correttamente schema, categorie, checksum SHA-256, record count e integrità del bundle; verificare la possibilità di riprendere una generazione fallita senza perdere dati.
4. Rigenerare i componenti a partire dalle **stesse sorgenti PBF/Photon** in `C:\\MotoGPS\\PBF`, non combinare dataset vecchi senza etichettare differenze di provenienza.
5. Android: aggiornare parser/import, controllo versione, query locali sul corridoio della rotta e scheda Road DNA nell'anteprima; Geoapify/OpenTripMap **solo su richiesta** con chiavi conservate in modo sicuro; nessuna credenziale nel pacchetto.
6. Testare su **Sestriere** e **Lago Maggiore**, più quattro rotte GPX (montagna, costa, cultura e laghi), false corrispondenze e possibili centri non raggiungibili.
7. **Una sola build CI finale, soltanto dopo autorizzazione esplicita**; evitare un nuovo MotoPack installato che Android corrente non può leggere.

**Sequenza operativa concordata:** specifica/schema e test generatori → estrazione multipoligoni/indici completi → nuova ricostruzione WSL in cartella distinta → validazione/checksum e dimensioni effettive → importer e funzioni Android → collaudo → CI finale autorizzata. Nessuna necessità di aggiornare ESP32 o BLE per la sola ricerca turistica.

**Ulteriori richieste Android già registrate:** partenza di itinerario selezionabile e distinta dal GPS reale, impostazioni chiavi Geoapify e OpenTripMap, correzione dei risultati Photon dove vie vicine sopravanzano città o laghi.

**Nota storica:** i paragrafi successivi descrivono il **prototipo precedente, prevalentemente online**, e possono contenere vincoli ora superati (come “nessun nuovo indice nel MotoPack”). Per le decisioni implementative prevale questa sezione.

## 1. Scopo e vincoli

Dare a ciascuna **rotta realmente calcolata** una descrizione del suo carattere motociclistico e del potenziale interesse turistico. Utilizzare Android, la geometria Valhalla già esistente e **ricerche turistiche online soltanto su esplicita richiesta**. Nessuna nuova mappa offline, indice POI permanente nel MotoPack, dipendenza di navigazione da rete, nuova caratteristica BLE, modifica del firmware ESP32 o chiamata continua durante la guida.

Road DNA è una **scheda descrittiva**, non un nuovo motore di routing. Non equivale all'attuale stile `PANORAMIC`: quel profilo preferisce certe alternative tortuose, mentre Road DNA analizza la rotta selezionata. Un valore alto su «natura nei dintorni» NON promette un panorama visibile dalla carreggiata.

## 2. Integrazioni già presenti nel codice (verificate)

- `viewmodel/NavigationViewModel.kt`: `RouteOption(id, name, distanceMeters, durationSeconds, steps, rawRouteJson)`; `routeOptions`, `selectedRouteIndex`, `previewRoute()`, `selectRouteOption()`. La geometria disponibile nel `rawRouteJson` varia a seconda dell'adapter; estrarla mediante un solo decodificatore con controlli rigorosi.
- `ui/NavigationScreen.kt`: anteprima con elenco di alternative e tour salvati. Punto naturale di accesso alla nuova scheda: **anteprima > Road DNA**, per la rotta selezionata, prima di «Avvia navigazione».
- `routing/RouteCurvatureSelector.kt`: calcola già densità di curve per preferire alternative Valhalla; **riusare/estrarre** la metrica stradale, senza duplicarne una versione divergente.
- `map/OpenFreeMapRouteCorridor.kt`: esempi di lettura della geometria `geometry_gcj02`/`polyline`/`coordinates` e interpolazione. **Non** avviare download tile OpenFreeMap per calcolare Road DNA.
- `viewmodel/PhotonPlaceSearch.kt`: Photon pubblico `https://photon.komoot.io/api/`, già filtrato per categorie **fuel, restaurant, cafe, bar, hotel** e bbox. Le nuove categorie **non sono ancora implementate né validate nel provider**.
- `viewmodel/SavedCircularTourStore.kt`: salva piano del giro (partenza, tappe, preferenze), **non** la vecchia geometria. `TourSessionStore.kt` salva checkpoint, non geometria. Un tour riaperto viene ricalcolato; un vecchio risultato Road DNA non deve essere attribuito a una rotta cambiata.

## 3. Due sezioni indipendenti

### A. «Guida» — calcolo locale, immediato

Input: `RouteOption` selezionata e coordinate geografiche validate.

- `km` e `min`: già presenti nelle opzioni; mostrare senza un nuovo rating.
- `Curve`: indicatore di tortuosità dalla geometria, robusto a densità di vertici e rumore; inizialmente **«Bassa / Media / Alta»** con soglie da tarare su itinerari campione, non un «86/100» arbitrario.
- `Scorrevolezza`: stimare con cautela su tortuosità/struttura della rotta; non equipararla a velocità reali o traffico. Segnalare «non disponibile» se i dati non bastano.
- `Montagna`, `asfalto`, `dislivello`: solo se provengono da metadati davvero disponibili e con copertura sufficiente; il solo nome di un passo o la presenza di molte curve non basta a dimostrarli.
- Nessuna chiamata internet; niente nuove mappe; valori ricalcolati quando cambia l'alternativa o la geometria.

### B. «Da scoprire» — su richiesta, online

Categorie iniziali raggruppate in insiemi leggibili:
- **Natura:** laghi (`natural=water;water=lake`), spiagge (`natural=beach`), cascate, sorgenti termali naturali, montagne, belvedere.
- **Storia e cultura:** castelli, monumenti, siti archeologici, musei.
- **Verde e relax:** parchi, giardini, riserve, stabilimenti termali (distinti dalle sorgenti naturali).
- **Soste:** bar, ristoranti, distributori (opzionali; da non sommare agli indicatori paesaggistici).

Output: **luoghi trovati nei dintorni della rotta**, con distanza geometrica approssimata, categoria, nome, fonte e stato di verifica. Il totale dei POI non costituisce direttamente un indice panoramico: troppi punti concentrati in una città non devono trasformare un raccordo urbano in «percorso naturale».

In una seconda fase, solo per luoghi selezionati: ingresso/accesso stradale verificabile, **deviazione Valhalla** stimata e pulsante «Aggiungi tappa», sfruttando la gestione tappe esistente. Non avviare automaticamente una rotta verso il centroide di un lago/parco né considerare un poligono come POI accessibile.

## 4. Ricerca geografica e limiti del provider

**Alternativa scoperta 21/11/2026:** [ROAD_DNA_DATA_SOURCES.md](ROAD_DNA_DATA_SOURCES.md) confronta Geoapify (Places API **POST con LineString + buffer** e 3000 crediti/giorno sul piano gratuito), OpenTripMap (POI turistici arricchiti, free non-commerciale 5000 richieste/giorno), TomTom along-route legacy, Wikidata e l'**estensione dell'indice `poi_places` già presente in Photon SQLite v2** senza aumentare MotoMap/Valhalla. Geoapify è il candidato più diretto per la ricerca sul corridoio e potrebbe sostituire le bbox campionate previste sotto. Nessuna decisione di integrazione né validazione live ancora eseguita.


**Verifica Photon del 21/11/2026:** [PHOTON_ROAD_DNA_REVIEW.md](PHOTON_ROAD_DNA_REVIEW.md) documenta API, filtri `include`/`bbox`, struttura GeoJSON, limiti d'uso pubblici e differenza fra tag principali e secondari. Il server pubblico dichiara uso ragionevole ma **nessuna quota numerica garantita**; i 50 risultati sono il default configurabile del software, non una garanzia dell'istanza. In particolare `osm.natural.water` **non** permette di filtrare con certezza solo `water=lake`, e `osm.amenity.public_bath` **non** prova `bath:type=thermal`. Nessun endpoint che cerchi lungo una polilinea; i risultati parziali non vanno usati per un voto turistico esaustivo. Prova live su `photon.komoot.io` ancora da eseguire (accesso diretto non disponibile negli strumenti di verifica); nessun risultato inventato.



**Prototipo proposto e ancora da validare:** interfaccia `RoadDnaPlacesProvider`, adattatore iniziale Photon per **alcuni** tag realmente supportati. Photon offre ricerca per categoria e bbox e l'app ne usa già il modello, ma il server pubblico è una demo con uso ragionevole, numero di risultati limitato e nessuna garanzia di completezza per lunghi corridoi o categorie composte. Il suo endpoint **non è un inventario esaustivo** di tutti gli elementi OSM.

1. Campionare la **polilinea selezionata** a intervalli sulla distanza di percorrenza, non fare una singola bbox di una rotta lunga e sinuosa.
2. Per ciascun campione costruire una bbox piccola e sovrapposta alle adiacenti; deduplicare campioni/box. Raggio di prima prova: 3 km, **configurazione di prototipo, non parametro definitivo**.
3. Limite prudente di prima prova: **massimo 6 interrogazioni per azione esplicita**, concorrenza 1, nessun polling, timeout complessivo e cancellazione su cambio rotta/uscita. Per giri più lunghi mostrare chiaramente «analisi parziale» con % stimata di corridoio esplorato, senza estrapolazioni non giustificate.
4. Filtrare nel client per tag/categoria, coordinate sane e distanza punto-segmento dalla rotta; de-duplicare per OSM type/id quando disponibile, altrimenti nome+posizione con soglia ragionevole. Per aree estese e centriidi approssimati, marcare «posizione indicativa».
5. Risposte 429/503/timeout/errori di parsing: interrompere, non tentare aggressivamente altri server pubblici, **nessun errore bloccante per la navigazione**. Tracciare solo evento diagnostico aggregato, senza registrare tracciati o coordinate precise.
6. Se il pilota Photon risulta incompleto, **valutare un provider dedicato** con policy/chiave, quote, termini d'uso, attribuzione e disponibilità realmente verificati. Non assumere che Photon, Nominatim o un servizio pubblico gratuito forniscano query POI esaustive su centinaia di chilometri. **Non reintrodurre Overpass come dipendenza obbligatoria.**

Prima di sviluppare il punteggio turistico eseguire una **matrice di copertura** con 3–5 rotte campione (montagna, lago, costa, città storica, campagna), diverse lunghezze e categorie: numero di risultati attendibili, duplicati, falsi positivi, tempo di risposta e 429/503. Se troppo incompleto: mostrare soltanto «Luoghi da scoprire», senza voto numerico.

## 5. Semantica degli indicatori e affidabilità

Ogni indicatore deve conservare `value?`, `coverage`, `confidence`, `source` e `fetchedAt`; `null` / «Dati insufficienti» è diverso da zero.

- **Guida:** misura normalizzata per km di percorso; classi provvisorie, calibrate con benchmark riproducibili.
- **Natura/cultura:** per la V1 preferire **conteggi unici per categoria + distribuzione su tratte di rotta coperte**, non un voto 0–100 né un indice «panorama garantito». Se in futuro compariranno voti, documentare formula, pesi, copertura e test.
- **Copertura:** registrare tratti interrogati e tratti saltati/errore; esporre «Completa nei limiti del provider» oppure «Parziale» senza suggerire una copertura OSM assoluta.
- **Percorso modificato:** qualsiasi ricalcolo che cambia geometria, tappe o alternativa invalida il risultato, salvo identità verificabile della rotta.

## 6. UX Android proposta

- Nell'**anteprima** dell'alternativa selezionata: pulsante/scheda **«Road DNA»**.
- Scheda con intestazioni **«Guida»** (subito disponibile) e **«Da scoprire»** (pulsante **«Analizza online»**).
- Stati ben distinti: **Non analizzato**, **Ricerca in corso** (annullabile), **Risultati**, **Parziale**, **Errore rete**, **Salvato il [data]**.
- Elenco luoghi «Vicino al percorso» con categorie e distanza **in linea d'aria dalla polilinea** etichettata, non «deviazione»; pulsante «Aggiungi tappa» soltanto quando accesso e instradabilità verificati.
- I risultati online sono visibili in fase di **pianificazione**; nessuna notifica invasiva durante guida; nessun cambiamento della UI ESP32.

## 7. Persistenza e aggiornamento

- Un oggetto `RoadDnaSummary` **separato** da `TourSessionStore` e `SavedCircularTourStore`, persistito in Android (`SharedPreferences` o archivio locale leggero secondo dimensioni effettive).
- Chiave `routeFingerprint` derivata da geometria quantizzata, lunghezza, origine/destinazione e tappe; associare anche `savedTourId?`, `schemaVersion`, `providerVersion`.
- Persistenza **solo riepiloghi limitati** (per esempio categorie, fino a pochi luoghi scelti, timestamp, copertura), non risposta raw di molti POI; niente file aggiunto al MotoPack.
- Politica iniziale proposta di aggiornamento online: **7 giorni** con stato «dati salvati» anche quando scaduti; pulsante «Aggiorna online». Non aggiornarli autonomamente a ogni avvio.
- Quando si riapre un giro salvato, **prima ricalcolare la rotta**, poi controllare `routeFingerprint`; se cambia, mantenere lo storico eventuale ma non mostrarlo come analisi attuale.
- Offline sempre possibile consultare `Guida` e un riepilogo storico validato/etichettato con data; non promettere attualità.

## 8. Privacy, prestazioni e responsabilità

- L'analisi online **invia aree vicine al percorso** al provider esterno: avvisare l'utente prima del primo utilizzo e spiegare che possono rivelare informazioni sul viaggio; nessun caricamento automatizzato dei tour.
- Richieste solo dalla UI con esplicito consenso/azione, su `Dispatchers.IO`, cancellabili e con limiti. Non condividere coordinate nella telemetria diagnostica.
- Nessuna dipendenza necessaria durante la navigazione, nessuna modifica al protocollo BLE, nessuna modifica all'ESP32, nessun aumento dei pacchetti offline.
- Attribuzione OSM e fonte di eventuali servizi esterni secondo licenze e termini specifici; non presentare POI obsoleti come aperti/attivi.

## 9. Piano di implementazione e verifica (non avviato)

1. **Prova provider separata:** verificare documentazione e accesso effettivo ai tag natura/cultura su piccole aree; raccogliere misure e limiti prima di impegnarsi sul provider finale.
2. **Core puro:** `RoadDnaGeometry` (decodifica unificata, campionamento, distanza a segmento, impronta), `RoadDnaRoadMetrics` (curve, classi e qualità), `RoadDnaModel` (stato/coverage).
3. **Online adapter:** `RoadDnaPlacesProvider` + Photon prova-limitata, deduplicazione, cancellazione, stop su rate limit, indicatore analisi parziale.
4. **UI Compose:** scheda «Road DNA» integrata in anteprima, visualizzazione locale, analisi online esplicita e data salvataggio.
5. **Cache:** riepilogo Android separato, invalidazione fingerprint; conservare versione dati.
6. **Test unitari:** assenza rete, provider vuoto, rate limit 429, null distinto da 0, duplicati, anomalie coordinate, bbox di strade tortuose, POI molto vicino in linea d'aria ma irraggiungibile, due alternative con lunghezza simile, selezione alternativa mentre la query è in corso, tour salvato ricalcolato, cache scaduta, nessuna rete/BLE richiesta per `Guida`.
7. **Collaudo:** almeno 3-5 itinerari diversi, conferma dei luoghi su mappa e verifica delle coperture. Solo dopo calibrazione scegliere se aggiungere badge o indici sintetici.

**Regola repo:** sviluppare eventuale codice in un branch di test isolato, una sola CI finale se autorizzata; **questa progettazione non autorizza build, merge né implementazione**.

## 10. Decisioni assunte e ancora aperte

**Decise:** nome visibile «Road DNA»; Android-only; doppio livello locale/online; nessuna crescita del MotoPack per la funzione; attivazione online manuale; nessun nuovo traffico BLE; storico con data; navigazione indipendente dalla rete.

**Da verificare prima della V1:** provider e sua copertura turistica reale, quote/termini, granularità di campionamento delle rotte, soglie dei giudizi stradali, criteri di accessibilità dei grandi elementi naturali e selezione di una deviazione reale.

### Riferimenti
- Codice Moto Chat GPS `valhalla`: `NavigationViewModel.kt`, `NavigationScreen.kt`, `RouteCurvatureSelector.kt`, `PhotonPlaceSearch.kt`, `SavedCircularTourStore.kt`, `TourSessionStore.kt`, `OpenFreeMapRouteCorridor.kt`.
- Photon API e politica d'uso: https://github.com/komoot/photon e https://github.com/komoot/photon/blob/master/docs/api-v1.md .

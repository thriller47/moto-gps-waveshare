# Road DNA — alternative online e indice turistico MotoPack

**21 novembre 2026 — ricerca documentale (non prova live con chiavi API).** Solo progettazione. Nessuna modifica al codice, nessuna build. Nome UI: **Road DNA**.

## Fonti online candidate

| Provider | Caratteristiche confermate | Limiti / cautele | Giudizio |
|---|---|---|---|
| **Geoapify Places API** | Query per categorie, cerchio/rettangolo e, via POST, **GeoJSON LineString + buffer metrico** per ricercare POI entro un corridoio; filtri e paginazione, fino a 500 luoghi per pagina. Oltre 800 categorie. Risultati riutilizzabili in cache con attribuzione. | Chiave API; piano free **3000 crediti/giorno**, per Places **1 credito ogni 20 luoghi restituiti**; richieste paginabili e consumo proporzionale. Dati soprattutto OSM: buona tassonomia ma non fonte indipendente di POI mancanti. Geometria vicina != accessibilità stradale. Invio della rotta a terzi; verificare limiti di lunghezza/termini/quote del progetto e non incorporare in chiaro una chiave generale nel repo. | **Prima scelta per test del corridoio Road DNA**. |
| **OpenTripMap** | Catalogo turistico basato su OSM+Wikidata+Wikipedia e altre fonti; lista per area/tipologia, dettagli di luoghi, descrizioni e immagini quando presenti. | Piano free **non commerciale**, **5000 richieste/giorno e 10/s** nella pagina ufficiale corrente; chiave API, copertura italiana non misurata, no interrogazione corridoio nativa verificata. Esaminare attribuzioni e licenze delle singole immagini/testi. | Ottimo per **arricchimento di luoghi selezionati**, non inventario garantito. |
| **TomTom Search Along Route** | Endpoint legacy specifico per POI vicino a una rotta, con ordinamento per deviazione/tempo e ripartizione lungo il percorso. | API/proprietà e costi/termini propri; **TomTom Places Search API nuova non supporta ancora along-route** secondo documentazione attuale. Endpoint su piattaforma precedente, sconsigliato per nuova architettura finché non chiariti vita utile, quote e categorie. | Soluzione tecnica valida, ma non prima scelta open-source. |
| **Photon pubblico** | Ricerca geografica e `include` su **categorie/tag principali**, bbox, nomi. Già usato per carburante/ristoro. | Demo komoot con *fair use* non numerico; nessun corridoio polilinea; tag secondari come `water=lake` e `bath:type=thermal` non affidabilmente filtrabili. | Conservare per ricerca indirizzi/POI singoli. |
| **Wikidata/Wikipedia/Wikimedia** | Pagine/descrizioni/immagini e identificatori culturali per arricchimento; SPARQL geospaziale possibile. | Query online e politiche Wikimedia; WDQS in migrazione verso nuovo backend nel 2026, no garanzia di inventario POI. Licenze dei contenuti distinte (dati strutturati, testo, immagini). | **Seconda fonte di dettaglio**, non scansione di tutto il percorso. |
| **Overpass pubblico** | Filtra tutti i tag e relazioni OSM, compresi quelli secondari, su zone e poligoni. | Già sperimentati timeout/429 nel progetto; risorse pubbliche non garantite, query pesanti; rischio dipendenza fragile in navigazione. | Non usarlo come servizio necessario per Road DNA. |

**Link verificati:** https://apidocs.geoapify.com/how-to/place-discovery/places-in-custom-polygon/ · https://apidocs.geoapify.com/docs/places/ · https://www.geoapify.com/places-api/ · https://dev.opentripmap.org/product · https://dev.opentripmap.org/price · https://docs.tomtom.com/search-api/documentation/search-service/along-route-search · https://docs.tomtom.com/search-api/documentation/product-information/choosing-a-search-api · https://www.wikidata.org/wiki/Wikidata:SPARQL_query_service/API · https://github.com/komoot/photon

I dati tariffari sono quelli pubblicati al 21/11/2026 e possono cambiare. Il prezzo del piano gratuito Geoapify è in **crediti**, non equivalente a 3000 richieste Places indipendentemente dalla dimensione della risposta. Nessun test live su servizi con chiave è stato condotto.

## Alternativa offline: ampliamento mirato, NON «tutto il PBF nel MotoPack»

**Risultato dell'audit del codice:** `scripts/offline_map/add_poi_to_photon.py` legge già `italy-latest.osm.pbf` tramite `pyosmium`, genera `poi_places` e indice `poi_category_lat_lon` in **Photon SQLite v2**. Attualmente importa fuel, restaurant, fast_food, cafe, bar, pub e alcuni tipi di alloggio. Il formato MotoMap v4 e il grafo Valhalla V5 **non vanno ingranditi** per aggiungere POI turistici.

### Come estenderlo se decidiamo di includere POI nel MotoPack

1. Fare inventario PBF per categoria/quantità/densità/completamento tag: `tourism=viewpoint`, `historic=castle/monument/ruins/archaeological_site`, `tourism=museum/attraction`, `natural=beach/hot_spring/water`, `water=lake`, `leisure=park/garden/nature_reserve`, `boundary=protected_area`, `amenity=public_bath` + `bath:type=thermal`.
2. Non usare ingenuamente il `classify()` corrente: restituisce solo la **prima categoria** e l'archivio `poi_places` ha un'unica riga per `(osm_type,osm_id)`. Prevedere classificazione multi-tag e distinguere tag principali/secondari per lago e stabilimento termale; decidere schema v2 compatibile o tabella turistica separata nella stessa SQLite.
3. Il parser attuale considera **nodi e way**, non **relazioni**: i laghi, i parchi e le aree protette multipoligonali rischiano di mancare o duplicarsi. Risolvere relazione-multipoligono e de-dup node/way/relation, proteggendo memoria WSL.
4. La posizione ottenuta attualmente per le way è la **media dei vertici**, non ingresso/accesso. Per laghi/parchi usare punto rappresentativo/limiti spaziali e, quando disponibile, **ingressi praticabili**; la ricerca per distanza dalla linea è solo un filtro iniziale, prima di Valhalla per stimare una deviazione vera.
5. Usare indice spaziale (RTree oppure strategia `category,lat,lon` con verifica benchmark) e `id,category,nome,lat,lon,subtype` essenziali. Nessuna immagine/testo lungo incorporato nel MotoPack: usare Wikidata/Wikipedia/OpenTripMap **soltanto quando un luogo è selezionato**.
6. Produrre rapporto **prima/dopo in MiB**, query di ricerca corridoio e tempo di import. L'incremento dimensionale del DB **non è ancora misurato**: non promettere «pochi MB» prima dei conteggi.
7. Mantenere separati navigazione e discovery: nessun nuovo frame BLE, nessun segnale turismo sulla UI ESP32 senza richiesta utente, nessuna rete richiesta per turn-by-turn.

**Soluzione alternativa:** file add-on `motopoi-italy.sqlite` opzionale, se Photon v2 cresce troppo; è comunque distribuibile accanto al MotoPack senza rigenerare MotoMap/Valhalla. In entrambi i casi non stiamo importando «tutte le 28 macro-categorie OSM», ma **solo categorie concordate e dati necessari**.

## Scelta pratica e prossimi test

**Scelta consigliata provvisoria: Road DNA locale per guida + Geoapify per «Da scoprire» + dettagli OpenTripMap/Wikimedia su click; fallback futuro POI turistici compatti nel bundle se servizi online si rivelano troppo dipendenti, costosi o incompleti.** Geoapify nasce soprattutto dai dati OSM; il fallback non può correggere la mancanza di un luogo che nessuno ha mappato, ma conserva tag secondari, multipoligoni e stabilità offline se l'importatore è completo.

Prova mirata da eseguire con credenziali di test (non ora): 3-5 itinerari italiani — montagna, lago, costa, area termale, città storica — confronto POI Geoapify, OpenTripMap e conteggio diretto PBF, precisione lungo rotta, ingressi, latenza, quota consumata, dimensione proiettata dell'indice. **Solo dopo decidere la fonte principale e le eventuali categorie offline.** Non modificare codice né avviare CI sulla base di questa nota.

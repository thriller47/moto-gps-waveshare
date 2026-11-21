# Photon per Road DNA — verifica API, limiti e decisione progettuale

**21 novembre 2026 · Documentazione verificata · Test live dell'istanza pubblica NON eseguito:** l'accesso diretto a `photon.komoot.io/api/` non è stato disponibile negli strumenti di consultazione usati. Nessun risultato POI o tempo di risposta inventato. Nessuna build o codice applicativo modificati.

## Fonti ufficiali
- [komoot/photon README — funzioni, server demo e uso equo](https://github.com/komoot/photon/blob/master/README.md)
- [Photon API v1](https://github.com/komoot/photon/blob/master/docs/api-v1.md)
- [Photon categories](https://github.com/komoot/photon/blob/master/docs/categories.md)
- [Photon usage / limiti della configurazione server](https://github.com/komoot/photon/blob/master/docs/usage.md)
- [Nominatim: import styles e main tags](https://nominatim.org/release-docs/latest/customize/Import-Styles/)

## 1. Cosa offre

- Geocodifica diretta via `/api` (`q` testuale facoltativo **se** si usa `include/exclude`); ricerca strutturata `/structured`; geocodifica inversa `/reverse`; controllo `/status` (API documentata; disponibilità del server pubblico da verificare).
- Restituisce GeoJSON `features`, tipicamente `geometry.type=Point` e `coordinates=[lon,lat]`; proprietà `name`, `osm_key`, `osm_value`, `osm_type`, `osm_id`, contesto geografico e a volte `extent`.
- `bbox=minLon,minLat,maxLon,maxLat` filtra per area geografica. `lat`, `lon`, `zoom` danno *bias* di ordinamento, **non** un vero vincolo di prossimità al percorso.
- `limit` limita i risultati per interrogazione, ma il server ha un massimo configurabile e può restituire meno del richiesto.
- `include=osm.tourism.viewpoint,osm.historic.castle`: **OR** fra categorie elencate nello stesso parametro. Parametri `include` ripetuti sono **AND**, dunque non ripeterli aspettandosi di ampliare categorie. `include=osm.tourism` usa il prefisso di chiave OSM; non si può cercare con solo `include=osm`.
- `osm_tag` resta documentato ma è **deprecato**, preferire `include` e `exclude`.
- `lang=it` è un'opzione software, senza garanzia che tutte le istanze abbiano importato la lingua. `dedupe` controlla deduplicazione lato server. Ricerca per frase ha tolleranza agli errori e bias geografico.

### Esempi di richieste da collaudare (NON eseguite)

```text
https://photon.komoot.io/api/?include=osm.tourism.viewpoint&bbox=7.0,45.0,7.4,45.3&limit=10
https://photon.komoot.io/api/?include=osm.historic.castle,osm.historic.monument&bbox=7.0,45.0,7.4,45.3&limit=10
https://photon.komoot.io/api/?include=osm.natural.water&bbox=7.0,45.0,7.4,45.3&limit=10
```

La sintassi è conforme alla documentazione. **Non** dimostra che tutti i tipi siano indicizzati o che il server pubblico restituisca dati per quell'area.

## 2. Categorie Road DNA: potenziale e limiti di estrazione

| Famiglia | Filtro Photon candidato | Avvertenza |
|---|---|---|
| Belvedere | `osm.tourism.viewpoint` | Tag principale plausibile; copertura reale non verificata |
| Castelli, monumenti, rovine | `osm.historic.castle`, `osm.historic.monument`, `osm.historic.ruins` | Richiedono import come main tags |
| Musei e attrazioni | `osm.tourism.museum`, `osm.tourism.attraction` | Molte tipologie/aree non hanno necessariamente una categoria adatta |
| Spiagge | `osm.natural.beach` | Geometria dell'area potrebbe essere rappresentata come punto indicativo |
| Laghi | `osm.natural.water` | **NON** significa solo laghi: `water=lake` è tag secondario e non filtrabile in modo affidabile con l'indice category standard |
| Terme naturali | `osm.natural.hot_spring` | Non equivale a stabilimenti termali |
| Stabilimenti termali | `osm.amenity.public_bath` | `bath:type=thermal` secondario; senza `extra` o altra fonte non si distingue con certezza uno stabilimento termale |
| Parchi, giardini | `osm.leisure.park`, `osm.leisure.garden` | Aree estese: il centroide non corrisponde necessariamente all'ingresso |
| Riserve | `osm.leisure.nature_reserve` oppure `osm.boundary.protected_area` | Tipologie e geometrie eterogenee |

**Limite strutturale:** le categorie `osm.<key>.<value>` derivano dal tag principale del record importato da Nominatim; non tutti i tag OSM vengono importati come principali. La documentazione dell'exporter Photon specifica che per ciascun luogo crea una categoria `osm` dal tag principale. `extra` è distinto dalle categorie, non è indicizzato e la sua inclusione richiede configurazione di import (di default nessun extra tag). Quindi non possiamo promettere filtri affidabili su tag secondari o attribuire a tutta una zona tutte le categorie OSM del PBF.

## 3. Limiti veri del servizio pubblico

- `photon.komoot.io` è descritto ufficialmente come **demo pubblico**. Uso permesso per richieste «reasonable», ma **uso intensivo può essere rallentato o bloccato**, senza garanzia di servizio e con possibili modifiche unilaterali.
- **Non è pubblicata nel README una quota ufficiale in req/s, per IP, per giorno o per progetto.** Non confondere le scelte di throttling di client terzi con condizioni di komoot.
- Photon software usa **massimo 50 risultati per query come default** del parametro `-max-results`, ma è configurabile per installazione. Non equivale a 50 POI disponibili, a copertura completa o a quota del server pubblico.
- Photon software usa **7 s come query-timeout predefinito** in OpenSearch; valore configurabile, non SLA né timeout HTTP del server pubblico.
- Nessun endpoint documentato che accetti una **polilinea di percorso** come geometria filtro e non è documentata una paginazione completa di tutti i POI nel bbox. Il rapporto risultati/copertura dipende da ranking, limite, import e tipo di oggetto.
- Molte aree naturali sono grandi e possono avere nel risultato solo punto e `extent`; usare il punto per determinare visibilità/possibile accesso stradale è scorretto.
- Il server pubblico non è adatto per molte scansioni automatiche di lunghi tour o per un censimento esaustivo; una bbox gigantesca con pochi risultati non coprirebbe tutte le attrazioni.

## 4. Stato effettivo in Moto Chat GPS

In `platforms/android/app/src/main/java/com/motogps/android/viewmodel/PhotonPlaceSearch.kt`:
- ricerca per nome con `q` e `limit=30`;
- ricerca per categorie **soltanto** `fuel`, `restaurant`, `cafe`, `bar`, `hotel`, con `include` e bbox fino a 150 km, una ricerca per volta su richiesta dell'utente;
- `categoryResults()` filtra `osm_key/osm_value` anche lato client, deduplica per nome+coordinate e **mostra al massimo 8** risultati;
- `searchCategories()` allarga la ricerca per raggi di 15/50/150 km: ciò ha senso per cercare un distributore nelle vicinanze, **non** per analizzare un intero percorso;
- timeout HTTP: 10 s connessione, 12 s lettura.

La presenza di `OfflinePoiCategorySearch.kt` e Photon SQLite v2 riguarda **il pacchetto offline corrente**, con alcune categorie. Non estendere automaticamente questo indice per Road DNA.

## 5. Decisione progettuale consigliata

**Photon adatto come prima prova per la scheda facoltativa «Da scoprire», NON come unica sorgente affidabile per un «punteggio turistico» di tutta la rotta.**

- Road DNA «Guida»: sempre locale da geometria Valhalla, con metriche documentate.
- Road DNA «Da scoprire»: solo dopo click **«Analizza online»**, fino a un numero ridotto e configurabile di bbox campione, concatenazione delle categorie richieste, concorrenti=1, backoff/stop a 429 o 503, cache locale datata.
- Etichettare risultati come **«Luoghi trovati vicino al percorso»**, non «tutti i luoghi lungo la rotta»; mostrare tratte esplorate e «analisi parziale» quando applicabile; non dedurre punteggio 0 per categorie senza risultati.
- Deduplicare con `osm_type+osm_id` se disponibile; in fallback deduplica fuzzy per nome/prossimità. `bbox` non equivale a distanza stradale o punto di accesso.
- Non usare Photon per passaggi di categoria che richiedono **tag secondari essenziali** senza un'altra fonte, ad es. filtro affidabile `water=lake` e `bath:type=thermal`.
- Prima di implementare, effettuare un **piccolo test live volontario**: una richiesta per belvedere, una per castelli/monumenti, una per acqua naturale in aree note; verificare HTTP, numero e qualità dati, campi disponibili, lingua, latenze; **non** fare crawling estensivo.
- Se vogliamo davvero indici turistici accurati su rotte intere, valutare provider POI con termini/quote chiari o un servizio dedicato. Senza aumentare il MotoPack offline.

## 6. Esito

**Funzioni API documentate: verificate.** **Limiti pubblici dichiarati: verificati.**
**Tag effettivamente presenti su `photon.komoot.io`, risposte live e accuratezza: NON verificati**, per mancato accesso diretto agli endpoint API dagli strumenti disponibili.

Questa verifica non costituisce autorizzazione a modifiche applicative o build.

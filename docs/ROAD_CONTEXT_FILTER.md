# MotoMap v3: connettività OSM per le strade di contesto

## Raffinamento uscite e colori — prova successiva, 5 novembre 2026

Le ways connesse ad un ring vengono ora raggruppate per **ID nodo OSM condiviso e direzione di partenza entro 25°**; ogni gruppo usa un solo slot prioritario (fino a cinque). Quando sono presenti più di cinque direzioni, si dà precedenza a un ramo vicino alla posizione/percorso imminente e si distribuiscono i rimanenti attorno all'anello. Non si inventano connessioni geometriche senza nodi OSM; direzioni opposte sullo stesso nodo restano distinte. Il ring conserva i nodi delle uscite selezionate. I contatori `OSM_RING` distinguono `exit_ways`, `branches`, `prioritized` e `OSM_SCENE_POLICY.exits_sent` indica gli slot realmente inviati. Il limite resta **20/160**, con minimo sei strade GPS locali quando disponibili; filtro livelli sempre **spento**.

Il renderer ESP32 usa le nuove tinte: principali `#D0D5D8`, normali `#A8B0B5`, minori `#788188`. Non cambia il verde rotta. Per questa prova occorre aggiornare sia **Android** sia **firmware ESP32**; MotoMap v3 resta valido senza rigenerazione. Prova hardware e confronto con log ancora necessari.

## Esperimento attuale — 5 novembre 2026: rotonde multi-way e contesto sempre visibile

Branch `test/road-contrast-cleanup-20261104` (**non integrato in `valhalla`**). Rispetto al test precedente `09d3aa8`, `RoadContextJunctions` rileva sia anelli completi su una way sia circuiti chiusi su più ways con estremi OSM condivisi. Ricerca limitata a 8 ways per ring, 90 way candidate, 3500 espansioni e 48 candidati ring per scena. La geometria deve essere chiusa entro 2,5 m, avere diametro 12–160 m, circonferenza 35–550 m e forma non degenerata. Il punteggio iniziale include chiusura OSM, geometria, prossimità, numero uscite connesse e classe stradale; richiede almeno 70 punti. **Si seleziona un solo ring**, altrimenti selezione strade ordinaria.

Le uscite richiedono nodi OSM realmente condivisi, mai semplici attraversamenti 2D. `RoadContextWindow.closedRing` conserva i punti di chiusura e i nodi delle uscite durante la semplificazione (max 16 punti). `RoadContextSceneSelector` gestisce l'allocazione indipendentemente dal rilevamento: fino a 1 ring e 5 uscite, almeno 6 altre strade vicine al GPS, fino a 4 nuove strade del percorso imminente e poi altre nearest finché c'è budget. Le strade GPS non dipendono dall'aderenza alla rotta e rimangono disponibili durante una deviazione. Limite Android **20 strade/160 punti**, massimo 8 per polilinea ordinaria e 16 per ring; limiti firmware 24/192 invariati. L'anticipo `upcoming` è interpolato sul percorso (indicativamente 90–220 m secondo velocità/accuratezza).

Diagnostica in `OSM_RING`, `OSM_SCENE_POLICY`, `BLE_MAP_SCENE`: candidati, qualificati, punteggio, uscite trovate/inviate, motivi di scarto, quote effettive di slot/punti e continuità strade GPS fuori rotta. **Nessun filtro per layer/grade separation è attivo** in questa fase, come richiesto. `RoadContextTopology` resta in repository per un futuro test separato, non viene invocato dalla scena. Nessuna rigenerazione MotoMap v3 e nessun nuovo firmware richiesti.

**Limiti non risolti per definizione:** il pacchetto MotoMap v3 non esporta `junction=roundabout`, quindi il punteggio di confidenza è euristico. I circuiti urbani compatti possono ancora somigliare a una rotonda; dati mancanti o way molto lunghe possono rendere impossibile ricostruire un ring. **CI e test su strada di questa revisione ancora da verificare** prima del merge.

## Cronologia: primo tentativo di ring completo — 4 novembre

Il filtro `RoadContextTopology` è **disabilitato nella generazione delle scene** su richiesta dell'utente: i livelli e gli attraversamenti verranno affrontati più avanti. Il codice del filtro resta conservato per future prove. Ora `RoadContextJunctions` riconosce le piccole OSM ways chiuse vicine al GPS o al percorso imminente e dà priorità all'anello e alle uscite che condividono nodi OSM, senza escludere il resto delle strade. `RoadContextWindow.closedRing` conserva tutta la forma della rotonda. Rimane la selezione delle 8 strade locali più vicine e il fallback di prossimità, così da mostrare strade anche dopo una deviazione e prima del ricalcolo. Il budget sperimentale attuale è 20 strade/160 punti (prima 16/128); l'anello può usare fino a 16 punti. Il formato MotoMap v3 non include il tag junction=roundabout, quindi le rotonde composte da più ways non sono ancora rilevate come singola figura. **Da validare su strada prima del merge.**



**Stato:** implementazione sperimentale sul branch `test/road-contrast-cleanup-20261104` (4 novembre 2026). Il test ha confermato un cavalcavia correttamente filtrato, ma anche strade su altri livelli ancora visibili. La prova del selettore route-focused `4226b63` è stata negativa ed è stata annullata; restano le limitazioni indicate sotto.

## Perché non basta intersecare due linee sul display

Un cavalcavia e un'autostrada possono intersecarsi in 2D ma non condividere alcun nodo OSM. Una rotonda o una rampa realmente percorribile, invece, usa nodi OSM condivisi tra le way che si congiungono. `layer` descrive inoltre la quota stradale relativa (`bridge` e `tunnel` sono fallback quando il tag è assente).

## Formato MotoMap v3

L'archivio ZIP continua a contenere `tile_15_X_Y.motomap`; la versione è dichiarata in `motomap_v3.json`. Ogni riga stradale:

```text
classe,velocita,layer,osmWayId|minLat,minLon,maxLat,maxLon|lat,lon,nodeIdBase36;lat,lon,nodeIdBase36;...
```

Gli ID nodo sono **originali OSM**, base36 senza collisioni. Identificativi uguali rappresentano lo stesso nodo OSM; coordinate simili non sono considerate connettività. Esempio:

```text
1,90,1,34567|44999500,8000000,45000500,8000000|44999500,8000000,2s;45000500,8000000,2t
```

Il convertitore `scripts/offline_map/pbf_to_motomap.py` genera il formato v3 leggendo lo stesso PBF Geofabrik usato finora. Non richiede una seconda scansione completa del PBF; conserva gli identificativi nel medesimo passaggio di esportazione. Maggiori dimensioni dell'archivio sono previste per la presenza degli ID sui vertici.

## Algoritmo Android — filtro topologico storico, DISABILITATO nella scena attuale

1. Il lettore `HybridMapRepository` estrae `osmWayId`, `layer` e lista dei nodi parallelamente alle coordinate.
2. `RoadContextTopology` individua strade che **si sovrappongono alla geometria Valhalla** per almeno 12 m con distanza massima di 8 m e orientamento allineato (coseno >= 0,82).
3. L'unione dei nodi delle strade individuate determina i punti effettivamente connessi al percorso.
4. Fra le polilinee che attraversano il percorso in 2D, vengono scartate soltanto quelle senza nodi OSM condivisi. Le strade direttamente sul percorso, gli svincoli realmente connessi e quelle vicine che non attraversano il percorso restano visibili.
5. Se manca un abbinamento affidabile o se la riga è vecchia/senza metadati, il filtro **non elimina** quella strada. La navigazione e il BLE restano inalterati.

Dopo il rollback dell'esperimento `4226b63`, la selezione per prossimità GPS e percorso imminente è stata preservata; il budget attuale sperimentale è **20 polilinee / 160 vertici** per scena (8 vertici per strada ordinaria e fino a 16 per l'anello del focus). Firmware ESP32, protocollo BLE, routing Valhalla e zoom 3,4x invariati. È un compromesso sperimentale, non una soluzione definitiva per le rotonde.

## Rigenerazione e importazione

Usare il PBF Italia già utilizzato per la versione precedente:

```bash
python scripts/offline_map/pbf_to_motomap.py italy-261026.osm.pbf -o italy-v3.motomap.zip
```

Se necessario, aggiungere `--coverage-poly italy.poly` soltanto usando il confine `.poly` corrispondente alla stessa estrazione Geofabrik. Importare `italy-v3.motomap.zip` dalla gestione mappe Android e rimuovere il vecchio archivio per evitare duplicati e per assicurare che la scena utilizzi metadati v3. È necessaria anche la nuova build Android del branch di test; l'ESP32 deve soltanto avere la versione con palette/edifici già in prova.

## Limiti e verifiche

- La sola geometria Valhalla non è garantita coincidente con la way OSM vicino a rampe ravvicinate, strade parallele o strade su livelli differenti. Se la corrispondenza non è sicura il filtro preferisce mantenere il contesto.
- Gli ID nodo OSM garantiscono la topologia della mappa **non l'accessibilità legale o la direzione di marcia**; il filtro non modifica gli instradamenti.
- Sono necessari collaudi su cavalcavia senza rampa, sottopassi, svincoli con rampe, rotatorie e zone con mappatura incompleta.
- Alcune strade non connesse continueranno ad apparire come **contesto laterale** se non intersecano geometricamente la rotta; è intenzionale.
- Quando si importano più pacchetti con versioni diverse e zone sovrapposte, la deduplicazione non riconcilia le stesse way per ID, quindi utilizzare un solo archivio per l'Italia nel test.

## Prova negativa del selettore mirato alla rotta (4 novembre 2026)

Nel test del commit `4226b63`, il nuovo selettore privilegiava eccessivamente strade connesse alla rotta: molte scene con poche strade nonostante dati OSM disponibili, rotonde tardive e assenza di contesto in caso di deviazione prima del ricalcolo. L'algoritmo è stato **annullato** senza integrarlo in `valhalla`. L'ancoraggio grafico al progresso introdotto nello stesso commit è stato annullato insieme al selettore per ripristinare la configurazione di partenza del test. Le prossime soluzioni devono mantenere strade locali disponibili anche fuori rotta e preservare l'anello e tutte le uscite di una rotonda, senza confondere intersezione 2D e connettività OSM. Il filtro esistente non elimina ogni strada di livello diverso: questo resta aperto.

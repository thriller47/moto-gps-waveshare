# Filtro strade di contesto per cavalcavia e sottopassi

Stato: analisi; **non attivo** sul branch `test/road-contrast-cleanup-20261104`.

## Evidenza nel codice attuale

- `scripts/offline_map/pbf_to_motomap.py` esporta per ogni way soltanto classe, velocità, bounding box e coordinate. Non conserva OSM way ID, node ID condivisi, `bridge`, `tunnel`, `layer` o restrizioni d'accesso.
- `HybridMapRepository` ricostruisce `MapRoadSegment` con classe/velocità/bbox/punti.
- `NavigationViewModel.updateSpeedLimitAndMapScene` seleziona fino a 12 polilinee con al massimo 96 punti, inizialmente 6 vicine al pilota e 6 vicine alla rotta davanti.
- `MapScene` BLE contiene polilinee e classi, non informazioni altimetriche/topologiche.

## Obiettivo

Escludere le strade che passano sopra o sotto la rotta **senza collegamento percorribile**, ma conservare gli svincoli effettivi, le strade che confluiscono, le rotatorie e un contesto laterale limitato.

## Requisiti prima di attivare un filtro

1. Ottenere il grafo di connessione effettivo (nodi/archi del percorso Valhalla con corrispondenze alle way OSM, oppure formato MotoMap esteso con identificativi condivisi e `layer`/`bridge`/`tunnel`).
2. Impiegare la topologia come criterio di intersezione. **Non usare** il solo crossing tra polilinee 2D: un cavalcavia geometrico non è un nodo stradale.
3. Se metadati assenti (vecchi archivi `.motomap.zip`), conservare la selezione attuale. Non filtrare sulla base di ipotesi di quota o distanza soltanto.
4. Mantenere il limite BLE attuale di 12 strade/96 punti e il protocollo esistente; filtrare preferibilmente sul lato Android prima della selezione della scena.
5. Aggiungere fixture e test per: cavalcavia senza svincolo, sottopasso, rampa con connessione vera, rotonda adiacente, strada parallela, metadati assenti e vecchio pacchetto MotoMap.

**Impatto dati:** una soluzione tramite metadati MotoMap richiederebbe una rigenerazione dei pacchetti per beneficiare del filtro; non invalidare gli archivi Italia esistenti e non anticipare cambiamenti al firmware.

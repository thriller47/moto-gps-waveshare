# MotoMap v3: connettività OSM per le strade di contesto

**Stato:** implementazione sperimentale sul branch `test/road-contrast-cleanup-20261104` (4 novembre 2026). Non ancora collaudata su strada.

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

## Algoritmo Android

1. Il lettore `HybridMapRepository` estrae `osmWayId`, `layer` e lista dei nodi parallelamente alle coordinate.
2. `RoadContextTopology` individua strade che **si sovrappongono alla geometria Valhalla** per almeno 12 m con distanza massima di 8 m e orientamento allineato (coseno >= 0,82).
3. L'unione dei nodi delle strade individuate determina i punti effettivamente connessi al percorso.
4. Fra le polilinee che attraversano il percorso in 2D, vengono scartate soltanto quelle senza nodi OSM condivisi. Le strade direttamente sul percorso, gli svincoli realmente connessi e quelle vicine che non attraversano il percorso restano visibili.
5. Se manca un abbinamento affidabile o se la riga è vecchia/senza metadati, il filtro **non elimina** quella strada. La navigazione e il BLE restano inalterati.

La selezione finale continua a usare **12 polilinee / 96 vertici** per scena: nessun cambiamento al formato BLE, al firmware ESP32, al routing Valhalla o allo zoom 3,4x.

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

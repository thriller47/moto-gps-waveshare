# Valhalla offline — branch `valhalla`

Questo branch **non usa OSRM per il routing**: né il calcolo iniziale né
l'anteprima o il ricalcolo chiamano gli endpoint `/v1/routes` e
`/v1/route-options`. In assenza di dati Valhalla il calcolo si ferma
con un messaggio esplicito. **Il backend Render non cambia**; soltanto
l'APK di questo branch evita il routing del gateway.

## Primo test sul telefono

1. Installa l'APK Android del branch `valhalla`.
2. Apri **Gestione Mappe Offline → Routing Valhalla offline**.
3. Premi **Aggiorna regioni**, scegli un'area e **Scarica routing**.
   Per la Lombardia scegli **Italia Nord-Ovest** (include anche Piemonte,
   Liguria e Valle d'Aosta). Gli archivi possono pesare centinaia di MB.
4. Una volta installato il grafo, **disattiva Wi-Fi e rete dati**.
5. Per scegliere una destinazione offline usa una **pressione prolungata
   sulla mappa** (la ricerca testuale dei luoghi è ancora online).
6. Controlla il percorso sull'app e sull'ESP32, poi prova una deviazione
   per attivare il ricalcolo locale.

## Origine e formato

L'app interroga il catalogo dei pacchetti `.tar` delle release di
[Librescoot Valhalla Tiles](https://github.com/librescoot/valhalla-tiles/releases/tag/latest).
Sono grafi stradali derivati da OpenStreetMap; non sono file `.osm.pbf`,
`.motomap` o archivi compressi `.tar.zst`.

Il download avviene in streaming dentro un file provvisorio; dimensione
e SHA-256 sono confrontati con le informazioni pubblicate dalla release,
e si controlla che il tar contenga almeno un file graph tile `.gph`.
Solo dopo tutte le verifiche la nuova versione sostituisce quella precedente.
Se manca spazio, il trasferimento è interrotto, viene annullato o il checksum
non coincide, il vecchio grafo rimane disponibile.

Percorso dell'archivio:
```
Android/data/com.motogps.android/files/routing/valhalla_tiles.tar
```

## Limitazioni della fase di prova

- **Un solo archivio alla volta.** Scaricare una nuova regione sostituisce la precedente.
- Non sostituire o eliminare il grafo durante la navigazione: i controlli
  nell'app bloccano queste azioni.
- Il routing funziona soltanto per percorsi coperti dalle tile scaricate.
- La **ricerca indirizzi** e il caricamento delle **mappe di visualizzazione**
  possono ancora accedere alla rete. Solo **il calcolo del percorso** è
  esclusivamente offline.
- Non sono ancora disponibili download persistenti in background, unione
  di regioni né ricerca luoghi offline.
- La compatibilità delle tile di Librescoot con la versione nativa di
  Valhalla Mobile richiede ancora una prova reale: un tar integro non
  certifica la compatibilità binaria del grafo con la libreria.
- ESP32, formato BLE, `main`, `signal`, `NoOta` e Render non sono modificati.

Dati: © OpenStreetMap contributors (ODbL); archivi della community Librescoot.

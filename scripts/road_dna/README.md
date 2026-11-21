# Road DNA — confronto reale delle fonti POI (WSL, senza build)

Strumento esplorativo: [compare_sources.py](compare_sources.py). Confronta la presenza di luoghi **vicini a vere rotte GPX/GeoJSON** in tre fonti. Non modifica MotoPack, Valhalla o Photon installati.

**Stato 21/11/2026:** il primo test online dell'utente su 4 rotte è riuscito dopo aver corretto una chiave Geoapify incollata solo in parte; OpenTripMap e Geoapify hanno restituito dati. La prima scansione offline sul PBF Windows **è stata interrotta** per crescita dell'indice temporaneo `sparse_file_array` (>4 GB) e lentezza dei confronti. Lo script corrente adotta una lettura PBF in **due passaggi, senza indice posizioni OSM su disco**, e un reticolo spaziale per le rotte. **Versione ottimizzata ancora da provare sul PBF reale.** Nessuna build o GitHub Action avviata.

## Rotte da confrontare

Preparare **3–5 percorsi stradali reali**, esportati da un navigatore o planner in formato GPX con punti di traccia (`trkpt`) o GeoJSON `LineString`; sono accettati anche JSON route Moto Chat GPS con `geometry_gcj02`, `polyline` o `coordinates` **come lista di coordinate**. Un GPX con soli waypoint isolati non è sufficiente per valutare un corridoio. Esempi di scenari interessanti:
- montagna/tornanti: Cuneo – Sestriere;
- laghi: un giro intorno al Lago Maggiore;
- costa: una rotta nella Riviera ligure;
- città storiche: Torino e dintorni;
- terme/collina: un giro tra colline e località termali.

Le denominazioni sono proposte di test, **non geometrie già presenti**. I GPX devono seguire vere strade, non segmenti retti disegnati tra le città. Conservare le tracce private soltanto in locale: non caricarle su GitHub.

Esempio di cartella Windows: `C:\MotoGPS\RoadDNA\`. Mettervi `montagna.gpx`, `lago.gpx`, `costa.gpx` ecc.

## Controlli locali leggeri (senza compilazioni)

```bash
cd ~/MotoGPS/moto-gps-waveshare
python3 -m unittest discover -s scripts/road_dna -p 'test_*.py' -v
```

Sono test di parser GeoJSON/GPX, tag secondari e distanza dalla rotta. **Non** aprono il PBF, non eseguono richieste HTTP e non lanciano GitHub Actions.

## Prestazioni PBF (correzione 21/11/2026)

La prima versione creava un indice temporaneo `sparse_file_array` con posizioni di moltissimi nodi OSM (>4 GB nel primo test dell'utente). La versione aggiornata legge il PBF **due volte**: prima raccoglie i soli riferimenti dei nodi appartenenti a way turistiche, poi risolve **solo quei nodi**. Evita quindi il grande file `.idx`; mantiene però una struttura selettiva in memoria (fino a 3 milioni di riferimenti di nodo) e una tabella SQLite temporanea di *way* candidate, che possono comunque usare risorse non trascurabili. Se la soglia viene raggiunta, alcune way vengono saltate e riportate nel report. L'indice spaziale in memoria dei segmenti dei quattro itinerari riduce drasticamente i confronti di distanza. Log di avanzamento ogni 2 milioni di nodi OSM in entrambi i passaggi e messaggio prima del `VACUUM`. **Non sono ancora disponibili misure effettive di velocità/memoria della nuova versione.** I multipoligoni e le relazioni OSM restano dati incompleti. Se hai interrotto la scansione, fai `git pull --ff-only origin valhalla` e scegli una **nuova cartella `--output`**, perché il database SQLite viene creato da zero. Non altera MotoPack né avvia CI.

## 1. Avvio offline: inventario PBF e dimensione SQLite

In **WSL**, senza lanciare alcuna GitHub Action:

```bash
cd ~/MotoGPS/moto-gps-waveshare
git pull --ff-only origin valhalla
source ~/valhalla-env/bin/activate
python3 -c "import osmium; print('pyosmium OK')"

ls -lh /mnt/c/MotoGPS/RoadDNA/*.gpx

python3 scripts/road_dna/compare_sources.py \
  --pbf /mnt/c/MotoGPS/PBF/italy-latest.osm.pbf \
  --route /mnt/c/MotoGPS/RoadDNA/Montagna.gpx \
  --route /mnt/c/MotoGPS/RoadDNA/Costa.gpx \
  --route /mnt/c/MotoGPS/RoadDNA/Cultura.gpx \
  --route /mnt/c/MotoGPS/RoadDNA/Laghi.gpx \
  --buffer-m 2000 \
  --output ~/motogps-build/road-dna-comparison-v2
```

I nomi `Montagna.gpx`, `Costa.gpx`, `Cultura.gpx`, `Laghi.gpx` sono derivati dalle etichette presenti nel report online; **verificarli con `ls`** e adattare maiuscole/estensioni ai file effettivi. La cartella di output `road-dna-comparison-v2` deve essere nuova. Le precedenti cartelle di output e indici temporanei **non vengono riutilizzati**.

Se `import osmium` fallisce, con l'ambiente di lavoro attivo installare il solo modulo Python per lettura OSM: `pip install osmium`. Non reinstallare Valhalla.

Risultati:
- `report.json`: conteggi per categoria nell'intero PBF; conteggi **approssimativi** entro 2 km dai vertici delle rotte; relazioni OSM contate separatamente ma **non assegnate** a una rotta.
- `tourist-pois-proxy.sqlite`: **database sperimentale autonomo**, con coordinate e indice. La dimensione file è un indicatore approssimativo, **NON** l'incremento verificato del MotoPack né la dimensione compressa.
- Nessun file esistente sovrascritto **tranne i file di output della cartella scelta**. Per una nuova prova usare una nuova cartella, perché l'indice SQLite esistente viene creato con `CREATE TABLE`.

**Limiti del conteggio offline:** vengono indicizzati nodi e *way* OSM, ma le relazioni multipoligonali non hanno coordinate ricostruite. Per una *way* il calcolo distanza usa vertici della geometria, non un'intersezione esatta dei poligoni; ciò può escludere laghi, riserve e grandi parchi rappresentati con relazioni, e non rappresenta ingressi o raggiungibilità. Prima di decidere se integrare POI turistici nel MotoPack dovremo completare il trattamento delle relazioni oppure limitarci a POI puntuali affidabili.

## 2. Confronto online opzionale (serve account e API key)

- Geoapify: [API key](https://myprojects.geoapify.com/) e [Places API](https://apidocs.geoapify.com/docs/places/places/).
- OpenTripMap: [API key](https://dev.opentripmap.org/) e [piano](https://dev.opentripmap.org/price).

```bash
# Stessa sessione WSL, con il venv già attivo.
read -r -s -p "Geoapify key: " GEOAPIFY_API_KEY; echo
export GEOAPIFY_API_KEY
read -r -s -p "OpenTripMap key: " OPENTRIPMAP_API_KEY; echo
export OPENTRIPMAP_API_KEY

python3 scripts/road_dna/compare_sources.py \
  --route /mnt/c/MotoGPS/RoadDNA/montagna.gpx \
  --route /mnt/c/MotoGPS/RoadDNA/lago.gpx \
  --route /mnt/c/MotoGPS/RoadDNA/costa.gpx \
  --buffer-m 2000 --online \
  --output ~/motogps-build/road-dna-online

unset GEOAPIFY_API_KEY OPENTRIPMAP_API_KEY
```

**Privacy:** `--online` invia la **geometria completa** della rotta a Geoapify e tre campioni a OpenTripMap; l'accesso ai due servizi è una scelta deliberata, mai un'operazione in background. Le chiavi sono lette da variabili d'ambiente e non salvate nel report.

**Limiti delle API/test:** Geoapify usa una singola interrogazione di tipo `POST polyline` con `buffer=2000`, **prima pagina di massimo 100 luoghi**; oltre quel limite, il report marca `partial_limit_hit`. Se la geometria supera 10.000 punti/102.400 byte, la richiesta viene saltata. OpenTripMap usa **tre interrogazioni a raggio** su punti campionati e un massimo di 100 risultati ciascuna: `sample_only` significa volutamente **non esaustivo** e i suoi conteggi non sono direttamente equivalenti. Errori HTTP e quote sono annotati senza ritentativi aggressivi. Non utilizzare questo report per proclamare un vincitore assoluto.

## 3. Valutazione finale

Confrontare categorie e affidabilità, non soltanto il totale dei risultati:
1. casi chiave realmente trovati e identificati per nome;
2. false corrispondenze (per es. `natural.water` non equivale a lago), duplicati e POI non raggiungibili;
3. copertura delle rotte e risultati tagliati;
4. tempo del servizio, eventuali errori/quote;
5. peso del database e tempo necessario alla scansione PBF.

Per il primo giro di prova preferire **3 rotte brevi** (circa 40–120 km) invece di 5 giri lunghissimi. Non registrare coordinate nel report che si desidera condividere; `tourist-pois-proxy.sqlite` contiene coordinate OSM e va mantenuto localmente. Il report JSON contiene solo il **nome del file**, conteggi e stati dei provider, non la traccia.

### Unica regola di sicurezza del progetto

**Questo script non esegue compilazioni, non importa/riscrive MotoPack, non cambia APK e non avvia GitHub Actions.** Gli aggiornamenti del repository sono marcati `[skip ci]`.

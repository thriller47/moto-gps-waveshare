# Road DNA — confronto reale delle fonti POI (WSL, senza build)

Strumento esplorativo: [compare_sources.py](compare_sources.py). Confronta la presenza di luoghi **vicini a vere rotte GPX/GeoJSON** in tre fonti. Non modifica MotoPack, Valhalla o Photon installati.

**Stato 21/11/2026:** codice e procedura preparati. **Non eseguito** su `italy-latest.osm.pbf` dell'utente (il file è soltanto sul suo PC) e non sono disponibili chiavi API Geoapify/OpenTripMap. I numeri reali compaiono in `report.json` dopo l'esecuzione locale. La suite GitHub Actions resta ferma.

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

## 1. Avvio offline: inventario PBF e dimensione SQLite

In **WSL**, senza lanciare alcuna GitHub Action:

```bash
cd ~/MotoGPS/moto-gps-waveshare
git pull --ff-only origin valhalla
source ~/valhalla-env/bin/activate
python3 -c "import osmium; print('pyosmium OK')"

python3 scripts/road_dna/compare_sources.py \
  --pbf /mnt/c/MotoGPS/PBF/italy-latest.osm.pbf \
  --route /mnt/c/MotoGPS/RoadDNA/montagna.gpx \
  --route /mnt/c/MotoGPS/RoadDNA/lago.gpx \
  --route /mnt/c/MotoGPS/RoadDNA/costa.gpx \
  --buffer-m 2000 \
  --output ~/motogps-build/road-dna-comparison
```

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

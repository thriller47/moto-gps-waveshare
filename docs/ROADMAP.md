# Moto Chat GPS — Roadmap operativa

**Aggiornata:** 19 novembre 2026 · **Integrazione:** `valhalla` · **Sviluppo e verifiche:** `test/waypoint-markers-20261119`.

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

## Priorità 1 — Ricerca Photon locale e fallback online (IMPLEMENTATA, DA RICOLLAUDARE)

Nel branch di test sono presenti: fallback a Photon online quando la ricerca SQLite v2 restituisce zero risultati; ranking offline con preferenza geografica; ricerca online fino a 30 candidati e ranking nell'app fino a 8 risultati; distanze in linea d'aria per risultati di destinazioni e tappe quando il GPS è disponibile.

**Verifiche pendenti:** nuova ricerca "Via Roma" dalla stessa posizione, "Via Roma, Torino", destinazione estera assente dal DB Italia, POI di marca vs parola generica, GPS non disponibile, assenza rete, consistenza fra classificazione Photon online/offline. Verificare compilazione e test del **commit finale di codice**; una CI verde su commit precedente non valida i successivi `[skip ci]`. La prova utente precedente conferma solo il miglioramento iniziale dell'offline, non il ranking online nuovo.

**Limiti da non mascherare:** massimo 30 candidati recuperati dall'endpoint online e 4.000 nella preselezione SQLite possono ancora perdere località se il servizio/indice non le restituisce. Non promettere ricerca geografica esaustiva dei distributori senza indice POI per categoria/prossimità.

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

# Valutazione delle proposte heading QMI8658/GNSS — 17/11/2026

## Stato di partenza verificato

- Gyro fusion su ESP32: guadagno 0,22; nessun ramo gain=0,82; correzione sospesa quando |yaw|>5dps
- Android: speed>=3m/s, bearingAccuracy<=25 gradi se disponibile, displacement fallback>=12m
- Protocollo BLE: NavigationSnapshot v1 finisce con due stringhe e decoder richiede end-of-payload: estensione non negoziata romprebbe un vecchio ESP.
- Incoerenza residua: ESP accettava course da 1,5m/s (Android ancora trattava come held finche <3).

## Modifiche sperimentali

1. Allineato ESP moving course a 3m/s, con rilascio IMU a 1m/s, fermo sotto 0,6. Evita di reancorare sul valore Android conservato.
2. Correzione scalare dipendente da sigma angolare. Precisione GNSS opzionale nell'API, fallback velocita in attesa di payload BLE compatibile. Primo moving course snap solo con sigma<=30. Jump >80 gradi confermato da due ancore coerenti (se sigma>12).
3. Gyro bias autoappreso da fermo con esclusione |raw yaw|>=3dps, al ritmo di 0,002/campione, conserva bias nei reset; deadband 0,3dps.
4. Fallback Android displacement>=12m e orizzontale accuracy<=displacement/3, quando precisione disponibile.
5. Test sintetici native e Android.

## Non implementato e perche

- Trasporto `bearingAccuracyDegrees` nel BLE: necessita di capacità negoziata e compatibilità downgrade; attuale payload revision 1 rifiuta trailer inattesi. Non alterare simultaneamente decoder e encoder senza verifiche con vecchia versione.
- Timeout staleness 10s: necessario anche definire semantica e badge diagnostico; la disconnessione BLE e il replay gia hanno flags di stale, ma non la qualita IMU.
- Proiezione yaw su inclinazione e sterzo: implica modello di montaggio/assetto, non misurabile direttamente dal replay web.
- Predizione lungo tangente Valhalla: esperimento futuro, no map matching affidabile garantito ai rami.

## Convalida

Le build CI test e la verifica sulla scheda reale restano distinte. Non abilitare una patch nel branch stabile prima di test su strada, ritardi in curve lente e rotonde.

**Revisione del fallback:** senza accuratezza BLE, la sigma minima stimata dalla velocità è 20° (non 8°). Il replay delle 07:49:53 mostra un bearing errato di ~40° a ~8m/s; far crescere troppo la fiducia senza accuracy reale potrebbe peggiorare lo scatto della mappa. Il guadagno massimo del fallback è quindi 100/(100+400)=0,20; con accuracy realmente disponibile la formula usa invece quella misura.

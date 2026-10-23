# Analisi del log seriale (23 ottobre 2026) — branch chatgpt-test

## Problema riproducibile dal codice: limite ATT/CRC

Il log mostra `ATT MTU=185, protocol frame=182`, poi più volte
`frame rejected: crc_mismatch at 180` e `missing_start at 1038`.
L'app Android impostava il limite del codec a `mtu.coerceAtMost(185)`:
a MTU 185 ciò consentiva frame da 185 byte, mentre un'operazione ATT
Write Without Response può trasportare solo `MTU - 3 = 182` byte.
Il troncamento/rifiuto di una write può compromettere il CRC e la
ricomposizione dei frammenti. Si tratta di una causa verificabile nel
codice e coerente con il log, non di una prova strumentale che il driver
radio abbia fisicamente troncato i frame.

Correzioni:
- codec inizializzato con limite prudente di 20 byte (ATT MTU 23);
- limite TX impostato a `min(mtu - 3, 182)` dopo negoziazione riuscita;
- limite ripristinato a 20 su una nuova connessione o MTU negotiation fallita;
- regressione su payload frammentato che verifica dimensioni e CRC.

## Altre anomalie del log e verifica necessaria

1. **SPI LCD:** `DMA TX underflow detected` e
   `panel_co5300_draw_bitmap: send color data failed`.
   Il display usa due buffer da 320 righe in PSRAM e
   `psram_dma_direct=true` a 40 MHz QSPI. Un underflow non prova
   che l'allocazione abbia fallito; possibili cause comprendono
   contesa di accesso PSRAM/cache, throughput DMA, sincronizzazione
   dei trasferimenti e frequenza del bus. Non abbassare a caso la
   frequenza o cambiare la configurazione DMA senza benchmark su scheda.
   Riprovare con e senza BLE e con frequenza QSPI ridotta, raccogliendo
   percentuale di underflow, FPS e heap minimo.
2. **Memoria:** circa 49 KiB heap interno libero, minimo 7 KiB
   nell'istantanea. Monitorare il minimo sotto carico BLE e rendering.
3. **I2C:** avvertimento sulle resistenze pull-up; controllare
   cablaggio e valori effettivi con oscilloscopio se si verificano errori.
4. **GPIO ISR:** `GPIO isr service already installed` seguito da
   registrazione corretta del touch: warning non necessariamente fatale.
5. **NimBLE:** `Failed to persist local IRK (rc=8)` e
   `Failed to restore IRKs from store`: verificare NVS/persistenza
   delle chiavi se pairing e bonding falliscono.
6. **Prestazioni:** circa 19.8 FPS; massimo render 99.2 ms.
   Profilare la UI sotto traffico mappa e rotte.
7. **Tipo di crash:** non compare alcun backtrace, reset reason o
   `Guru Meditation Error`; il log allegato documenta errori di
   trasporto e visualizzazione, non consente di localizzare
   un'eccezione fatale.

## Validazione consigliata

- Android: eseguire `./gradlew testDebugUnitTest` nella directory
  `platforms/android` (richiede Android SDK/Gradle configurato).
- ESP32: compilare con versione ESP-IDF e dipendenze del repository,
  flashare su hardware Waveshare reale e verificare le operazioni BLE.
- Dopo connessione verificare MTU 185, frame 182 ed **assenza di
  nuovi `crc_mismatch`** durante invio di geometrie/mappe grandi.
- Misurare separatamente `DMA TX underflow` durante UI inattiva,
  UI animata, BLE collegato e trasferimento di mappe.
- Non effettuare merge senza verificare regressioni hardware su
  firmware 0.2.1 e app Android.

Nota: il log cita un iPhone collegato, mentre il bug corretto riguarda
il client Android. Questa correzione Android non risolve da sola eventuali
errori di frammentazione nel client iOS; è necessaria una verifica
separata del trasmettitore iOS.

## Confronto con il progetto iOS di origine

Sorgente di riferimento: https://github.com/mx3353672833-debug/moto-gps-waveshare/tree/main/platforms/ios

Il trasmettitore iOS in `App/Adapters/BLE/ESP32BLECentral.swift`
calcola il limite dei dati per write tramite
`peripheral.maximumWriteValueLength(for: writeType)`, lo limita
al massimo del protocollo e poi negozia il minimo con il dispositivo.
Per `.withoutResponse` usa `canSendWriteWithoutResponse` e pacing
di 15 ms. Quindi l'errore Android `maxFrameSize=mtu` non è osservabile
direttamente in questo codice iOS: **non attribuire gli errori CRC del
log iPhone alla correzione Android**.

Nel firmware `BleNavTransport::gatt_access` copia il contenuto completo
dell'mbuf tramite `ble_hs_mbuf_to_flat` e controlla che il numero di
byte copiati corrisponda a `OS_MBUF_PKTLEN`. Il parser confronta il
CRC dei byte effettivamente ricevuti. L'offset 180 del CRC è coerente
con frame BLE lunghi 182 byte (ultimi 2 byte CRC), ma non identifica
da solo il punto in cui i dati cambiano.

Prossimi controlli mirati: eseguire una prova di compatibilità
incrociata tra byte prodotti dal codec iOS e parser nativo ESP32;
registrare lunghezza, sequenza, offset frammento, CRC atteso e reale
soltanto sui frame rifiutati; verificare che il firmware in esecuzione
sia prodotto esattamente dal commit analizzato e che le code non
vengano svuotate durante messaggi frammentati. Non registrare payload
contenenti posizione privata in log di produzione.

# MotoGPS diagnostica delle prove su strada

Questa build salva due log persistenti anche senza portatile e senza Internet.

## Android
Dopo il giro apri Moto GPS, tocca **Condividi / Esporta diagnostica**
(l'icona di condivisione nella barra alta) e salva lo ZIP. Include il log
corrente e quello precedente: fino a circa 768 KiB. Non disinstallare l'app o
cancellare i dati prima dell'esportazione. I normali aggiornamenti APK
preservano i log.

## ESP32
Il firmware salva record binari di 64 byte in una partizione flash dedicata da
256 KiB all'indirizzo **0xC20000**, fuori dall'area del firmware, di NVS e di
`storage`. I record vecchi vengono riscritti a rotazione per settori;
non si scrive ogni pacchetto BLE, e le operazioni flash avvengono fuori dal
callback BLE in una coda limitata.

**Al primo flash:** installare anche la nuova
`partition-table.bin` oltre al firmware: altrimenti la partizione `diag`
non sarà riconosciuta. Non fare una cancellazione completa della flash.

Dopo il giro, collega l'ESP32 a Windows con un cavo USB dati e usa PowerShell
con esptool (sostituisci COM5 con la porta corretta):

```powershell
python -m esptool --chip esp32s3 -p COM5 read_flash 0xC20000 0x40000 esp-diag.bin
python scripts/diagnostics/decode_esp32_trace.py esp-diag.bin
```

Alcune versioni di esptool accettano `read-flash` anziché `read_flash`.
Il decoder produce **esp-diag.csv**, ordinato per sequenza. Invia il CSV e
lo ZIP Android per confrontare manovre BLE ricevute, rendering ESP32, assenza
strade grigie, GPS, direzione e ricalcoli.

Record ESP32:
- NAV_SNAPSHOT: ID manovra, metri alla svolta, heading ricevuto dal telefono
  (centesimi di grado), velocità (decimi di km/h), flag e tipo manovra
- MAP_SCENE: revisione, numero strade, punti e edifici
- ESP_RENDER: tipo manovra, distanza, punti percorso, strade e zoom attivo
- LINK: collegamento, BOOT: motivo del riavvio

I log contengono orari, velocità e informazioni sulle manovre, anche se non
contengono posizioni GPS precise o nomi di strade: condividili privatamente.
La partizione è circolare e il file Android ruota; salva i file dopo la prova.

## Interpretazione dei blocchi grafici

Il tracciato registra ora `RENDER_PHASE` con questi valori in colonna `a`:
1 prima della copia dello snapshot, 2 prima della proiezione, 3 prima del
mutex LVGL, 4 dopo l'acquisizione del mutex, 5 dopo l'applicazione del frame.
`DISPLAY_LOCK_TIMEOUT` significa che il mutex del display è rimasto occupato
per 250 ms; il task tenta nuovamente senza riavviare né interrompere il BLE.
`ESP_RENDER` viene ora scritto solo dopo il ritorno dell'aggiornamento UI.
Questo permette di distinguere il blocco del rendering dalla ricezione BLE.

## Test A/B stabilità display (diagnostica ESP32 disattivata)

Durante la prova senza MotoMap, il display si bloccava ripetutamente dopo
l'introduzione della diagnostica flash. La build normale ora compila
`MOTO_ESP_DIAG_FLASH_ENABLED=OFF` di default. Questo **elimina del tutto**
la scansione e le scritture flash del logger (anche i 64 byte ogni 25 ms
quando erano presenti eventi), senza modificare il renderer, la navigazione,
la fusione GPS/giroscopio o lo zoom. Non ripartiziona e non cancella i log
già presenti in `0xC20000`: sono ancora leggibili con esptool.

Durante questo test non verranno registrati nuovi eventi ESP32; i log Android
rimangono disponibili con il pulsante Condividi. Il test serve a stabilire
se le scritture flash erano la causa dei blocchi LVGL. Solo per build da banco,
è possibile riabilitare esplicitamente la diagnostica ESP32 passando
`-DMOTO_ESP_DIAG_FLASH_ENABLED=ON` alla configurazione CMake.

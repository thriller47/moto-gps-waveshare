# ESP32: diagnostica attivabile senza scritture flash

Branch: `valhalla`. Il vecchio sistema `moto_diagnostics.cpp` esiste
solo come riferimento storico e **non** viene compilato. Il firmware
include `moto_diagnostics_ram.cpp`, con 64 KiB di PSRAM.

## Utilizzo

1. Collegare normalmente Android all'ESP32.
2. Lasciare **LOG > OFF** durante le normali prove di navigazione.
3. In caso di anomalia aprire **LOG > BASE** (soli eventi critici)
   o **LOG > DEBUG** (fotogrammi, BLE e navigazione).
4. La registrazione si disattiva automaticamente dopo **60 minuti**; si
   disattiva anche alla disconnessione BLE e al riavvio dell'ESP32.
   Un'eventuale nuova selezione BASE/DEBUG riavvia la cattura.
5. Con la connessione ancora attiva premere il pulsante **Condividi**
   (icona nella barra superiore) per esportare lo ZIP Android. Include
   `esp32-current.log`/`esp32-previous.log` se sono arrivati eventi.

## Sicurezza e limiti

- OFF all'avvio: nessun comando diagnostico viene inviato alla connessione.
- Nessuna partizione, file, NVS, lettura o scrittura flash ESP32.
- Buffer circolare di massimo 2048 eventi numerici; gli eventi più
  vecchi vengono sovrascritti.
- Un massimo di 4 eventi per pacchetto BLE e **un pacchetto al secondo**.
  Nessuna trasmissione dal callback BLE o dal task grafico.
- I log Android e ESP32 sono persistenti sull'app, con due file a rotazione
  per sorgente: **1 MiB per file Android, 2 MiB per file ESP32** (massimo
  indicativo 6 MiB più lo ZIP condiviso). I vecchi dati sono sovrascritti
  automaticamente, senza necessità di un pulsante per cancellarli.
- La sessione dura al massimo 60 minuti, ma il ring PSRAM resta da 2048
  eventi: se vengono prodotti più di 4 eventi al secondo per periodi
  prolungati, quelli non trasmessi possono venire sovrascritti.
- Nessuna coordinata GPS né nome strada nei pacchetti diagnostici ESP32.
  I dati di navigazione normali restano naturalmente sul canale BLE esistente.
- I record non ancora ricevuti da Android si perdono in caso di
  spegnimento/riavvio/disconnessione. OFF non trasferisce dati arretrati.
- L'attivazione impone un carico aggiuntivo, quindi **DEBUG va utilizzato
  solo per diagnosticare un problema**, preferibilmente in prova controllata.
- Eventi: 1=avvio cattura, 2=link, 3=snapshot, 4=mappa,
  5=render, 6=fase render, 7=timeout lock display, 8=tempi rendering.
- **Evento 8 = RENDER_TIMING**, registrato al massimo ogni 2 secondi solo con
  **LOG > DEBUG**. Campi: `a` = tempo di proiezione/preparazione in µs,
  `b` = attesa di acquisizione lock LVGL in µs,
  `c` = tempo dentro il lock in µs, `d` = maschera frame
  (1=navigazione, 2=movimento IMU, 4=media), `e` = riservato.
  Le misure vengono emesse dopo lo sblocco del display; nessun dato GPS.
  Un campione ogni 2 secondi **non misura il massimo assoluto**:
  combinare con evento 7 per i timeout e confrontare più prove.
- Per un confronto sulla stabilità usare sempre la configurazione **OFF**.

La capacità diagnostica è opzionale (bit `CapabilityDiagnosticsRam`) e
usa messaggi BLE 0x21 (comando modalità) e 0x22 (batch eventi).
Il protocollo di navigazione rimane invariato.

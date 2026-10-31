# ESP32: diagnostica attivabile senza scritture flash

Branch: `valhalla`. Il vecchio sistema `moto_diagnostics.cpp` esiste
solo come riferimento storico e **non** viene compilato. Il firmware
include `moto_diagnostics_ram.cpp`, con 64 KiB di PSRAM.

## Utilizzo

1. Collegare normalmente Android all'ESP32.
2. Lasciare **LOG > OFF** durante le normali prove di navigazione.
3. In caso di anomalia aprire **LOG > BASE** (soli eventi critici)
   o **LOG > DEBUG** (fotogrammi, BLE e navigazione).
4. La registrazione si disattiva automaticamente dopo 10 minuti; si
   disattiva anche alla disconnessione BLE e al riavvio dell'ESP32.
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
- Il file su Android è persistente e limitato/ruotato come i normali log.
- Nessuna coordinata GPS né nome strada nei pacchetti diagnostici ESP32.
  I dati di navigazione normali restano naturalmente sul canale BLE esistente.
- I record non ancora ricevuti da Android si perdono in caso di
  spegnimento/riavvio/disconnessione. OFF non trasferisce dati arretrati.
- L'attivazione impone un carico aggiuntivo, quindi **DEBUG va utilizzato
  solo per diagnosticare un problema**, preferibilmente in prova controllata.
- Eventi: 1=avvio cattura, 2=link, 3=snapshot, 4=mappa,
  5=render, 6=fase render, 7=timeout lock display.
- Per un confronto sulla stabilità usare sempre la configurazione **OFF**.

La capacità diagnostica è opzionale (bit `CapabilityDiagnosticsRam`) e
usa messaggi BLE 0x21 (comando modalità) e 0x22 (batch eventi).
Il protocollo di navigazione rimane invariato.

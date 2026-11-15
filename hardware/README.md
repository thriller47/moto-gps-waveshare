# Hardware storico di Moto Chat GPS

**Configurazione in uso:** scheda commerciale **Waveshare ESP32-S3-Touch-AMOLED-1.75C**, display 466×466, 8 MB PSRAM e 32 MB flash. Le personalizzazioni attuali sono software e nel supporto meccanico di prova.

Questa cartella conserva **progetti hardware precedenti, sorgenti CAD e documentazione di verifica**. Non appartengono al firmware operativo e non devono essere confusi con un prodotto pronto per la fabbricazione. Alcuni allegati tecnici originali rimangono in inglese/cinese perché sono documenti dei fornitori o note progettuali da preservare.

## Materiali da conservare

- [Rev A1 — progetto KiCad e materiali di revisione](manufacturing/engineering-candidates/rev-a1-h0175-evt1-20260903/) — sorgenti, simboli/footprint, BOM/CPL, Gerber, disegni e controlli.
- [Rev A0 R4 e revisioni precedenti](manufacturing/engineering-candidates/) — materiali storici; verificare il contenuto effettivo prima di ordinare.
- [Schemi e interfacce](rev_a/) — documentazione del progetto elettrico e riferimenti Waveshare/FPC.
- [Meccanica, STEP, STL e modelli](mechanical/) — CAD per culla, supporti e prototipi.
- [Strumenti hardware](../scripts/hardware/README.md) — utilità di generazione/verifica.

**ATTENZIONE:** i pacchetti `DO_NOT_ORDER` sono sperimentali e non sono autorizzati per la produzione. Non eliminare asset KiCad, footprint, modelli CAD, disegni o datasheet per il solo fatto che abbiano nomi non italiani: servono per la riproducibilità e per le licenze di terzi.

Per la compilazione del firmware Waveshare corrente usare [il manuale operativo](../docs/MANUALE_OPERATIVO.md). Per licenze e attribuzioni vedere [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md).

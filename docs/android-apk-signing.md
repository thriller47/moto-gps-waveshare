# Android Moto-GPS: icona, versionamento e firma persistente

## Icona launcher

L'icona fornita dall'utente e stata aggiunta in
`app/src/main/res/drawable-nodpi/moto_gps_icon.webp`,
con un'icona adattiva in `mipmap-anydpi-v26/ic_launcher.xml`.
Il manifest fa riferimento a `@mipmap/ic_launcher` sia per l'icona
standard sia per quella rotonda. Il progetto supporta Android API 26+.

## Perche gli APK GitHub non si aggiornano

Android consente un aggiornamento in-place solo se il nuovo pacchetto
ha lo stesso `applicationId`, una firma compatibile e un `versionCode`
non inferiore. Il precedente workflow generava APK `debug` su runner
temporanei senza conservare la chiave; inoltre `versionCode` era fisso a 1.

Questa modifica usa `github.run_number` per valorizzare
`MOTO_GPS_VERSION_CODE`. Senza variabili, in locale usa 2.
Il workflow firma il debug APK e il release APK con la medesima chiave
permanente **solo quando le credenziali sono configurate**.

## Configurare i GitHub Secrets (operazione obbligatoria una tantum)

La chiave di firma NON deve essere inserita nel repository.
Creare una chiave JKS sul PC usando il JDK `keytool`:

```bash
keytool -genkeypair -v -keystore moto-gps-release.jks -alias moto-gps \
  -keyalg RSA -keysize 3072 -validity 10000
```

In GitHub -> repository -> Settings -> Secrets and variables -> Actions
inserire i seguenti **repository secrets**:

| Nome | Valore |
| --- | --- |
| `MOTO_GPS_KEYSTORE_BASE64` | Base64 del contenuto binario di `moto-gps-release.jks` |
| `MOTO_GPS_KEYSTORE_PASSWORD` | Password del keystore |
| `MOTO_GPS_KEY_ALIAS` | Alias, nell'esempio `moto-gps` |
| `MOTO_GPS_KEY_PASSWORD` | Password della chiave |

Per calcolare Base64 su Linux:

```bash
base64 -w 0 moto-gps-release.jks
```

Su Windows PowerShell:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\\percorso\\moto-gps-release.jks"))
```

Conservare il file JKS e le password in un backup privato: perderli
impedisce gli aggiornamenti futuri con la stessa firma.

Quando il secret Base64 manca, il workflow continua a generare l'APK
debug firmato con la chiave temporanea Android e scrive un avviso.
Non e quindi ancora garantito l'aggiornamento senza reinstallazione
finche i quattro secret non sono configurati e verificati.

**Prima migrazione:** se la firma dell'APK gia installato e diversa
dalla nuova firma persistente, Android richiedera **una sola ultima
reinstallazione**. Prima di disinstallare, salvare una copia esterna
di `Italy.tar.zst` e dei pacchetti `.motomap.zip`, poiche i file
delle cartelle private dell'app possono essere eliminati.
Dopo la migrazione, firmare ogni successiva build con la stessa chiave.

## Build

Il workflow rimane avviabile manualmente tramite `workflow_dispatch`
e i successivi build potranno essere eseguiti quando richiesto.
Questo commit usa `[skip ci]` per non avviarne alcuno automaticamente.

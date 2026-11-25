# Anello

App Android personale per vedere i dati dell'anello Oura (Ring 4) senza dipendere
dall'abbonamento. Nessun account e nessun server: i dati restano sul telefono.

## Come funziona

```
Anello Oura ──Bluetooth──▶ App Oura ──▶ Health Connect ──▶ Anello (questa app)
```

L'app ufficiale Oura sincronizza l'anello e scrive i dati in **Health Connect**.
Anello li legge, li salva in locale (per tenere lo storico oltre i 30 giorni di Health Connect)
e calcola i propri punteggi:

- **Sonno**: durata, efficienza, sonno profondo, REM, risvegli
- **Recupero**: HRV e FC a riposo rispetto alla tua media degli ultimi 14 giorni, sonno, debito di sonno
- **Attività**: passi e calorie attive rispetto all'obiettivo

Gli algoritmi sono di questa app (vedi `app/src/main/java/it/anello/app/score/Scores.kt`),
non quelli proprietari di Oura.

## Installazione

1. Vai su **Actions** in GitHub → ultima esecuzione di "Build APK" → scarica l'artefatto `anello-apk`.
2. Estrai lo zip e apri `app-debug.apk` sul telefono (consenti "installa app sconosciute").
3. Nell'app Oura: **Impostazioni → Health Connect** → attiva la condivisione di tutti i dati.
4. Apri Anello e concedi i permessi di lettura.

## Da verificare senza abbonamento

Prima di disdire, controlla che l'app Oura continui a scrivere i dati in Health Connect
anche senza abbonamento. Nella scheda **Impostazioni** di Anello c'è il pulsante
"Verifica quali app scrivono il sonno" per controllarlo.

Finché l'abbonamento è attivo, scarica anche l'esportazione completa dei dati dal tuo
account sul sito di Oura e conservala.

## Sviluppo

- Kotlin + Jetpack Compose, `minSdk 28`
- Compilazione: `./gradlew assembleDebug` (serve l'Android SDK) oppure GitHub Actions
- `app/debug.keystore` è una chiave di debug fissa, così ogni nuova versione si installa
  sopra la precedente senza perdere i dati. Non usarla per pubblicare app.

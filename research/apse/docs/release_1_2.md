# Release 1.2 — riferimento HRV robusto, dati reali, W′bal

Prototipo di ricerca. Nessuna validazione su dati fisiologici con misure di
riferimento: i numeri qui sotto vengono da simulazioni, che verificano il
comportamento del software, non la fisiologia.

## 1. Riferimento HRV (sostituisce la baseline protetta 1.1)

### Problema della 1.1
La baseline protetta (dual.py) aveva una soglia fissa del 20%: sopra la soglia
si congelava per sempre, sotto la soglia continuava ad adattarsi. Tre scenari
lo mostrano (RMSSD dal dispositivo, sonno 8 h):

| Scenario | 1.1 | 1.2 |
|---|---|---|
| A. Cambio permanente −26% (es. cambio di anello) | allarme per sempre, recupero bloccato a 69 | stesso dispositivo: allarme, dopo 28 giorni "cambiamento persistente", ricalibrazione su conferma; dispositivo dichiarato diverso: nessun allarme, baseline separata |
| B. Calo graduale −1%/giorno | nessun allarme in 40 giorni, recupero 95 → 82 | allarme dopo ~10 giorni, recupero 98 → 66 |
| C. Calo persistente −15% | assorbito: recupero risale a 95 in 40 giorni | allarme dopo ~3 giorni, recupero resta 81 |

### Modello 1.2 (apse/reference_model.py)
- Riferimento = mediana di lnRMSSD negli ultimi 60 giorni esclusi gli ultimi 7,
  stesso dispositivo. Durante l'avvio (< 14 giorni nella finestra) riferimento
  provvisorio su tutta la storia disponibile, segnalato `provisional`.
- Fascia normale: riferimento ± 0,5·SD (minimo 0,05 ln).
- Giorno basso: calo > 20% (brusco) oppure media 7 giorni sotto il riferimento
  di più di 1·SD (minimo 0,05) con valore del giorno sotto il riferimento.
- Episodio: 3 giorni bassi consecutivi. Rilascio: 3 giorni consecutivi nella
  fascia. Un giorno senza dati non rilascia.
- I giorni di un episodio non entrano nel riferimento; all'inizio dell'episodio
  il riferimento viene congelato. I singoli giorni bassi invece restano: una
  prima versione li escludeva e generava falsi allarmi a catena (il riferimento,
  privato solo dei valori bassi, saliva).
- Dopo 28 giorni di episodio: stato `persistent_change`. Solo
  `Observation.rebaseline=True` riparte da zero. L'HRV da sola non distingue
  fatica da un cambiamento benigno (farmaco, stagione, dispositivo).
- `Observation.device`: storia separata per dispositivo. Nella stessa notte
  Ring 4 (via app Oura) e Gen3 (lettura diretta) hanno dato 47 e 35 ms.

### Taratura della soglia di avvio (simulazioni)
lnRMSSD = ln 50 + rumore gaussiano indipendente; 60 simulazioni per riga;
"falsi ep/anno" su 335 giorni di solo rumore; ritardo dal giorno del cambio.

| Soglia media 7 g | rumore SD | falsi ep/anno | −25% rilevato | −15% rilevato | −1%/g rilevato |
|---|---|---|---|---|---|
| 0,5·SD | 0,10 | 3,17 | 100% in 3 g | 100% in 5 g | 100% in 11 g |
| 0,5·SD | 0,15 | 3,83 | 100% in 4 g | 100% in 7 g | 100% in 12 g |
| 0,75·SD | 0,10 | 1,02 | 100% in 3 g | 100% in 6 g | 100% in 13 g |
| 0,75·SD | 0,15 | 1,33 | 100% in 4 g | 95% in 10 g | 100% in 16 g |
| **1,0·SD (scelta)** | 0,10 | **0,25** | 100% in 4 g | 98% in 7 g | 100% in 16 g |
| **1,0·SD (scelta)** | 0,15 | **0,48** | 100% in 5 g | 88% in 12 g | 100% in 20 g |
| 1,25·SD | 0,10 | 0,02 | 100% in 4 g | 97% in 9 g | 100% in 18 g |
| 1,25·SD | 0,15 | 0,15 | 100% in 5 g | 78% in 14 g | 100% in 22 g |
| *1.1 (confronto)* | 0,10 | 0,00 | 90% in 5 g | 8% in 4 g | 85% in 42 g |
| *1.1 (confronto)* | 0,15 | 0,45 | 97% in 6 g | 52% in 17 g | 100% in 27 g |

Limiti della taratura: rumore indipendente giorno per giorno (l'HRV reale è
autocorrelata), un solo livello di partenza, nessun effetto del ciclo
settimanale di allenamento. Va ripetuta su dati reali (vedi §5).

## 2. Debito di sonno
Il processo S giornaliero satura in due notti: una settimana di 5,5 h pesava
come due notti. Nuovo nodo `sleep_debt`: media esponenziale (τ = 7 notti) di
(fabbisogno − sonno), surplus limitato a 2 h; fabbisogno 8 h o
`sleep_need_hours`. Nel recupero il sonno vale
(100·(1−S) + 100·exp(−debito/2 h)) / 2. Una notte mancante non modifica il debito.

## 3. Dati reali
- `apse/fit.py`: lettore FIT senza dipendenze (CRC-16/ARC verificato sul valore
  di controllo standard 0xBB3D, timestamp compressi, campi sviluppatore, file
  concatenati, endianness per messaggio).
- `apse/store.py`: SQLite, un database per individuo, scritture in transazione,
  importazione FIT idempotente (hash del file), carico giornaliero = somma
  delle attività del giorno.
- `apse/importers.py`: CSV esportato dall'app Anello (RMSSD notturno, sonno,
  FC a riposo).
- `Observation.ibi_segments`: battiti a pacchetti (l'anello registra pochi
  battiti per volta): le differenze si calcolano solo dentro ogni pacchetto.
- `Observation.rmssd_ms`: RMSSD notturno già calcolato dal dispositivo.
- Strumenti: `python -m tools.import_fit`, `python -m tools.compare_devices`
  (concordanza Bland–Altman tra due dispositivi sulle stesse notti).

## 4. Allenamento
- `apse/wbal.py`: W′bal differenziale (Skiba 2015) e integrale (Skiba 2012),
  potenza normalizzata (media mobile 30 s, quarta potenza). W′bal negativo
  segnalato come CP/W′ sottostimati, non corretto.
- Carico giornaliero: usa `load_au` se fornito (somma attività FIT), altrimenti
  la potenza normalizzata, altrimenti la media (comportamento 1.1).
- `research.fatmax_curve`: parabola ai minimi quadrati su ≥ 4 stadi
  stazionari; picco solo se la curva è concava, limitato agli stadi misurati
  con segnalazione.

## 5. API
Token opzionale (`APSE_TOKEN`, header `Authorization: Bearer`, confronto a
tempo costante), rifiuto di ascoltare fuori da localhost senza token,
Content-Type obbligatorio, nessun log delle richieste, `Cache-Control: no-store`.
Niente TLS: per uso in rete serve un reverse proxy HTTPS.

## 6. Punti della roadmap non implementati, e perché
- **VLamax e MLSS**: i modelli esistono (es. Mader per VLamax), ma senza misure
  di lattato o test a carico costante non si possono calibrare; un numero non
  calibrato sembrerebbe preciso senza esserlo. Da fare solo con dati di
  laboratorio.
- **Fusione probabilistica integrata e digital twin individuale**: richiedono
  mesi di dati reali di una persona; con pochi dati il modello impara il rumore.
  La fusione resta descrittiva (`fusion`), il filtro congiunto resta
  sperimentale (`joint_update`).
- **Benchmark rispetto al Sistema A**: un confronto con i punteggi che l'utente
  vede nell'app del produttore è legittimo e si può fare con i dati esportati;
  eseguire o analizzare il binario del produttore no, ed è fuori dal progetto.
- **Validazione su dati reali**: gli strumenti ci sono (importatori, confronto
  tra dispositivi); serve raccogliere i dati, con misure di riferimento
  indipendenti per una validazione vera (vedi validation_protocol.md).

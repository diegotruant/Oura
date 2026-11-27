# Nota metodologica — APSE 0.1

## Modelli e ipotesi
RMSSD: radice della media delle differenze quadratiche tra coppie consecutive accettate. Soglie 300–2000 ms, salto relativo massimo 20%, minimo 20 coppie e copertura 80% sono scelte ingegneristiche illustrative, non soglie validate. La copertura non rappresenta una probabilità di accuratezza; l'incertezza RMSSD non è ancora stimata. Nessun controllo del contesto o della durata effettiva della registrazione.

Baseline: modello random walk in lnRMSSD, Q=0.0025 per giorno, R=0.04/copertura, P iniziale 0.25. Innovazione limitata a 2.5 deviazioni predittive; varianza non ridotta in caso di clipping. Parametri illustrativi. L'incertezza restituita è la deviazione standard interna al modello, non un intervallo di confidenza empiricamente calibrato. La baseline si adatta anche a periodi di affaticamento: il demo mostra recupero apparente durante HRV persistentemente bassa. Priorità: separare baseline lenta e deviazione acuta, identificazione dei cambiamenti persistenti.

Sonno: processo S normalizzato, veglia seguita da sonno, tau 18.2 e 4.2 ore, stato iniziale 0.25. Nessuna fase circadiana, cronotipo, nap o timeline intragiornaliera. Una lacuna interrompe lo stato; la ripresa usa lo stato iniziale convenzionale. Non misura il debito in ore.

Recovery: media di 100 exp(-max(0,-z)/2) e 100(1-S), con almeno cinque osservazioni valide di baseline. z è innovazione standardizzata sul prior. Pesi e soglie arbitrari per test architetturale. Non è un indicatore clinico, non ha incertezza calibrata né gestisce l'intera fisiologia. La copertura è una media di disponibilità/qualità operativa dei due contributori, non un livello di confidenza.

## Evidenze e confronto
Il comparatore della fase 3 riproduce solo la media generalizzata con esclusione dei mancanti. Nell'esempio [40,85,90,75,80] il risultato passa da 69 a 83 dopo la rimozione del primo dato. Non dimostra il comportamento end-to-end del Sistema A. Il codice APSE non richiama il comparatore nella pipeline. Test unitari e simulazione non stabiliscono superiorità fisiologica.

## Riferimenti di partenza da verificare bibliograficamente
- Task Force ESC/NASPE, Heart rate variability: standards of measurement, physiological interpretation and clinical use (1996).
- Borbély, A two process model of sleep regulation (1982).
- Kalman, A New Approach to Linear Filtering and Prediction Problems (1960).
Questi riferimenti motivano famiglie di metodi, non i parametri scelti o il punteggio sperimentale. Revisione peer-reviewed e controllo bibliografico ancora da svolgere.

## Protocollo futuro
1. Definire endpoint indipendenti: RMSSD ECG di riferimento, vigilanza e prestazione standardizzata; per il sonno, confronti con misure appropriate.
2. Separare individui e finestre temporali per training/calibrazione/test; evitare leakage.
3. Confrontare baseline semplice, baseline robusta e modello a due stati; misurare errore, latenza, falsi allarmi, calibrazione e copertura.
4. Eseguire stress test di missingness casuale e informativa; analizzare bias di selezione e incertezza.
5. Testare replay, modifiche retroattive, gap, unità e versioni; introdurre persistenza transazionale e API solo dopo stabilizzazione dei contratti.
6. Implementare motori di carico, metabolismo, cardiovascolare e adattamento con specifiche e validazione dedicate. Non inferire stati non identificabili senza dati adeguati.

## Estensione v0.2 (prevale sulle descrizioni v0.1)
Si mantiene un riferimento lento in lnRMSSD e uno stato acuto con aggiornamento a = 0.5. Dopo quattro osservazioni valide, un rapporto HRV osservata/riferimento inferiore a 0.8 blocca l'aggiornamento del riferimento; tre osservazioni consecutive sfavorevoli attivano l'allerta, tre non sfavorevoli la rimuovono. Le osservazioni mancanti azzerano le sequenze ma non l'allerta. Il guadagno lento è limitato a 0.08 dopo l'inizializzazione. La varianza è limitata superiormente a 0.25 e aggiornata in forma Joseph quando l'innovazione non è limitata. Queste scelte non costituiscono un filtro bayesiano esatto.
Il punteggio autonomico è 100 exp(min(0, min(lnRMSSD, stato acuto)-riferimento precedente)/0.4); il recupero è la media aritmetica con 100(1-pressione del sonno). Le osservazioni mancanti impediscono il punteggio. Tutti i coefficienti sono sperimentali. Il blocco può impedire l'adattamento a variazioni fisiologiche benigne; occorre una strategia di ricalibrazione e validazione longitudinale indipendente. I test sintetici dimostrano proprietà informatiche, non superiorità fisiologica.

## Aggiornamento 1.2
Baseline: il modello random walk e la baseline protetta 1.1 sono sostituiti dal
riferimento robusto di apse/reference_model.py (mediana 60 giorni esclusi gli
ultimi 7, per dispositivo, episodi esclusi e riferimento congelato, ricalibrazione
solo su conferma). Recupero: la componente autonomica resta 100·exp(min(0,Δ)/0,4)
con Δ = min(oggi, acuto) − riferimento; la componente sonno aggiunge il debito
medio pesato su 7 notti. Parametri scelti con simulazioni (docs/release_1_2.md),
non calibrati su dati reali. Il debito di sonno non è il debito "in ore totali":
è il deficit medio per notte, pesato verso le notti recenti.

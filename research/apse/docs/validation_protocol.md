# Protocollo di validazione APSE — ricerca

## Separazione delle evidenze
Test unitari e sintetici verificano implementazione, non validità fisiologica. Sistema A è un riferimento matematico parziale, non un gold standard. Nessuna superiorità clinica o sportiva è dimostrata.

| Modulo | Riferimento indipendente | Metriche |
|---|---|---|
| HRV | ECG con intervalli NN annotati | MAE, bias, limiti di concordanza, copertura |
| Sonno | PSG, diario e test di vigilanza | MAE durata, concordanza, errore prospettico |
| CP/W′ | prove massimali ripetute e prova a durata esclusa | RMSE W, errore tempo esaurimento |
| VO2 | CPET con analisi gas | MAE, bias, limiti concordanza |
| Substrati | calorimetria indiretta a stadi stabili | errore g/min, ripetibilità |
| Recupero | test prestativi standardizzati prospettici | MAE, calibrazione, copertura |
| Resilienza | perturbazione e recupero longitudinali | errore durata recupero |
| Fusione | osservazioni indipendenti e stato target definito | log score, copertura intervalli |
| Predizione | carichi e prestazioni future non utilizzate in fit | MAE per orizzonte, confronto baseline |

Separare soggetti tra training e test; per personalizzazione usare split cronologico. Pre-registrare endpoint e dimensionamento campione. Riportare dati mancanti e analisi sensibilità. Usare consenso, pseudonimizzazione e approvazione etica quando necessaria. Nessun dataset reale fornito: protocollo non eseguito.

## Bibliografia essenziale
- Borbély et al. (2016), The two-process model of sleep regulation: a reappraisal. https://doi.org/10.1111/jsr.12371
- Poole et al. (2016), Critical Power. https://doi.org/10.1249/MSS.0000000000000939
- Frayn (1983), Calculation of substrate oxidation rates in vivo from gaseous exchange. https://pubmed.ncbi.nlm.nih.gov/6618956/

## Limiti e decisioni
Sonno: costanti 18.2 e 4.2 h e ampiezza circadiana 0.1 illustrative; tempo iniziale locale 0, fase configurabile. CP: fit lineare lavoro-tempo, prove massimali dichiarate; non identifica MLSS o VLamax. FATmax: massimo discreto, non interpolazione. Substrati: gas in L/min, steady state e azoto trascurabile; non applicare a esercizio non stazionario. Fusione: stato scalare e osservazioni sulla stessa scala, covarianza fornita esternamente; non integrata nella fusione giornaliera. Resilienza: media dei recuperi disponibili, almeno 10 su 14, non costrutto validato. Forecast: parametri non calibrati, unità arbitrarie, nessuna incertezza predittiva. API locale senza autenticazione, non per produzione. Modelli sperimentali esposti separatamente, non tutti collegati al grafo giornaliero.

# Release 1.0: contratti e limiti

Le equazioni sono implementazioni indipendenti di modelli didattici o proxy ingegneristici; nessuna equivalenza dimostrata al Sistema A. La bibliografia delle versioni precedenti non costituisce una revisione sistematica.

## Motori aggiunti
- Circadiano: cos(2π(h-16)/24), fase fissa illustrativa, h ora locale di risveglio. Non stima la fase biologica; non modifica il processo S.
- Carico: L=100*(minuti/60)*(potenza media/CP)^2. Proxy, non carico metabolico né indice normalizzato su potenza variabile. A e C: medie esponenziali con costanti 7 e 42 giorni. Stato iniziale zero; un giorno mancante interrompe lo storico. Riposo esplicito: minuti=0.
- Cardiovascolare: misura diretta se fornita, altrimenti 7+10.8*W/kg, costo di ossigeno ergometrico approssimato; non validato come VO2max da ramp test. Fonte concettuale: equazione metabolica di cicloergometria ACSM, applicazione al picco non validata.
- Energia: lavoro kJ=W*s/1000; kcal=kJ/(4.184*0.23). Efficienza lorda assunta, non misurata. Non include dispendio a riposo; ossidazione substrati non stimata.
- Stress: media della pressione del sonno e penalizzazione dello scostamento lnRMSSD. Non identifica stress psicologico o cause.
- Adattamento: F=exp(-1/42)*Fprev+L; G=exp(-1/7)*Gprev+2L. F-G in unità arbitrarie, non previsione prestativa. Coefficienti ipotetici; famiglia di modelli impulso-risposta associata a Banister.
- Fusione: vettore di metriche e frazione disponibile, non probabilità di recupero; non costituisce filtro multivariato né propagazione calibrata delle covarianze. Evita di sommare nuovamente lo stress al recupero.
- Previsione: evoluzione F,G a carico futuro zero per 7 giorni; scenario condizionale, non digital twin validato.

## Limiti e prossimi criteri di accettazione
Parametri non calibrati; stato iniziale arbitrario; reset dopo lacune nel carico; baseline protetta può congelare variazioni fisiologiche benigne; qualità IBI euristica; incertezza disponibile solo in alcuni output. Nessuna stima VLamax, MLSS, FATmax, substrati o resilienza validata. Nessuna importazione FIT, database, autenticazione o deployment remoto. API locale dimostrativa. Nessun confronto dinamico con il binario originale.

La validazione dovrà utilizzare dati indipendenti e split per soggetto e tempo, confronto con misure dirette (gas respiratori, ECG, calorimetria, performance), intervalli di confidenza, analisi Bland–Altman dove pertinente, MAE/RMSE, ablation e test prospettici. I test sintetici sono solo controlli di correttezza software. Per uso accademico verificare fonti primarie e citazioni con il relatore; conservare provenienza originale separata.

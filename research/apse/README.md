# APSE 1.2.0 — research prototype

Framework sperimentale indipendente, Python 3.10+. Nessuna dipendenza esterna. Non validato per uso clinico o decisioni di allenamento. Versione 1.0 del prototipo, non accettazione scientifica finale.

## Avvio
```
python -m unittest discover -s tests -v
python demo.py
python benchmark.py
python -m apse.api
```
API locale, senza autenticazione, non esporre in rete. GET /health, GET /graph, POST /analyze. Esempio JSON: {"observations":[{"day":"2026-01-01","sleep_hours":8,"training_minutes":60,"mean_power_w":200,"cp_w":250,"mass_kg":70,"wake_hour":7}]}. Date ISO; un individuo per richiesta; i giorni mancanti sono elaborati come assenti, non riposo. Nessuna persistenza. Per test e modello consultare docs/methodology.md e docs/release_1.md.

## Research Edition 1.1
Compatibilità API giornaliera 1.0 mantenuta. Nuovo nodo resilience e endpoint locale POST /experiment con payload {"model":"cp_fit","inputs":{"tests":[[180,350],[360,300],[720,275]]}}. Modelli disponibili: cp_fit, substrates, fatmax, sleep_timeline, forecast_load, joint_update. Funzioni sperimentali separate dal DAG giornaliero. Vedere docs/validation_protocol.md. Test: python -m unittest discover -s tests -v. Non utilizzare per decisioni cliniche o prescrizioni automatiche. Validazione esterna non eseguita.

## Release 1.2
Riferimento HRV robusto per dispositivo (sostituisce la baseline protetta 1.1),
debito di sonno, lettore FIT, W′bal, persistenza SQLite, importatore CSV di
Anello, confronto tra dispositivi, API con token. Dettagli, simulazioni e punti
non implementati in docs/release_1_2.md.

```
python -m unittest discover -s tests -v
python -m tools.import_fit apse.db uscita.fit --cp 250 --wprime 20000 --utc 1
python -m tools.compare_devices ring4.csv ring4 gen3.csv gen3
APSE_TOKEN=... python -m apse.api
```

Nuovi campi di Observation: device, rmssd_ms, ibi_segments, sleep_need_hours,
rebaseline, normalized_power_w, load_au. Esperimenti aggiunti: fatmax_curve, wbal.

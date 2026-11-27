"""Importatori di dati reali.

`from_anello_csv`: il CSV esportato dall'app Anello (Impostazioni → Esporta CSV),
una riga per notte. Colonne usate: data, sonno_min, hrv, fc_riposo.
L'HRV è l'RMSSD notturno calcolato dal dispositivo: entra come rmssd_ms.
"""
import csv
import io
import math

from .core import Observation


def _num(v):
    if v is None or str(v).strip() == '':
        return None
    x = float(v)
    return x if math.isfinite(x) else None


def from_anello_csv(device, path=None, text=None):
    """Legge il CSV da `path` oppure da `text`. Ritorna una lista di Observation."""
    if not isinstance(device, str) or not device:
        raise ValueError('indicare il dispositivo (es. "ring4", "gen3")')
    if (path is None) == (text is None):
        raise ValueError('indicare path oppure text')
    if path is not None:
        with open(path, encoding='utf-8') as f:
            text = f.read()
    rows = csv.DictReader(io.StringIO(text))
    required = {'data', 'sonno_min', 'hrv', 'fc_riposo'}
    if not rows.fieldnames or not required <= set(rows.fieldnames):
        raise ValueError('colonne mancanti: ' + ', '.join(sorted(required - set(rows.fieldnames or []))))
    out = []
    for r in rows:
        sleep_min = _num(r['sonno_min'])
        hrv = _num(r['hrv'])
        rhr = _num(r['fc_riposo'])
        out.append(Observation(
            r['data'].strip(),
            sleep_hours=sleep_min / 60 if sleep_min and 0 < sleep_min <= 1440 else None,
            rmssd_ms=hrv if hrv is not None and 1 <= hrv <= 500 else None,
            resting_hr=rhr if rhr is not None and 20 <= rhr <= 250 else None,
            device=device,
        ))
    return out

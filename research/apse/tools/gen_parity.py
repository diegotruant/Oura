"""Genera i valori attesi per app/src/test/java/it/anello/app/score/ApseParityTest.kt.

Uso (dalla cartella research/apse):  python -m tools.gen_parity > /tmp/rows.txt
Stampa le righe Row(...) da incollare nel test Kotlin. Va rilanciato ogni volta che
cambia reference_model.py, insieme alla modifica equivalente in Apse.kt.
"""
import math
import random
from datetime import date, timedelta

from apse.core import Observation
from apse.pipeline import replay

STATUS = {'warmup': 'WARMUP', 'normal': 'NORMAL', 'above': 'ABOVE', 'low': 'LOW', 'alert': 'ALERT',
          'persistent_change': 'PERSISTENT_CHANGE', 'no_data': 'NO_DATA'}


def series():
    rng = random.Random(7)
    days = []
    for i in range(180):
        if i in (40, 41, 95, 130):  # giorni mancanti nel calendario
            continue
        base = math.log(48)
        if 60 <= i < 100:
            base += math.log(.72)  # episodio lungo, diventa persistente
        if 140 <= i:
            base += math.log(.85)
        rm = None if i in (15, 77, 150) else round(math.exp(base + rng.gauss(0, .12)), 1)
        sl = 0 if i in (20, 110) else int(rng.choice([420, 450, 480, 500]) if not (70 <= i < 77) else 330)
        days.append((i, rm, sl))
    return days


def main():
    days = series()
    obs = [Observation((date(2026, 1, 1) + timedelta(days=i)).isoformat(), rmssd_ms=rm,
                       sleep_hours=(sl / 60 if sl else None), sleep_need_hours=8, rebaseline=(i == 120))
           for i, rm, sl in days]
    for (i, rm, sl), r in zip(days, replay(obs)):
        m = r['metrics']
        rec = m['recovery'].value
        print(f"        Row({i}, {'null' if rm is None else repr(rm)}, {sl}, "
              f"{'null' if rec is None else repr(rec)}, Apse.Status.{STATUS[m['baseline']['status']]}),")


if __name__ == '__main__':
    main()

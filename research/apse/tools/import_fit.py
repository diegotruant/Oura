"""Importa file FIT in un database APSE e stampa il riepilogo di ogni attività.

Uso:
  python -m tools.import_fit apse.db uscita1.fit [uscita2.fit ...] --cp 250 --wprime 20000 [--utc 1]

--cp e --wprime: i tuoi valori attuali (W e J), da test recenti (vedi research.cp_fit).
--utc: scostamento del tuo fuso orario da UTC in ore, per assegnare il giorno giusto.
Importare di nuovo lo stesso file non lo duplica.
"""
import argparse
import sys

from apse.fit import FitError
from apse.store import Store


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('db')
    ap.add_argument('files', nargs='+')
    ap.add_argument('--cp', type=float, required=True)
    ap.add_argument('--wprime', type=float, required=True)
    ap.add_argument('--utc', type=float, default=0.)
    a = ap.parse_args(argv)
    store = Store(a.db)
    status = 0
    try:
        for path in a.files:
            try:
                s, new = store.import_fit(path, a.cp, a.wprime, a.utc)
            except (FitError, ValueError, OSError) as e:
                print(f'{path}: ERRORE {e}')
                status = 1
                continue
            np_ = '--' if s['normalized_power_w'] is None else f"{s['normalized_power_w']:.0f}"
            print(f"{path}: {s['day']} {'importata' if new else 'già presente'} · "
                  f"{s['seconds'] / 60:.0f} min · media {s['mean_power_w']:.0f} W · NP {np_} W · "
                  f"carico {s['load_au'] or 0:.0f} · W'bal minimo {s['wbal_min_j'] / 1000:.1f} kJ "
                  f"({100 * s['wbal_min_fraction']:.0f}%)" + (' · ATTENZIONE: CP o W\' sottostimati' if s['wbal_negative'] else ''))
    finally:
        store.close()
    return status


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))

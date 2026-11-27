"""Confronta due dispositivi sulle stesse notti (es. Ring 4 via app Oura e Gen3 diretto).

Uso:
  python -m tools.compare_devices ring4.csv ring4 gen3.csv gen3

I CSV sono quelli esportati da Anello (Impostazioni → Esporta CSV), uno per fonte.
Per ogni notte in comune stampa RMSSD, sonno e recupero APSE di entrambi, poi la
concordanza secondo Bland-Altman: differenza media (bias) e limiti al 95%.
Per l'RMSSD il confronto è sul logaritmo (rapporto tra dispositivi), perché
l'errore cresce col valore. Concordanza tra dispositivi, non accuratezza:
nessuno dei due è un riferimento ECG.
"""
import math
import statistics
import sys

from apse.importers import from_anello_csv
from apse.pipeline import replay


def bland_altman(a, b):
    d = [x - y for x, y in zip(a, b)]
    if len(d) < 3:
        return None
    bias = statistics.mean(d)
    sd = statistics.stdev(d)
    return {'n': len(d), 'bias': bias, 'sd': sd, 'loa_low': bias - 1.96 * sd, 'loa_high': bias + 1.96 * sd}


def compare(obs_a, obs_b):
    ra = {r['day']: r['metrics'] for r in replay(obs_a)}
    rb = {r['day']: r['metrics'] for r in replay(obs_b)}
    oa = {o.day: o for o in obs_a}
    ob = {o.day: o for o in obs_b}
    days = sorted(set(oa) & set(ob))
    rows = []
    for d in days:
        rows.append({'day': d, 'rmssd_a': oa[d].rmssd_ms, 'rmssd_b': ob[d].rmssd_ms,
                     'sleep_a': oa[d].sleep_hours, 'sleep_b': ob[d].sleep_hours,
                     'rec_a': ra[d]['recovery'].value, 'rec_b': rb[d]['recovery'].value})
    pr = [(r['rmssd_a'], r['rmssd_b']) for r in rows if r['rmssd_a'] and r['rmssd_b']]
    ps = [(r['sleep_a'], r['sleep_b']) for r in rows if r['sleep_a'] and r['sleep_b']]
    pc = [(r['rec_a'], r['rec_b']) for r in rows if r['rec_a'] is not None and r['rec_b'] is not None]
    stats = {
        'ln_rmssd': bland_altman([math.log(a) for a, b in pr], [math.log(b) for a, b in pr]),
        'sleep_h': bland_altman([a for a, b in ps], [b for a, b in ps]),
        'recovery': bland_altman([a for a, b in pc], [b for a, b in pc]),
    }
    return rows, stats


def main(argv):
    if len(argv) != 4:
        print(__doc__)
        return 2
    path_a, dev_a, path_b, dev_b = argv
    rows, stats = compare(from_anello_csv(dev_a, path=path_a), from_anello_csv(dev_b, path=path_b))
    f = lambda v, n=1: '--' if v is None else f'{v:.{n}f}'
    print(f'giorno      RMSSD {dev_a}/{dev_b}   sonno h {dev_a}/{dev_b}   recupero {dev_a}/{dev_b}')
    for r in rows:
        print(f"{r['day']}  {f(r['rmssd_a'],0):>5}/{f(r['rmssd_b'],0):<5}   {f(r['sleep_a'],2):>5}/{f(r['sleep_b'],2):<5}   {f(r['rec_a'],0):>4}/{f(r['rec_b'],0):<4}")
    print()
    for name, s in stats.items():
        if s is None:
            print(f'{name}: meno di 3 notti in comune')
        elif name == 'ln_rmssd':
            print(f"RMSSD {dev_a}/{dev_b}: rapporto medio {math.exp(s['bias']):.2f}, "
                  f"95% delle notti tra {math.exp(s['loa_low']):.2f} e {math.exp(s['loa_high']):.2f} (n={s['n']})")
        else:
            print(f"{name}: differenza media {s['bias']:+.2f}, limiti 95% {s['loa_low']:+.2f} / {s['loa_high']:+.2f} (n={s['n']})")
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))

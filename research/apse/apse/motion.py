"""Motore dei passi da accelerometro e classificatore a centroidi (APSE 1.3).

Ripreso da APSE Ring Lab 2.6 (LocalEngine.steps, IntegratedEngine.motionFeatures,
WindowedMotion, CalibratedEngine) con una correzione: i picchi si cercano su tutta la
registrazione con un filtro continuo e poi si assegnano alle finestre di 10 s, così i
passi a cavallo tra due finestre non vanno persi. Stessa logica, riga per riga, di
app/src/main/java/it/anello/app/motion/Motion.kt (test di parità in entrambi i progetti).

Soglie illustrative, non validate: servono sessioni con conteggio manuale o video.
"""
import math

WINDOW_S = 10.0
VERSION = 'passi-1.1'
WALK, RUN, NON_LOCOMOTOR, UNKNOWN = 'WALK', 'RUN', 'NON_LOCOMOTOR', 'UNKNOWN'
CLASSES = frozenset({WALK, RUN, NON_LOCOMOTOR})


def magnitude(xyz):
    return [math.sqrt(x * x + y * y + z * z) for x, y, z in xyz]


def bandpass(mag, hz):
    slow = 1 - math.exp(-2 * math.pi * .5 / hz)
    fast = 1 - math.exp(-2 * math.pi * 4 / hz)
    base = mag[0] if mag else 0.
    lp = 0.
    out = []
    for m in mag:
        base += slow * (m - base)
        lp += fast * (m - base - lp)
        out.append(lp)
    return out


def analyze(xyz, hz):
    """xyz in g, campionato uniformemente a hz (10–200). Ritorna picchi e finestre."""
    if not (isinstance(hz, (int, float)) and math.isfinite(hz) and 10 <= hz <= 200):
        raise ValueError('frequenza di campionamento non valida')
    width = int(math.floor(WINDOW_S * hz + .5))
    if len(xyz) < width:
        raise ValueError('servono almeno 10 secondi')
    mag = magnitude(xyz)
    if any(not math.isfinite(m) or m > 32 for m in mag):
        raise ValueError('accelerazione non valida')
    f = bandpass(mag, hz)
    n_win = len(xyz) // width
    thresholds = []
    for w in range(n_win + 1):
        a, b = w * width, min(len(xyz), w * width + width)
        if a >= b:
            thresholds.append(math.inf)
            continue
        thresholds.append(max(.06, .6 * math.sqrt(sum(v * v for v in f[a:b]) / (b - a))))
    min_gap = max(1, int(.25 * hz))
    settle = int(hz)
    peaks = []
    last = -1_000_000
    for i in range(max(1, settle), len(f) - 1):
        if f[i] > thresholds[i // width] and f[i] > f[i - 1] and f[i] >= f[i + 1] and i - last >= min_gap:
            peaks.append(i)
            last = i
    regular = regular_peaks(peaks, hz)
    reg_set = set(regular)
    windows = []
    for w in range(n_win):
        a, b = w * width, w * width + width
        seg = mag[a:b]
        mean = sum(seg) / width
        sd = math.sqrt(max(0., sum(v * v for v in seg) / width - mean * mean))
        jerk = sum(abs(seg[i] - seg[i - 1]) * hz for i in range(1, width)) / (width - 1)
        in_win = [p for p in peaks if a <= p < b]
        n_reg = sum(1 for p in in_win if p in reg_set)
        cadence = n_reg * 60. / WINDOW_S
        iv = [(q - p) / hz for p, q in zip(in_win, in_win[1:])]
        if len(iv) >= 2:
            m = sum(iv) / len(iv)
            cv = math.sqrt(sum((v - m) ** 2 for v in iv) / len(iv)) / m
        else:
            cv = 1.
        windows.append({'start': a, 'end': b, 'features': [mean, sd, jerk, cadence, cv], 'peaks': len(in_win), 'regular': n_reg})
    return {'hz': hz, 'peaks': peaks, 'regular': regular, 'windows': windows, 'unresolved_samples': len(xyz) % width}


def regular_peaks(peaks, hz):
    """Picchi con andatura regolare (controllo di APSE Ring Lab, esteso ai bordi).

    Un picco interno è regolare se i due intervalli adiacenti sono tra 0,25 e 1,25 s e
    differiscono al massimo del 25% del maggiore. Ring Lab scartava sempre il primo e l'ultimo
    picco; qui un picco al bordo di un tratto di cammino (un solo intervallo valido) è accettato
    se il picco vicino, da quel lato, è regolare: così ogni tratto non perde due passi.
    """
    n = len(peaks)
    iv = [(peaks[i + 1] - peaks[i]) / hz for i in range(n - 1)]
    good = [.25 <= v <= 1.25 for v in iv]
    interior = [False] * n
    for i in range(1, n - 1):
        a, b = iv[i - 1], iv[i]
        interior[i] = good[i - 1] and good[i] and abs(a - b) <= .25 * max(a, b)
    out = []
    for i in range(n):
        ok = interior[i]
        if not ok:
            left = i > 0 and good[i - 1] and interior[i - 1]
            right = i < n - 1 and good[i] and interior[i + 1]
            # bordo: valido da un lato solo, e dall'altro lato nessun intervallo valido
            ok = (left and not (i < n - 1 and good[i])) or (right and not (i > 0 and good[i - 1]))
        if ok:
            out.append(peaks[i])
    return out


def rule_label(features):
    _, sd, _, cadence, cv = features
    if not 70 <= cadence <= 210 or cv > .35 or sd < .05:
        return NON_LOCOMOTOR
    return RUN if cadence >= 150 else WALK


def count(xyz, hz, model=None):
    """Tre conteggi: candidati (tutti i picchi), regolari (algoritmo deterministico) e passi
    (regolari nelle finestre classificate come cammino o corsa)."""
    a = analyze(xyz, hz)
    labels = [model.predict(w['features']) if model else rule_label(w['features']) for w in a['windows']]
    steps = sum(w['regular'] for w, l in zip(a['windows'], labels) if l in (WALK, RUN))
    # La coda più corta di una finestra eredita l'etichetta dell'ultima finestra completa.
    tail_start = len(a['windows']) * int(math.floor(WINDOW_S * hz + .5))
    if labels and labels[-1] in (WALK, RUN):
        steps += sum(1 for p in a['regular'] if p >= tail_start)
    return {'candidates': len(a['peaks']), 'regular': len(a['regular']), 'steps': steps, 'labels': labels,
            'unknown_windows': labels.count(UNKNOWN), 'unresolved_samples': a['unresolved_samples'],
            'version': VERSION,
            'status': 'trained_classifier_not_validated' if model else 'rule_based_not_validated'}


def validation_report(rows):
    """Metriche del protocollo di validazione per una configurazione.

    rows: (classe, secondi, passi_veri, passi_contati). Per le sessioni con passi veri > 0:
    errore assoluto medio, bias, MAPE e limiti di concordanza di Bland–Altman (con almeno 3
    sessioni). Per le sessioni senza passi veri (attività non locomotorie): falsi passi al minuto.
    """
    loco = [(t, c) for cls, sec, t, c in rows if t and t > 0]
    still = [(sec, c) for cls, sec, t, c in rows if not t]
    out = {'n_locomotor': len(loco), 'n_non_locomotor': len(still),
           'mae': None, 'bias': None, 'mape': None, 'loa_low': None, 'loa_high': None, 'false_steps_per_min': None}
    if loco:
        d = [c - t for t, c in loco]
        out['mae'] = sum(abs(v) for v in d) / len(d)
        out['bias'] = sum(d) / len(d)
        out['mape'] = 100 * sum(abs(c - t) / t for t, c in loco) / len(loco)
        if len(d) >= 3:
            m = out['bias']
            sd = math.sqrt(sum((v - m) ** 2 for v in d) / (len(d) - 1))
            out['loa_low'], out['loa_high'] = m - 1.96 * sd, m + 1.96 * sd
    minutes = sum(sec for sec, c in still) / 60
    if minutes > 0:
        out['false_steps_per_min'] = sum(c for sec, c in still) / minutes
    return out


class CentroidModel:
    """Classificatore a centroidi su caratteristiche normalizzate, con rifiuto dei casi incerti."""

    def __init__(self, mean, scale, centers, max_distance, min_margin):
        self.mean, self.scale, self.centers = mean, scale, centers
        self.max_distance, self.min_margin = max_distance, min_margin

    def predict(self, x):
        if len(x) != len(self.mean):
            raise ValueError('numero di caratteristiche')
        best = second = math.inf
        label = UNKNOWN
        for name, c in self.centers.items():
            d = math.sqrt(sum(((x[j] - self.mean[j]) / self.scale[j] - c[j]) ** 2 for j in range(len(x))) / len(x))
            if d < best:
                second, best, label = best, d, name
            else:
                second = min(second, d)
        return label if best <= self.max_distance and second - best >= self.min_margin else UNKNOWN


def fit(x, labels, required=CLASSES, max_distance=3., min_margin=.05):
    if len(x) != len(labels) or len(x) < 6:
        raise ValueError('servono almeno 6 esempi')
    p = len(x[0])
    if any(len(r) != p or any(not math.isfinite(v) for v in r) for r in x):
        raise ValueError('caratteristiche non valide')
    counts = {l: labels.count(l) for l in set(labels)}
    if set(counts) != set(required) or any(n < 3 for n in counts.values()):
        raise ValueError('servono almeno 3 esempi per ogni classe')
    mean = [sum(r[j] for r in x) / len(x) for j in range(p)]
    scale = [max(1e-9, math.sqrt(sum((r[j] - mean[j]) ** 2 for r in x) / len(x))) for j in range(p)]
    centers = {}
    for name in sorted(required):
        rows = [r for r, l in zip(x, labels) if l == name]
        centers[name] = [sum((r[j] - mean[j]) / scale[j] for r in rows) / len(rows) for j in range(p)]
    return CentroidModel(mean, scale, centers, max_distance, min_margin)


def evaluate(model, x, labels, train_labels):
    """Accuratezza sul test e del classificatore banale (classe più frequente in addestramento)."""
    majority = max(sorted(set(train_labels)), key=train_labels.count)
    pred = [model.predict(r) for r in x]
    n = len(x)
    return {'accuracy': sum(p == l for p, l in zip(pred, labels)) / n if n else 0.,
            'majority_accuracy': sum(l == majority for l in labels) / n if n else 0.,
            'n': n, 'rejected': pred.count(UNKNOWN)}

"""W′bal, potenza normalizzata e riepilogo di un'attività con potenza a 1 Hz.

Modelli pubblicati, implementazione indipendente:
- W′bal differenziale (Skiba et al. 2015, forma usata anche da Golden Cheetah):
  sopra CP:  dW/dt = -(P - CP)
  sotto CP:  dW/dt = (CP - P) · (W′ - W) / W′
- W′bal integrale (Skiba et al. 2012):
  W′bal(t) = W′ - Σ W′spesa(u) · exp(-(t-u)/τ),
  τ = 546 · exp(-0,01 · D_CP) + 316,  D_CP = CP - potenza media sotto CP.
  τ dipende dalla potenza media di TUTTA l'attività: è nota solo a fine sessione.
- Potenza normalizzata: media mobile su 30 s, elevata alla quarta, media,
  radice quarta (definizione pubblica di Coggan).

Un W′bal negativo significa che CP o W′ sono sottostimati per quella giornata,
non che l'atleta ha "superato il 100%": viene segnalato, non corretto.
Nessuna validazione individuale: CP e W′ vanno da test (vedi research.cp_fit).
"""
import math


def _check(power, cp, wprime):
    if not power:
        raise ValueError('serie di potenza vuota')
    if len(power) > 172_800:
        raise ValueError('serie oltre 48 ore')
    if not (isinstance(cp, (int, float)) and 50 <= cp <= 700):
        raise ValueError('CP fuori intervallo 50-700 W')
    if not (isinstance(wprime, (int, float)) and 2_000 <= wprime <= 60_000):
        raise ValueError("W' fuori intervallo 2-60 kJ")
    if any(not isinstance(p, (int, float)) or not math.isfinite(p) or p < 0 or p > 3_000 for p in power):
        raise ValueError('potenza non valida')


def wbal_differential(power, cp, wprime):
    _check(power, cp, wprime)
    w = float(wprime)
    out = []
    for p in power:
        if p > cp:
            w -= p - cp
        else:
            w += (cp - p) * (wprime - w) / wprime
        out.append(w)
    return out


def wbal_integral(power, cp, wprime):
    _check(power, cp, wprime)
    below = [p for p in power if p < cp]
    dcp = cp - (sum(below) / len(below) if below else 0.)
    tau = 546 * math.exp(-0.01 * dcp) + 316
    decay = math.exp(-1 / tau)
    spent = 0.
    out = []
    for p in power:
        spent = spent * decay + max(0., p - cp)
        out.append(wprime - spent)
    return out, tau


def normalized_power(power):
    if len(power) < 30:
        return None
    window = 30
    acc = sum(power[:window])
    rolls = [acc / window]
    for i in range(window, len(power)):
        acc += power[i] - power[i - window]
        rolls.append(acc / window)
    return (sum(r ** 4 for r in rolls) / len(rolls)) ** .25


def summary(power, cp, wprime, filled_seconds=0):
    """Riepilogo dell'attività per il grafo giornaliero e per l'analisi."""
    wd = wbal_differential(power, cp, wprime)
    wi, tau = wbal_integral(power, cp, wprime)
    np_ = normalized_power(power)
    seconds = len(power)
    mean = sum(power) / seconds
    if_ = None if np_ is None else np_ / cp
    load = None if if_ is None else seconds / 3600 * if_ ** 2 * 100
    above = sum(1 for p in power if p > cp)
    return {
        'seconds': seconds,
        'filled_seconds': filled_seconds,
        'mean_power_w': mean,
        'normalized_power_w': np_,
        'intensity_factor': if_,
        'load_au': load,
        'work_kj': sum(power) / 1000,
        'seconds_above_cp': above,
        'wbal_min_j': min(wd),
        'wbal_min_fraction': min(wd) / wprime,
        'wbal_integral_min_j': min(wi),
        'wbal_tau_s': tau,
        'wbal_negative': min(wd) < 0,
        'status': 'cp_wprime_user_supplied_not_validated' if min(wd) >= 0
        else 'wbal_negative_cp_or_wprime_underestimated',
    }

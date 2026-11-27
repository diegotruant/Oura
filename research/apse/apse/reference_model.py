"""APSE 1.2 — riferimento HRV personale e recupero.

Sostituisce la baseline protetta 1.1 (dual.py), che o si congelava per sempre o
assorbiva i cali sotto il 20% (vedi docs/release_1_2.md, scenari A-C).

Principi:
- Riferimento lento = mediana di lnRMSSD negli ultimi 60 giorni, ESCLUSI gli
  ultimi 7, dello STESSO dispositivo. La mediana resiste ai singoli valori anomali,
  l'esclusione dei 7 giorni impedisce di inseguire un calo in corso.
- I giorni di un episodio confermato (allarme attivo) non entrano nel riferimento,
  e all'inizio dell'episodio il riferimento viene congelato: un calo persistente non
  viene mai assorbito automaticamente. I singoli giorni bassi invece restano nel
  riferimento: escluderli (tenendo quelli alti) lo spingerebbe verso l'alto e
  creerebbe falsi allarmi a catena.
- Fascia normale = riferimento ± SWC, con SWC = 0,5 × deviazione standard del
  riferimento (minimo 0,05 in ln, circa 5%), come negli studi su lnRMSSD negli
  atleti. Un giorno è "basso" se cala di oltre il 20% (brusco) oppure se la media
  degli ultimi 7 giorni è sotto il riferimento di più di 1 × SD (minimo 0,05) e
  il valore del giorno è sotto il riferimento (calo lento). La soglia 1 × SD è
  stata scelta con le simulazioni di docs/release_1_2.md (falsi allarmi contro
  ritardo di rilevamento), non validata su dati reali.
- Allarme dopo 3 giorni bassi consecutivi; rilascio dopo 3 giorni consecutivi
  dentro la fascia. Un giorno mancante non rilascia l'allarme.
- Se l'episodio dura almeno 28 giorni il motore lo segnala come possibile nuovo
  livello normale. Solo una conferma esplicita (Observation.rebaseline=True)
  riparte da zero: l'HRV da sola non distingue la fatica da un cambiamento benigno.
- Ogni dispositivo ha la sua storia: dispositivi diversi misurano RMSSD diversi.

Parametri illustrativi, non calibrati. Non è un indicatore clinico.
"""
import math
import statistics
from datetime import date
from .core import Metric

WINDOW_DAYS = 60
EXCLUDE_DAYS = 7
FULL_REFERENCE_N = 14
MIN_RECOVERY_N = 4
SWC_FACTOR = .5
SWC_FLOOR = .05
ONSET_FACTOR = 1.0   # soglia della media a 7 giorni per far scattare un episodio (× SD)
ABRUPT_DROP = math.log(.8)
ALERT_DAYS = 3
RELEASE_DAYS = 3
NEW_NORMAL_DAYS = 28
DEFAULT_VAR = .04
DRIFT_PER_DAY = .0025
ACUTE_ALPHA = .5
RESPONSE_SCALE = .4
DEFAULT_SLEEP_NEED = 8.
DEBT_TAU_DAYS = 7.
DEBT_SCALE_H = 2.
MAX_SURPLUS_H = 2.


def _device(obs):
    return obs.device or 'default'


def _new_device_state():
    return {'hist': [], 'low': 0, 'good': 0, 'alert': False, 'episode': 0, 'acute': None, 'last': None,
            'frozen': None}


def _reference(st, today):
    """Riferimento per il giorno `today` (ordinale) dalla storia non esclusa."""
    usable = [(o, v) for o, v, excluded in st['hist'] if not excluded and o < today]
    full = [v for o, v in usable if today - WINDOW_DAYS <= o <= today - EXCLUDE_DAYS - 1]
    if len(full) >= FULL_REFERENCE_N:
        values, kind = full, 'full'
    else:
        # Avvio: tutta la storia disponibile, segnalata come provvisoria.
        values, kind = [v for o, v in usable], 'provisional'
    if st.get('frozen') is not None and (st['alert'] or len(full) < FULL_REFERENCE_N):
        # Durante un episodio (o finché la finestra non si è ripopolata) vale il
        # riferimento congelato all'inizio dell'episodio.
        if st['alert'] or len(values) < len(st['frozen']['values']):
            values, kind = st['frozen']['values'], 'frozen'
    if not values:
        return None
    ref = statistics.median(values)
    var = statistics.variance(values) if len(values) >= 3 else DEFAULT_VAR
    swc = max(SWC_FLOOR, SWC_FACTOR * math.sqrt(var))
    since = today - st['last'] if st['last'] is not None else 0
    unc = math.sqrt(var / len(values) + DRIFT_PER_DAY * max(0, since))
    onset = max(SWC_FLOOR, ONSET_FACTOR * math.sqrt(var))
    return {'value': ref, 'swc': swc, 'onset': onset, 'n': len(values), 'kind': kind, 'uncertainty': unc}


def baseline(obs, state, deps):
    m = deps['autonomic']['lnrmssd']
    devices = state.setdefault('devices', {})
    key = _device(obs)
    today = date.fromisoformat(obs.day).toordinal()
    st = devices.get(key)
    if st is None:
        if m.value is None:
            return dict(prior=None, posterior=None, z=None, n=0, acute=None, alert=False, streak=0,
                        device=key, week_mean=None, normal_range=None, status='no_data', episode_days=0)
        st = devices[key] = _new_device_state()

    if obs.rebaseline and m.value is not None:
        # Conferma esplicita: nuovo livello normale, la storia precedente non conta più.
        st.update(_new_device_state())

    ref = _reference(st, today)
    prior = Metric(ref['value'], 'ln(ms)', 1, ref['uncertainty'], ref['kind']) if ref else None
    z = week_mean = None
    status = 'warmup'
    low = False

    if m.value is not None:
        v = m.value
        st['acute'] = v if st['acute'] is None else st['acute'] + ACUTE_ALPHA * (v - st['acute'])
        recent = [x for o, x, _ in st['hist'] if today - 6 <= o < today] + [v]
        week_mean = sum(recent) / len(recent)
        if ref is not None:
            sd = math.sqrt(ref['uncertainty'] ** 2 + DEFAULT_VAR)
            z = (v - ref['value']) / sd
        if ref is not None and ref['n'] >= MIN_RECOVERY_N:
            d = v - ref['value']
            low = d < ABRUPT_DROP or (week_mean - ref['value'] < -ref['onset'] and d < 0)
            normal_today = d >= -ref['swc']
            st['low'] = st['low'] + 1 if low else 0
            st['good'] = st['good'] + 1 if normal_today else 0
            if st['low'] >= ALERT_DAYS and not st['alert']:
                st['alert'] = True
                st['frozen'] = {'values': [x for o, x, e in st['hist'] if not e and o < today and
                                           (ref['kind'] != 'full' or today - WINDOW_DAYS <= o <= today - EXCLUDE_DAYS - 1)]
                                or [ref['value']]}
            if st['alert'] and st['good'] >= RELEASE_DAYS:
                st['alert'] = False
                st['episode'] = 0
            status = 'low' if low else ('above' if d > ref['swc'] else 'normal')
        excluded = st['alert']
        if st['alert']:
            st['episode'] += 1
        st['hist'].append((today, v, excluded))
        st['hist'] = [h for h in st['hist'] if h[0] > today - WINDOW_DAYS - 1]
        st['last'] = today
    else:
        status = 'no_data'  # un giorno senza dati non azzera né rilascia l'allarme

    if st['alert']:
        status = 'persistent_change' if st['episode'] >= NEW_NORMAL_DAYS else 'alert'

    post = _reference(st, today + 1)
    posterior = Metric(post['value'], 'ln(ms)', m.coverage, post['uncertainty'], post['kind']) if post else None
    n_valid = sum(1 for o, v, e in st['hist'])
    rng = None
    if ref is not None:
        rng = {'low': ref['value'] - ref['swc'], 'high': ref['value'] + ref['swc']}
    return dict(prior=prior, posterior=posterior, z=z, n=n_valid,
                acute=Metric(st['acute'], 'ln(ms)', m.coverage) if m.value is not None else None,
                alert=st['alert'], streak=st['low'], device=key,
                week_mean=Metric(week_mean, 'ln(ms)', m.coverage) if week_mean is not None else None,
                normal_range=rng, status=status, episode_days=st['episode'],
                reference_n=ref['n'] if ref else 0)


def sleep_debt(obs, state, deps):
    """Deficit di sonno medio pesato (h/notte), media esponenziale su ~7 notti.

    Il processo S giornaliero satura in 2 notti; il deficit invece si accumula,
    quindi una settimana di notti corte pesa più di una notte sola.
    Una notte mancante non modifica lo stato (dato ignoto, non riposo).
    """
    if obs.sleep_hours is None:
        d = state.get('sleep_debt')
        return Metric(d, 'h_per_night', 0., reason='missing_night_state_kept') if d is not None else \
            Metric(None, 'h_per_night', 0., reason='missing_night')
    need = obs.sleep_need_hours or DEFAULT_SLEEP_NEED
    deficit = max(-MAX_SURPLUS_H, need - obs.sleep_hours)
    d = state.get('sleep_debt', 0.)
    d += (1 - math.exp(-1 / DEBT_TAU_DAYS)) * (deficit - d)
    state['sleep_debt'] = d
    return Metric(max(0., d), 'h_per_night', 1., reason='ewma_7_nights_illustrative')


def recovery(obs, state, deps):
    b = deps['baseline']
    s = deps['sleep']
    debt = deps['sleep_debt']
    a = deps['autonomic']['lnrmssd']
    valid = b['prior'] is not None and b.get('reference_n', 0) >= MIN_RECOVERY_N and a.value is not None
    coverage = (a.coverage if valid else 0) / 2 + s.coverage / 2
    if not valid or s.value is None or debt.value is None:
        return Metric(None, 'index_0_100', coverage, reason='insufficient_contributors')
    # Scala di risposta fissa: l'incertezza non deve migliorare il punteggio da sola.
    delta = min(a.value, b['acute'].value) - b['prior'].value
    autonomic_score = 100 * math.exp(min(0., delta) / RESPONSE_SCALE)
    sleep_score = (100 * (1 - s.value) + 100 * math.exp(-debt.value / DEBT_SCALE_H)) / 2
    if b['status'] == 'persistent_change':
        reason = 'persistent_change_confirm_rebaseline_if_benign'
    elif b['prior'].reason == 'provisional':
        reason = 'provisional_reference_not_validated'
    else:
        reason = 'experimental_reference_model_not_validated'
    return Metric((autonomic_score + sleep_score) / 2, 'index_0_100', coverage, reason=reason)

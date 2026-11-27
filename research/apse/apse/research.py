"""Independent research extensions. No clinical or performance validity implied."""
import math
from .core import Metric

def m(v,u,r=''): return Metric(v,u,float(v is not None),reason=r)
def finite(x): return isinstance(x,(int,float)) and not isinstance(x,bool) and math.isfinite(x)
def cp_fit(tests):
    if len(tests)<3: raise ValueError('three distinct maximal tests required')
    if any(not finite(t) or not finite(p) or not 120<=t<=1200 or p<=0 for t,p in tests): raise ValueError('invalid duration/power')
    if len({t for t,p in tests})!=len(tests): raise ValueError('duplicate duration')
    x=[t for t,p in tests]; y=[t*p for t,p in tests]; a=sum(x)/len(x); b=sum(y)/len(y)
    cp=sum((t-a)*(w-b) for t,w in zip(x,y))/sum((t-a)**2 for t in x); wp=b-cp*a
    if cp<=0 or wp<=0 or cp>=min(p for t,p in tests): raise ValueError('nonphysical fit')
    rmse=math.sqrt(sum((p-cp-wp/t)**2 for t,p in tests)/len(x))
    return {'cp_w':cp,'wprime_j':wp,'rmse_w':rmse,'n':len(x),'status':'fit_not_independent_validation'}

def substrates(vo2,vco2):
    if not all(finite(v) and v>0 for v in (vo2,vco2)): raise ValueError('positive gas exchange required')
    rer=vco2/vo2
    if not 3.21/4.55<=rer<=1: raise ValueError('outside nonprotein steady-state model domain')
    return {'fat_g_min':1.67*(vo2-vco2),'cho_g_min':4.55*vco2-3.21*vo2,'rer':rer}

def fatmax(stages):
    if len(stages)<3: raise ValueError('three steady state stages required')
    if any(not finite(p) or p<=0 for p,o,c in stages) or len({p for p,o,c in stages})!=len(stages): raise ValueError('invalid stage power')
    rows=sorted((p,substrates(o,c)['fat_g_min']) for p,o,c in stages)
    p,f=max(rows,key=lambda x:x[1]); return {'observed_peak_power_w':p,'fat_g_min':f,'boundary_peak':p in (rows[0][0],rows[-1][0]),'status':'discrete_observed_peak_not_continuous_fatmax'}

def sleep_timeline(segments,initial=.25,phase=16):
    if not finite(initial) or not 0<=initial<=1 or not finite(phase): raise ValueError('initial state/phase')
    s=initial; elapsed=0; rows=[]
    for mode,h in segments:
        if mode not in ('wake','sleep') or not finite(h) or not 0<h<=24: raise ValueError('invalid segment')
        s=1-(1-s)*math.exp(-h/18.2) if mode=='wake' else s*math.exp(-h/4.2)
        elapsed+=h; c=math.cos(2*math.pi*(elapsed-phase)/24)
        rows.append({'elapsed_h':elapsed,'homeostatic':s,'circadian':c,'combined_proxy':s-.1*c})
    return rows

def forecast_load(fitness,fatigue,loads):
    if not 1<=len(loads)<=90 or any(not finite(v) or v<0 for v in [fitness,fatigue,*loads]): raise ValueError('nonnegative finite state/load; 1-90 days')
    rows=[]
    for i,l in enumerate(loads,1):
        fitness=fitness*math.exp(-1/42)+l; fatigue=fatigue*math.exp(-1/7)+2*l
        rows.append({'day':i,'fitness_au':fitness,'fatigue_au':fatigue,'balance_au':fitness-fatigue})
    return rows

def resilience(o,s,d):
    r=d['recovery']; old=s.get('resilience',[]); old=(old+[r.value])[-14:]; s['resilience']=old
    valid=[v for v in old if v is not None]; coverage=len(valid)/14
    return Metric(sum(valid)/len(valid) if len(valid)>=10 else None,'index_0_100',coverage,reason='rolling_recovery_summary_not_validated_resilience')

def joint_update(prior,prior_var,observations,covariance):
    """Scalar latent state with correlated simultaneous observations; Cholesky SPD solve."""
    n=len(observations)
    if not finite(prior) or not finite(prior_var) or prior_var<=0 or not 1<=n<=20 or len(covariance)!=n or any(len(r)!=n for r in covariance): raise ValueError('invalid shape/prior')
    if any(not finite(v) for v in observations) or any(not finite(v) for r in covariance for v in r): raise ValueError('nonfinite')
    if any(abs(covariance[i][j]-covariance[j][i])>1e-10 for i in range(n) for j in range(n)): raise ValueError('non-symmetric covariance')
    L=[[0.]*n for _ in range(n)]
    for i in range(n):
        for j in range(i+1):
            v=covariance[i][j]-sum(L[i][k]*L[j][k] for k in range(j))
            if i==j:
                if v<=0: raise ValueError('covariance not positive definite')
                L[i][j]=math.sqrt(v)
            else: L[i][j]=v/L[j][j]
    def whiten(y):
        z=[]
        for i in range(n): z.append((y[i]-sum(L[i][j]*z[j] for j in range(i)))/L[i][i])
        return z
    a=whiten([1.]*n); b=whiten(observations); var=1/(1/prior_var+sum(v*v for v in a))
    return {'mean':var*(prior/prior_var+sum(x*y for x,y in zip(a,b))),'variance':var,'status':'conditional_on_user_supplied_covariance_and_common_scale'}

def fatmax_curve(stages):
    """FATmax continuo: parabola ai minimi quadrati di ossidazione dei grassi vs potenza.

    stages: almeno 4 stadi stazionari [potenza W, VO2 L/min, VCO2 L/min].
    Il picco è riportato solo se la curva è concava; se cade fuori dagli stadi
    misurati viene limitato al bordo e segnalato (estrapolazione non affidabile).
    Interpolazione dei dati forniti, non validata contro un protocollo FATmax.
    """
    if len(stages) < 4: raise ValueError('four steady state stages required')
    if any(not finite(p) or p<=0 for p,o,c in stages) or len({p for p,o,c in stages})!=len(stages): raise ValueError('invalid stage power')
    pts=sorted((p,substrates(o,c)['fat_g_min']) for p,o,c in stages)
    xs=[p for p,f in pts]; ys=[f for p,f in pts]
    mx=sum(xs)/len(xs); sx=max(xs)-min(xs)
    u=[(x-mx)/sx for x in xs]  # scala per stabilità numerica
    S=[[sum(v**(i+j) for v in u) for j in range(3)] for i in range(3)]
    T=[sum(y*v**i for v,y in zip(u,ys)) for i in range(3)]
    # Eliminazione di Gauss con pivot parziale su 3x3
    A=[row[:]+[t] for row,t in zip(S,T)]
    for c in range(3):
        piv=max(range(c,3),key=lambda r:abs(A[r][c]))
        if abs(A[piv][c])<1e-12: raise ValueError('stages do not determine a curve')
        A[c],A[piv]=A[piv],A[c]
        for r in range(3):
            if r!=c:
                f=A[r][c]/A[c][c]; A[r]=[a-f*b for a,b in zip(A[r],A[c])]
    c0,c1,c2=(A[i][3]/A[i][i] for i in range(3))
    pred=[c0+c1*v+c2*v*v for v in u]; my=sum(ys)/len(ys)
    ss_tot=sum((y-my)**2 for y in ys); r2=1-sum((y-p)**2 for y,p in zip(ys,pred))/ss_tot if ss_tot>0 else None
    if c2>=0:
        return {'fatmax_w':None,'fat_g_min':None,'r2':r2,'status':'no_concave_peak_in_data'}
    upeak=-c1/(2*c2); peak=mx+upeak*sx
    clipped=not min(xs)<=peak<=max(xs); peak=min(max(peak,min(xs)),max(xs)); up=(peak-mx)/sx
    return {'fatmax_w':peak,'fat_g_min':c0+c1*up+c2*up*up,'r2':r2,'extrapolation_clipped':clipped,
            'status':'quadratic_fit_of_supplied_stages_not_validated'}

def wbal(power, cp_w, wprime_j):
    """W′bal e riepilogo di una serie di potenza a 1 Hz (vedi apse/wbal.py)."""
    from .wbal import summary
    return summary(power, cp_w, wprime_j)

class DynamicFilter:
    """Filtro esplorativo a tre stati (da APSE Ring Lab 2.6, DynamicFusion).

    Stato: scostamenti standardizzati di recupero, battito e temperatura (proxy senza unità).
    x <- A x, P <- A P A' + Q con A = 0,85 I, Q diagonale 0,25 e fuori diagonale 0,05; R = I;
    P iniziale 4 I. Aggiornamenti scalari in sequenza solo sulle componenti osservate.
    Coefficienti ipotetici, non stimati: non è un modello fisiologico validato.
    """
    A = .85

    def __init__(self):
        self.x = [0., 0., 0.]
        self.p = [[4. if i == j else 0. for j in range(3)] for i in range(3)]
        self.observed = 0

    def update(self, autonomic=None, cardiovascular=None, thermal=None):
        z = [autonomic, cardiovascular, thermal]
        if any(v is not None and (not finite(v) or abs(v) > 1000) for v in z): raise ValueError('observation')
        q = [[.25 if i == j else .05 for j in range(3)] for i in range(3)]
        self.x = [self.A * v for v in self.x]
        self.p = [[self.A * self.A * self.p[i][j] + q[i][j] for j in range(3)] for i in range(3)]
        self.observed = 0
        for k in range(3):
            if z[k] is None: continue
            self.observed += 1
            s_ = self.p[k][k] + 1.
            gain = [self.p[i][k] / s_ for i in range(3)]
            row = list(self.p[k])
            innovation = z[k] - self.x[k]
            self.x = [self.x[i] + gain[i] * innovation for i in range(3)]
            self.p = [[self.p[i][j] - gain[i] * row[j] for j in range(3)] for i in range(3)]
        for i in range(3):
            for j in range(i):
                self.p[i][j] = self.p[j][i] = (self.p[i][j] + self.p[j][i]) / 2
        return {'means': list(self.x), 'observed': self.observed,
                'composite': sum(self.x) / 3 if self.observed == 3 else None,
                'composite_variance': sum(sum(r) for r in self.p) / 9,
                'status': 'exploratory_filter_hypothetical_coefficients'}

def fitness_fatigue_calibrate(load, performance, fitness=0., fatigue=0.):
    """Stima le due costanti di tempo del modello fitness-fatigue (da Ring Lab, CalibratedEngine).

    load: carico giornaliero (>= 0), giorni consecutivi; performance: prestazione osservata nella
    stessa scala arbitraria oppure None nei giorni senza misura. Ricerca a griglia: fitness
    20–70 giorni (passo 5), fatica 3–15 giorni; minimizza l'errore quadratico di (F − D).
    Servono almeno 28 giorni e 10 misure. La scala della prestazione deve essere già coerente
    con il carico: non viene stimato alcun guadagno. Nessuna validazione su dati esclusi.
    """
    if len(load) != len(performance) or len(load) < 28: raise ValueError('calibration history')
    if any(not finite(l) or l < 0 for l in load) or any(p is not None and not finite(p) for p in performance): raise ValueError('history')
    n = sum(p is not None for p in performance)
    if n < 10: raise ValueError('insufficient observations')
    best = None
    for tf in range(20, 71, 5):
        for td in range(3, 16):
            a, b = math.exp(-1 / tf), math.exp(-1 / td)
            f, d, err = fitness, fatigue, 0.
            for l, p in zip(load, performance):
                f = a * f + (1 - a) * l; d = b * d + (1 - b) * l
                if p is not None: err += (f - d - p) ** 2
            if best is None or err < best[0]: best = (err, tf, td)
    return {'fitness_days': best[1], 'fatigue_days': best[2], 'rmse': math.sqrt(best[0] / n), 'n': n,
            'status': 'grid_fit_no_holdout_validation'}

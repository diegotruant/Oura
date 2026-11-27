import math
from .core import Metric

def autonomic(obs,state,deps):
    # Illustrative bounds and relative jump filter; no bridging of rejected beats.
    def valid(x): return x is not None and math.isfinite(x) and 300<=x<=2000
    if obs.rmssd_ms is not None and not obs.ibi_ms and not obs.ibi_segments:
        # 1.2: RMSSD notturno calcolato dal dispositivo (es. anello). Qualità non verificabile qui.
        v=float(obs.rmssd_ms)
        return {'rmssd':Metric(v,'ms',1.,reason='device_reported'),'lnrmssd':Metric(math.log(v),'ln(ms)',1.,reason='device_reported')}
    # 1.2: con ibi_segments le coppie si formano solo dentro ciascun pacchetto di battiti
    # consecutivi; l'ultimo battito di un pacchetto e il primo del successivo non sono vicini.
    segments=obs.ibi_segments or (obs.ibi_ms,)
    pairs=[(a,b) for seg in segments for a,b in zip(seg,seg[1:])]
    ds=[(b-a)**2 for a,b in pairs if valid(a) and valid(b) and abs(b-a)/max(a,b)<=.20]
    coverage=len(ds)/max(1,len(pairs))
    if len(ds)<20 or coverage<.8: return {'rmssd':Metric(None,'ms',coverage,reason='insufficient_pairs'),'lnrmssd':Metric(None,'ln(ms)',coverage,reason='insufficient_pairs')}
    rmssd=math.sqrt(sum(ds)/len(ds))
    return {'rmssd':Metric(rmssd,'ms',coverage), 'lnrmssd':Metric(math.log(rmssd) if rmssd>0 else None,'ln(ms)',coverage,reason='' if rmssd>0 else 'zero_rmssd')}

def baseline(obs,state,deps):
    m=deps['autonomic']['lnrmssd']; prior=state.get('baseline')
    # Scalar random-walk state model; provisional Q/R and Huber innovation clipping.
    if prior is None:
        if m.value is None: return {'prior':None,'posterior':None,'z':None,'n':0}
        state['baseline']=(m.value,.25,1)
        return {'prior':None,'posterior':Metric(m.value,'ln(ms)',m.coverage,.5),'z':None,'n':1}
    mu,p,n=prior; p+=.0025
    previous=Metric(mu,'ln(ms)',1,math.sqrt(p))
    z=None
    if m.value is not None:
        r=.04/max(m.coverage,.01); innovation=m.value-mu; sd=math.sqrt(p+r)
        z=innovation/sd; k=p/(p+r); limited=max(-2.5*sd,min(2.5*sd,innovation))
        mu+=k*limited
        # Conservative: do not reduce variance for clipped observations.
        if abs(innovation)<=2.5*sd: p=(1-k)*p
        n+=1
    state['baseline']=(mu,p,n)
    return {'prior':previous,'posterior':Metric(mu,'ln(ms)',m.coverage,math.sqrt(p)),'z':z,'n':n}

def sleep(obs,state,deps):
    # Daily approximation: wake then sleep; normalized homeostatic state, no circadian phase.
    if obs.sleep_hours is None:
        state.pop('sleep',None)
        return Metric(None,'normalized',0,reason='missing_sleep_breaks_state')
    old=state.get('sleep',.25); h=obs.sleep_hours
    wake=1-(1-old)*math.exp(-(24-h)/18.2)
    pressure=wake*math.exp(-h/4.2)
    state['sleep']=pressure
    return Metric(pressure,'normalized',1,reason='daily_approximation')

def recovery(obs,state,deps):
    b=deps['baseline']; s=deps['sleep']; z=b['z']; a=deps['autonomic']['lnrmssd']
    # Heuristic, not calibrated or a readiness prediction. Fixed required contributors.
    coverage=(a.coverage if z is not None and b['n']>=5 else 0)/2+s.coverage/2
    if z is None or b['n']<5 or s.value is None: return Metric(None,'index_0_100',coverage,reason='insufficient_contributors')
    autonomic_score=100*math.exp(-max(0,-z)/2)
    sleep_score=100*(1-s.value)
    return Metric((autonomic_score+sleep_score)/2,'index_0_100',coverage,reason='experimental_heuristic_not_validated')

"""Experimental guarded reference and acute state; not a fatigue diagnosis."""
import math
from .core import Metric

def baseline(obs,state,deps):
    m=deps['autonomic']['lnrmssd']; old=state.get('baseline')
    if old is None:
        if m.value is None: return dict(prior=None,posterior=None,z=None,n=0,acute=None,alert=False,streak=0)
        old=(m.value,.25,0,m.value,0,0,False)
    mu,p,n,acute,low,good,alert=old
    p=min(.25,p+.0025)
    prior=Metric(mu,'ln(ms)',1,math.sqrt(p)) if n else None
    z=None
    if m.value is not None:
        r=.04/max(m.coverage,.01); sd=math.sqrt(p+r)
        z=(m.value-mu)/sd if n else None
        acute += .5*(m.value-acute)
        # Fixed log-ratio guard prevents increasing uncertainty from hiding a persistent change.
        adverse=n>=4 and m.value-mu < math.log(.8)
        low=low+1 if adverse else 0
        good=good+1 if n>=4 and not adverse else 0
        alert=(alert or low>=3) and good<3
        if not adverse and not alert:
            k=min(.08 if n>=4 else 1.,p/(p+r))
            e=max(-2.5*sd,min(2.5*sd,m.value-mu))
            mu+=k*e
            # Joseph-form variance for the capped gain; clipping is not a Gaussian update.
            if prior is None or abs(m.value-prior.value)<=2.5*sd:
                p=(1-k)**2*p+k*k*r
        n+=1
    else:
        low=good=0 # missing observations do not clear a latched alert
    state['baseline']=(mu,p,n,acute,low,good,alert)
    return dict(prior=prior,posterior=Metric(mu,'ln(ms)',m.coverage,math.sqrt(p)),z=z,n=n,
                acute=Metric(acute,'ln(ms)',m.coverage) if m.value is not None else None,
                alert=alert,streak=low)

def recovery(obs,state,deps):
    b=deps['baseline']; s=deps['sleep']; a=deps['autonomic']['lnrmssd']
    valid=b['prior'] is not None and b['n']>=5 and a.value is not None
    coverage=(a.coverage if valid else 0)/2+s.coverage/2
    if not valid or s.value is None: return Metric(None,'index_0_100',coverage,reason='insufficient_contributors')
    # Fixed response scale: uncertainty must not mechanically improve the index.
    delta=min(a.value,b['acute'].value)-b['prior'].value
    score=100*math.exp(min(0,delta)/.4)
    return Metric((score+100*(1-s.value))/2,'index_0_100',coverage,reason='experimental_guarded_reference_not_validated')

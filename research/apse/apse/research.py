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

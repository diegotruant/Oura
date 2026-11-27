"""Independent experimental physiological models; no clinical claims."""
import math
from .core import Metric

def metric(v,u,reason='',coverage=None,uncertainty=None):
    return Metric(v,u,float(v is not None) if coverage is None else coverage,uncertainty,reason)

def circadian(o,s,d):
    if o.wake_hour is None: return metric(None,'normalized','missing_local_wake_hour')
    return metric(math.cos(2*math.pi*(o.wake_hour-16)/24),'normalized','illustrative_phase_not_measured')

def load(o,s,d):
    # Power-based workload proxy; not a proprietary score.
    if o.training_minutes==0: impulse=0.
    elif o.training_minutes is None or o.mean_power_w is None or o.cp_w is None: impulse=None
    else: impulse=o.training_minutes/60*(o.mean_power_w/o.cp_w)**2*100
    old=s.get('load')
    if impulse is None:
        s.pop('load',None)
        return {k:metric(None,'arbitrary_units','missing_load_breaks_history') for k in ('daily','acute','chronic','balance')}
    a,c=old if old is not None else (0.,0.)
    a+= (1-math.exp(-1/7))*(impulse-a); c+=(1-math.exp(-1/42))*(impulse-c)
    s['load']=(a,c)
    return {k:metric(v,'arbitrary_units','experimental_power_proxy') for k,v in [('daily',impulse),('acute',a),('chronic',c),('balance',c-a)]}

def cardiovascular(o,s,d):
    if o.measured_vo2 is not None: v=o.measured_vo2; reason='reported_direct_measurement'
    elif o.ramp_peak_w is not None and o.mass_kg is not None:
        v=7+10.8*o.ramp_peak_w/o.mass_kg; reason='cycling_work_rate_oxygen_cost_proxy_not_vo2max_validation'
    else: v=None; reason='missing_test'
    return {'vo2':metric(v,'ml/kg/min',reason),'resting_hr':metric(o.resting_hr,'bpm')}

def energy(o,s,d):
    if o.training_minutes==0: work=0.
    elif o.training_minutes is None or o.mean_power_w is None: work=None
    else: work=o.mean_power_w*o.training_minutes*60/1000
    # gross efficiency illustrative 0.23, not individually measured
    kcal=None if work is None else work/(.23*4.184)
    return {'mechanical_work':metric(work,'kJ'),'exercise_energy':metric(kcal,'kcal','assumed_gross_efficiency_0.23'), 'carbohydrate_oxidation':metric(None,'g','requires_substrate_measurement_or_validated_model')}

def stress(o,s,d):
    b=d['baseline']; a=d['autonomic']['lnrmssd']; sl=d['sleep']
    if b['prior'] is None or a.value is None or b['n']<5 or sl.value is None: return metric(None,'normalized','insufficient_inputs')
    v=(1-math.exp(min(0,a.value-b['prior'].value)/.4)+sl.value)/2
    return metric(v,'normalized','autonomic_sleep_deviation_proxy_not_stress_diagnosis',min(a.coverage,sl.coverage))

def adaptation(o,s,d):
    l=d['load']['daily']; old=s.get('adaptation')
    if l.value is None:
        s.pop('adaptation',None); return {'fitness':metric(None,'AU'),'fatigue':metric(None,'AU'),'balance':metric(None,'AU')}
    f,g=old or (0.,0.); f=f*math.exp(-1/42)+l.value; g=g*math.exp(-1/7)+2*l.value
    s['adaptation']=(f,g)
    return {k:metric(v,'AU','uncalibrated_impulse_response') for k,v in [('fitness',f),('fatigue',g),('balance',f-g)]}

def fusion(o,s,d):
    # No double counting: stress proxy is descriptive, not fused again with HRV/sleep.
    r=d['recovery']; l=d['load']['balance']; a=d['adaptation']['balance']; c=d['circadian']; cv=d['cardiovascular']['vo2']; e=d['energy']['exercise_energy']
    vals={'recovery':r,'load_balance':l,'adaptation_balance':a,'circadian':c,'vo2':cv,'exercise_energy':e}
    return {'components':vals,'component_availability':sum(m.value is not None for m in vals.values())/len(vals),'status':'experimental_no_calibrated_joint_posterior','alert':d['baseline']['alert']}

def forecast(o,s,d):
    old=s.get('adaptation')
    if old is None: return {'status':'unavailable','days':[]}
    f,g=old; rows=[]
    for day in range(1,8):
        f*=math.exp(-1/42); g*=math.exp(-1/7); rows.append({'day_ahead':day,'fitness_au':f,'fatigue_au':g,'balance_au':f-g})
    return {'status':'conditional_zero_future_load_not_performance_prediction','days':rows}

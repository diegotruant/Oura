from datetime import date,timedelta
import json,random
from apse.core import Observation
from apse.pipeline import replay,serializable
from apse.reference import generalized_mean
rng=random.Random(42)
obs=[]
for i in range(30):
    sd=40 if i<20 else 15
    ibi=tuple(900+rng.gauss(0,sd) for _ in range(300)) if i!=25 else ()
    obs.append(Observation((date(2026,1,1)+timedelta(days=i)).isoformat(),ibi,8 if i<20 else 5.5))
rows=serializable(replay(obs))
with open('demo_results.json','w') as f: json.dump(rows,f,indent=2,allow_nan=False)
print('day         RMSSD  baseline-ln  sleep-pressure  recovery  coverage')
for row in rows:
    m=row['metrics']; b=m['baseline']['posterior']; r=m['recovery']
    def fmt(v): return '--' if v is None else f'{v:.2f}'
    print(row['day'],fmt(m['autonomic']['rmssd']['value']),fmt(b['value'] if b else None),fmt(m['sleep']['value']),fmt(r['value']),fmt(r['coverage']))
print('Comparator missingness example:',generalized_mean([40,85,90,75,80],[.3,.2,.2,.15,.15]),generalized_mean([None,85,90,75,80],[.3,.2,.2,.15,.15]))

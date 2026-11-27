"""Synthetic scenario, not a physiological validation."""
from datetime import date,timedelta
from apse.core import Observation
from apse.pipeline import replay,serializable
import json
rows=[]
for i in range(56):
    rest=i%7 in (5,6); stressed=28<=i<42
    amp=10 if stressed else 25
    rows.append(Observation((date(2026,1,1)+timedelta(days=i)).isoformat(),tuple(900+amp*(-1)**j for j in range(101)) if i!=35 else (),5.5 if stressed else 8,0 if rest else 60,0 if rest else (230 if stressed else 180),250,70,350,None,58,7))
r=serializable(replay(rows))
with open('benchmark_results.json','w') as f: json.dump(r,f,indent=2,allow_nan=False)
for i in (27,28,34,35,41,48,55):
    m=r[i]['metrics']; print(i+1,m['recovery']['value'],m['load']['acute']['value'],m['adaptation']['balance']['value'],m['baseline']['alert'])

from dataclasses import asdict,is_dataclass
from datetime import date,timedelta
from .core import Graph,Node,Observation
from .engines import autonomic,sleep
from .reference_model import baseline,recovery,sleep_debt
from . import advanced as a
from . import __version__ as VERSION
from .research import resilience

def graph():
    return Graph([Node('autonomic',(),autonomic),Node('baseline',('autonomic',),baseline),Node('sleep',(),sleep),Node('sleep_debt',(),sleep_debt),Node('recovery',('autonomic','baseline','sleep','sleep_debt'),recovery),Node('circadian',(),a.circadian),Node('load',(),a.load),Node('cardiovascular',(),a.cardiovascular),Node('energy',(),a.energy),Node('stress',('baseline','autonomic','sleep'),a.stress),Node('adaptation',('load',),a.adaptation),Node('fusion',('recovery','load','adaptation','circadian','cardiovascular','energy','baseline'),a.fusion),Node('forecast',('adaptation',),a.forecast),Node('resilience',('recovery',),resilience)])

def replay(observations):
    ordered=sorted(observations,key=lambda x:x.day)
    if len({o.day for o in ordered})!=len(ordered): raise ValueError('duplicate day')
    state={}; g=graph(); result=[]; previous=None
    for obs in ordered:
        current=date.fromisoformat(obs.day)
        if previous is not None:
            gap=(current-previous).days
            if gap>3660: raise ValueError('gap too large')
            for i in range(1,gap):
                missing=Observation((previous+timedelta(days=i)).isoformat()); g.execute(missing,state)
        result.append({'day':obs.day,'version':VERSION,'metrics':g.execute(obs,state)})
        previous=current
    return result

def serializable(x):
    if is_dataclass(x): return asdict(x)
    if isinstance(x,dict): return {k:serializable(v) for k,v in x.items()}
    if isinstance(x,list): return [serializable(v) for v in x]
    return x

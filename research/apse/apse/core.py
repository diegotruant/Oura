from dataclasses import dataclass, asdict
from datetime import date
import math

@dataclass(frozen=True)
class Observation:
    day: str
    ibi_ms: tuple[float | None, ...] = ()
    sleep_hours: float | None = None
    training_minutes: float | None = None
    mean_power_w: float | None = None
    cp_w: float | None = None
    mass_kg: float | None = None
    ramp_peak_w: float | None = None
    measured_vo2: float | None = None
    resting_hr: float | None = None
    wake_hour: float | None = None
    # --- 1.2 ---
    device: str | None = None                 # baseline separata per dispositivo
    rmssd_ms: float | None = None             # RMSSD notturno già calcolato dal dispositivo
    ibi_segments: tuple[tuple[float | None, ...], ...] = ()  # battiti a pacchetti (es. anello)
    sleep_need_hours: float | None = None     # fabbisogno personale, default 8 h
    rebaseline: bool = False                  # conferma esplicita di un nuovo livello normale
    normalized_power_w: float | None = None   # potenza normalizzata dell'allenamento
    load_au: float | None = None              # carico già calcolato (es. somma di più attività)
    def __post_init__(self):
        date.fromisoformat(self.day)
        if date.fromisoformat(self.day).isoformat()!=self.day: raise ValueError('canonical date required')
        for key,lo,hi in [('training_minutes',0,1440),('mean_power_w',0,2500),('cp_w',1,1500),('mass_kg',15,350),('ramp_peak_w',0,2000),('measured_vo2',1,120),('resting_hr',20,250),('wake_hour',0,23.999999)]:
            v=getattr(self,key)
            if v is not None and (isinstance(v,bool) or not math.isfinite(v) or not lo<=v<=hi): raise ValueError(key)
        for key,lo,hi in [('rmssd_ms',1,500),('sleep_need_hours',3,14),('normalized_power_w',0,2500),('load_au',0,2000)]:
            v=getattr(self,key)
            if v is not None and (isinstance(v,bool) or not isinstance(v,(int,float)) or not math.isfinite(v) or not lo<=v<=hi): raise ValueError(key)
        if not isinstance(self.rebaseline,bool): raise ValueError('rebaseline')
        if self.device is not None and (not isinstance(self.device,str) or not 1<=len(self.device)<=64): raise ValueError('device')
        if self.ibi_ms and self.ibi_segments: raise ValueError('use ibi_ms or ibi_segments, not both')
        if sum(len(seg) for seg in self.ibi_segments)>100000 or len(self.ibi_segments)>20000: raise ValueError('too many intervals')
        if any(v is not None and (isinstance(v,bool) or not isinstance(v,(int,float))) for seg in self.ibi_segments for v in seg): raise ValueError('invalid IBI type')
        if len(self.ibi_ms)>100000: raise ValueError('too many intervals')
        if any(v is not None and (isinstance(v,bool) or not isinstance(v,(int,float))) for v in self.ibi_ms): raise ValueError('invalid IBI type')
        if self.sleep_hours is not None and (not math.isfinite(self.sleep_hours) or not 0 <= self.sleep_hours <= 24):
            raise ValueError('sleep_hours outside [0,24]')

@dataclass(frozen=True)
class Metric:
    value: float | None
    unit: str
    coverage: float
    uncertainty: float | None = None
    reason: str = ''
    def __post_init__(self):
        if not math.isfinite(self.coverage) or not 0 <= self.coverage <= 1: raise ValueError('coverage')
        if self.value is not None and not math.isfinite(self.value): raise ValueError('nonfinite value')
        if self.uncertainty is not None and (not math.isfinite(self.uncertainty) or self.uncertainty < 0): raise ValueError('uncertainty')

@dataclass(frozen=True)
class Node:
    name: str
    deps: tuple[str,...]
    run: object

class Graph:
    def __init__(self,nodes):
        self.nodes={n.name:n for n in nodes}
        if len(self.nodes)!=len(nodes): raise ValueError('duplicate node')
        visiting=set(); done=set(); self.order=[]
        def visit(k):
            if k in visiting: raise ValueError('cycle')
            if k in done: return
            if k not in self.nodes: raise ValueError('unknown dependency: '+k)
            visiting.add(k)
            for d in self.nodes[k].deps: visit(d)
            visiting.remove(k); done.add(k); self.order.append(k)
        for k in self.nodes: visit(k)
    def execute(self,observation,state):
        outputs={}
        for k in self.order:
            n=self.nodes[k]; outputs[k]=n.run(observation,state,{d:outputs[d] for d in n.deps})
        return outputs

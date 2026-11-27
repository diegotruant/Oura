"""Mathematical comparator from phase 3; not dynamically validated."""
import math

def generalized_mean(scores,weights):
    pairs=[(min(100,max(1,s)),w) for s,w in zip(scores,weights,strict=True) if s is not None]
    d=sum(w for s,w in pairs)
    return math.floor((sum(w*s**.6 for s,w in pairs)/d)**(1/.6)+.5) if d>0 else None

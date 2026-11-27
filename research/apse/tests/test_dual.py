import unittest, math
from datetime import date,timedelta
from apse.core import Observation
from apse.pipeline import replay

def series(amps):
    return [Observation((date(2026,1,1)+timedelta(days=i)).isoformat(),tuple(900+a*(-1)**j for j in range(101)) if a is not None else (),8) for i,a in enumerate(amps)]
class DualTests(unittest.TestCase):
    def test_guard(self):
        rows=replay(series([25]*20+[10]*30)); b=[r['metrics']['baseline'] for r in rows]
        self.assertAlmostEqual(b[19]['posterior'].value,b[-1]['posterior'].value)
        self.assertTrue(b[-1]['alert'])
    def test_alert_timing(self):
        b=[r['metrics']['baseline'] for r in replay(series([25]*20+[10]*3))]
        self.assertFalse(b[-2]['alert']); self.assertTrue(b[-1]['alert'])
    def test_recovery_release(self):
        b=replay(series([25]*20+[10]*5+[25]*3))[-1]['metrics']['baseline']; self.assertFalse(b['alert'])
    def test_missing_latch(self):
        m=replay(series([25]*20+[10]*3+[None]))[-1]['metrics']; self.assertTrue(m['baseline']['alert']); self.assertIsNone(m['recovery'].value)
    def test_no_false_rebound(self):
        r=replay(series([25]*20+[10]*30)); self.assertAlmostEqual(r[20]['metrics']['recovery'].value,r[-1]['metrics']['recovery'].value,places=5)
    def test_positive_adaptation(self):
        r=replay(series([25]*20+[30]*30)); self.assertGreater(r[-1]['metrics']['baseline']['posterior'].value,math.log(50))
    def test_acute(self):
        r=replay(series([25]*20+[10]))[-1]['metrics']['baseline']; self.assertAlmostEqual(r['acute'].value,(math.log(50)+math.log(20))/2)

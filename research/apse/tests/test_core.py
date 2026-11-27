import unittest,math
from apse.core import Observation,Metric,Graph,Node
from apse.pipeline import replay,serializable
from apse.engines import autonomic
from apse.reference import generalized_mean

def obs(day=1,sleep=8,amp=20): return Observation(f'2026-01-{day:02}',tuple(900+amp*(-1)**i for i in range(101)),sleep)
class Tests(unittest.TestCase):
    def test_rmssd(self): self.assertAlmostEqual(autonomic(obs(),{}, {})['rmssd'].value,40)
    def test_log(self): self.assertAlmostEqual(autonomic(obs(),{}, {})['lnrmssd'].value,math.log(40))
    def test_zero(self): self.assertIsNone(autonomic(obs(amp=0),{}, {})['lnrmssd'].value)
    def test_missing(self): self.assertIsNone(autonomic(Observation('2026-01-01'),{}, {})['rmssd'].value)
    def test_nan(self): self.assertIsNone(autonomic(Observation('2026-01-01',(float('nan'),)*100),{}, {})['rmssd'].value)
    def test_no_bridge(self):
        r=autonomic(Observation('2026-01-01',(900,None,950)*30),{}, {})['rmssd']; self.assertIsNone(r.value)
    def test_bad_sleep(self):
        with self.assertRaises(ValueError): obs(sleep=25)
    def test_bad_metric(self):
        with self.assertRaises(ValueError): Metric(1,'x',1.1)
    def test_cycle(self):
        with self.assertRaises(ValueError): Graph([Node('a',('b',),None),Node('b',('a',),None)])
    def test_unknown(self):
        with self.assertRaises(ValueError): Graph([Node('a',('b',),None)])
    def test_duplicate(self):
        with self.assertRaises(ValueError): replay([obs(),obs()])
    def test_replay(self): self.assertEqual(serializable(replay([obs(2),obs()])),serializable(replay([obs(),obs(2)])))
    def test_warmup(self): self.assertIsNone(replay([obs()])[0]['metrics']['recovery'].value)
    def test_recovery(self): self.assertIsNotNone(replay([obs(i) for i in range(1,6)])[-1]['metrics']['recovery'].value)
    def test_sleep(self): self.assertGreater(replay([obs(sleep=5)])[0]['metrics']['sleep'].value,replay([obs(sleep=8)])[0]['metrics']['sleep'].value)
    def test_missing_recovery(self): self.assertIsNone(replay([obs(i) for i in range(1,6)]+[obs(6,None)])[-1]['metrics']['recovery'].value)
    def test_gap(self): self.assertGreater(replay([obs(),obs(3)])[-1]['metrics']['baseline']['prior'].uncertainty,replay([obs(),obs(2)])[-1]['metrics']['baseline']['prior'].uncertainty)
    def test_baseline_prior(self): self.assertAlmostEqual(replay([obs(),obs(2,amp=10)])[-1]['metrics']['baseline']['prior'].value,math.log(40))
    def test_reference(self): self.assertLess(generalized_mean([40,85],[.5,.5]),generalized_mean([None,85],[.5,.5]))
    def test_correction(self): self.assertNotEqual(replay([obs(),obs(2)])[-1],replay([obs(amp=10),obs(2)])[-1])
if __name__=='__main__': unittest.main()

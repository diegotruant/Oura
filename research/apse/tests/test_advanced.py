import unittest,math
from apse.core import Observation
from apse.pipeline import replay,serializable,graph
from apse.api import analyze
class Advanced(unittest.TestCase):
 def row(self,**kwargs): return replay([Observation('2026-01-01',**kwargs)])[0]['metrics']
 def test_load(self): self.assertAlmostEqual(self.row(training_minutes=60,mean_power_w=200,cp_w=250)['load']['daily'].value,64)
 def test_rest(self): self.assertEqual(self.row(training_minutes=0)['load']['daily'].value,0)
 def test_missing(self): self.assertIsNone(self.row()['load']['daily'].value)
 def test_work(self): self.assertEqual(self.row(training_minutes=60,mean_power_w=200)['energy']['mechanical_work'].value,720)
 def test_energy(self): self.assertAlmostEqual(self.row(training_minutes=60,mean_power_w=200)['energy']['exercise_energy'].value,720/(.23*4.184))
 def test_vo2(self): self.assertEqual(self.row(measured_vo2=55,ramp_peak_w=400,mass_kg=70)['cardiovascular']['vo2'].value,55)
 def test_proxy(self): self.assertAlmostEqual(self.row(ramp_peak_w=350,mass_kg=70)['cardiovascular']['vo2'].value,61)
 def test_circadian(self): self.assertEqual(self.row(wake_hour=16)['circadian'].value,1)
 def test_bounds(self):
  for k,v in [('cp_w',0),('mass_kg',float('nan')),('training_minutes',-1),('wake_hour',24),('mean_power_w',True)]:
   with self.subTest(k=k),self.assertRaises(ValueError): self.row(**{k:v})
 def test_prediction(self):
  m=self.row(training_minutes=60,mean_power_w=250,cp_w=250); f=m['forecast']['days']; self.assertEqual(len(f),7); self.assertLess(f[-1]['fatigue_au'],f[0]['fatigue_au'])
 def test_no_prediction(self): self.assertEqual(self.row()['forecast']['days'],[])
 def test_missing_gap(self):
  r=replay([Observation('2026-01-01',training_minutes=60,mean_power_w=200,cp_w=250),Observation('2026-01-03')]); self.assertIsNone(r[-1]['metrics']['adaptation']['fitness'].value)
 def test_substrate(self): self.assertIsNone(self.row(training_minutes=0)['energy']['carbohydrate_oxidation'].value)
 def test_api(self): self.assertEqual(analyze({'observations':[{'day':'2026-01-01'}]})['version'],'1.0.0')
 def test_api_bad(self):
  with self.assertRaises(ValueError): analyze({'observations':[]})
 def test_graph(self): self.assertLess(graph().order.index('load'),graph().order.index('adaptation'))
 def test_fusion(self): self.assertEqual(self.row()['fusion']['component_availability'],0)

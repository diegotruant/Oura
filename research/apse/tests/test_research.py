import unittest, math
from apse.research import *
from apse.experiments import evaluate
class Research(unittest.TestCase):
 def test_cp(self):
  r=cp_fit([(t,250+18000/t) for t in (180,360,720)]); self.assertAlmostEqual(r['cp_w'],250);self.assertAlmostEqual(r['wprime_j'],18000)
 def test_cp_bad(self):
  with self.assertRaises(ValueError): cp_fit([(180,250),(360,250),(720,250)])
 def test_substrate(self): self.assertAlmostEqual(substrates(2,1.6)['fat_g_min'],.668)
 def test_rer(self):
  with self.assertRaises(ValueError): substrates(2,2.2)
 def test_fatmax(self): self.assertEqual(fatmax([(100,1,.8),(150,2,1.5),(200,3,2.9)])['observed_peak_power_w'],150)
 def test_sleep(self):
  a,b=sleep_timeline([('wake',16),('sleep',8)]); self.assertGreater(a['homeostatic'],b['homeostatic'])
 def test_sleep_split(self): self.assertAlmostEqual(sleep_timeline([('wake',8),('wake',8)])[-1]['homeostatic'],sleep_timeline([('wake',16)])[-1]['homeostatic'])
 def test_forecast(self): self.assertGreater(forecast_load(100,100,[100])[0]['fatigue_au'],forecast_load(100,100,[0])[0]['fatigue_au'])
 def test_joint(self):
  r=joint_update(0,1,[1,1],[[1,0],[0,1]]);self.assertAlmostEqual(r['mean'],2/3);self.assertAlmostEqual(r['variance'],1/3)
 def test_correlation(self): self.assertGreater(joint_update(0,1,[1,1],[[1,.9],[.9,1]])['variance'],1/3)
 def test_singular(self):
  with self.assertRaises(ValueError): joint_update(0,1,[1,1],[[1,1],[1,1]])
 def test_api(self): self.assertAlmostEqual(evaluate({'model':'substrates','inputs':{'vo2':2,'vco2':1.6}})['fat_g_min'],.668)

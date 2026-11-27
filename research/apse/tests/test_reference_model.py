"""Test del modello di riferimento 1.2 sugli scenari che rompevano la 1.1."""
import math
import random
import unittest
from datetime import date, timedelta

from apse.core import Observation
from apse.pipeline import replay


def run(values, sleep=None, devices=None, rebase=()):
    obs = [Observation((date(2026, 1, 1) + timedelta(days=i)).isoformat(),
                       rmssd_ms=v, sleep_hours=8 if sleep is None else sleep[i],
                       device=None if devices is None else devices[i], rebaseline=i in rebase)
           for i, v in enumerate(values)]
    return [r['metrics'] for r in replay(obs)]


def first_alert(rows, start):
    return next((i for i in range(start, len(rows)) if rows[i]['baseline']['alert']), None)


class ReferenceModel(unittest.TestCase):

    def test_permanent_change_not_absorbed(self):
        # Scenario A della 1.1: il riferimento non deve seguire un calo persistente.
        r = run([47] * 20 + [35] * 70)
        self.assertAlmostEqual(math.exp(r[-1]['baseline']['prior'].value), 47, places=3)
        self.assertTrue(r[-1]['baseline']['alert'])
        self.assertIsNotNone(r[-1]['recovery'].value)  # riferimento congelato, non scaduto
        self.assertEqual(r[-1]['baseline']['status'], 'persistent_change')

    def test_persistent_change_flag_after_28_days(self):
        r = run([47] * 20 + [35] * 70)
        alert_day = first_alert(r, 20)
        self.assertEqual(r[alert_day + 26]['baseline']['status'], 'alert')
        self.assertEqual(r[alert_day + 27]['baseline']['status'], 'persistent_change')
        self.assertIn('rebaseline', r[alert_day + 30]['recovery'].reason)

    def test_explicit_rebaseline(self):
        r = run([47] * 20 + [35] * 70, rebase={49})
        self.assertFalse(r[-1]['baseline']['alert'])
        self.assertAlmostEqual(math.exp(r[-1]['baseline']['prior'].value), 35, places=3)
        self.assertGreater(r[-1]['recovery'].value, 95)

    def test_device_switch_is_not_a_drop(self):
        r = run([47] * 20 + [35] * 70, devices=['ring4'] * 20 + ['gen3'] * 70)
        self.assertFalse(any(x['baseline']['alert'] for x in r))
        self.assertEqual(r[-1]['baseline']['device'], 'gen3')
        self.assertIsNone(r[20]['recovery'].value)  # nuovo dispositivo: riparte il periodo iniziale

    def test_gradual_decline_detected(self):
        # Scenario B: -1% al giorno, nessun giorno supera la soglia brusca del 20%.
        r = run([55] * 20 + [55 * .99 ** k for k in range(1, 41)])
        alert_day = first_alert(r, 20)
        self.assertIsNotNone(alert_day)
        self.assertLess(alert_day - 20, 20)
        self.assertLess(r[-1]['recovery'].value, r[19]['recovery'].value - 20)

    def test_moderate_drop_not_absorbed(self):
        # Scenario C: -15% persistente; nella 1.1 il recupero tornava a 95.
        r = run([55] * 20 + [55 * .85] * 40)
        self.assertTrue(r[-1]['baseline']['alert'])
        self.assertAlmostEqual(r[25]['recovery'].value, r[-1]['recovery'].value, places=5)

    def test_rise_is_followed(self):
        r = run([50] * 20 + [60] * 60)
        self.assertFalse(any(x['baseline']['alert'] for x in r))
        self.assertAlmostEqual(math.exp(r[-1]['baseline']['prior'].value), 60, places=3)

    def test_single_low_night_no_alert(self):
        r = run([50] * 30 + [30] + [50] * 10)
        self.assertFalse(any(x['baseline']['alert'] for x in r))

    def test_missing_night_keeps_alert(self):
        vals = [50] * 20 + [30] * 4 + [None] + [50]
        obs = [Observation((date(2026, 1, 1) + timedelta(days=i)).isoformat(), rmssd_ms=v, sleep_hours=8)
               for i, v in enumerate(vals)]
        r = [x['metrics'] for x in replay(obs)]
        self.assertTrue(r[24]['baseline']['alert'])
        self.assertTrue(r[25]['baseline']['alert'])  # un solo giorno buono non basta

    def test_release_after_three_normal_days(self):
        r = run([50] * 20 + [30] * 5 + [50] * 3)
        self.assertFalse(r[-1]['baseline']['alert'])

    def test_false_alarm_rate_on_noise(self):
        # HRV che oscilla per caso (sd 0,15 in ln): pochi episodi falsi all'anno.
        episodes = []
        for seed in range(20):
            rng = random.Random(seed)
            vals = [min(500, math.exp(math.log(50) + rng.gauss(0, .15))) for _ in range(365)]
            al = [x['baseline']['alert'] for x in run(vals)][30:]
            episodes.append(sum(1 for a, b in zip([False] + al, al) if b and not a))
        self.assertLess(sum(episodes) / len(episodes), 1.0)

    def test_sleep_debt_accumulates(self):
        one = run([55] * 21, sleep=[8] * 20 + [5.5])[-1]['sleep_debt'].value
        week = run([55] * 27, sleep=[8] * 20 + [5.5] * 7)[-1]['sleep_debt'].value
        self.assertGreater(week, 3 * one)
        r = run([55] * 40, sleep=[8] * 20 + [5.5] * 7 + [8] * 13)
        self.assertLess(r[26]['recovery'].value, r[19]['recovery'].value - 10)
        self.assertGreater(r[-1]['recovery'].value, r[26]['recovery'].value)

    def test_missing_night_does_not_reset_debt(self):
        obs = [Observation((date(2026, 1, 1) + timedelta(days=i)).isoformat(), rmssd_ms=55,
                           sleep_hours=None if i == 5 else 5.5) for i in range(7)]
        r = [x['metrics'] for x in replay(obs)]
        self.assertAlmostEqual(r[5]['sleep_debt'].value, r[4]['sleep_debt'].value)
        self.assertEqual(r[5]['sleep_debt'].coverage, 0)

    def test_sleep_need(self):
        a = run([55] * 10, sleep=[7] * 10)[-1]['sleep_debt'].value
        obs = [Observation((date(2026, 1, 1) + timedelta(days=i)).isoformat(), rmssd_ms=55, sleep_hours=7,
                           sleep_need_hours=7) for i in range(10)]
        b = replay(obs)[-1]['metrics']['sleep_debt'].value
        self.assertGreater(a, 0)
        self.assertEqual(b, 0)

    def test_device_reported_rmssd(self):
        r = run([42])[0]['autonomic']
        self.assertEqual(r['rmssd'].value, 42)
        self.assertEqual(r['rmssd'].reason, 'device_reported')

    def test_ibi_segments_do_not_pair_across_bursts(self):
        burst_a = tuple(800 + 20 * (-1) ** j for j in range(30))
        burst_b = tuple(1100 + 20 * (-1) ** j for j in range(30))
        joined = replay([Observation('2026-01-01', burst_a + burst_b)])[0]['metrics']['autonomic']
        split = replay([Observation('2026-01-01', ibi_segments=(burst_a, burst_b))])[0]['metrics']['autonomic']
        self.assertAlmostEqual(split['rmssd'].value, 40)
        self.assertEqual(split['rmssd'].coverage, 1)
        self.assertLess(joined['rmssd'].coverage, 1)

    def test_validation(self):
        for kw in ({'rmssd_ms': 0}, {'rmssd_ms': float('nan')}, {'sleep_need_hours': 20},
                   {'rebaseline': 1}, {'device': ''}, {'ibi_ms': (900,), 'ibi_segments': ((900,),)}):
            with self.subTest(kw=kw), self.assertRaises(ValueError):
                Observation('2026-01-01', **kw)


if __name__ == '__main__':
    unittest.main()

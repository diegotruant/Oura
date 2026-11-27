"""Motore dei passi, classificatore, filtro a tre stati e calibrazione fitness-fatigue (1.3)."""
import math
import random
import unittest

from apse import motion
from apse.experiments import evaluate
from apse.research import DynamicFilter, fitness_fatigue_calibrate


def sig(freq, amp, seconds, hz=50):
    return [(0., 0., 1 + amp * math.sin(2 * math.pi * freq * i / hz)) for i in range(seconds * hz)]


class Steps(unittest.TestCase):
    # Gli stessi valori sono attesi dal test Kotlin MotionTest (parità tra app e modello).

    def test_walk(self):
        r = motion.count(sig(1.8, .3, 60), 50)
        self.assertEqual((r['candidates'], r['steps']), (106, 106))
        self.assertEqual(set(r['labels']), {motion.WALK})

    def test_run(self):
        r = motion.count(sig(2.8, .8, 60), 50)
        self.assertEqual(r['steps'], 165)
        self.assertEqual(set(r['labels']), {motion.RUN})

    def test_small_hand_motion_gives_no_steps(self):
        rng = random.Random(1)
        r = motion.count([(0., 0., 1 + (rng.random() - .5) * .04) for _ in range(3000)], 50)
        self.assertEqual(r['steps'], 0)

    def test_no_loss_at_window_boundaries(self):
        a = motion.analyze(sig(1.8, .3, 60), 50)
        self.assertEqual(sum(w['peaks'] for w in a['windows']), len(a['peaks']))
        # 59 secondi utili (il primo serve al filtro) a 1,8 passi al secondo
        self.assertAlmostEqual(len(a['peaks']), 1.8 * 59, delta=1)

    def test_mixed_and_tail(self):
        mixed = sig(1.8, .3, 30) + [(0., 0., 1.)] * 1500 + sig(1.8, .3, 35)
        r = motion.count(mixed, 50)
        self.assertEqual(r['labels'], ['WALK'] * 3 + ['NON_LOCOMOTOR'] * 3 + ['WALK'] * 3)
        self.assertEqual(r['unresolved_samples'], 250)
        self.assertEqual((r['candidates'], r['steps']), (115, 115))  # la coda eredita l'ultima etichetta

    def test_irregular_motion_rejected_by_rule(self):
        self.assertEqual(motion.rule_label([1., .3, 2., 100., .6]), motion.NON_LOCOMOTOR)   # passi irregolari
        self.assertEqual(motion.rule_label([1., .3, 2., 40., .1]), motion.NON_LOCOMOTOR)    # cadenza troppo bassa
        self.assertEqual(motion.rule_label([1., .01, .2, 100., .1]), motion.NON_LOCOMOTOR)  # quasi fermo
        self.assertEqual(motion.rule_label([1., .3, 2., 110., .1]), motion.WALK)
        self.assertEqual(motion.rule_label([1., .6, 5., 170., .1]), motion.RUN)

    def test_validation(self):
        with self.assertRaises(ValueError): motion.analyze(sig(1.8, .3, 5), 50)
        with self.assertRaises(ValueError): motion.analyze(sig(1.8, .3, 60), 5)
        with self.assertRaises(ValueError): motion.analyze([(0., 0., 40.)] * 600, 50)


class Classifier(unittest.TestCase):
    def data(self):
        rng = random.Random(2)
        x, y = [], []
        for name, c in ((motion.WALK, (1., 110.)), (motion.RUN, (3., 170.)), (motion.NON_LOCOMOTOR, (.2, 10.))):
            for _ in range(12):
                x.append([c[0] + rng.gauss(0, .05), c[1] + rng.gauss(0, 3)]); y.append(name)
        return x, y

    def test_fit_predict_reject(self):
        x, y = self.data()
        m = motion.fit(x, y)
        self.assertEqual(m.predict([1., 111.]), motion.WALK)
        self.assertEqual(m.predict([3., 168.]), motion.RUN)
        self.assertEqual(m.predict([50., 900.]), motion.UNKNOWN)   # lontano da tutto: rifiutato
        ev = motion.evaluate(m, x, y, y)
        self.assertEqual(ev['accuracy'], 1.)
        self.assertAlmostEqual(ev['majority_accuracy'], 1 / 3)

    def test_requires_all_classes(self):
        x, y = self.data()
        with self.assertRaises(ValueError): motion.fit(x[:24], y[:24])
        with self.assertRaises(ValueError): motion.fit(x[:5], y[:5])

    def test_trained_model_in_count(self):
        feats, labels = [], []
        for f, a, name in ((1.8, .3, motion.WALK), (2.8, .8, motion.RUN), (0., 0., motion.NON_LOCOMOTOR)):
            for w in motion.analyze(sig(f, a, 60), 50)['windows']:
                feats.append(w['features']); labels.append(name)
        m = motion.fit(feats, labels)
        self.assertEqual(motion.count(sig(1.8, .3, 40), 50, m)['steps'], motion.count(sig(1.8, .3, 40), 50)['steps'])


class ResearchPorts(unittest.TestCase):
    def test_dynamic_filter_moves_toward_observations(self):
        f = DynamicFilter()
        for _ in range(20): r = f.update(2., 2., 2.)
        self.assertTrue(all(1.2 < v < 2. for v in r['means']))
        self.assertIsNotNone(r['composite'])
        self.assertLess(r['composite_variance'], 4.)

    def test_dynamic_filter_missing_component(self):
        f = DynamicFilter()
        r = f.update(3., None, None)
        self.assertEqual(r['observed'], 1)
        self.assertIsNone(r['composite'])
        self.assertGreater(r['means'][0], 2.)
        # Gli stati sono debolmente collegati (covarianza di processo): quello non osservato si muove poco.
        self.assertTrue(0 < r['means'][2] < .1)
        with self.assertRaises(ValueError): f.update(float('nan'))

    def test_fitness_fatigue_recovers_time_constants(self):
        rng = random.Random(3)
        load = [rng.choice([0, 60, 90, 120]) for _ in range(120)]
        a, b = math.exp(-1 / 45), math.exp(-1 / 8)
        f = d = 0.
        perf = []
        for i, l in enumerate(load):
            f = a * f + (1 - a) * l; d = b * d + (1 - b) * l
            perf.append(f - d if i % 3 == 0 else None)
        r = fitness_fatigue_calibrate(load, perf)
        self.assertEqual((r['fitness_days'], r['fatigue_days']), (45, 8))
        self.assertLess(r['rmse'], 1e-9)
        self.assertEqual(evaluate({'model': 'fitness_fatigue_calibrate', 'inputs': {'load': load, 'performance': perf}})['fitness_days'], 45)

    def test_fitness_fatigue_validation(self):
        with self.assertRaises(ValueError): fitness_fatigue_calibrate([1.] * 10, [1.] * 10)
        with self.assertRaises(ValueError): fitness_fatigue_calibrate([1.] * 30, [None] * 30)


if __name__ == '__main__':
    unittest.main()

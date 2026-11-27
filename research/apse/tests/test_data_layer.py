"""Test di FIT, W′bal, persistenza, importatore, API e FATmax continuo (1.2)."""
import json
import math
import os
import struct
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

from apse import fit, wbal
from apse.api import make_handler, analyze
from apse.experiments import evaluate
from apse.importers import from_anello_csv
from apse.research import fatmax_curve
from apse.store import Store, observation_from_dict
from tools.compare_devices import compare

T0 = 1_100_000_000  # timestamp FIT arbitrario


def steady(watts, seconds, hr=140):
    return [{'offset_s': i, 'power_w': watts, 'heart_rate': hr} for i in range(seconds)]


def wrap(body):
    header = bytearray([14, 0x20]) + struct.pack('<H', 2132) + struct.pack('<I', len(body)) + b'.FIT'
    header += struct.pack('<H', fit.crc16(header))
    blob = bytes(header) + bytes(body)
    return blob + struct.pack('<H', fit.crc16(blob))


class Fit(unittest.TestCase):

    def test_crc_check_value(self):
        self.assertEqual(fit.crc16(b'123456789'), 0xBB3D)  # CRC-16/ARC

    def test_roundtrip(self):
        recs = [{'offset_s': i, 'power_w': 100 + i, 'heart_rate': 120 + i % 5} for i in range(120)]
        d = fit.decode(fit.encode_records(recs, T0))
        self.assertEqual(len(d['records']), 120)
        self.assertEqual(d['records'][7], {'timestamp': T0 + 7, 'power_w': 107, 'heart_rate': 122})

    def test_compressed_timestamps_rollover(self):
        recs = [{'offset_s': i, 'power_w': 200, 'heart_rate': 130} for i in range(100)]
        d = fit.decode(fit.encode_records(recs, T0 + 25, compressed_every=4))  # attraversa più confini di 32 s
        self.assertEqual([r['timestamp'] for r in d['records']], [T0 + 25 + i for i in range(100)])

    def test_big_endian(self):
        d = fit.decode(fit.encode_records(steady(250, 10), T0, big_endian=True))
        self.assertEqual(d['records'][-1]['power_w'], 250)

    def test_invalid_values_are_none(self):
        d = fit.decode(fit.encode_records([{'offset_s': 0, 'power_w': None, 'heart_rate': None}], T0))
        self.assertNotIn('power_w', d['records'][0])

    def test_corruption_detected(self):
        b = bytearray(fit.encode_records(steady(200, 30), T0))
        b[40] ^= 0xFF
        with self.assertRaises(fit.FitError):
            fit.decode(bytes(b))

    def test_truncated(self):
        with self.assertRaises(fit.FitError):
            fit.decode(fit.encode_records(steady(200, 30), T0)[:-10])

    def test_not_fit(self):
        with self.assertRaises(fit.FitError):
            fit.decode(b'x' * 40)

    def test_chained_files(self):
        a = fit.encode_records(steady(100, 10), T0)
        b = fit.encode_records(steady(300, 10), T0 + 100)
        d = fit.decode(a + b)
        self.assertEqual(len(d['records']), 20)
        self.assertEqual(d['records'][-1]['power_w'], 300)

    def test_developer_fields_skipped(self):
        body = bytearray()
        # definizione record con flag sviluppatore: timestamp + power, più 1 campo dev da 3 byte
        body += bytes([0x60, 0, 0]) + struct.pack('<H', fit.RECORD) + bytes([2, 253, 4, 0x86, 7, 2, 0x84])
        body += bytes([1, 0, 3, 0])
        body += bytes([0x00]) + struct.pack('<IH', T0, 333) + b'\x01\x02\x03'
        body += bytes([0x00]) + struct.pack('<IH', T0 + 1, 334) + b'\x04\x05\x06'
        d = fit.decode(wrap(body))
        self.assertEqual([r['power_w'] for r in d['records']], [333, 334])

    def test_power_series_fills_gaps(self):
        recs = steady(200, 5) + [{'offset_s': 10, 'power_w': 200, 'heart_rate': 1}]
        d = fit.decode(fit.encode_records(recs, T0))
        series, filled = fit.power_series(d['records'])
        self.assertEqual(len(series), 11)
        self.assertEqual(filled, 5)
        self.assertEqual(series[7], 0.)


class WBal(unittest.TestCase):

    def test_depletion_is_linear_above_cp(self):
        w = wbal.wbal_differential([350.] * 60, 250, 20_000)
        self.assertAlmostEqual(w[-1], 20_000 - 100 * 60)

    def test_recovery_matches_closed_form(self):
        # sotto CP: W(t) = W' - (W' - W0) · exp(-(CP - P) t / W')
        spent = [350.] * 60
        rest = [0.] * 300
        w = wbal.wbal_differential(spent + rest, 250, 20_000)
        w0 = w[59]
        expected = 20_000 - (20_000 - w0) * (1 - 250 / 20_000) ** 300
        self.assertAlmostEqual(w[-1], expected, places=6)
        self.assertGreater(w[-1], w0)

    def test_integral_tau(self):
        _, tau = wbal.wbal_integral([100.] * 100, 250, 20_000)
        self.assertAlmostEqual(tau, 546 * math.exp(-0.01 * 150) + 316)

    def test_negative_wbal_flagged(self):
        s = wbal.summary([500.] * 120, 250, 20_000)
        self.assertTrue(s['wbal_negative'])
        self.assertIn('underestimated', s['status'])

    def test_normalized_power(self):
        self.assertAlmostEqual(wbal.normalized_power([200.] * 600), 200)
        bursty = ([400.] * 30 + [0.] * 30) * 20
        self.assertGreater(wbal.normalized_power(bursty), sum(bursty) / len(bursty))
        self.assertIsNone(wbal.normalized_power([200.] * 10))

    def test_load_one_hour_at_cp_is_100(self):
        self.assertAlmostEqual(wbal.summary([250.] * 3600, 250, 20_000)['load_au'], 100, places=6)

    def test_validation(self):
        for args in (([], 250, 20_000), ([200.], 10, 20_000), ([200.], 250, 100), ([-5.], 250, 20_000)):
            with self.subTest(args=args), self.assertRaises(ValueError):
                wbal.wbal_differential(*args)

    def test_experiment(self):
        r = evaluate({'model': 'wbal', 'inputs': {'power': [300.] * 60, 'cp_w': 250, 'wprime_j': 20_000}})
        self.assertAlmostEqual(r['wbal_min_j'], 17_000)


class StoreTests(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = Store(os.path.join(self.tmp.name, 'apse.db'))

    def tearDown(self):
        self.store.close()
        self.tmp.cleanup()

    def test_fit_import_idempotent(self):
        data = fit.encode_records(steady(250, 3600), T0)
        s1, new1 = self.store.import_fit(data, 250, 20_000)
        s2, new2 = self.store.import_fit(data, 250, 20_000)
        self.assertTrue(new1)
        self.assertFalse(new2)
        self.assertEqual(s1['id'], s2['id'])
        obs = self.store.get(s1['day'])
        self.assertAlmostEqual(obs.load_au, 100, places=4)
        self.assertAlmostEqual(obs.training_minutes, 60)

    def test_two_activities_same_day_add_up(self):
        self.store.import_fit(fit.encode_records(steady(250, 1800), T0), 250, 20_000)
        s, _ = self.store.import_fit(fit.encode_records(steady(250, 1800), T0 + 7200), 250, 20_000)
        self.assertAlmostEqual(self.store.get(s['day']).load_au, 100, places=4)
        self.assertEqual(len(self.store.activities(s['day'])), 2)

    def test_merge_keeps_other_fields(self):
        self.store.merge('2026-01-05', sleep_hours=7.5, rmssd_ms=50, device='gen3')
        self.store.import_fit(fit.encode_records(steady(250, 600), T0), 250, 20_000)  # altro giorno
        self.store.merge('2026-01-05', resting_hr=48)
        o = self.store.get('2026-01-05')
        self.assertEqual((o.sleep_hours, o.rmssd_ms, o.device, o.resting_hr), (7.5, 50, 'gen3', 48))

    def test_utc_offset_changes_day(self):
        # T0 è 2024-11-09 alle 11:46 UTC circa; +14 h sposta al giorno dopo
        s, _ = self.store.import_fit(fit.encode_records(steady(200, 60), T0), 250, 20_000, utc_offset_hours=14)
        s0 = fit.unix_time(T0)
        import datetime
        utc_day = datetime.datetime.fromtimestamp(s0, datetime.timezone.utc).date()
        self.assertEqual(s['day'], (utc_day + datetime.timedelta(days=1)).isoformat())

    def test_no_power_rejected(self):
        data = fit.encode_records([{'offset_s': i, 'power_w': None, 'heart_rate': 120} for i in range(60)], T0)
        with self.assertRaises(fit.FitError):
            self.store.import_fit(data, 250, 20_000)

    def test_invalid_merge_not_written(self):
        with self.assertRaises(ValueError):
            self.store.merge('2026-01-05', sleep_hours=30)
        self.assertIsNone(self.store.get('2026-01-05'))

    def test_replay_from_store(self):
        for i in range(10):
            self.store.put(observation_from_dict({'day': f'2026-02-{i + 1:02d}', 'rmssd_ms': 50, 'sleep_hours': 8}))
        rows = self.store.replay()
        self.assertEqual(len(rows), 10)
        self.assertIsNotNone(rows[-1]['metrics']['recovery'].value)

    def test_persists_across_connections(self):
        path = os.path.join(self.tmp.name, 'apse.db')
        self.store.put(observation_from_dict({'day': '2026-03-01', 'ibi_segments': [[900, 920], [800, 810]]}))
        self.store.close()
        self.store = Store(path)
        self.assertEqual(self.store.get('2026-03-01').ibi_segments, ((900, 920), (800, 810)))

    def test_unknown_field_rejected(self):
        with self.assertRaises(ValueError):
            observation_from_dict({'day': '2026-01-01', 'hrv': 50})


CSV = """data,inizio_sonno,fine_sonno,a_letto_min,sonno_min,profondo_min,rem_min,leggero_min,sveglio_min,fc_min,fc_media_sonno,hrv,fc_riposo,spo2,respiro,passi,kcal_attive
2026-11-27,1,2,393,357,83,62,213,36,44.6,49.3,35.0,44.6,97.8,14.5,,
2026-11-28,1,2,400,0,,,,,,,,,,,,
"""


class ImportAndCompare(unittest.TestCase):

    def test_anello_csv(self):
        obs = from_anello_csv('gen3', text=CSV)
        self.assertEqual(obs[0].rmssd_ms, 35.0)
        self.assertAlmostEqual(obs[0].sleep_hours, 357 / 60)
        self.assertEqual(obs[0].device, 'gen3')
        self.assertIsNone(obs[1].sleep_hours)
        self.assertIsNone(obs[1].rmssd_ms)

    def test_missing_columns(self):
        with self.assertRaises(ValueError):
            from_anello_csv('gen3', text='data,foo\n2026-01-01,1\n')

    def test_compare_devices_ratio(self):
        a = [observation_from_dict({'day': f'2026-01-{i + 1:02d}', 'rmssd_ms': 47 * (1 + .02 * (i % 3)),
                                    'sleep_hours': 6, 'device': 'ring4'}) for i in range(20)]
        b = [observation_from_dict({'day': f'2026-01-{i + 1:02d}', 'rmssd_ms': 35 * (1 + .02 * (i % 3)),
                                    'sleep_hours': 6.1, 'device': 'gen3'}) for i in range(20)]
        rows, stats = compare(a, b)
        self.assertEqual(len(rows), 20)
        self.assertAlmostEqual(math.exp(stats['ln_rmssd']['bias']), 47 / 35, places=6)
        self.assertAlmostEqual(stats['sleep_h']['bias'], -0.1, places=6)


class FatmaxCurve(unittest.TestCase):

    @staticmethod
    def stages_for(fat):
        # dato un grasso g/min desiderato con RER noto, ricava VO2/VCO2 coerenti
        out = []
        for p, f in fat:
            vo2 = 2.0 + p / 100
            vco2 = vo2 - f / 1.67
            out.append((p, vo2, vco2))
        return out

    def test_recovers_parabola_peak(self):
        fat = [(p, 0.6 - 0.00002 * (p - 180) ** 2) for p in (100, 140, 180, 220, 260)]
        r = fatmax_curve(self.stages_for(fat))
        self.assertAlmostEqual(r['fatmax_w'], 180, places=6)
        self.assertAlmostEqual(r['fat_g_min'], 0.6, places=6)
        self.assertFalse(r['extrapolation_clipped'])

    def test_peak_outside_range_clipped(self):
        fat = [(p, 0.6 - 0.000002 * (p - 400) ** 2) for p in (100, 140, 180, 220)]
        r = fatmax_curve(self.stages_for(fat))
        self.assertEqual(r['fatmax_w'], 220)
        self.assertTrue(r['extrapolation_clipped'])

    def test_convex_has_no_peak(self):
        fat = [(p, 0.2 + 0.00002 * (p - 180) ** 2) for p in (100, 140, 180, 220)]
        self.assertIsNone(fatmax_curve(self.stages_for(fat))['fatmax_w'])

    def test_needs_four_stages(self):
        with self.assertRaises(ValueError):
            fatmax_curve([(100, 2, 1.6), (150, 2.5, 2), (200, 3, 2.6)])


class Api(unittest.TestCase):

    def start(self, token):
        srv = ThreadingHTTPServer(('127.0.0.1', 0), make_handler(token))
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        self.addCleanup(srv.server_close)
        self.addCleanup(srv.shutdown)
        return f'http://127.0.0.1:{srv.server_address[1]}'

    def post(self, url, body, headers):
        req = urllib.request.Request(url, data=json.dumps(body).encode(), headers=headers, method='POST')
        try:
            with urllib.request.urlopen(req, timeout=5) as r:
                return r.status, json.loads(r.read())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read())

    def test_token_required(self):
        base = self.start('segreto')
        body = {'observations': [{'day': '2026-01-01', 'rmssd_ms': 50}]}
        self.assertEqual(self.post(base + '/analyze', body, {'Content-Type': 'application/json'})[0], 401)
        self.assertEqual(self.post(base + '/analyze', body, {'Content-Type': 'application/json',
                                                             'Authorization': 'Bearer sbagliato'})[0], 401)
        code, data = self.post(base + '/analyze', body, {'Content-Type': 'application/json',
                                                         'Authorization': 'Bearer segreto'})
        self.assertEqual(code, 200)
        self.assertEqual(data['results'][0]['metrics']['autonomic']['rmssd']['value'], 50)

    def test_health_open_and_content_type(self):
        base = self.start('segreto')
        with urllib.request.urlopen(base + '/health', timeout=5) as r:
            self.assertEqual(r.status, 200)
        code, _ = self.post(base + '/analyze', {'observations': []}, {'Content-Type': 'text/plain',
                                                                     'Authorization': 'Bearer segreto'})
        self.assertEqual(code, 415)

    def test_analyze_accepts_json_lists(self):
        r = analyze({'observations': [{'day': '2026-01-01', 'ibi_segments': [[900, 920, 900], [800, 820]]}]})
        self.assertIsNotNone(r['results'][0]['metrics']['autonomic'])


if __name__ == '__main__':
    unittest.main()

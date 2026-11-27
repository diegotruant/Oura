"""Persistenza locale su SQLite (libreria standard): osservazioni e attività FIT.

- Un database = un individuo.
- Scritture in transazione; importare due volte lo stesso file FIT non duplica
  nulla (identificato dall'hash SHA-256 del contenuto).
- Il carico del giorno è ricalcolato dalla somma delle attività di quel giorno.
- `replay()` ricalcola l'intera serie dal database (nessuno stato salvato a metà).
"""
import hashlib
import json
import sqlite3
from dataclasses import fields
from datetime import datetime, timedelta, timezone

from . import fit, wbal
from .core import Observation
from .pipeline import replay as _replay

SCHEMA_VERSION = 1
_SCHEMA = """
create table if not exists meta(key text primary key, value text not null);
create table if not exists observations(day text primary key, data text not null);
create table if not exists activities(
  id text primary key, day text not null, start_unix integer not null,
  cp_w real not null, wprime_j real not null, summary text not null);
create index if not exists activities_day on activities(day);
"""
_TUPLE_FIELDS = {'ibi_ms'}
_NESTED_FIELDS = {'ibi_segments'}
_DEFAULTS = {f.name: f.default for f in fields(Observation)}


def _to_json(obs):
    d = {}
    for f in fields(Observation):
        v = getattr(obs, f.name)
        if f.name == 'day' or v != _DEFAULTS[f.name]:
            d[f.name] = [list(s) for s in v] if f.name in _NESTED_FIELDS else (list(v) if f.name in _TUPLE_FIELDS else v)
    return json.dumps(d, allow_nan=False, sort_keys=True)


def observation_from_dict(d):
    if not isinstance(d, dict):
        raise ValueError('osservazione non valida')
    unknown = set(d) - set(_DEFAULTS)
    if unknown:
        raise ValueError('campi sconosciuti: ' + ', '.join(sorted(unknown)))
    d = dict(d)
    for k in _TUPLE_FIELDS & set(d):
        d[k] = tuple(d[k])
    for k in _NESTED_FIELDS & set(d):
        d[k] = tuple(tuple(s) for s in d[k])
    return Observation(**d)


class Store:
    def __init__(self, path):
        self.db = sqlite3.connect(path)
        self.db.execute('pragma foreign_keys=on')
        with self.db:
            self.db.executescript(_SCHEMA)
            row = self.db.execute("select value from meta where key='schema'").fetchone()
            if row is None:
                self.db.execute("insert into meta values('schema', ?)", (str(SCHEMA_VERSION),))
            elif int(row[0]) != SCHEMA_VERSION:
                raise ValueError('versione del database non supportata')

    def close(self):
        self.db.close()

    # --- osservazioni ------------------------------------------------------

    def get(self, day):
        row = self.db.execute('select data from observations where day=?', (day,)).fetchone()
        return observation_from_dict(json.loads(row[0])) if row else None

    def put(self, obs):
        """Sostituisce l'osservazione del giorno."""
        if not isinstance(obs, Observation):
            raise ValueError('Observation richiesta')
        with self.db:
            self.db.execute('insert or replace into observations values(?,?)', (obs.day, _to_json(obs)))

    def merge(self, day, **values):
        """Aggiorna solo i campi indicati, lasciando gli altri invariati."""
        current = self.get(day)
        base = json.loads(_to_json(current)) if current else {'day': day}
        base.update(values)
        obs = observation_from_dict(base)  # valida prima di scrivere
        self.put(obs)
        return obs

    def observations(self):
        rows = self.db.execute('select data from observations order by day').fetchall()
        return [observation_from_dict(json.loads(r[0])) for r in rows]

    def replay(self):
        return _replay(self.observations())

    # --- attività FIT ------------------------------------------------------

    def import_fit(self, data, cp_w, wprime_j, utc_offset_hours=0.):
        """Importa un file FIT (bytes). Ritorna (riepilogo, nuovo: bool)."""
        if not -14 <= utc_offset_hours <= 14:
            raise ValueError('fuso orario non valido')
        if isinstance(data, str):
            with open(data, 'rb') as f:
                data = f.read()
        act_id = hashlib.sha256(data).hexdigest()
        row = self.db.execute('select summary from activities where id=?', (act_id,)).fetchone()
        if row:
            return json.loads(row[0]), False
        parsed = fit.decode(data)
        recs = parsed['records']
        if not recs:
            raise fit.FitError('nessun record nel file')
        if not any(r.get('power_w') is not None for r in recs):
            raise fit.FitError('nessun dato di potenza nel file')
        power, filled = fit.power_series(recs)
        summ = wbal.summary(power, cp_w, wprime_j, filled)
        start = fit.unix_time(min(r['timestamp'] for r in recs))
        local = datetime.fromtimestamp(start, timezone.utc) + timedelta(hours=utc_offset_hours)
        day = local.date().isoformat()
        summ.update({'start_unix': start, 'day': day, 'id': act_id})
        with self.db:
            self.db.execute('insert into activities values(?,?,?,?,?,?)',
                            (act_id, day, start, cp_w, wprime_j, json.dumps(summ, allow_nan=False)))
        self._refresh_day_load(day, cp_w)
        return summ, True

    def activities(self, day=None):
        q = 'select summary from activities' + (' where day=?' if day else '') + ' order by start_unix'
        return [json.loads(r[0]) for r in self.db.execute(q, (day,) if day else ())]

    def _refresh_day_load(self, day, cp_w):
        acts = self.activities(day)
        seconds = sum(a['seconds'] for a in acts)
        load = sum(a['load_au'] or 0. for a in acts)
        mean = sum(a['mean_power_w'] * a['seconds'] for a in acts) / seconds if seconds else None
        self.merge(day, load_au=min(2000., load), training_minutes=min(1440., seconds / 60),
                   mean_power_w=mean, cp_w=cp_w)

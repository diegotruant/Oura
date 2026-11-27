"""Lettore FIT minimale, senza dipendenze (formato pubblico del Garmin FIT SDK).

Gestisce: header da 12/14 byte, CRC di header e file, messaggi di definizione e
dati, endianness per messaggio, campi array, campi sviluppatore (saltati),
intestazioni a timestamp compresso, file FIT concatenati.

Estrae i messaggi `record` (al secondo: potenza, battito, cadenza, velocità,
distanza, altitudine) e `session`. Gli altri messaggi vengono ignorati.

`encode_records` scrive un FIT minimale valido: serve per i test, non per
produrre file da caricare su altre piattaforme.
"""
import struct

FIT_EPOCH_UNIX = 631065600  # 1989-12-31T00:00:00Z

_CRC_TABLE = (0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
              0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400)

# base type id (5 bit bassi) -> (formato struct, dimensione, valore non valido)
_BASE = {
    0x00: ('B', 1, 0xFF), 0x01: ('b', 1, 0x7F), 0x02: ('B', 1, 0xFF),
    0x03: ('h', 2, 0x7FFF), 0x04: ('H', 2, 0xFFFF), 0x05: ('i', 4, 0x7FFFFFFF),
    0x06: ('I', 4, 0xFFFFFFFF), 0x07: (None, 1, None), 0x08: ('f', 4, None),
    0x09: ('d', 8, None), 0x0A: ('B', 1, 0x00), 0x0B: ('H', 2, 0x0000),
    0x0C: ('I', 4, 0x00000000), 0x0D: (None, 1, None), 0x0E: ('q', 8, 0x7FFFFFFFFFFFFFFF),
    0x0F: ('Q', 8, 0xFFFFFFFFFFFFFFFF), 0x10: ('Q', 8, 0),
}

# Messaggi e campi usati (numero globale -> {numero campo: (nome, scala, offset)})
RECORD, SESSION = 20, 18
_FIELDS = {
    RECORD: {253: ('timestamp', 1, 0), 0: ('position_lat', 1, 0), 1: ('position_long', 1, 0),
             2: ('altitude_m', 5, 500), 78: ('altitude_m', 5, 500), 3: ('heart_rate', 1, 0),
             4: ('cadence', 1, 0), 5: ('distance_m', 100, 0), 6: ('speed_ms', 1000, 0),
             73: ('speed_ms', 1000, 0), 7: ('power_w', 1, 0)},
    SESSION: {253: ('timestamp', 1, 0), 2: ('start_time', 1, 0), 5: ('sport', 1, 0),
              7: ('total_elapsed_s', 1000, 0), 8: ('total_timer_s', 1000, 0),
              20: ('avg_power_w', 1, 0), 34: ('normalized_power_w', 1, 0)},
}


class FitError(ValueError):
    pass


def crc16(data, crc=0):
    for byte in data:
        tmp = _CRC_TABLE[crc & 0xF]
        crc = (crc >> 4) & 0x0FFF
        crc = crc ^ tmp ^ _CRC_TABLE[byte & 0xF]
        tmp = _CRC_TABLE[crc & 0xF]
        crc = (crc >> 4) & 0x0FFF
        crc = crc ^ tmp ^ _CRC_TABLE[(byte >> 4) & 0xF]
    return crc


def _value(raw, base_type, little):
    fmt, size, invalid = _BASE.get(base_type & 0x1F, (None, 1, None))
    if fmt is None or len(raw) != size:
        return None  # stringhe, byte, array: non servono qui
    v = struct.unpack(('<' if little else '>') + fmt, raw)[0]
    if invalid is not None and v == invalid:
        return None
    return v


def decode(data):
    """Decodifica un file FIT (anche concatenato). Ritorna {'records': [...], 'sessions': [...]}."""
    if not isinstance(data, (bytes, bytearray)):
        raise FitError('bytes richiesti')
    out = {'records': [], 'sessions': []}
    pos = 0
    while pos < len(data):
        if len(data) - pos < 12:
            if data[pos:].strip(b'\x00'):
                raise FitError('dati residui non validi dopo il file FIT')
            break
        pos = _decode_one(data, pos, out)
    return out


def _decode_one(data, pos, out):
    hsize = data[pos]
    if hsize not in (12, 14) or data[pos + 8:pos + 12] != b'.FIT':
        raise FitError('intestazione FIT non valida')
    data_size = struct.unpack('<I', data[pos + 4:pos + 8])[0]
    if hsize == 14:
        hcrc = struct.unpack('<H', data[pos + 12:pos + 14])[0]
        if hcrc != 0 and hcrc != crc16(data[pos:pos + 12]):
            raise FitError('CRC intestazione errato')
    end = pos + hsize + data_size
    if end + 2 > len(data):
        raise FitError('file troncato')
    if crc16(data[pos:end + 2]) != 0:
        raise FitError('CRC del file errato')

    defs = {}
    last_ts = None
    p = pos + hsize
    while p < end:
        h = data[p]
        p += 1
        if h & 0x80:  # timestamp compresso
            local = (h >> 5) & 0x03
            offset = h & 0x1F
            if last_ts is None:
                raise FitError('timestamp compresso senza riferimento')
            ts = (last_ts & ~0x1F) + offset
            if offset < (last_ts & 0x1F):
                ts += 0x20
            last_ts = ts
            p, _ = _read_data(data, p, defs, local, out, ts)
            continue
        local = h & 0x0F
        if h & 0x40:  # definizione
            if p + 5 > end:
                raise FitError('definizione troncata')
            little = data[p + 1] == 0
            glob = struct.unpack('<H' if little else '>H', data[p + 2:p + 4])[0]
            nfields = data[p + 4]
            p += 5
            fields = []
            for _ in range(nfields):
                fields.append((data[p], data[p + 1], data[p + 2]))
                p += 3
            dev_size = 0
            if h & 0x20:
                ndev = data[p]
                p += 1
                for _ in range(ndev):
                    dev_size += data[p + 1]
                    p += 3
            defs[local] = (glob, little, fields, dev_size)
        else:
            p, ts = _read_data(data, p, defs, local, out, None)
            if ts is not None:
                last_ts = ts
    if p != end:
        raise FitError('lunghezza dei messaggi incoerente')
    return end + 2


def _read_data(data, p, defs, local, out, compressed_ts):
    if local not in defs:
        raise FitError('messaggio dati senza definizione')
    glob, little, fields, dev_size = defs[local]
    names = _FIELDS.get(glob)
    msg = {}
    ts = None
    for num, size, base in fields:
        raw = data[p:p + size]
        if len(raw) != size:
            raise FitError('messaggio troncato')
        p += size
        if num == 253:
            ts = _value(raw, base, little)
        if names and num in names:
            v = _value(raw, base, little)
            if v is not None:
                name, scale, off = names[num]
                msg[name] = v / scale - off if (scale != 1 or off) else v
    p += dev_size
    if compressed_ts is not None:
        msg['timestamp'] = compressed_ts
    if glob == RECORD and 'timestamp' in msg:
        out['records'].append(msg)
    elif glob == SESSION:
        out['sessions'].append(msg)
    return p, ts


def read(path, max_bytes=50_000_000):
    with open(path, 'rb') as f:
        data = f.read(max_bytes + 1)
    if len(data) > max_bytes:
        raise FitError('file troppo grande')
    return decode(data)


def unix_time(fit_ts):
    return fit_ts + FIT_EPOCH_UNIX


def power_series(records):
    """Serie di potenza a 1 Hz dall'inizio alla fine dell'attività.

    Secondi mancanti (pausa automatica, buchi di registrazione) e potenza non
    valida valgono 0 W: per W'bal equivalgono a recupero. Ritorna
    (lista di watt, numero di secondi riempiti).
    """
    pts = sorted((r['timestamp'], r.get('power_w')) for r in records if 'timestamp' in r)
    if not pts:
        return [], 0
    t0, t1 = pts[0][0], pts[-1][0]
    if t1 - t0 > 48 * 3600:
        raise FitError('attività più lunga di 48 ore')
    series = [None] * (t1 - t0 + 1)
    for t, w in pts:
        if w is not None:
            series[t - t0] = float(w)
    filled = sum(1 for w in series if w is None)
    return [0. if w is None else w for w in series], filled


# --- Encoder minimale (solo per test e fixture) -------------------------------

def encode_records(records, start_fit_ts, compressed_every=0, big_endian=False):
    """Crea un FIT con file_id e messaggi record (timestamp, potenza, battito).

    records: lista di dict con 'offset_s', 'power_w' (o None), 'heart_rate' (o None).
    compressed_every: se >0, usa timestamp compressi per i record intermedi.
    """
    e = '>' if big_endian else '<'
    arch = 1 if big_endian else 0
    body = bytearray()
    # file_id (0): type=4 activity
    body += bytes([0x40, 0, arch]) + struct.pack(e + 'H', 0) + bytes([1, 0, 1, 0x00])
    body += bytes([0x00, 4])
    # record completo (locale 1): timestamp, power, heart_rate
    body += bytes([0x41, 0, arch]) + struct.pack(e + 'H', RECORD) + bytes([3, 253, 4, 0x86, 7, 2, 0x84, 3, 1, 0x02])
    # record compresso (locale 2): power, heart_rate
    body += bytes([0x42, 0, arch]) + struct.pack(e + 'H', RECORD) + bytes([2, 7, 2, 0x84, 3, 1, 0x02])
    last = None
    for i, r in enumerate(records):
        ts = start_fit_ts + r['offset_s']
        pw = 0xFFFF if r.get('power_w') is None else int(r['power_w'])
        hr = 0xFF if r.get('heart_rate') is None else int(r['heart_rate'])
        use_comp = (compressed_every and last is not None and i % compressed_every and 0 <= ts - last < 32)
        if use_comp:
            body += bytes([0x80 | (2 << 5) | (ts & 0x1F)]) + struct.pack(e + 'H', pw) + bytes([hr])
        else:
            body += bytes([0x01]) + struct.pack(e + 'IH', ts, pw) + bytes([hr])
        last = ts
    header = bytearray([14, 0x20]) + struct.pack('<H', 2132) + struct.pack('<I', len(body)) + b'.FIT'
    header += struct.pack('<H', crc16(header))
    blob = header + body
    return bytes(blob + struct.pack('<H', crc16(blob)))

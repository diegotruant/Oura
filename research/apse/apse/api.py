"""API HTTP locale di ricerca.

Sicurezza (1.2):
- ascolta solo su 127.0.0.1 per default (APSE_HOST per cambiarlo);
- con APSE_TOKEN impostato, tutte le richieste tranne /health richiedono
  `Authorization: Bearer <token>` (confronto a tempo costante);
- su un indirizzo non locale il server rifiuta di partire senza token;
- POST solo con Content-Type application/json e corpo fino a 4 MB.
Resta un servizio di ricerca: niente TLS, niente utenti. Per esporlo in rete
mettere davanti un reverse proxy con HTTPS.
"""
import hmac
import ipaddress
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from . import __version__ as VERSION
from .experiments import evaluate
from .pipeline import replay, serializable, graph
from .store import observation_from_dict

MAX_BODY = 4_000_000


def analyze(payload):
    if not isinstance(payload, dict) or set(payload) != {'observations'}: raise ValueError('expected observations object')
    rows = payload['observations']
    if not isinstance(rows, list) or not 1 <= len(rows) <= 366: raise ValueError('1–366 observations required')
    if not all(isinstance(r, dict) for r in rows): raise ValueError('invalid observation')
    return {'version': VERSION, 'results': serializable(replay([observation_from_dict(r) for r in rows]))}


def _authorized(headers, token):
    if not token:
        return True
    got = headers.get('Authorization', '')
    return hmac.compare_digest(got.encode(), ('Bearer ' + token).encode())


def make_handler(token):
    class Handler(BaseHTTPRequestHandler):
        server_version = 'APSE'
        sys_version = ''

        def log_message(self, fmt, *args):  # niente dati di salute nei log
            pass

        def reply(self, status, payload):
            data = json.dumps(payload, allow_nan=False).encode()
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(data)))
            self.send_header('Cache-Control', 'no-store')
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            if self.path == '/health': return self.reply(200, {'status': 'ok', 'version': VERSION})
            if not _authorized(self.headers, token): return self.reply(401, {'error': 'unauthorized'})
            if self.path == '/graph': self.reply(200, {k: list(n.deps) for k, n in graph().nodes.items()})
            else: self.reply(404, {'error': 'not_found'})

        def do_POST(self):
            if self.path not in ('/analyze', '/experiment'): return self.reply(404, {'error': 'not_found'})
            if not _authorized(self.headers, token): return self.reply(401, {'error': 'unauthorized'})
            if self.headers.get('Content-Type', '').split(';')[0].strip() != 'application/json':
                return self.reply(415, {'error': 'application/json required'})
            try:
                n = int(self.headers.get('Content-Length', '0'))
                if not 0 < n <= MAX_BODY: raise ValueError('invalid request size')
                payload = json.loads(self.rfile.read(n))
                self.reply(200, (analyze if self.path == '/analyze' else evaluate)(payload))
            except (ValueError, TypeError, OverflowError, KeyError) as e:
                self.reply(400, {'error': str(e)})
    return Handler


def serve(host=None, port=None, token=None):
    host = host or os.environ.get('APSE_HOST', '127.0.0.1')
    port = int(port or os.environ.get('APSE_PORT', '8765'))
    token = token if token is not None else os.environ.get('APSE_TOKEN', '')
    try:
        loopback = ipaddress.ip_address(host).is_loopback
    except ValueError:
        loopback = host == 'localhost'
    if not loopback and not token:
        raise SystemExit('Rifiuto di ascoltare su un indirizzo non locale senza APSE_TOKEN.')
    ThreadingHTTPServer((host, port), make_handler(token)).serve_forever()


if __name__ == '__main__':
    serve()

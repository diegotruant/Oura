"""Local research HTTP API. No authentication: bind loopback only."""
import json
from .experiments import evaluate
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from .core import Observation
from . import __version__ as VERSION
from .pipeline import replay,serializable,graph

def analyze(payload):
    if not isinstance(payload,dict) or set(payload)!={'observations'}: raise ValueError('expected observations object')
    rows=payload['observations']
    if not isinstance(rows,list) or not 1<=len(rows)<=366: raise ValueError('1–366 observations required')
    if not all(isinstance(r,dict) for r in rows): raise ValueError('invalid observation')
    return {'version':VERSION,'results':serializable(replay([Observation(**r) for r in rows]))}

class Handler(BaseHTTPRequestHandler):
    def reply(self,status,payload):
        data=json.dumps(payload,allow_nan=False).encode(); self.send_response(status); self.send_header('Content-Type','application/json'); self.send_header('Content-Length',str(len(data))); self.end_headers(); self.wfile.write(data)
    def do_GET(self):
        if self.path=='/health': self.reply(200,{'status':'ok','version':VERSION})
        elif self.path=='/graph': self.reply(200,{k:list(n.deps) for k,n in graph().nodes.items()})
        else: self.reply(404,{'error':'not_found'})
    def do_POST(self):
        if self.path not in ('/analyze','/experiment'): return self.reply(404,{'error':'not_found'})
        try:
            n=int(self.headers.get('Content-Length','0'))
            if not 0<n<=4_000_000: raise ValueError('invalid request size')
            payload=json.loads(self.rfile.read(n))
            self.reply(200,(analyze if self.path=='/analyze' else evaluate)(payload))
        except (ValueError,TypeError,OverflowError,KeyError) as e: self.reply(400,{'error':str(e)})
if __name__=='__main__': ThreadingHTTPServer(('127.0.0.1',8765),Handler).serve_forever()

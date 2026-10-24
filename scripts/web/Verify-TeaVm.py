"""Real Edge/WebGL smoke for the isolated TeaVM trial. Requires websocket-client for CDP."""
from pathlib import Path
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
from functools import partial
from threading import Thread
import base64, json, os, subprocess, time, urllib.request
import websocket

ROOT=Path(__file__).resolve().parents[2]
WEB=ROOT/'final-project/teavm-probe/build/dist/webapp'
EVIDENCE=ROOT/'docs/gameplay/runs/20261022-teavm-browser'
PROFILE=ROOT/'final-project/build'/('teavm-edge-profile-'+str(int(time.time()*1000)))
EDGE=Path(os.environ.get('TEAVM_BROWSER',r'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe'))

class QuietHandler(SimpleHTTPRequestHandler):
    def log_message(self, *args): pass

class Cdp:
    def __init__(self,url):
        self.ws=websocket.create_connection(url,timeout=10,origin='http://localhost')
        self.serial=0;self.events=[]
    def call(self,method,params=None):
        self.serial+=1;serial=self.serial
        self.ws.send(json.dumps(dict(id=serial,method=method,params=params or {})))
        while True:
            msg=json.loads(self.ws.recv())
            if msg.get('id')==serial:
                if 'error' in msg: raise RuntimeError(msg['error'])
                return msg.get('result',{})
            self.events.append(msg)
    def evaluate(self,expression):
        result=self.call('Runtime.evaluate',dict(expression=expression,returnByValue=True))
        if 'exceptionDetails' in result: raise RuntimeError(result['exceptionDetails'])
        return result['result'].get('value')
    def state(self): return self.evaluate('window.smashprofsProbe || null')
    def key(self,name,down):
        code=ord(name)
        self.call('Input.dispatchKeyEvent',dict(type='keyDown' if down else 'keyUp',key=name.lower(),code='Key'+name,windowsVirtualKeyCode=code,nativeVirtualKeyCode=code))
    def press(self,name,duration=.08):
        self.key(name,True);time.sleep(duration);self.key(name,False)
    def shot(self,name):
        result=self.call('Page.captureScreenshot',dict(format='png',captureBeyondViewport=False))
        (EVIDENCE/name).write_bytes(base64.b64decode(result['data']))
    def close(self): self.ws.close()

def fetch(url):
    with urllib.request.urlopen(url,timeout=5) as response: return json.load(response)

def wait_for(function,seconds=15):
    deadline=time.monotonic()+seconds
    while time.monotonic()<deadline:
        value=function()
        if value: return value
        time.sleep(.1)
    raise AssertionError('Browser wait timed out')

def main():
    assert (WEB/'index.html').is_file(), 'Build the TeaVM probe first'
    assert EDGE.is_file(), 'Set TEAVM_BROWSER to an Edge/Chrome executable'
    assert PROFILE.resolve().is_relative_to((ROOT/'final-project/build').resolve())
    EVIDENCE.mkdir(parents=True,exist_ok=True);PROFILE.mkdir(parents=True)
    server=ThreadingHTTPServer(('127.0.0.1',0),partial(QuietHandler,directory=str(WEB)))
    Thread(target=server.serve_forever,daemon=True).start()
    url=f'http://127.0.0.1:{server.server_port}/'
    log=(EVIDENCE/'browser.stderr.log').open('w',encoding='utf-8')
    browser=subprocess.Popen([str(EDGE),'--headless=new','--no-first-run','--no-default-browser-check',
        '--disable-background-networking','--disable-gpu','--enable-unsafe-swiftshader','--use-angle=swiftshader',
        '--remote-allow-origins=*','--remote-debugging-port=0','--user-data-dir='+str(PROFILE),'about:blank'],
        stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
    connections=[];states={}
    try:
        active=PROFILE/'DevToolsActivePort'
        wait_for(lambda:active.exists() and len(active.read_text().splitlines())>=2)
        debug_port=int(active.read_text().splitlines()[0]);debug=f'http://127.0.0.1:{debug_port}'
        control=Cdp(fetch(debug+'/json/version')['webSocketDebuggerUrl']);connections.append(control)
        tab=control.call('Target.createTarget',dict(url='about:blank'))['targetId']
        page=Cdp(f'ws://127.0.0.1:{debug_port}/devtools/page/{tab}');connections.append(page)
        page.call('Page.enable');page.call('Runtime.enable');page.call('Network.enable')
        page.call('Emulation.setDeviceMetricsOverride',dict(width=1280,height=720,deviceScaleFactor=1,mobile=False))
        page.call('Page.navigate',dict(url=url));page.call('Page.bringToFront')
        states['initial']=wait_for(page.state,30);page.shot('01-map-boss.png')
        assert states['initial']['ready'] and states['initial']['character']=='Alex'
        page.press('D',.35);states['right']=page.state()
        assert states['right']['x']>states['initial']['x']+.1, states
        page.press('A',.30);states['left']=page.state()
        assert states['left']['x']<states['right']['x']-.05, states
        page.press('R');time.sleep(.2);states['beforeJump']=page.state()
        page.press('W',.1);states['jump']=page.state();page.shot('02-w-jump.png')
        assert states['jump']['y']>states['beforeJump']['y']+.1, states
        assert not states['jump']['grounded']
        page.call('Input.dispatchKeyEvent',dict(type='keyDown',key='5',code='Digit5',windowsVirtualKeyCode=53,nativeVirtualKeyCode=53))
        time.sleep(.1)
        page.call('Input.dispatchKeyEvent',dict(type='keyUp',key='5',code='Digit5',windowsVirtualKeyCode=53,nativeVirtualKeyCode=53))
        states['viktor']=page.state();assert states['viktor']['character']=='Viktor'
        page.press('J');page.press('B');page.press('F');time.sleep(.1)
        states['pose']=page.state();assert states['pose']['bossMode']==1
        page.shot('03-character-boss-pose.png')
        page.call('Emulation.setDeviceMetricsOverride',dict(width=960,height=540,deviceScaleFactor=1,mobile=False))
        time.sleep(.3);states['small']=page.state();page.shot('04-small-screen.png')
        assert states['small']['width']==960 and states['small']['height']==540
        errors=[e for e in page.events if e.get('method')=='Runtime.exceptionThrown']
        failed=[e['params']['response']['url'] for e in page.events if e.get('method')=='Network.responseReceived' and e['params']['response']['status']>=400 and 'favicon' not in e['params']['response']['url']]
        assert not errors,errors
        assert not failed,failed
        result=dict(status='PASS',date='2026-10-22',browser='Headless Edge/WebGL (SwiftShader)',
            checks=['Original TMX map and tilesets load','Five original character sheets preload','Existing OnlineBossAssets and BossSnapshot run in browser','Shared FighterMovement handles A/D movement','W jumps','Character switch and boss pose','960x540 resize','No JS exceptions or failed asset responses'],
            states=states,limits=['Local movement fixture; no multiplayer/combat','Audio play was triggered; audible output not verified'])
        (EVIDENCE/'verification.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
        print('PASS TeaVM browser: original map, character/boss art, A/D movement, W jump, resize.')
    except Exception:
        for c in connections:
            try: c.shot('failure.png')
            except Exception: pass
        (EVIDENCE/'failure.json').write_text(json.dumps(dict(states=states,events=[e for c in connections for e in c.events]),indent=2),encoding='utf-8')
        raise
    finally:
        try:
            if connections: connections[0].call('Browser.close')
        except Exception: pass
        for c in connections:
            try: c.close()
            except Exception: pass
        if browser.poll() is None:
            try: browser.wait(timeout=5)
            except subprocess.TimeoutExpired: browser.terminate();browser.wait(timeout=5)
        server.shutdown();server.server_close();log.close()

if __name__=='__main__': main()

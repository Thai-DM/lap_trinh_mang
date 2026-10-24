"""Exercise graphics startup/failure/fallback on the actual TeaVM build in isolated Edge pages."""
from pathlib import Path
from functools import partial
from http.server import ThreadingHTTPServer
from threading import Thread
import importlib.util, json, os, subprocess, time

spec=importlib.util.spec_from_file_location('browser_tools',Path(__file__).with_name('Verify-TeaVm.py'))
b=importlib.util.module_from_spec(spec);spec.loader.exec_module(b)
ROOT=b.ROOT
EVIDENCE=ROOT/'docs/gameplay/runs/20261024-webgl-startup'

def main():
    EVIDENCE.mkdir(parents=True,exist_ok=True);b.EVIDENCE=EVIDENCE
    run=ROOT/'final-project/build'/('webgl-verify-'+str(int(time.time()*1000)));profile=run/'edge';profile.mkdir(parents=True)
    server=ThreadingHTTPServer(('127.0.0.1',0),partial(b.QuietHandler,directory=str(ROOT/'final-project/web/build/dist/webapp')))
    Thread(target=server.serve_forever,daemon=True).start()
    log=(EVIDENCE/'browser.stderr.log').open('w',encoding='utf-8')
    process=subprocess.Popen([str(b.EDGE),'--headless=new','--no-first-run','--disable-background-networking','--disable-gpu',
        '--enable-unsafe-swiftshader','--use-angle=swiftshader','--disable-renderer-backgrounding','--disable-background-timer-throttling',
        '--remote-allow-origins=*','--remote-debugging-port=0','--user-data-dir='+str(profile),'about:blank'],
        stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
    connections=[];checks=[];states={}
    try:
        active=profile/'DevToolsActivePort';b.wait_for(lambda:active.exists() and len(active.read_text().splitlines())>=2)
        debug='http://127.0.0.1:'+active.read_text().splitlines()[0]
        control=b.Cdp(b.fetch(debug+'/json/version')['webSocketDebuggerUrl']);connections.append(control)
        cases=[('disabled',"return null;",False),('throws',"throw new Error('GPU unavailable');",False),
            ('low-power',"if(!options || options.powerPreference!=='low-power') return null;return original.call(this,kind,options);",True),
            ('legacy-name',"if(kind==='webgl')return null;return original.call(this,'webgl',options);",True),('normal',None,True)]
        for name,override,starts in cases:
            target=control.call('Target.createTarget',dict(url='about:blank'))['targetId']
            page=b.Cdp(f'{debug.replace("http://","ws://")}/devtools/page/{target}');connections.append(page)
            page.call('Runtime.enable');page.call('Page.enable')
            page.call('Emulation.setDeviceMetricsOverride',dict(width=1280,height=720,deviceScaleFactor=1,mobile=False))
            setup="localStorage.setItem('webgl-profile-preserved','keep');"
            if override:
                setup+="const original=HTMLCanvasElement.prototype.getContext;HTMLCanvasElement.prototype.getContext=function(kind,options){if(kind==='webgl'||kind==='experimental-webgl'){"+override+"}return original.call(this,kind,options);};"
            page.call('Page.addScriptToEvaluateOnNewDocument',dict(source=setup))
            page.call('Page.navigate',dict(url=f'http://127.0.0.1:{server.server_port}/?debug=1'))
            if starts:
                b.wait_for(lambda:page.evaluate("window.smashprofsWeb && window.smashprofsWeb.screen==='MainMenuScreen'"),45)
                assert page.evaluate("document.getElementById('failure').hidden")
                if name=='low-power':assert page.evaluate('window.smashprofsGraphics.mode')=='low-power'
                if name=='legacy-name':assert page.evaluate('window.smashprofsGraphics.attempts')==2
                checks.append(name+': real shared menu starts with the selected graphics context')
            else:
                b.wait_for(lambda:page.evaluate("document.getElementById('failure').dataset.code==='webgl-unavailable'"),20)
                assert page.evaluate('window.smashprofsWeb || null') is None
                assert not page.evaluate("document.getElementById('graphics-help').hidden")
                checks.append(name+': unavailable WebGL stops before TeaVM initialization and shows actionable help')
            assert page.evaluate("localStorage.getItem('webgl-profile-preserved')")=='keep'
            assert not [e for e in page.events if e.get('method')=='Runtime.exceptionThrown']
            states[name]=page.evaluate("({graphics:window.smashprofsGraphics,failure:document.getElementById('failure').dataset.code||null})")
            page.shot(name+'.png')
            if name=='normal':
                assert page.evaluate("!!document.getElementById('canvas').getContext('webgl').getExtension('WEBGL_lose_context')")
                page.evaluate("document.getElementById('canvas').getContext('webgl').getExtension('WEBGL_lose_context').loseContext()")
                b.wait_for(lambda:page.evaluate("document.getElementById('failure').dataset.code==='webgl-lost'"))
                assert page.evaluate('window.smashprofsGraphicsLost')
                assert page.evaluate("localStorage.getItem('webgl-profile-preserved')")=='keep'
                page.shot('context-lost.png');checks.append('Lost context stops rendering and offers reload without deleting browser profile')
            control.call('Target.closeTarget',dict(targetId=target));page.close();print('PASS '+name,flush=True)
        (EVIDENCE/'verification.json').write_text(json.dumps(dict(status='PASS',date='2026-10-24',checks=checks,states=states),indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
    finally:
        for connection in reversed(connections):
            try:connection.close()
            except Exception:pass
        if process.poll() is None:
            process.terminate()
            try:process.wait(timeout=8)
            except subprocess.TimeoutExpired:process.kill();process.wait(timeout=5)
        server.shutdown();server.server_close();log.close()

if __name__=='__main__':main()

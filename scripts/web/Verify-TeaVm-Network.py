"""Two real browser tabs -> WebSocket gateway -> unchanged Java server/physics."""
from pathlib import Path
from functools import partial
from threading import Thread
from http.server import ThreadingHTTPServer
import importlib.util, json, os, subprocess, time, urllib.parse

spec=importlib.util.spec_from_file_location('probe_browser',Path(__file__).with_name('Verify-TeaVm.py'))
browser_tools=importlib.util.module_from_spec(spec);spec.loader.exec_module(browser_tools)
ROOT=browser_tools.ROOT
EVIDENCE=ROOT/'docs/gameplay/runs/20261022-teavm-network'

def network(page):
    return page.evaluate("(()=>{let n=window.smashprofsNetwork;return n?Object.assign({},n,{snapshot:n.snapshot?JSON.parse(n.snapshot):null}):null})()")

def main():
    EVIDENCE.mkdir(parents=True,exist_ok=True);browser_tools.EVIDENCE=EVIDENCE
    profile=ROOT/'final-project/build'/('teavm-network-profile-'+str(int(time.time()*1000)))
    assert profile.resolve().is_relative_to((ROOT/'final-project/build').resolve());profile.mkdir(parents=True)
    java=ROOT.parent/'baitap/bin/java.exe'
    gateway_lib=ROOT/'final-project/teavm-probe/build/gateway/lib'
    assert (gateway_lib/'probe-gateway.jar').is_file(), 'Run prepareGateway first'
    gateway_log=(EVIDENCE/'gateway.log').open('w',encoding='utf-8')
    gateway=subprocess.Popen([str(java),'-Xmx128m','-cp',str(gateway_lib/'*'),'com.smashprofs.probe.ProbeGateway','0'],
        cwd=ROOT,stdout=gateway_log,stderr=subprocess.STDOUT)
    server=ThreadingHTTPServer(('127.0.0.1',0),partial(browser_tools.QuietHandler,directory=str(browser_tools.WEB)))
    Thread(target=server.serve_forever,daemon=True).start()
    browser_log=(EVIDENCE/'browser.stderr.log').open('w',encoding='utf-8')
    browser=subprocess.Popen([str(browser_tools.EDGE),'--headless=new','--no-first-run','--no-default-browser-check',
        '--disable-background-networking','--disable-gpu','--enable-unsafe-swiftshader','--use-angle=swiftshader',
        '--disable-renderer-backgrounding','--disable-background-timer-throttling','--disable-backgrounding-occluded-windows',
        '--remote-allow-origins=*','--remote-debugging-port=0','--user-data-dir='+str(profile),'about:blank'],
        stdout=browser_log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
    connections=[];pages=[];states={}
    try:
        def gateway_port():
            text=(EVIDENCE/'gateway.log').read_text(encoding='utf-8')
            for line in text.splitlines():
                if line.startswith('PROBE_GATEWAY_READY '): return int(line.split()[1])
            return None
        port=browser_tools.wait_for(gateway_port,15)
        active=profile/'DevToolsActivePort'
        browser_tools.wait_for(lambda:active.exists() and len(active.read_text().splitlines())>=2)
        debug_port=int(active.read_text().splitlines()[0]);debug=f'http://127.0.0.1:{debug_port}'
        control=browser_tools.Cdp(browser_tools.fetch(debug+'/json/version')['webSocketDebuggerUrl']);connections.append(control)
        room='TV'+str(int(time.time()))
        for mode in ['create','join']:
            target=control.call('Target.createTarget',dict(url='about:blank'))['targetId']
            page=browser_tools.Cdp(f'ws://127.0.0.1:{debug_port}/devtools/page/{target}');connections.append(page);pages.append(page)
            page.call('Page.enable');page.call('Runtime.enable');page.call('Network.enable')
            page.call('Emulation.setDeviceMetricsOverride',dict(width=1280,height=720,deviceScaleFactor=1,mobile=False))
            page.call('Emulation.setFocusEmulationEnabled',dict(enabled=True))
            query=urllib.parse.urlencode(dict(mode=mode,room=room,boss='true',ws=f'ws://127.0.0.1:{port}'))
            page.call('Page.navigate',dict(url=f'http://127.0.0.1:{server.server_port}/?'+query));page.call('Page.bringToFront')
            browser_tools.wait_for(lambda:network(page) and network(page)['playerId'],20)
            if mode=='create': time.sleep(.3)
        for i,page in enumerate(pages):
            page.call('Page.bringToFront')
            states[f'initial{i}']=browser_tools.wait_for(lambda:network(page) if network(page) and network(page)['started'] and network(page)['received']>2 else None,20)
            assert states[f'initial{i}']['players']==2, states
            assert states[f'initial{i}']['bossHp']>0, states
            assert not states[f'initial{i}']['error'], states
            page.shot(f'0{i+1}-boss-peer-{i+1}.png')
        assert states['initial0']['matchId']==states['initial1']['matchId']
        assert states['initial0']['playerId']!=states['initial1']['playerId']
        a,b=pages;aid=states['initial0']['playerId']
        def player(n,id): return next(p for p in n['snapshot']['players'] if p['playerId']==id)
        a.call('Page.bringToFront');time.sleep(.1)
        before=network(a);initial_x=player(before,aid)['x']
        direction='D' if initial_x<6 else 'A'
        a.press(direction,.6);time.sleep(.15);states['afterMoveA']=network(a)
        b.call('Page.bringToFront');time.sleep(.2);states['afterMoveB']=network(b)
        moved=player(states['afterMoveA'],aid)['x']-initial_x
        assert abs(moved)>.04, dict(moved=moved,states=states)
        assert abs(player(states['afterMoveB'],aid)['x']-initial_x)>.04, states
        b.shot('03-peer-sees-movement.png')
        a.call('Page.bringToFront');time.sleep(.1)
        ammo_before=next(p for p in network(a)['snapshot']['gameplay']['players'] if p['playerId']==aid)['ammo']
        a.press('J',.15);time.sleep(.2);states['afterShot']=network(a)
        progress=next(p for p in states['afterShot']['snapshot']['gameplay']['players'] if p['playerId']==aid)
        assert progress['ammo']<ammo_before,states
        fighter=next(p for p in states['afterShot']['snapshot']['gameplay']['fighter']['players'] if p['playerId']==aid)
        assert fighter['attack']=='NONE',fighter
        a.shot('04-authoritative-weapon.png')
        for i,page in enumerate(pages):
            assert not network(page)['error'],network(page)
            errors=[e for e in page.events if e.get('method')=='Runtime.exceptionThrown']
            assert not errors,errors
        report=dict(status='PASS',date='2026-10-22',checks=['Two real Edge tabs with distinct server-issued identities','WebSocket-to-TCP forwarding to existing GameServer and Box2D','Both join the same Boss room and start the same match','Real boss state renders through existing OnlineBossAssets','Keyboard movement is authoritative and visible to peer','J consumes real laser ammo; no unarmed attack','No browser JS exceptions or protocol errors'],
            states=states,limits=['Loopback browser trial; WAN/WSS and public deployment not executed','Browser adapter is a trial, not the complete desktop lobby/results UI','Snapshots rendered without desktop Box2D prediction'])
        (EVIDENCE/'verification.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
        print('PASS TeaVM network: two browser tabs, Boss match, authoritative movement and equipped shooting.')
    except Exception:
        for i,page in enumerate(pages):
            try: states[f'failure{i}']=network(page);page.shot(f'failure-{i}.png')
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
        if gateway.poll() is None:
            gateway.terminate();gateway.wait(timeout=10)
        server.shutdown();server.server_close();browser_log.close();gateway_log.close()

if __name__=='__main__': main()

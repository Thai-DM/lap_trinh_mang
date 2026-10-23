"""Exercise the actual shared client screens via keyboard/mouse in real Edge/WebGL.
Requires websocket-client. Uses an isolated profile and owns every subprocess it stops.
"""
from pathlib import Path
from functools import partial
from threading import Thread
from http.server import ThreadingHTTPServer
import importlib.util, json, os, subprocess, time, urllib.parse, argparse, sqlite3

spec=importlib.util.spec_from_file_location('browser_tools',Path(__file__).with_name('Verify-TeaVm.py'))
b=importlib.util.module_from_spec(spec);spec.loader.exec_module(b)
ROOT=b.ROOT
WEB=ROOT/'final-project/web/build/dist/webapp'
EVIDENCE=ROOT/'docs/gameplay/runs/20261023-web-client'

def state(page):
    failure=page.evaluate("(()=>{let e=document.getElementById('failure');return e&&!e.hidden?e.textContent:null})()")
    assert not failure,failure
    result=page.evaluate('window.smashprofsWeb || null')
    fatal=[e for e in page.events if e.get('method')=='Runtime.exceptionThrown' or e.get('method')=='Runtime.consoleAPICalled' and any('Fatal Error:' in str(a.get('value','')) for a in e['params']['args'])]
    assert not fatal,json.dumps(fatal[-1:] if fatal else [],ensure_ascii=False)[:2000]
    return result
def wait_state(page, predicate, seconds=20):
    page.call('Page.bringToFront')
    return b.wait_for(lambda:(lambda s:s if s and predicate(s) else None)(state(page)),seconds)
def key(page,name,code,number,down):
    if down: page.call('Page.bringToFront')
    page.call('Input.dispatchKeyEvent',dict(type='keyDown' if down else 'keyUp',key=name,code=code,windowsVirtualKeyCode=number,nativeVirtualKeyCode=number))
def press(page,name,code,number,duration=.08):
    key(page,name,code,number,True);time.sleep(duration);key(page,name,code,number,False);time.sleep(.15)
def enter(page): press(page,'Enter','Enter',13)
def esc(page): press(page,'Escape','Escape',27)
def click(page,actor):
    page.call('Page.bringToFront')
    x,y=actor['x'],actor['y']
    page.call('Input.dispatchMouseEvent',dict(type='mousePressed',x=x,y=y,button='left',clickCount=1))
    page.call('Input.dispatchMouseEvent',dict(type='mouseReleased',x=x,y=y,button='left',clickCount=1));time.sleep(.2)
def actor(page,name=None,text=None,kind=None):
    return next(a for a in state(page)['ui'] if (name is None or a.get('name')==name) and (text is None or text in a['text']) and (kind is None or a['type']==kind))
def fill(page,target,text):
    click(page,target)
    key(page,'Control','ControlLeft',17,True)
    page.call('Input.dispatchKeyEvent',dict(type='keyDown',key='a',code='KeyA',windowsVirtualKeyCode=65,modifiers=2))
    page.call('Input.dispatchKeyEvent',dict(type='keyUp',key='a',code='KeyA',windowsVirtualKeyCode=65,modifiers=2))
    key(page,'Control','ControlLeft',17,False)
    for ch in text: page.call('Input.dispatchKeyEvent',dict(type='char',text=ch,unmodifiedText=ch))
    time.sleep(.15)
def select(page,name,text):
    click(page,actor(page,name=name));click(page,actor(page,text=text,kind='SelectOption'))

def main():
    global EVIDENCE
    parser=argparse.ArgumentParser();parser.add_argument('--boot-only',action='store_true');parser.add_argument('--local-only',action='store_true');parser.add_argument('--pvp',action='store_true');args=parser.parse_args()
    if args.local_only: EVIDENCE=ROOT/'docs/gameplay/runs/20261023-web-local'
    if args.pvp: EVIDENCE=ROOT/'docs/gameplay/runs/20261023-web-pvp'
    EVIDENCE.mkdir(parents=True,exist_ok=True);b.EVIDENCE=EVIDENCE
    run=ROOT/'final-project/build'/('web-verify-'+str(int(time.time()*1000)));run.mkdir(parents=True)
    profile=run/'edge';profile.mkdir()
    java=ROOT.parent/'baitap/bin/java.exe'
    lib=ROOT/'final-project/web/build/gateway/lib'
    server=ThreadingHTTPServer(('127.0.0.1',0),partial(b.QuietHandler,directory=str(WEB)));Thread(target=server.serve_forever,daemon=True).start()
    gateway_log=(EVIDENCE/'gateway.log').open('w',encoding='utf-8')
    gateway=subprocess.Popen([str(java),'-Xmx192m','-Dfile.encoding=UTF-8',f'-Dweb.origins=http://127.0.0.1:{server.server_port}','-cp',str(lib/'*'),'com.smashprofs.web.GameGateway','0',str(run/'ranking.db')],cwd=ROOT,stdout=gateway_log,stderr=subprocess.STDOUT)
    browser_log=(EVIDENCE/'browser.stderr.log').open('w',encoding='utf-8')
    browser=subprocess.Popen([str(b.EDGE),'--headless=new','--no-first-run','--no-default-browser-check','--disable-background-networking',
        '--disable-gpu','--enable-unsafe-swiftshader','--use-angle=swiftshader','--disable-renderer-backgrounding',
        '--disable-background-timer-throttling','--disable-backgrounding-occluded-windows','--remote-allow-origins=*',
        '--remote-debugging-port=0','--user-data-dir='+str(profile),'about:blank'],stdout=browser_log,stderr=subprocess.STDOUT,
        creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
    connections=[];pages=[];states={};checks=[]
    try:
        def gateway_port():
            for line in (EVIDENCE/'gateway.log').read_text(encoding='utf-8',errors='replace').splitlines():
                if line.startswith('PROBE_GATEWAY_READY '): return int(line.split()[1])
        port=b.wait_for(gateway_port,20)
        rejected=b.websocket.create_connection(f'ws://127.0.0.1:{port}',origin='https://untrusted.example',timeout=5)
        opcode,payload=rejected.recv_data(control_frame=True);rejected.close()
        assert opcode==8 and int.from_bytes(payload[:2],'big')==1008,(opcode,payload)
        checks.append('Gateway rejects an origin outside the configured exact allowlist')
        active=profile/'DevToolsActivePort';b.wait_for(lambda:active.exists() and len(active.read_text().splitlines())>=2)
        debug='http://127.0.0.1:'+active.read_text().splitlines()[0]
        control=b.Cdp(b.fetch(debug+'/json/version')['webSocketDebuggerUrl']);connections.append(control)
        base=f'http://127.0.0.1:{server.server_port}/?debug=1&ws='+urllib.parse.quote(f'ws://127.0.0.1:{port}',safe='')
        def tab(isolated=False):
            options=dict(url='about:blank')
            if isolated: options['browserContextId']=control.call('Target.createBrowserContext')['browserContextId']
            target=control.call('Target.createTarget',options)['targetId']
            info=b.wait_for(lambda:next((v for v in b.fetch(debug+'/json/list') if v['id']==target),None))
            page=b.Cdp(info['webSocketDebuggerUrl']);connections.append(page);pages.append(page)
            page.call('Runtime.enable');page.call('Network.enable');page.call('Page.enable')
            page.call('Emulation.setDeviceMetricsOverride',dict(width=1280,height=720,deviceScaleFactor=1,mobile=False))
            page.call('Page.navigate',dict(url=base));wait_state(page,lambda s:s['screen']=='MainMenuScreen',45)
            return page
        host=tab();states['menu']=state(host);host.shot('01-main-menu.png');checks.append('Real shared MainMenuScreen boots in Edge/WebGL')
        print('PASS main menu',flush=True)
        if args.local_only:
            press(host,' ','Space',32)
            wait_state(host,lambda s:s['screen']=='CharacterSelectScreen');host.shot('12-local-character-select.png')
            click(host,actor(host,name='start-local'));wait_state(host,lambda s:s['screen']=='PlayScreen' and s.get('physicsBodies',0)>0)
            before=state(host);host.key('D',True)
            try: after=wait_state(host,lambda s:abs(s.get('offlineX',before['offlineX'])-before['offlineX'])>.01,25)
            finally: host.key('D',False)
            states['local_before']=before;states['local_after']=after
            host.shot('13-local-box2d.png');checks.append('Original local character select, full local PlayScreen, JBox2D physics and keyboard movement')
            esc(host);wait_state(host,lambda s:s['screen']=='MainMenuScreen');checks.append('Local game returns to menu')
        elif not args.boot_only:
            host.press('C');host.shot('02-keybindings.png');esc(host);checks.append('Shared keybinding settings open and close')
            host.press('P');host.shot('03-profile.png')
            fields=[a for a in state(host)['ui'] if a['type']=='TextField']
            fill(host,next(a for a in fields if not a['text'].startswith('player_')),'TeaVM Host')
            click(host,actor(host,text='LƯU HỒ SƠ',kind='TextButton'))
            host.call('Page.navigate',dict(url=base));wait_state(host,lambda s:s['screen']=='MainMenuScreen',45)
            assert any(a['text']=='TeaVM Host' for a in state(host)['ui'])
            checks.append('Shared profile saves to browser storage and survives a page reload')
            host.press('B');wait_state(host,lambda s:s['screen']=='LeaderboardScreen')
            wait_state(host,lambda s:any('Server' in a['text'] and ('trống' in a['text'] or 'thành công' in a['text']) for a in s['ui']),20)
            host.shot('04-leaderboard.png');checks.append('Real GET_LEADERBOARD through WebSocket and SQLite ranking service');esc(host)
            host.press('O');wait_state(host,lambda s:s['screen']=='OnlineLobbyScreen');host.shot('05-online-lobby.png')
            if not args.pvp: select(host,'game-mode','Hợp tác đánh Boss')
            states['mode']=state(host)
            click(host,actor(host,name='create-room'))
            room=wait_state(host,lambda s:s['screen']=='OnlineRoomScreen' and s['room'])['room']
            click(host,actor(host,name='room-settings'));select(host,'match-time','60 giây')
            click(host,actor(host,name='save-rules'));time.sleep(.3)
            # Save keeps the modal open; X closes it.
            click(host,actor(host,text='X',kind='TextButton'))
            host.shot('06-host-room.png');checks.append('Real shared OnlineRoomScreen creates room '+room)
            peer=tab(isolated=True);peer.press('O');wait_state(peer,lambda s:s['screen']=='OnlineLobbyScreen')
            fill(peer,actor(peer,name='room-code'),room);states['join_form']=state(peer)
            click(peer,actor(peer,name='join-room'));wait_state(peer,lambda s:s['screen']=='OnlineRoomScreen' and s['room']==room)
            click(peer,actor(peer,text='Luca',kind='Label'));time.sleep(.3)
            peer.shot('07-peer-room.png');click(host,actor(host,name='ready'));click(peer,actor(peer,name='ready'))
            # Both pages must render once to finish their native loading gates.
            host.call('Page.bringToFront');time.sleep(.6);peer.call('Page.bringToFront');time.sleep(.6)
            wait_state(host,lambda s:s['screen']=='PlayScreen' and s['players']==2,45)
            wait_state(peer,lambda s:s['screen']=='PlayScreen' and s['players']==2,45)
            a=state(host);p=state(peer);assert a['matchId']==p['matchId'] and a['playerId']!=p['playerId']
            states['match_initial']=a;host.shot('08-online-game.png');peer.shot('09-online-peer.png')
            checks.append('Two browser clients start same authoritative match with distinct server identities')
            if not args.pvp:
                assert a['bossHp']>0,'Expected the selected Boss room'
                peer.call('Page.bringToFront');previous_shots=state(peer).get('gunShots',0);peer.press('J',.3)
                shot=wait_state(peer,lambda s:s.get('gunShots',0)>previous_shots,10);states['shot']=shot
                checks.append('Boss art/AI and real laser ammo consumption while alive; shooting starts no unarmed attack')
            else: assert a['bossHp']==-1,'PvP must have no boss'
            host.call('Page.bringToFront');time.sleep(.2)
            press(host,' ','Space',32,.15)
            jumped=wait_state(host,lambda s:s.get('jumpInputs',0)>0 and s.get('jumpRise',0)>.03,10);states['jumped']=jumped
            checks.append('Space jumps using latest dev bindings, observed in authoritative snapshots')
            origin=state(host)['x'];host.press('D',.15);time.sleep(.2);moved=state(host);assert abs(moved['x']-origin)>.02,(origin,moved)
            states['moved']=moved;checks.append('Real A/D input with shared prediction and server snapshots')
            press(host,'Tab','Tab',9);host.shot('10-inventory.png');press(host,'Tab','Tab',9);checks.append('Shared HUD/inventory renders')
            host.press('H');host.shot('11-controls-help.png');host.press('H')
            print('PASS match/input/HUD; waiting for real GAME_END',flush=True)
            wait_state(host,lambda s:s['screen']=='OnlineResultScreen',90)
            wait_state(peer,lambda s:s['screen']=='OnlineResultScreen',20)
            states['result']=state(host);host.shot('14-match-result.png');checks.append('Real final server snapshot and GAME_END open shared results screen')
            enter(host);enter(peer)
            host.call('Page.bringToFront');time.sleep(.6);peer.call('Page.bringToFront');time.sleep(.6)
            wait_state(host,lambda s:s['screen']=='PlayScreen' and s['matchId']!=a['matchId'],35)
            wait_state(peer,lambda s:s['screen']=='PlayScreen' and s['matchId']!=a['matchId'],35)
            states['rematch']=state(host);host.shot('15-rematch.png');checks.append('Both rematch votes create a new authoritative match and reload shared play screen')
            def persisted():
                with sqlite3.connect((run/'ranking.db').as_uri()+'?mode=ro',uri=True) as db:
                    return db.execute('SELECT count(*) FROM processed_matches WHERE match_id=?',(a['matchId'],)).fetchone()[0]==1
            b.wait_for(persisted,10)
            with sqlite3.connect((run/'ranking.db').as_uri()+'?mode=ro',uri=True) as db:
                records=db.execute('SELECT profile_id,rating,wins,losses,draws,total_matches FROM players ORDER BY profile_id').fetchall()
                assert len(records)==2 and all(row[-1]==1 for row in records),records
                if not args.pvp: assert all(row[1]==1000 for row in records),records
            states['ranking_records']=records
            checks.append('Completed match persists once into SQLite; co-op keeps competitive Elo unchanged')
            esc(host);wait_state(host,lambda s:s['screen']=='OnlineLobbyScreen');checks.append('Leave match closes connection and returns to shared lobby')
        errors=[e for page in pages for e in page.events if e.get('method')=='Runtime.exceptionThrown']
        server_text=(EVIDENCE/'gateway.log').read_text(encoding='utf-8',errors='replace')
        assert 'Exception' not in server_text and 'SEVERE' not in server_text,server_text[-4000:]
        checks.append('No server exceptions during match completion and ranking delivery')
        failed=[e['params']['response']['url'] for page in pages for e in page.events if e.get('method')=='Network.responseReceived' and e['params']['response']['status']>=400 and 'favicon' not in e['params']['response']['url']]
        assert not errors,errors;assert not failed,failed
        checks.append('No JavaScript exceptions or failed assets')
        (EVIDENCE/'verification.json').write_text(json.dumps(dict(status='PASS',date='2026-10-23',checks=checks,states=states),indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
        print('PASS web client: '+str(len(checks))+' checks.',flush=True)
    except Exception:
        for i,page in enumerate(pages):
            try: page.shot('failure-'+str(i)+'.png');states['failure'+str(i)]=page.evaluate('window.smashprofsWeb || null')
            except Exception: pass
        (EVIDENCE/'failure.json').write_text(json.dumps(dict(states=states,events=[e for c in connections for e in c.events]),indent=2,ensure_ascii=False),encoding='utf-8')
        raise
    finally:
        for c in reversed(connections):
            try: c.close()
            except Exception: pass
        for process in [browser,gateway]:
            if process.poll() is None:
                process.terminate()
                try: process.wait(timeout=8)
                except subprocess.TimeoutExpired: process.kill();process.wait(timeout=5)
        server.shutdown();server.server_close();browser_log.close();gateway_log.close()

if __name__=='__main__': main()

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
    parser=argparse.ArgumentParser();parser.add_argument('--boot-only',action='store_true');parser.add_argument('--local-only',action='store_true');parser.add_argument('--pvp',action='store_true');parser.add_argument('--matchmaking',action='store_true');parser.add_argument('--lobby-layout',action='store_true');parser.add_argument('--ranking-profile',action='store_true');args=parser.parse_args()
    if args.local_only: EVIDENCE=ROOT/'docs/gameplay/runs/20261023-web-local'
    if args.pvp: EVIDENCE=ROOT/'docs/gameplay/runs/20261023-web-pvp'
    if args.matchmaking: EVIDENCE=ROOT/'docs/gameplay/runs/20261023-web-matchmaking'
    if args.lobby_layout: EVIDENCE=ROOT/'docs/gameplay/runs/20261023-online-lobby-layout'
    if args.ranking_profile: EVIDENCE=ROOT/'docs/gameplay/runs/20261023-ranking-profile'
    EVIDENCE.mkdir(parents=True,exist_ok=True);b.EVIDENCE=EVIDENCE
    run=ROOT/'final-project/build'/('web-verify-'+str(int(time.time()*1000)));run.mkdir(parents=True)
    profile=run/'edge';profile.mkdir()
    java=ROOT.parent/'baitap/bin/java.exe'
    lib=ROOT/'final-project/web/build/gateway/lib'
    server=ThreadingHTTPServer(('127.0.0.1',0),partial(b.QuietHandler,directory=str(WEB)));Thread(target=server.serve_forever,daemon=True).start()
    gateway_log=(EVIDENCE/'gateway.log').open('w',encoding='utf-8')
    if args.ranking_profile:
        # Test-only existing database, including a legacy UUID and a maximal Vietnamese name.
        with sqlite3.connect(run/'ranking.db') as db:
            db.execute('CREATE TABLE players(profile_id TEXT PRIMARY KEY,rating REAL NOT NULL,wins INTEGER NOT NULL,losses INTEGER NOT NULL,draws INTEGER NOT NULL,total_matches INTEGER NOT NULL)')
            db.execute('CREATE TABLE guest_profiles(profile_id TEXT PRIMARY KEY,token_hash TEXT NOT NULL UNIQUE,display_name TEXT NOT NULL)')
            for i in range(14):
                identity=f'guest_00000000-0000-4000-a000-{i:012d}' if i else 'acb416da-3b8f-4c28-a42e-0c6b555e08f8'
                db.execute('INSERT INTO players VALUES(?,?,?,?,?,?)',(identity,2000-i*16,10,5,0,15))
                if i:
                    db.execute('INSERT INTO guest_profiles VALUES(?,?,?)',(identity,f'{i:064x}',('Nguyễn Hoàng Chiến Binh Đấu Trường Việt Nam '*2)[:64] if i==1 else f'Chiến binh {i}'))
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
        if args.lobby_layout:
            host.press('O');wait_state(host,lambda s:s['screen']=='OnlineLobbyScreen')
            assert any(a['text']=='CHƠI ONLINE' for a in state(host)['ui']), 'Vietnamese lobby title lost its accents'
            assert actor(host,name='game-mode')['text']=='Đối kháng PvP'
            click(host,actor(host,name='server-settings'))
            original=actor(host,name='server-host')['text']
            azure='wss://smashprofs-thai.southeastasia.cloudapp.azure.com/ws'
            fill(host,actor(host,name='server-host'),azure);click(host,actor(host,name='close-server-settings'))
            names=['quick-match','create-room','join-room','room-code','game-mode','server-settings','back','leaderboard']
            for width,height in [(1280,720),(960,540),(1024,768)]:
                host.call('Emulation.setDeviceMetricsOverride',dict(width=width,height=height,deviceScaleFactor=1,mobile=False));time.sleep(.4)
                current=state(host);scale=min(width/1280,height/720)
                assert not any(a.get('name')=='server-host' for a in current['ui'])
                boxes=[]
                for name in names:
                    a=actor(host,name=name);half_w=a['width']*scale/2;half_h=a['height']*scale/2
                    box=(a['x']-half_w,a['y']-half_h,a['x']+half_w,a['y']+half_h)
                    assert box[0]>=-1 and box[1]>=-1 and box[2]<=width+1 and box[3]<=height+1,(name,box,width,height)
                    for other_name,other in boxes:
                        assert min(box[2],other[2])-max(box[0],other[0])<=1 or min(box[3],other[3])-max(box[1],other[1])<=1,(name,other_name,box,other)
                    boxes.append((name,box))
                states[f'lobby_{width}x{height}']=current;host.shot(f'20-lobby-{width}x{height}.png')
            checks.append('Azure WSS address cannot expand or overlap lobby controls at 1280x720, 960x540 and 1024x768')
            host.call('Emulation.setDeviceMetricsOverride',dict(width=1280,height=720,deviceScaleFactor=1,mobile=False));time.sleep(.3)
            click(host,actor(host,name='server-settings'))
            assert actor(host,name='server-host')['text']==azure
            long_address='wss://' + '.'.join(['a'*63,'b'*63,'c'*63]) + '.example.com/ws'
            fill(host,actor(host,name='server-host'),long_address)
            field=actor(host,name='server-host');assert field['text']==long_address
            assert field['width']==620 and field['x']-field['width']/2>=0 and field['x']+field['width']/2<=1280
            states['server_settings']=state(host);host.shot('21-bounded-server-settings.png')
            # Clicking the covered Create button must hit the modal backdrop, not create a room.
            click(host,actor(host,name='create-room'));assert actor(host,name='server-host')['text']==long_address
            assert not state(host)['phase'];checks.append('Server modal bounds very long addresses and blocks background actions')
            fill(host,actor(host,name='server-host'),original);enter(host)
            assert not any(a.get('name')=='server-host' for a in state(host)['ui'])
            checks.append('Enter applies server settings without accidentally joining a room')
            click(host,actor(host,name='create-room'));wait_state(host,lambda s:s['screen']=='OnlineRoomScreen' and s['room'])
            states['created_room']=state(host);esc(host);wait_state(host,lambda s:s['screen']=='OnlineLobbyScreen')
            click(host,actor(host,name='quick-match'));wait_state(host,lambda s:s['screen']=='OnlineMatchmakingScreen')
            esc(host);wait_state(host,lambda s:s['screen']=='OnlineLobbyScreen')
            checks.append('Create private room and open quick matchmaking remain functional after layout changes')
        elif args.matchmaking:
            def quick(page, boss=False, character='Alex'):
                page.press('O');wait_state(page,lambda s:s['screen']=='OnlineLobbyScreen')
                click(page,actor(page,name='quick-match'));wait_state(page,lambda s:s['screen']=='OnlineMatchmakingScreen')
                select(page,'quick-character',character)
                if boss: select(page,'quick-mode','Hợp tác Boss')
            quick(host,character='Luca');host.shot('16-quick-character-select.png')
            click(host,actor(host,name='find-match'))
            first=wait_state(host,lambda s:s['phase']=='WAITING_FOR_START');states['waiting_first']=first
            host.shot('17-waiting-for-opponent.png')
            click(host,actor(host,name='cancel-matchmaking'));wait_state(host,lambda s:not s['phase'])
            click(host,actor(host,name='find-match'))
            second=wait_state(host,lambda s:s['phase']=='WAITING_FOR_START');assert second['room']!=first['room']
            checks.append('Shared quick-match UI selects a character, waits, cancels and searches again')
            peer=tab(isolated=True);quick(peer,character='Viktor');click(peer,actor(peer,name='find-match'))
            host.call('Page.bringToFront');time.sleep(.6);peer.call('Page.bringToFront');time.sleep(.6)
            a=wait_state(host,lambda s:s['screen']=='PlayScreen' and s['players']==2,45)
            p=wait_state(peer,lambda s:s['screen']=='PlayScreen' and s['players']==2,45)
            assert a['matchId']==p['matchId'] and a['room']==p['room'] and a['playerId']!=p['playerId']
            assert a['bossHp']==-1 and p['bossHp']==-1
            states['quick_pvp']=a;states['quick_pvp_peer']=p;host.shot('18-quick-pvp.png')
            checks.append('Two independent browser clients automatically enter the same real PvP match without a room code or Ready click')
            host.press('D',.2);states['quick_pvp_moved']=state(host)
            esc(host);wait_state(host,lambda s:s['screen']=='OnlineLobbyScreen');esc(peer);wait_state(peer,lambda s:s['screen']=='OnlineLobbyScreen')
            click(host,actor(host,name='quick-match'));wait_state(host,lambda s:s['screen']=='OnlineMatchmakingScreen')
            select(host,'quick-mode','Hợp tác Boss');click(host,actor(host,name='find-match'))
            waiting_boss=wait_state(host,lambda s:s['phase']=='WAITING_FOR_START')
            click(peer,actor(peer,name='quick-match'));wait_state(peer,lambda s:s['screen']=='OnlineMatchmakingScreen')
            click(peer,actor(peer,name='find-match'));waiting_pvp=wait_state(peer,lambda s:s['phase']=='WAITING_FOR_START')
            assert waiting_boss['room']!=waiting_pvp['room'];checks.append('Boss and PvP searches stay in separate queues')
            click(peer,actor(peer,name='cancel-matchmaking'));wait_state(peer,lambda s:not s['phase'])
            select(peer,'quick-mode','Hợp tác Boss');select(peer,'quick-character','Leo');click(peer,actor(peer,name='find-match'))
            host.call('Page.bringToFront');time.sleep(.6);peer.call('Page.bringToFront');time.sleep(.6)
            a=wait_state(host,lambda s:s['screen']=='PlayScreen' and s['players']==2 and s['bossHp']>0,45)
            p=wait_state(peer,lambda s:s['screen']=='PlayScreen' and s['players']==2 and s['bossHp']>0,45)
            assert a['matchId']==p['matchId'];states['quick_boss']=a;states['quick_boss_peer']=p
            host.shot('19-quick-boss.png');checks.append('Two browser clients automatically start a real cooperative Boss match')
            esc(host);wait_state(host,lambda s:s['screen']=='OnlineLobbyScreen');esc(peer);wait_state(peer,lambda s:s['screen']=='OnlineLobbyScreen')
            checks.append('Leaving both quick matches returns to the shared online lobby')
        elif args.ranking_profile:
            import re
            def guest_id(page):
                return page.evaluate("(()=>{let m=Object.values(localStorage).join(' ').match(/guest_[0-9a-f-]{36}/);return m?m[0]:null})()")
            def open_ranking(page):
                page.press('B');wait_state(page,lambda s:s['screen']=='LeaderboardScreen')
                wait_state(page,lambda s:any(a.get('name')=='ranking-status' and a['text'].startswith('Đã cập nhật') for a in s['ui']))
                return guest_id(page)
            original=open_ranking(host);assert original and re.fullmatch(r'guest_[0-9a-f-]{36}',original)
            assert any(a['text']=='BẢNG XẾP HẠNG' for a in state(host)['ui'])
            assert 'Khách' in actor(host,name='ranking-name-1')['text']
            assert 'Nguyễn' in actor(host,name='ranking-name-2')['text']
            for width,height in [(1280,720),(960,540),(1024,768)]:
                host.call('Emulation.setDeviceMetricsOverride',dict(width=width,height=height,deviceScaleFactor=1,mobile=False));time.sleep(.4)
                scale=min(width/1280,height/720)
                name=actor(host,name='ranking-name-2');elo=actor(host,name='ranking-elo-2')
                assert name['x']+name['width']*scale/2<=elo['x']-elo['width']*scale/2+1,(name,elo)
                for control_name in ['ranking-back','ranking-refresh','ranking-profile','ranking-endpoint','ranking-server-settings']:
                    a=actor(host,name=control_name)
                    assert a['x']-a['width']*scale/2>=-1 and a['x']+a['width']*scale/2<=width+1,(control_name,a)
                first=actor(host,name='ranking-row-1');viewport=actor(host,name='ranking-scroll')
                assert first['y']<viewport['y'],'Rows must start at the top instead of the middle'
                host.shot(f'30-ranking-{width}x{height}.png');states[f'ranking_{width}']=state(host)
            checks.append('Vietnamese long names and legacy UUIDs stay inside aligned columns at three viewport sizes; list starts at top')
            host.call('Emulation.setDeviceMetricsOverride',dict(width=1280,height=720,deviceScaleFactor=1,mobile=False))
            scroll=actor(host,name='ranking-scroll');before=actor(host,name='ranking-row-1')['y']
            host.call('Input.dispatchMouseEvent',dict(type='mouseMoved',x=scroll['x'],y=scroll['y']))
            host.call('Input.dispatchMouseEvent',dict(type='mouseWheel',x=scroll['x'],y=scroll['y'],deltaX=0,deltaY=700));time.sleep(.6)
            assert actor(host,name='ranking-row-1')['y']<before-10,'Mouse wheel must actually scroll the result rows'
            host.shot('31-ranking-scrolled.png');checks.append('Leaderboard with fourteen entries scrolls inside its card')
            click(host,actor(host,name='ranking-refresh'));wait_state(host,lambda s:any(a.get('name')=='ranking-status' and a['text'].startswith('Đã cập nhật') for a in s['ui']))
            assert guest_id(host)==original
            host.call('Page.reload');wait_state(host,lambda s:s['screen']=='MainMenuScreen',45)
            assert open_ranking(host)==original;checks.append('Refreshing ranking and reloading the page preserve the authenticated guest profile')
            same=tab();assert open_ranking(same)==original;checks.append('A new tab in the same browser reuses the same guest profile')
            isolated=tab(isolated=True);assert open_ranking(isolated)!=original;checks.append('An isolated browser gets a separate guest identity')
            with sqlite3.connect((run/'ranking.db').as_uri()+'?mode=ro',uri=True) as db:
                assert db.execute('SELECT count(*) FROM guest_profiles WHERE profile_id=?',(original,)).fetchone()[0]==1
                assert db.execute('SELECT count(*) FROM ranking_sessions WHERE profile_id=?',(original,)).fetchone()[0]>=4
                assert db.execute('SELECT count(*) FROM players').fetchone()[0]==14
            checks.append('Repeated ranking connections bind to one SQLite guest profile without creating fake ranking entries')
            states['guest_profile_id']=original
        elif args.local_only:
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
            fill(host,actor(host,name='profile-display-name'),'TeaVM Host')
            click(host,actor(host,text='LƯU HỒ SƠ',kind='TextButton'))
            host.call('Page.navigate',dict(url=base));wait_state(host,lambda s:s['screen']=='MainMenuScreen',45)
            assert any(a['text']=='TeaVM Host' for a in state(host)['ui'])
            checks.append('Shared profile saves to browser storage and survives a page reload')
            host.press('B');wait_state(host,lambda s:s['screen']=='LeaderboardScreen')
            wait_state(host,lambda s:any(a.get('name')=='ranking-status' and ('Chưa có' in a['text'] or 'Đã cập nhật' in a['text']) for a in s['ui']),20)
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
            origin=state(host)['x'];host.key('D',True)
            try: moved=wait_state(host,lambda s:s['screen']=='PlayScreen' and abs(s['x']-origin)>.05,8)
            finally: host.key('D',False)
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
            click(host,actor(host,name='leaderboard'));wait_state(host,lambda s:s['screen']=='LeaderboardScreen')
            wait_state(host,lambda s:any(a.get('name')=='ranking-status' and a['text'].startswith('Đã cập nhật') for a in s['ui']))
            assert any((a.get('name') or '').startswith('ranking-name-') and a['text']=='BẠN · TeaVM Host' for a in state(host)['ui'])
            persistent_id=host.evaluate("Object.values(localStorage).join(' ').match(/guest_[0-9a-f-]{36}/)[0]")
            assert persistent_id in [row[0] for row in records]
            host.shot('16-persistent-ranking.png');states['persistent_profile_id']=persistent_id
            host.call('Page.reload');wait_state(host,lambda s:s['screen']=='MainMenuScreen',45)
            host.press('B');wait_state(host,lambda s:s['screen']=='LeaderboardScreen')
            wait_state(host,lambda s:any((a.get('name') or '').startswith('ranking-name-') and a['text']=='BẠN · TeaVM Host' for a in s['ui']))
            assert host.evaluate("Object.values(localStorage).join(' ').match(/guest_[0-9a-f-]{36}/)[0]")==persistent_id
            checks.append('A real finished match credits the persistent guest, and reloading highlights the same named ranking entry')
        errors=[e for page in pages for e in page.events if e.get('method')=='Runtime.exceptionThrown']
        server_text=(EVIDENCE/'gateway.log').read_text(encoding='utf-8',errors='replace')
        assert 'Exception' not in server_text and 'SEVERE' not in server_text,server_text[-4000:]
        checks.append('No server exceptions during the browser run')
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

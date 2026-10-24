# Super Smash Profs — TeaVM client

Full shared Java client, compiled to JavaScript/WebGL. Unlike `teavm-probe`, this starts the real menu and uses the existing room/loading/play/HUD/results screens, local game and client-side prediction.

From the repository root, on Windows with JDK 17 and Python 3:

```powershell
.\scripts\web\Run-Web.ps1
```

Then open the printed URL. Choose Online, create a PvP or Boss room, and enter its code from another browser. Local mode uses the original two-player game on one keyboard.

After a successful build, use `-SkipBuild`. Use `-BuildOnly` for static output, or `-ClientOnly` to run the client without starting a local server. Stop the runner with Ctrl+C. Adjust `-JavaHome`, `-Port`, and `-GatewayPort` as needed.

The server remains the existing authoritative Java server. Browser connections use WebSocket through `GameGateway`; ranking uses the original SQLite store. No server code runs in a browser tab.

Output: `build/dist/webapp/`; server runtime: `build/gateway/lib/`. Hosting configuration and implementation notes: [full guide](../../docs/gameplay/18-TEAVM-WEB-CLIENT.md).

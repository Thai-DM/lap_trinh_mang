# SmashProfs TeaVM compatibility trial

Isolated build: gdx-teavm 1.6.2 / TeaVM 0.15.0 / libGDX 1.14.2 / Java 17.
The desktop project remains on its existing dependencies. Selected production sources
and original assets are referenced directly by this build.

From the repository root:

```powershell
.\scripts\web\Run-TeaVm.ps1 -Multiplayer -Boss
```

Open the CREATE and JOIN links in two tabs. Both auto-ready and start a real Boss
match on the existing JVM GameServer, connected through a local WebSocket-to-TCP
gateway. Add `-SkipBuild` on later launches. Ctrl+C stops owned helpers.

Without `-Multiplayer`, this is a local rendering/input fixture rather than an online match.

See [the full trial report](../../docs/gameplay/17-TEAVM-PROBE.md) for evidence,
controls, build commands, limits, and remaining migration work. This is a local
trial; public WSS hosting and automated deployment are not configured.

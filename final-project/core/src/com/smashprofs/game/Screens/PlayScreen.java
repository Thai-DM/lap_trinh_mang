package com.smashprofs.game.Screens;


import com.badlogic.gdx.controllers.Controller;
import com.badlogic.gdx.controllers.Controllers;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.maps.tiled.renderers.OrthogonalTiledMapRenderer;
import com.badlogic.gdx.math.Matrix4;
import com.crashinvaders.vfx.VfxManager;
import com.smashprofs.game.Actors.Players.Player;
import com.smashprofs.game.Game;
import com.smashprofs.game.Helper.*;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.maps.MapObject;
import com.badlogic.gdx.maps.objects.RectangleMapObject;
import com.badlogic.gdx.maps.tiled.TiledMap;
import com.badlogic.gdx.maps.tiled.TmxMapLoader;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.*;
import com.badlogic.gdx.utils.viewport.FillViewport;
import com.badlogic.gdx.utils.viewport.Viewport;
import com.smashprofs.game.Scenes.Hud;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


import static com.smashprofs.game.Actors.Players.Player.PPM;


import com.smashprofs.common.model.GameSnapshot;
import com.smashprofs.common.model.MatchResult;
import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.model.PlayerInput;
import com.smashprofs.common.model.PlayerState;
import com.smashprofs.common.protocol.MessageType;
import com.smashprofs.common.protocol.NetworkPacket;
import com.smashprofs.game.network.client.ConnectionListener;
import com.smashprofs.game.network.client.NetworkClient;
import com.smashprofs.game.network.client.NetworkMetricsTracker;
import com.smashprofs.game.network.client.OutboundInputQueue;
import com.smashprofs.game.network.client.SnapshotBuffer;
import com.smashprofs.game.network.client.StateInterpolator;
import java.util.Map;

/**
 * The main game screen
 */
public class PlayScreen implements Screen {

    private static Logger log = LogManager.getLogger(PlayScreen.class);

    private Game game;
    private Screen onlineScreen;
    public static Viewport viewport; // Manages a Camera and determines how world coordinates are mapped to and from the screen.
    private Hud hud;
    private WinScreen winScreen;

    // Network Integration (Phase 6 & 7)
    private NetworkClient networkClient;
    private SnapshotBuffer snapshotBuffer;
    private StateInterpolator stateInterpolator;
    private OutboundInputQueue outboundInputQueue;
    private NetworkMetricsTracker networkMetricsTracker;
    private float pingTimer = 0.0f;
    private MatchResult latestMatchResult;
    private boolean networkActive = false;
    private long clientInputTick = 0;
    // Only the legacy MockServer fixture uses these IDs; production identity/roster lives in LobbyClient.
    private PlayerId p1Id;
    private PlayerId p2Id;


    //tiled map
    public static ShapeRenderer debugRenderer;
    private TmxMapLoader mapLoader;
    private TiledMap map;
    private OrthogonalTiledMapRenderer tiledMapRenderer;
    public Player playerOne;
    public Player playerTwo;


    //managers
    private final B2dContactListener contactListener;
    private SoundManager soundManager;

    private String gameSong = "music/beste music ever.wav";
    private CombatManager combatManager;

    private CameraManager cameraManager;

    private VAFXManager vafxManager;

    private OrthographicCamera gamecamera; //set by camera manager

    //batch and game world
    public static SpriteBatch batch;

    //Box2D
    public static World world;

    //collision flags


    //debug
    public Boolean debugMode;
    private Box2DDebugRenderer box2DDebugRenderer; //renders outline of box2d bodies


    //factories
    private PlayerFactory playerFactory = null;

    private VfxManager postProcessingManager;

    /**
     * Checks if player presses the "ESC" and if so, opens the main menu.
     * @param deltatime
     * The game deltatime
     */
    public void checkInput(float deltatime){
        if (onlineScreen != null) { return; }

        //Player input is now handeled in the PlayerClass
        //Only external input is handled here

        //exit game
        if(Gdx.input.isKeyPressed(Input.Keys.ESCAPE)){
           // Gdx.app.exit();
            game.setScreen(new MainMenuScreen(game));
        }

        // Phím tắt BACKSPACE: Giả lập ngắt kết nối server đột ngột để kiểm thử Graceful Disconnect (Giai đoạn 7)
        if (Gdx.input.isKeyJustPressed(Input.Keys.BACKSPACE)) {
            if (game.getMockServer() != null && game.getMockServer().isRunning()) {
                log.warn("[TEST] Người dùng ấn BACKSPACE: Dừng MockServer đột ngột để kiểm tra xử lý ngắt kết nối an toàn...");
                game.getMockServer().stop();
            } else if (networkClient != null && networkClient.isConnected()) {
                log.warn("[TEST] Người dùng ấn BACKSPACE: Ngắt kết nối NetworkClient đột ngột...");
                networkClient.disconnect();
            }
        }
    }


    public static SpriteBatch getBatch() {
        return batch;
    }

    /**
     * Updates the PlayScreen and everything which is part of it.
     * @param deltatime
     * The game delta time
     */
    public void update(float deltatime) {
        if (onlineScreen != null) { return; } // Online updates only through its presentation render path.
        tiledMapRenderer.setView(gamecamera);

        if (networkActive && networkClient != null && networkClient.isConnected()) {
            // 1. Capture local Player 1 keyboard input and enqueue into OutboundInputQueue
            var cfg = com.smashprofs.game.Helper.KeyBindingConfig.getInstance();
            float moveAxis = 0.0f;
            if (Gdx.input.isKeyPressed(cfg.getKey(com.smashprofs.game.Helper.KeyBindingConfig.Action.LEFT))) moveAxis -= 1.0f;
            if (Gdx.input.isKeyPressed(cfg.getKey(com.smashprofs.game.Helper.KeyBindingConfig.Action.RIGHT))) moveAxis += 1.0f;
            boolean jump = Gdx.input.isKeyJustPressed(cfg.getKey(com.smashprofs.game.Helper.KeyBindingConfig.Action.JUMP));
            boolean stomp = Gdx.input.isKeyJustPressed(cfg.getKey(com.smashprofs.game.Helper.KeyBindingConfig.Action.DOWN));
            boolean melee = Gdx.input.isKeyJustPressed(cfg.getKey(com.smashprofs.game.Helper.KeyBindingConfig.Action.LIGHT)) || Gdx.input.isKeyJustPressed(Input.Keys.V);
            boolean ability = Gdx.input.isKeyJustPressed(cfg.getKey(com.smashprofs.game.Helper.KeyBindingConfig.Action.ABILITY));
            boolean block = Gdx.input.isKeyPressed(cfg.getKey(com.smashprofs.game.Helper.KeyBindingConfig.Action.DODGE)) || Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT);

            outboundInputQueue.recordFrameInput(moveAxis, jump, stomp, melee, block, ability);
            PlayerInput input = outboundInputQueue.pollNextInputToSend(clientInputTick++);
            if (input != null) {
                networkClient.sendPacket(NetworkPacket.of(MessageType.PLAYER_INPUT, p1Id.value(), input));
            }

            // 2. Periodic Ping (Phase 7 - RTT Measurement)
            pingTimer += deltatime;
            if (pingTimer >= 1.0f) {
                pingTimer = 0.0f;
                networkMetricsTracker.sendPing(networkClient, p1Id.value());
            }

            // 2. Advance StateInterpolator and apply interpolated state to Remote Player 2
            stateInterpolator.update(deltatime);
            Map<PlayerId, PlayerState> interpolated = stateInterpolator.getInterpolatedPlayers();
            if (interpolated != null) {
                PlayerState p2State = interpolated.get(p2Id);
                if (p2State != null) {
                    playerTwo.getB2dbody().setTransform(p2State.x(), p2State.y(), 0);
                    playerTwo.getB2dbody().setLinearVelocity(p2State.velocityX(), p2State.velocityY());
                    playerTwo.setFacingRight(p2State.facingRight());
                }
            }
        }

        playerOne.update(deltatime);
        playerTwo.update(deltatime); // update method must be before most of the code below otherwise some values are null;
        checkInput(deltatime);
        //combatManager
        combatManager.update(deltatime, playerOne, playerTwo, world);
        vafxManager.update(deltatime);



        //updates the physics 60 times per second
        world.step(1/60f, 6, 2); //higher iterations make physics more accurate but also way slower

        viewport.setScreenPosition(0, 0);

        // Draw a debug line between p1 and p2
        if(debugMode) {
            DrawDebugLine(playerOne.getPosition(), playerTwo.getPosition(), gamecamera.combined);
        }

        cameraManager.updateCameraManager(playerOne, playerTwo);
    }

    public static void DrawDebugLine(Vector2 start, Vector2 end, Matrix4 projectionMatrix)
    {
        Gdx.gl.glLineWidth(2);
        debugRenderer.setProjectionMatrix(projectionMatrix);
        debugRenderer.begin(ShapeRenderer.ShapeType.Line);
        debugRenderer.setColor(Color.WHITE);
        debugRenderer.line(start, end);
        debugRenderer.end();
        Gdx.gl.glLineWidth(1);
    }

    public static Viewport getViewport() {
        return viewport;
    }

    /**
     * World getter-method.
     * @return
     * Returns the game world.
     */
    public static World getWorld(){
        return world;
    }

    /**
     * Constructor of PlayScreen. Sets up postprocessing, creates the player factory, sets viewport,
     * creates Players, initializes connected controllers, resets combat manager and contact listener.
     * @param game
     * The game.
     */
    public PlayScreen(Game game) {
        this(game, session -> new OnlinePlayScreen(game, session));
    }

    /** Screen factory seam for headless verification of the online/local simulation boundary. */
    PlayScreen(Game game, java.util.function.Function<com.smashprofs.game.network.client.ClientMatchSession, Screen> onlineFactory) {
        this.game = game;
        if (game.getLobbyClient() != null) {
            var session = game.getLobbyClient().getMatchSession();
            if (session == null) { throw new IllegalStateException("Online PlayScreen requires validated GAME_START"); }
            contactListener = null;
            onlineScreen = java.util.Objects.requireNonNull(onlineFactory.apply(session));
            return; // No PlayerFactory, local World, CombatManager, Hud.testWin or offline WinScreen.
        }
        soundManager = SoundManager.getSoundManager_INSTANCE();
        cameraManager = CameraManager.getCameraManager_INSTANCE();
        vafxManager = VAFXManager.getVFXManager_INSTANCE();
        if (debugRenderer == null) { debugRenderer = new ShapeRenderer(); }
        this.debugMode = Game.debugMode;

        //add pre configured settings to PostProcessingManager
        PostProcessingSettings ppSetUpHandler = new PostProcessingSettings();
        this.postProcessingManager = ppSetUpHandler.getPostProcessingManager();

        playerFactory = PlayerFactory.getPlayerFactory_INSTANCE();

        soundManager.setupMusic(gameSong);
        this.combatManager = CombatManager.getCombatManager_INSTANCE();
        this.game = game;
        gamecamera = cameraManager.getGameCamera();
        viewport = new FillViewport(Game.V_WIDTH / PPM, Game.V_HEIGHT / PPM, gamecamera);

        //StretchViewport is a Viewport that stretches the screen to fill the window.
        //Screen Viewport is a Viewport that show as much of the world as possible on the screen -> makes the the world you see depend on the size of the window.
        //FitViewport is a Viewport that maintains the aspect ratio of the world and fills the window. -> Probalby the best option.

        createTileMap();

        String p1Type = "Alex";
        String p2Type = "Maurice";
        try {
            if (CharacterSelectScreen.getSelectedPlayerOne() != null) {
                p1Type = CharacterSelectScreen.getSelectedPlayerOne();
            }
            if (CharacterSelectScreen.getSelectedPlayerTwo() != null) {
                p2Type = CharacterSelectScreen.getSelectedPlayerTwo();
            }
        } catch (Exception ignored) {}

        switch(p1Type) {
            case "Alex": playerOne = playerFactory.getPlayer(PlayerTypes.Alex); break;
            case "Maurice": playerOne = playerFactory.getPlayer(PlayerTypes.Maurice); break;
            case "Luca": playerOne = playerFactory.getPlayer(PlayerTypes.Luca); break;
            case "Leo": playerOne = playerFactory.getPlayer(PlayerTypes.Leo); break;
            case "Viktor": playerOne = playerFactory.getPlayer(PlayerTypes.Viktor); break;
            default: playerOne = playerFactory.getPlayer(PlayerTypes.Alex);
        }

        switch(p2Type) {
            case "Alex": playerTwo = playerFactory.getPlayer(PlayerTypes.Alex); break;
            case "Maurice": playerTwo = playerFactory.getPlayer(PlayerTypes.Maurice); break;
            case "Luca": playerTwo = playerFactory.getPlayer(PlayerTypes.Luca); break;
            case "Leo": playerTwo = playerFactory.getPlayer(PlayerTypes.Leo); break;
            case "Viktor": playerTwo = playerFactory.getPlayer(PlayerTypes.Viktor); break;
            default: playerTwo = playerFactory.getPlayer(PlayerTypes.Maurice);
        }

        contactListener = B2dContactListener.getContactListener_INSTANCE();
        world.setContactListener(contactListener);

        log.info("playerOne:" + playerOne);
        log.info("playerTwo:" + playerTwo);

        hud = new Hud(game.batch, playerOne, playerTwo);

        // Log all connected controllers:
        log.info("Connected Controllers:");
        log.info("---------------------------------");
        int controllerIndex = 1;
        for (Controller controller : Controllers.getControllers()) {
            log.info("Controller " + controllerIndex + ": " + controller.getUniqueId(), controller.getName());
            controllerIndex++;
        }
        log.info("---------------------------------");

        winScreen=new WinScreen(game,playerOne,playerTwo);

        // Necessary to avoid leftovers from another game session
        playerFactory.resetFactory();
        combatManager.resetCombatManager();
        contactListener.resetContactListener();

        // Network Initialization (Phase 6 & 7)
        this.networkClient = game.getNetworkClient();
        // Legacy mock adapter only; production belongs to LobbyClient/ClientMatchSession.
        if (this.networkClient != null && this.networkClient.isMockProtocol()) {
            this.networkActive = true;
            this.p1Id = new PlayerId("player-1");
            this.p2Id = new PlayerId("player-2");
            this.snapshotBuffer = new SnapshotBuffer(32);
            this.stateInterpolator = new StateInterpolator(snapshotBuffer, 30.0f, 2.0f);
            this.outboundInputQueue = new OutboundInputQueue(p1Id, 32);
            this.networkMetricsTracker = new NetworkMetricsTracker();

            // Remote player input is disabled; updated via network interpolation
            playerTwo.setInputState(Player.InputState.NETWORK);

            networkClient.setListener(new ConnectionListener() {
                @Override
                public void onConnected() {
                    networkMetricsTracker.onConnected();
                    log.info("Network connected to server. Sending JOIN_ROOM...");
                    networkClient.sendPacket(new NetworkPacket(MessageType.JOIN_ROOM, p1Id.value(), "{\"roomId\":\"mock-room\"}"));
                }

                @Override
                public void onPacketReceived(NetworkPacket packet) {
                    if (packet.getType() == MessageType.GAME_STATE) {
                        GameSnapshot snapshot = packet.parsePayload(GameSnapshot.class);
                        if (snapshot != null) {
                            snapshotBuffer.push(snapshot);
                            networkMetricsTracker.onSnapshotReceived(snapshot.serverTick());
                        }
                    } else if (packet.getType() == MessageType.PONG) {
                        networkMetricsTracker.onPongReceived(packet);
                    } else if (packet.getType() == MessageType.MATCH_RESULT) {
                        MatchResult result = packet.parsePayload(MatchResult.class);
                        if (result != null) {
                            latestMatchResult = result;
                            log.info("Authoritative MATCH_RESULT received: winner={}", result.winnerId());
                            Gdx.app.postRunnable(() -> {
                                winScreen.setMatchResult(result);
                                game.setScreen(winScreen);
                            });
                        }
                    }
                }

                @Override
                public void onDisconnected(String reason) {
                    networkMetricsTracker.onDisconnected(reason);
                    log.warn("Network disconnected: {}", reason);
                }
            });

            if (!networkClient.isConnected()) {
                try {
                    networkClient.connect();
                } catch (Exception e) {
                    networkMetricsTracker.onDisconnected(e.getMessage());
                    log.error("Failed to connect network client: " + e.getMessage());
                }
            }
        }

    }
    /**
     * Initializes the tile map. Loads the files, sets up the renderer, creates b2dBodies for
     * shapes in the object layers of the tile map.
     */
    private void createTileMap() {
        mapLoader = new TmxMapLoader();
        String mapRes = System.getProperty("ssprofs.map", "1/Map1New2Remake.tmx");
        map = mapLoader.load(mapRes);
        tiledMapRenderer = new OrthogonalTiledMapRenderer(map, 1 / PPM);
        //tiledMapRenderer.setBlending(true);

        this.batch = new SpriteBatch();

        world = new World(new Vector2(0, 0), true); //y value -> gravity -> now handled by the player class
        box2DDebugRenderer = new Box2DDebugRenderer();


        //define body
        BodyDef bdef = new BodyDef();
        PolygonShape shape = new PolygonShape();
        FixtureDef fdef = new FixtureDef();
        fdef.filter.categoryBits = B2dContactListener.WORLD_ENTITY;
        fdef.filter.maskBits = B2dContactListener.PLAYER_ENTITY | B2dContactListener.PROJECTILE_ENTITY;
        Body body;

        for(MapObject object : map.getLayers().get("obj").getObjects().getByType(RectangleMapObject.class)){
            Rectangle rect = ((RectangleMapObject) object).getRectangle();
            bdef.type = BodyDef.BodyType.StaticBody;
            bdef.position.set((rect.getX() + rect.getWidth() / 2 ) / PPM, (rect.getY() + rect.getHeight() / 2 ) / PPM);
            body = world.createBody(bdef);
            body.setUserData("Tile");
            shape.setAsBox(rect.getWidth() / 2 / PPM, rect.getHeight() / 2 / PPM);
            fdef.shape = shape;
            body.createFixture(fdef);
        }
        log.debug("TiledMap created. Initialized object layers and created matching b2dBodies.");
    }


    @Override
    public void show() {
        if (onlineScreen != null) { onlineScreen.show(); }
        else { Gdx.input.setInputProcessor(null); }
    }

    /**
     * Renders the playScreen after calling update(). Renders every necessary part of the screen
     * (players, items, tilemap, debug renderers, postprocessing filters and HUD).
     * Checks, if one player has won.
     * @param delta The time in seconds since the last render.
     */
    @Override
    public void render(float delta) {
        if (onlineScreen != null) { onlineScreen.render(delta); return; }

        Gdx.gl.glClear(Gdx.gl.GL_COLOR_BUFFER_BIT);
        Gdx.gl.glClearColor(0, 0, 0, 1); //-> light blue
        postProcessingManager.cleanUpBuffers();
        // Begin render to an off-screen buffer.
        postProcessingManager.beginInputCapture();

        tiledMapRenderer.render();

        update(delta);


        if (debugMode) {
            //render our tiled map debug outlines to screen
            box2DDebugRenderer.render(world, gamecamera.combined);

            // REMOVE "//" TO PRINT FPS TO THE CONSOLE!!-----
            //System.out.println(Gdx.graphics.getFramesPerSecond());
            // -------------------------------------------------
        }

        batch.setProjectionMatrix(gamecamera.combined);

        batch.begin();

        playerOne.draw(batch);
        playerTwo.draw(batch);
        combatManager.drawProjectiles(batch);
        vafxManager.drawVFX(batch);

        batch.end();


        // Muss unter batch.end() stehen
        if (networkActive && networkMetricsTracker != null) {
            hud.setNetworkStatus(networkMetricsTracker.getStatusSummary());
        }
        hud.stage.draw();
        hud.updateHud(delta, playerOne, playerTwo);

        postProcessingManager.endInputCapture();

        postProcessingManager.applyEffects();

        postProcessingManager.renderToScreen();

        //Test for win and set to win screen
       if(hud.testWin(playerOne,playerTwo))
       {
           if (latestMatchResult != null) {
               winScreen.setMatchResult(latestMatchResult);
           }
           game.setScreen(winScreen);
       }


    }

    /**
     * Resizes the viewport.
     * @param width
     * The width the viewport should be set to.
     * @param height
     * The height the viewport should be set to.
     */
    @Override
    public void resize(int width, int height) {
        if (onlineScreen != null) { onlineScreen.resize(width, height); return; }
        viewport.update(width, height);
    }

    @Override
    public void pause() {
        if (onlineScreen != null) { onlineScreen.pause(); }
    }

    @Override
    public void resume() {
        if (onlineScreen != null) { onlineScreen.resume(); }
    }

    @Override
    public void hide() {
        if (onlineScreen != null) { onlineScreen.hide(); }
    }

    /**
     * Dispose the PlayScreen and the map, the tiledMapRenderer, the b2dDebug renderer, the world and the hud.
     */
    @Override
    public void dispose() {
        if (onlineScreen != null) { onlineScreen.dispose(); return; }
        if (networkClient != null) {
            networkClient.disconnect();
        }
        map.dispose();
        tiledMapRenderer.dispose();
        box2DDebugRenderer.dispose();
        world.dispose();
        hud.dispose();
    }
}

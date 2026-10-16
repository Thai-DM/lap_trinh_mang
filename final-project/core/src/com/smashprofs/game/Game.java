package com.smashprofs.game;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.smashprofs.game.Exceptions.NegativeSeconds;
import com.smashprofs.game.Helper.GameThread;
import com.smashprofs.game.Helper.gamePropertiesManager;
import com.smashprofs.game.Screens.IntroScreen;
import com.smashprofs.game.Screens.PlayScreen;
import com.smashprofs.game.Screens.CharacterSelectScreen;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.smashprofs.game.network.client.NetworkClient;
import com.smashprofs.game.network.client.LobbyClient;
import com.smashprofs.game.network.client.LobbyConfiguration;
import com.smashprofs.game.network.mock.MockServer;

import static com.smashprofs.game.Helper.Util.log4JconfLoad;

public class Game extends com.badlogic.gdx.Game {
	public static SpriteBatch batch;
	private static Logger log = LogManager.getLogger(Game.class);
	public static final int V_WIDTH = 400;
	public static final int V_HEIGHT = 208;
	private GameThread thread;
	public static Boolean soundEffects, ingameMusic, debugMode, showFPS, unitTestMode;
	public static Boolean mockMode = false;

	private NetworkClient networkClient;
	private MockServer mockServer;
	private LobbyClient lobbyClient;

	public Game() {
		this(null, null);
	}

	public Game(NetworkClient networkClient, MockServer mockServer) {
		this.networkClient = networkClient;
		this.mockServer = mockServer;
		if (mockServer != null) {
			mockMode = true;
		}
	}

	public Game(LobbyConfiguration configuration) {
		this(null, null);
		mockMode = false;
		lobbyClient = new LobbyClient(configuration);
		networkClient = lobbyClient.getNetworkClient();
	}

	public LobbyClient getLobbyClient() { return lobbyClient; }

	public static Game forLobby(LobbyClient lobby) {
		var game = new Game();
		game.lobbyClient = java.util.Objects.requireNonNull(lobby);
		game.networkClient = lobby.getNetworkClient();
		mockMode = false;
		return game;
	}

	/** UI transfers an already joined connection; no socket connect/write on the render thread. */
	public void openOnlineLobby(LobbyClient joined) {
		if (joined.getSnapshot().phase() != LobbyClient.Phase.JOINED) {
			throw new IllegalArgumentException("The connection must join a room first");
		}
		if (lobbyClient != null) { lobbyClient.close(); }
		if (networkClient != null && networkClient != joined.getNetworkClient()) {
			var previous = networkClient;
			new Thread(() -> previous.disconnectAndWait(3000), "Game-PreviousConnectionClose").start();
		}
		if (mockServer != null) {
			var previous = mockServer; mockServer = null;
			new Thread(previous::stop, "Game-MockServerClose").start();
		}
		mockMode = false; lobbyClient = joined; networkClient = joined.getNetworkClient();
        setScreen(joined.supportsGameplay()?new com.smashprofs.game.Screens.HostLobbySettingsScreen(this):new CharacterSelectScreen(this));
	}

	public void leaveOnlineLobby() {
		if (lobbyClient != null) { lobbyClient.close(); lobbyClient = null; networkClient = null; }
	}

	public NetworkClient getNetworkClient() {
		return networkClient;
	}

	public MockServer getMockServer() {
		return mockServer;
	}

	public boolean isNetworkActive() {
		return networkClient != null;
	}
	
	@Override
	public void create () {
		log4JconfLoad(System.getProperty("user.dir") + "\\log4j2.xml");
		log.info("Game created, starting up...");
		log.warn("---------------------------------------");
		log.warn("This game uses assets made by others.");
		log.warn("For credits, see assets/credits.txt");
		log.warn("---------------------------------------");
		if (lobbyClient == null) {
			thread = new GameThread();
			gamePropertiesManager.firstStart();
			thread.start();
		}

		// --Toggles sound effects--
		soundEffects = true;
		//--------------------------
		// --Toggles in-game music--
		ingameMusic = true;
		//--------------------------
		// --Toggle unit test mode--
		unitTestMode = false;
		//--------------------------
		// --Toggles debug mode--
		debugMode = false;
		// ----------------------
		// --Toggles FPS visibility--
		showFPS = true;
		// --------------------------
		// ! See other graphic settings in DesktopLauncher.java !

		batch = new SpriteBatch();

		if (lobbyClient != null) {
			setScreen(new CharacterSelectScreen(this));
			lobbyClient.startAsync(); // TCP connect never runs on the render thread.
		} else if(mockMode || unitTestMode) {
			log.warn("Mock or unit test mode enabled, skipping intro screen");
			log.warn("Redirecting to PlayScreen...");
			setScreen(new PlayScreen(this));
		}
		else {
			log.info("Redirecting to IntroScreen...");
			setScreen(new IntroScreen(this));
		}


		//setScreen(new PlayScreen(this)); // passes game to set screen on its own -> now started by intro screen
		if(debugMode) {
			log.warn("-------------------------------------------");
			log.warn("Game debug mode activated!");
			log.warn("You might encounter debug lines and more.");
			log.warn("-------------------------------------------");
		}
		if(showFPS) {
			log.warn("-------------------------------------------");
			log.warn("FPS counter visible!");
			log.warn("Keep in mind that the FPS indicator is only ");
			log.warn("visible when the PlayScreen is active.");
			log.warn("-------------------------------------------");
		}
	}

	@Override
	public void render () {
		super.render();
	}
	
	@Override
	public void dispose () {
		if (getScreen() != null) { getScreen().dispose(); }
		batch.dispose();
		if (lobbyClient != null) {
			lobbyClient.close();
		} else if (networkClient != null) {
			networkClient.disconnect();
		}
		if (mockServer != null) {
			mockServer.stop();
		}
		try {
			if (thread != null) { thread.threadEnd(); }
		} catch (NegativeSeconds e) {
			e.printStackTrace();
		}

	}
}

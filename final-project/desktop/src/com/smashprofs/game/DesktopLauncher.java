package com.smashprofs.game;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;

// Please note that on macOS your application needs to be started with the -XstartOnFirstThread JVM argument
public class DesktopLauncher {
	public static boolean unitTestMode;
	static int width = 1920;
	static int height = 1080;

	public static int getWidth() {
		return width;
	}

	public static int getHeight() {
		return height;
	}

	public static void main (String[] arg) {

		// --Toggle unit test mode--
		unitTestMode = false;
		//--------------------------



		Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();

		String iconPath = "icon.png";


		//------------- Graphics --------------
		int width = 1920;
		int height = 1080;
		boolean useFullScreenMode = false;
		boolean useOwnResolution = false;
		boolean useVSync = false;
		int fps = 60;
		//-------------------------------------

		config.setTitle("SuperSmashProfs");
		config.setWindowIcon(iconPath);
		config.disableAudio(false);

		if(useVSync) config.useVsync(true); //uses max refresh rate of the monitor » prevents tearing and other errors
		else config.setForegroundFPS(fps);

		// If unitTestMode is activated, set the game to invisible
		if(unitTestMode) {
			config.setInitialVisible(false);
		}

		if(useFullScreenMode) config.setFullscreenMode(Lwjgl3ApplicationConfiguration.getDisplayMode());

		else if(useOwnResolution) config.setWindowedMode(getWidth(), getHeight());
		else config.setMaximized(true);

		boolean mockMode = false;
		if (arg != null) {
			for (String s : arg) {
				if ("--mock".equalsIgnoreCase(s)) {
					mockMode = true;
					break;
				}
			}
		}

		Game game;
		if (mockMode) {
			try {
				com.smashprofs.game.network.mock.MockServer mockServer = new com.smashprofs.game.network.mock.MockServer();
				mockServer.start();
				int port = mockServer.getPort();
				System.out.println("[DesktopLauncher] Started MockServer on 127.0.0.1:" + port);
				com.smashprofs.game.network.client.NetworkClient networkClient = new com.smashprofs.game.network.client.NetworkClient("127.0.0.1", port, null);
				game = new Game(networkClient, mockServer);
			} catch (Exception e) {
				System.err.println("[DesktopLauncher] Failed to start MockServer: " + e.getMessage());
				game = new Game();
			}
		} else {
			game = new Game();
		}

		new Lwjgl3Application(game, config);
	}
}

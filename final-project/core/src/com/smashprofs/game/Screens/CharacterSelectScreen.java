package com.smashprofs.game.Screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.Game;
import com.badlogic.gdx.controllers.Controller;
import com.badlogic.gdx.controllers.Controllers;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.Sprite;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.ImageButton;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.SpriteDrawable;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.FillViewport;
import com.badlogic.gdx.utils.viewport.Viewport;
import com.crashinvaders.vfx.VfxManager;
import com.smashprofs.game.Helper.*;
import com.smashprofs.game.network.client.LobbyClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class CharacterSelectScreen implements Screen {

    private static Logger log = LogManager.getLogger(CharacterSelectScreen.class);
    int width = Gdx.graphics.getWidth();
    int height = Gdx.graphics.getHeight();
    private float zoomFactor = 1f;
    private OrthographicCamera camera;
    private Viewport viewport;
    private Stage stage;
    private SoundManager soundManager;
    private Array<Controller> controllers;
    //private TextureRegion[] alex, maurice, luca, leo, viktor;
    private Image left1, right1, left2, right2, playButton, p1ctrls, p2ctrls;
    private Table table;
    private VfxManager postProcessingManager;
    private static int carouselCounter = 0;
    private static int carouselCounter2 = 0;
    //private TextureRegion[] playerImages;
    private List<TextureRegion> playerImages;
    private static String[] playerNames;
    private ImageButton currentSelection, currentSelection2;
    private Label p1Label, p2Label;
    private BitmapFont labelFont;
    SpriteBatch batch;
    Game game;
    private final LobbyClient lobby;
    private Label lobbyStatus;
    private TextButton readyButton;
    private TextButton changeRoomButton;
    private TextButton rulesButton;
    private BitmapFont lobbyFont;
    private String displayedMatchId;
    private final Map<String, Texture> ownedTextures = new java.util.LinkedHashMap<>();
    private boolean disposed;

    private Texture loadTexture(String path) { return ownedTextures.computeIfAbsent(path, Texture::new); }

    public CharacterSelectScreen(Game game) {
        this.camera = new OrthographicCamera(width, height);
        this.batch = new SpriteBatch();
        this.game = game;
        this.lobby = game instanceof com.smashprofs.game.Game localGame ? localGame.getLobbyClient() : null;
        this.viewport = new FillViewport(1920, 1080, this.camera);
        this.stage = new Stage(this.viewport, this.batch);
        this.soundManager = SoundManager.getSoundManager_INSTANCE();
        this.controllers = Controllers.getControllers();
        table = new Table();
        PostProcessingSettings ppSetUpHandler = new PostProcessingSettings();
        this.postProcessingManager = ppSetUpHandler.getPostProcessingManager();

        Map<String, String> players = Map.of("Alex", "Sprites/Alex/alex_stand.png", "Maurice", "Sprites/Momo/momo_strip.png", "Luca", "Sprites/Luca/luca_stand.png", "Leo", "Sprites/Leo/leo_stand.png", "Viktor", "Sprites/Viktor/viktor_stand.png");
        this.playerImages = players.values().stream().map(this::loadTexture).map(t -> TextureRegion.split(t, 100, 100)[0]).map(tr -> tr[0]).toList();
        playerNames = players.keySet().toArray(new String[players.keySet().size()]);

        this.currentSelection = new ImageButton(new TextureRegionDrawable(playerImages.get(carouselCounter)));
        this.currentSelection2 = new ImageButton(new TextureRegionDrawable(playerImages.get(carouselCounter2)));

        this.labelFont = new BitmapFont();
        labelFont.getData().setScale(4f);
        this.p1Label = new Label(playerNames[carouselCounter], new Label.LabelStyle(labelFont, Color.valueOf("FFFFFF")));
        this.p2Label = new Label(playerNames[carouselCounter2], new Label.LabelStyle(labelFont, Color.valueOf("FFFFFF")));

        if (lobby != null) {
            lobbyFont = new BitmapFont();
            lobbyFont.getData().setScale(2f);
            lobbyStatus = new Label(lobby.getSnapshot().statusText(), new Label.LabelStyle(lobbyFont, Color.WHITE));
            lobbyStatus.setWrap(true);
            TextButton.TextButtonStyle style = new TextButton.TextButtonStyle();
            style.font = lobbyFont;
            style.fontColor = Color.WHITE;
            style.disabledFontColor = Color.GRAY;
            readyButton = new TextButton("Ready (Enter)", style);
            readyButton.setDisabled(true);
            readyButton.addListener(new ClickListener() {
                @Override public void clicked(InputEvent event, float x, float y) {
                    lobby.ready(getSelectedPlayerOne());
                }
            });
            changeRoomButton = new TextButton("Change room / server", style);
            if(lobby.supportsGameplay()) {
                rulesButton=new TextButton("Room rules / Host settings",style);
                rulesButton.addListener(new ClickListener() { @Override public void clicked(InputEvent event,float x,float y) { game.setScreen(new HostLobbySettingsScreen((com.smashprofs.game.Game)game)); } });
            }
            changeRoomButton.addListener(new ClickListener() {
                @Override public void clicked(InputEvent event, float x, float y) {
                    var localGame = (com.smashprofs.game.Game) game;
                    localGame.leaveOnlineLobby();
                    game.setScreen(new OnlineLobbyScreen(localGame));
                }
            });
        }

        left1 = new Image(loadTexture("ui/arrowLeft.png"));
        left2 = new Image(loadTexture("ui/arrowLeft.png"));
        right1 = new Image(loadTexture("ui/arrowRight.png"));
        right2 = new Image(loadTexture("ui/arrowRight.png"));

        left1.scaleBy(2f);
        left2.scaleBy(2f);
        right1.scaleBy(2f);
        right2.scaleBy(2f);

        playButton = new Image(loadTexture("mainmenu/buttons/playButtonInactive.png"));
        p1ctrls = new Image(loadTexture("ui/ctrlsP1.png"));
        p2ctrls = new Image(loadTexture("ui/ctrlsP2.png"));
        // Set control hints to "Gamepad" if every player has a connected gamepad.
        if(controllers.size > 1) {
            log.info("More than 1 gamepad connected! Showing controller hints.");
            p1ctrls = new Image(loadTexture("ui/ctrlsGamepad.png"));
            p2ctrls = new Image(loadTexture("ui/ctrlsGamepad.png"));
        }
        // Keep in mind: scaleBy(1f) equals setScale(2f)!
        p1ctrls.scaleBy(0.5f);
        p2ctrls.scaleBy(0.5f);

        left1.addListener(new ClickListener() {
            public void clicked(InputEvent event, float x, float y){
                    decrementCarouselCounter(1);
            }
        });
        left2.addListener(new ClickListener() {
            public void clicked(InputEvent event, float x, float y){
                decrementCarouselCounter(2);
            }
        });
        right1.addListener(new ClickListener() {
            public void clicked(InputEvent event, float x, float y){
                incrementCarouselCounter(1);
            }
        });
        right2.addListener(new ClickListener() {
            public void clicked(InputEvent event, float x, float y){
                incrementCarouselCounter(2);
            }
        });

        playButton.addListener(new InputListener() {
            @Override
            public void enter(InputEvent event, float x, float y, int pointer, Actor actor) {

                //playButton = new Image(playButtonActive);
                playButton.setDrawable(new SpriteDrawable(new Sprite(loadTexture("mainmenu/buttons/playButtonActive.png"))));
                if (Gdx.input.isButtonJustPressed(0)) {
                    if (lobby != null) { return; } // Production waits for server GAME_START.
                    soundManager.playSound("sounds/minecraft_click.mp3");
                    log.info("PlayerOne selected the character " + getSelectedPlayerOne());
                    log.info("PlayerTwo selected the character " + getSelectedPlayerTwo());
                    game.setScreen(new PlayScreen((com.smashprofs.game.Game) game));
                }
            }

            @Override
            public void exit(InputEvent event, float x, float y, int pointer, Actor actor) {
                playButton.setDrawable(new SpriteDrawable(new Sprite(loadTexture("mainmenu/buttons/playButtonInactive.png"))));
            }
        });

        log.info("Created CharacterSelectScreen");

    }


    @Override
    public void show() {
        Gdx.input.setInputProcessor(stage);
        //Create Table
        //Set table to fill stage
        table.setFillParent(true);
        //Set alignment of contents in the table.
        renderTable();


        //table.setDebug(true);
        if(table.getDebug()) {
            log.warn("Table is set to debug mode!");
        }
        
        stage.addActor(table);

        log.info("Showing CharacterSelectScreen");
    }

    @Override
    public void render(float delta) {
        if (lobby != null && updateLobbyView()) { return; } // Screen transition only on the render thread.
        ScreenUtils.clear(Color.CLEAR);
        postProcessingManager.cleanUpBuffers();
        postProcessingManager.beginInputCapture();

        //character selection with input keys
        scrollWithKeys();

        this.stage.act();
        this.stage.draw();

        postProcessingManager.endInputCapture();

        postProcessingManager.applyEffects();

        postProcessingManager.renderToScreen();

    }

    private boolean updateLobbyView() {
        var state = lobby.getSnapshot();
        lobbyStatus.setText(state.statusText());
        readyButton.setDisabled(!state.canReady());
        if (state.canReady() && Gdx.input.isKeyJustPressed(Input.Keys.ENTER)) {
            lobby.ready(getSelectedPlayerOne());
        }
        if (state.isMatchStarted() && displayedMatchId == null) {
            displayedMatchId = state.match().matchId();
            game.setScreen(new PlayScreen((com.smashprofs.game.Game) game));
            return true;
        }
        return false;
    }

    @Override
    public void resize(int width, int height) {
        this.viewport.update(width, height);
        camera.position.set(camera.viewportWidth / 2, camera.viewportHeight / 2, 0);
        camera.update();
    }

    private void scrollWithKeys() {
        if(Gdx.input.isKeyJustPressed(Input.Keys.A)){
            decrementCarouselCounter(1);
        }
        if(Gdx.input.isKeyJustPressed(Input.Keys.D)){
            incrementCarouselCounter(  1);
        }
        if(Gdx.input.isKeyJustPressed(Input.Keys.LEFT)){
            decrementCarouselCounter(2);
        }
        if(Gdx.input.isKeyJustPressed(Input.Keys.RIGHT)){
            incrementCarouselCounter(  2);
        }
    }

    public void incrementCarouselCounter(int NumberOfCarouselCounter) {
        if (!canChangeSelection(NumberOfCarouselCounter)) { return; }

        if(NumberOfCarouselCounter == 1) {
            if(carouselCounter < playerImages.size()-1) {
                carouselCounter++;
            }
            else if (carouselCounter == playerImages.size() -1) {
                carouselCounter = 0;
            }
            System.out.println("CarouselCounter1: " + carouselCounter);
            System.out.println("CurrentPlayer1: " + playerNames[carouselCounter]);
        } else if (NumberOfCarouselCounter == 2) {
            if(carouselCounter2 < playerImages.size()-1) {
                carouselCounter2++;
            }
            else if (carouselCounter2 == playerImages.size() -1) {
                carouselCounter2 = 0;
            }
            System.out.println("CarouselCounter2: " + carouselCounter2);
            System.out.println("CurrentPlayer2: " + playerNames[carouselCounter2]);
        }

        updateScreenContents();
    }

    public void decrementCarouselCounter(int NumberOfCarouselCounter) {
        if (!canChangeSelection(NumberOfCarouselCounter)) { return; }

        if(NumberOfCarouselCounter == 1) {
            if(carouselCounter > 0) {
                carouselCounter--;
            }
            else if (carouselCounter == 0) {
                carouselCounter = playerImages.size()-1;
            }
            System.out.println("CarouselCounter1: " + carouselCounter);
            System.out.println("CurrentPlayer1: " + playerNames[carouselCounter]);
        }
        else if(NumberOfCarouselCounter == 2) {
            if(carouselCounter2 > 0) {
                carouselCounter2--;
            }
            else if (carouselCounter2 == 0) {
                carouselCounter2 = playerImages.size()-1;
            }
            System.out.println("CarouselCounter2: " + carouselCounter2);
            System.out.println("CurrentPlayer2: " + playerNames[carouselCounter2]);
        }

        updateScreenContents();
    }

    public void updateScreenContents() {
        //this.currentSelection = new ImageButton(new TextureRegionDrawable(playerImages.get(carouselCounter)));
        this.currentSelection = new ImageButton(new TextureRegionDrawable(playerImages.get(carouselCounter)));
        this.p1Label.setText(playerNames[carouselCounter]);
        this.currentSelection2 = new ImageButton(new TextureRegionDrawable(playerImages.get(carouselCounter2)));
        this.p2Label.setText(playerNames[carouselCounter2]);
        table.clear();

        renderTable();
    }

    private boolean canChangeSelection(int selection) {
        if (lobby == null) { return true; }
        if (selection != 1) { return false; }
        return switch (lobby.getSnapshot().phase()) {
            case IDLE, CONNECTING, CREATING, JOINING, JOINED -> true;
            default -> false;
        };
    }

    public void renderTable() {

        if (lobby != null) {
            table.top();
            table.add(currentSelection).colspan(2).width(width / 2f).padTop(100);
            table.row();
            table.add(p1Label).colspan(2).padTop(30);
            table.row();
            table.add(left1).padTop(30);
            table.add(right1).padTop(30);
            table.row();
            table.add(readyButton).colspan(2).padTop(40);
            table.row();
            table.add(lobbyStatus).colspan(2).width(1600).padTop(40);
            table.row();
            table.add(changeRoomButton).colspan(2).padTop(25);
            if(rulesButton!=null) { table.row();table.add(rulesButton).colspan(2).padTop(15); }
            table.background(new TextureRegionDrawable(loadTexture("ui/selecbg.png")));
            stage.addActor(table);
            return;
        }

        table.top();
        table.row().padTop(height/5f);
        table.add(currentSelection).colspan(2).width(width/2f);
        table.add(currentSelection2).colspan(2).width(width/2f);
        table.row().height(100).padTop(100).left();
        table.add(p1Label).colspan(2).center();
        table.add(p2Label).colspan(2).center();
        table.row().height(100).padTop(100).left();
        table.add(left1).maxSize(left1.getWidth(), left1.getHeight()).center();
        table.add(right1).maxSize(right1.getWidth(), right1.getHeight()).center();
        table.add(left2).maxSize(left2.getWidth(), left2.getHeight()).center();
        table.add(right2).maxSize(right2.getWidth(), right2.getHeight()).center();
        table.row().padTop(height/12f).height(p1ctrls.getHeight());
        table.add(p1ctrls).colspan(2);
        table.add(p2ctrls).colspan(2);
        table.row();
        table.add(playButton).center().colspan(4);
        table.background(new TextureRegionDrawable(loadTexture("ui/selecbg.png")));
        stage.addActor(table);

    }

    public static String getSelectedPlayerOne() {
        if(com.smashprofs.game.Game.unitTestMode || com.smashprofs.game.Game.mockMode || playerNames == null) {
            return "Alex";
        }
        return playerNames[carouselCounter];
    }
    public static String getSelectedPlayerTwo() {
        if(com.smashprofs.game.Game.unitTestMode || com.smashprofs.game.Game.mockMode || playerNames == null) {
            return "Maurice";
        }
        return playerNames[carouselCounter2];
    }

    @Override
    public void pause() {

    }

    @Override
    public void resume() {

    }

    @Override
    public void hide() {
        if (lobby != null) { dispose(); }
    }

    @Override
    public void dispose() {
        if (disposed) { return; }
        disposed = true;
        if (Gdx.input.getInputProcessor() == stage) { Gdx.input.setInputProcessor(null); }
        if (lobbyFont != null) { lobbyFont.dispose(); }
        this.stage.dispose();
        this.batch.dispose();
        labelFont.dispose();
        postProcessingManager.dispose();
        ownedTextures.values().forEach(Texture::dispose);
        ownedTextures.clear();
    }
}

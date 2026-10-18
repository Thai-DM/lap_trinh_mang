package com.smashprofs.game.network.client;

import com.smashprofs.common.model.*;
import com.smashprofs.common.protocol.*;
import com.smashprofs.server.game.*;
import com.smashprofs.server.game.physics.TmxCollisionLoader;
import com.smashprofs.server.network.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class FighterClientTest {
    @Test public void bothControlPresetsResolveMultiKeyInputWithoutMixingAimAndJump() {
        var held=new HashSet<Integer>();var pressed=new HashSet<Integer>();
        held.add(com.badlogic.gdx.Input.Keys.A);held.add(com.badlogic.gdx.Input.Keys.W);held.add(com.badlogic.gdx.Input.Keys.J);
        pressed.add(com.badlogic.gdx.Input.Keys.W);pressed.add(com.badlogic.gdx.Input.Keys.J);
        var command=UnarmedControls.sample(UnarmedControls.Preset.B,held::contains,pressed::contains);
        assertEquals(-1,command.horizontal(),0);assertTrue(command.jumpPressed());assertTrue(command.lightPressed());assertFalse(command.fighter().upHeld());assertFalse(command.fighter().heavyPressed());
        held.clear();pressed.clear();held.add(com.badlogic.gdx.Input.Keys.RIGHT);held.add(com.badlogic.gdx.Input.Keys.DOWN);held.add(com.badlogic.gdx.Input.Keys.X);held.add(com.badlogic.gdx.Input.Keys.I);
        pressed.add(com.badlogic.gdx.Input.Keys.X);pressed.add(com.badlogic.gdx.Input.Keys.Z);
        command=UnarmedControls.sample(UnarmedControls.Preset.A,held::contains,pressed::contains);
        assertEquals(1,command.horizontal(),0);assertFalse(command.jumpPressed());assertTrue(command.fighter().upHeld());assertTrue(command.fighter().downHeld());assertTrue(command.fighter().heavyPressed());assertTrue(command.fighter().dodgePressed());
        held.add(com.badlogic.gdx.Input.Keys.LEFT);assertEquals(0,UnarmedControls.sample(UnarmedControls.Preset.A,held::contains,pressed::contains).horizontal(),0);
    }
    @Test public void unarmedDetailRoundTripsAndRejectsInvalidResourceCounters() {
        try(var r=runtime()) {
            var snapshot=r.getLatestSnapshot().gameplay();var node=GameplayProtocol.snapshot(snapshot);
            assertEquals(snapshot,GameplayProtocol.snapshot(node));
            ((com.fasterxml.jackson.databind.node.ObjectNode)node.path("fighter").path("players").get(0).path("detail")).put("aerialActionsUsed",4);
            assertThrows(IllegalArgumentException.class,()->GameplayProtocol.snapshot(node));
        }
    }
    @Test public void equipmentBindingsSurviveBothPresetsAndTheInputCodec() throws Exception {
        for(var preset:UnarmedControls.Preset.values()) {
            var pressed=Set.of(com.badlogic.gdx.Input.Keys.E,com.badlogic.gdx.Input.Keys.G,com.badlogic.gdx.Input.Keys.Q);
            var command=UnarmedControls.sample(preset,key->false,pressed::contains);
            assertTrue(command.interactPressed());assertTrue(command.dropPressed());assertTrue(command.fighter().throwPressed());
            assertFalse(command.lightPressed());assertFalse(command.fighter().heavyPressed());assertFalse(command.fighter().dodgePressed());
            var transport=new ValueTransport(a);transport.enableFighter();
            var roster=new GameStartRoster("fighter","room",List.of(new GameStartRoster.Participant(a,0,"Alex"),new GameStartRoster.Participant(b,1,"Luca")));
            try(var session=new ClientMatchSession(transport,roster,a,()->0L);var r=runtime()) {
                session.acceptState(message(r));
                assertTrue(session.recordFrameInput(command.horizontal(),command.jumpPressed(),false,command.lightPressed(),false,false,command.interactPressed(),command.dropPressed(),command.fighter()));
                var decoded=ProtocolV1.playerInput(transport.last,a);
                assertTrue(decoded.interactPressed());assertTrue(decoded.dropPressed());assertTrue(decoded.fighter().throwPressed());
            }
            var released=UnarmedControls.sample(preset,pressed::contains,key->false);
            assertFalse(released.interactPressed());assertFalse(released.dropPressed());assertFalse(released.fighter().throwPressed());
        }
    }
    private final PlayerId a=new PlayerId("a"),b=new PlayerId("b");
    private MatchRuntime runtime() {
        var r=new MatchRuntime(new MatchSetup("fighter","room",List.of(new MatchSetup.Participant(a,0,"Alex"),new MatchSetup.Participant(b,1,"Luca")),new MatchRules(2,0,3,MatchRules.DropRate.NONE,true,true)));r.start();r.tick();return r;
    }
    private static WireGameSnapshot wire(MatchRuntime r) { return WireGameSnapshot.parse(message(r).payload()); }
    private static ProtocolMessage message(MatchRuntime r) { return GameStateMessageMapper.map(new GameStateDelivery(r.getLatestSnapshot(),"PLAYING",0,()->true)); }
    private LocalFighterPrediction predictor() {
        var map=new TmxCollisionLoader().loadOriginalMap();
        var rects=map.rectangles().stream().map(p->new LocalFighterPrediction.Rect(p.xPixels()/100,p.yPixels()/100,p.widthPixels()/100,p.heightPixels()/100)).toList();
        return new LocalFighterPrediction(rects);
    }
    @Test public void protocolRoundTripRetainsFighterDamageStateAndRejectsMalformedInput() throws Exception {
        try(var r=runtime()) {
            var game=r.getLatestSnapshot().gameplay();assertEquals(game,GameplayProtocol.snapshot(GameplayProtocol.snapshot(game)));
            var fighter=new FighterInput(true,true,false,true,true,true,true,true);
            var input=new PlayerInput(a,1,1,1,true,false,true,false,false,true,false,fighter);
            assertEquals(input,ProtocolV1.playerInput(new ProtocolMessage(MessageType.PLAYER_INPUT,null,ProtocolV1.inputPayload(input)),a));
            var bad=ProtocolV1.inputPayload(input);((com.fasterxml.jackson.databind.node.ObjectNode)bad.get("fighter")).put("damagePercent",999);
            assertThrows(ProtocolException.class,()->ProtocolV1.playerInput(new ProtocolMessage(MessageType.PLAYER_INPUT,null,bad),a));
            var invalid=GameplayProtocol.snapshot(game);((com.fasterxml.jackson.databind.node.ObjectNode)invalid.path("fighter").path("players").get(0)).put("damagePercent",-1);
            assertThrows(IllegalArgumentException.class,()->GameplayProtocol.snapshot(invalid));
        }
    }
    @Test public void predictionReplaysUnacknowledgedMovementAgainstTheSameNativeMapAsAuthority() {
        try(var r=runtime();var prediction=predictor()) {
            var base=wire(r);var inputs=new ArrayList<PlayerInput>();
            for(int i=1;i<=10;i++) {
                var input=new PlayerInput(a,i,i,1,false,false,false,false,false,false,false,FighterInput.NONE);
                inputs.add(input);r.submitInput(input);r.tick();
            }
            var shown=prediction.sample(base.player("a"),base.gameplay().fighter().player("a"),inputs,1/60f);
            assertEquals(wire(r).player("a").x(),shown.x(),.00001f);assertEquals(wire(r).player("a").y(),shown.y(),.00001f);
            var stable=prediction.sample(wire(r).player("a"),wire(r).gameplay().fighter().player("a"),List.of(),1/60f);
            assertEquals(shown.x(),stable.x(),.00001f);
        }
    }
    @Test public void predictionMatchesAuthorityWhenReleasingAndReversingAirMovement() {
        try(var r=runtime();var prediction=predictor()) {
            var base=wire(r);var inputs=new ArrayList<PlayerInput>();
            for(int i=1;i<=15;i++) {
                float axis=i<=7?1:i<=11?0:-1;
                var input=new PlayerInput(a,i,i,axis,false,false,false,false,false,false,false,FighterInput.NONE);
                inputs.add(input);r.submitInput(input);r.tick();
            }
            var shown=prediction.sample(base.player("a"),base.gameplay().fighter().player("a"),inputs,1/60f);
            assertEquals(wire(r).player("a").x(),shown.x(),.00001f);assertEquals(wire(r).player("a").y(),shown.y(),.00001f);
        }
    }
    @Test public void predictedFastFallReboundsAtTheLavaInsteadOfFallingBelowTheMap() {
        try(var r=runtime();var prediction=new LocalFighterPrediction(List.of())) {
            var source=wire(r);var inputs=new ArrayList<PlayerInput>();
            for(int i=1;i<=15;i++) {
                inputs.add(new PlayerInput(a,i,i,0,false,false,false,false,false,false,false,
                        new FighterInput(true,false,false,false,false,false,false,false)));
            }
            var falling=new WireGameSnapshot.Player("a",0,"Alex",2.5f,.7f,0,-3,100,false,false,false,true);
            var shown=prediction.sample(falling,source.gameplay().fighter().player("a"),inputs,1/60f);
            assertTrue(shown.y()>FighterArena.LAVA_CENTER_Y+.1f);assertEquals(2.5f,shown.x(),.00001f);
        }
    }
    @Test public void predictionDoesNotDoubleMovementAt120FpsAndNeverReplaysAcknowledgedEdges() {
        try(var r=runtime();var first=predictor();var second=predictor()) {
            var state=wire(r);var single=new PlayerInput(a,1,1,1,false,false,false,false,false);
            var duplicateTick=new PlayerInput(a,2,1,1,false,false,false,false,false);
            var p1=first.sample(state.player("a"),state.gameplay().fighter().player("a"),List.of(single),1/60f);
            var p2=second.sample(state.player("a"),state.gameplay().fighter().player("a"),List.of(single,duplicateTick),1/120f);
            assertEquals(p1,p2);
            r.submitInput(duplicateTick);r.tick();var acknowledged=wire(r);
            try(var reset=predictor()) {
                var p=reset.sample(acknowledged.player("a"),acknowledged.gameplay().fighter().player("a"),List.of(single,duplicateTick),1/60f);
                assertEquals(acknowledged.player("a").x(),p.x(),0);assertEquals(acknowledged.player("a").y(),p.y(),0);
            }
        }
    }
    @Test public void predictionHistoryOnlyContainsSuccessfulEnqueuesAndAcknowledgementsRetireIt() throws Exception {
        try(var r=runtime()) {
            var transport=new ValueTransport(a);var roster=new GameStartRoster("fighter","room",List.of(new GameStartRoster.Participant(a,0,"Alex"),new GameStartRoster.Participant(b,1,"Luca")));
            try(var session=new ClientMatchSession(transport,roster,a,()->0L)) {
                session.acceptState(message(r));transport.accept=false;
                assertFalse(session.recordFrameInput(1,true,false,false,false,false,false,false,new FighterInput(false,true,false,false,false,false,false,false)));
                assertTrue(session.getPredictionInputs().isEmpty());transport.accept=true;assertTrue(session.flushInput());assertEquals(1,session.getPredictionInputs().size());
                var input=ProtocolV1.playerInput(transport.last,a);r.submitInput(input);r.tick();session.acceptState(message(r));assertTrue(session.getPredictionInputs().isEmpty());
            }
        }
    }
    @Test public void damageBandsCoverAllSpecifiedThresholds() {
        assertEquals(0,FighterViewModel.damageBand(0));assertEquals(0,FighterViewModel.damageBand(40));
        assertEquals(1,FighterViewModel.damageBand(41));assertEquals(2,FighterViewModel.damageBand(81));
        assertEquals(3,FighterViewModel.damageBand(121));assertEquals(4,FighterViewModel.damageBand(161));assertEquals("240%",FighterViewModel.damageText(240));
    }
    @Test public void firstInputBeforeTheFirstSnapshotKeepsHeldJumpAndItsMatchBinding() throws Exception {
        var transport=new ValueTransport(a);transport.enableFighter();
        var roster=new GameStartRoster("fighter","room",List.of(new GameStartRoster.Participant(a,0,"Alex"),new GameStartRoster.Participant(b,1,"Luca")));
        try(var session=new ClientMatchSession(transport,roster,a,()->0L)) {
            var controls=new FighterInput(false,true,true,false,false,false,false,false);
            assertTrue(session.recordFrameInput(0,true,false,false,false,false,false,false,controls));
            assertEquals("fighter",transport.last.payload().path("matchId").asText());
            assertEquals(controls,ProtocolV1.playerInput(transport.last,a).fighter());assertEquals(1,session.getPredictionInputs().size());
            try(var r=runtime()) {
                var ended=message(r).payload();ended.put("phase","FINISHED");session.acceptState(new ProtocolMessage(MessageType.GAME_STATE,null,ended));
                assertTrue(session.getPredictionInputs().isEmpty());assertFalse(session.recordFrameInput(1,true,false,false,false,false));
            }
        }
    }
    private static final class ValueTransport extends NetworkClient {
        final PlayerId id;boolean accept=true;ProtocolMessage last;
        ValueTransport(PlayerId id) { super("127.0.0.1",0,null);this.id=id; }
        @Override public PlayerId getPlayerId() { return id; }
        @Override public boolean sendMessage(ProtocolMessage message) { last=message;return accept; }
    }
}

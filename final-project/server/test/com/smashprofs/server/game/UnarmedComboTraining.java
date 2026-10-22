package com.smashprofs.server.game;

import com.smashprofs.common.model.*;
import com.smashprofs.common.protocol.ProtocolV1;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Reproducible training fixtures; never labels a community sequence universally true. */
public final class UnarmedComboTraining {
    private static final List<List<String>> CASES=List.of(
            List.of("DLIGHT","NAIR"),List.of("DLIGHT","SAIR"),List.of("DLIGHT","DAIR"),List.of("DLIGHT","RECOVERY"),List.of("DLIGHT","GROUND_POUND"),
            List.of("DLIGHT","GC DLIGHT","NAIR"),List.of("DLIGHT","GC DLIGHT","SAIR"),List.of("DLIGHT","GC DLIGHT","DAIR"),
            List.of("DLIGHT","GC DLIGHT","RECOVERY"),List.of("DLIGHT","GC DLIGHT","GROUND_POUND"),
            List.of("NLIGHT","NAIR"),List.of("SLIGHT","NLIGHT"),List.of("NAIR","SAIR"),List.of("NAIR","RECOVERY"),
            List.of("SAIR","NAIR"),List.of("DAIR","GC DLIGHT","SAIR"),List.of("DAIR","GROUND_POUND"));
    private final PlayerId attacker=new PlayerId("attacker"),target=new PlayerId("target");
    private MatchRuntime runtime;private GameplayMatch game;private long sequence,targetSequence,lastHit;
    private ArrayNode hits;
    private boolean evade;
    private ObjectNode scenario(List<String> chain,int hp,boolean dodge) {
        evade=dodge;sequence=targetSequence=lastHit=0;hits=ProtocolV1.object().putArray("hits");
        var out=ProtocolV1.object();var moves=out.putArray("requestedMoves");chain.forEach(moves::add);
        out.put("status","UNVERIFIED").put("targetHP",hp).put("targetDodgesAtFirstOpportunity",dodge).put("initialDistanceMeters",.27).put("initialHeightMeters",.23);
        runtime=new MatchRuntime(new MatchSetup("training","room",List.of(new MatchSetup.Participant(attacker,0,"Alex"),new MatchSetup.Participant(target,1,"Luca")),new MatchRules(2,0,5,MatchRules.DropRate.NONE,true,true)));
        try {
            runtime.start();game=runtime.gameplay();game.bodies.respawn(attacker,5.45f,.23f);game.bodies.respawn(target,5.72f,.23f);
            for(int i=0;i<10;i++) { runtime.tick(); }if(hp<100) { game.state.getPlayer(target).applyDamage(100-hp); }
            for(String requested:chain) {
                if(!game.active(attacker) || !game.active(target)) { break; }
                for(int i=0;i<100 && (game.fighter.fighters.get(attacker).attack!=null || game.fighter.fighters.get(attacker).hitstun>0);i++) { step(null); }
                boolean gc=requested.startsWith("GC ");String move=gc?requested.substring(3):requested;
                if(gc) {
                    if(game.jumps.getJumpState(attacker).grounded()) { step(command(0,true,false,false,false,false)); }
                    step(command(0,false,false,false,false,true));
                }
                boolean aerial=FighterMoves.get(move).requiredState().equals("Airborne");
                float axis=move.startsWith("S")?1:0;boolean down=move.startsWith("D") || move.equals("GROUND_POUND");
                step(command(axis,!gc && aerial && game.jumps.getJumpState(attacker).grounded(),!FighterMoves.get(move).heavy(),FighterMoves.get(move).heavy(),down,false));
                // Release charge without creating another edge; actual startup/recovery decides when the next action is possible.
                step(command(0,false,false,false,false,false));
            }
            for(int i=0;i<100 && !runtime.isFinished();i++) { step(null); }
            out.set("hits",hits);out.put("landedHits",hits.size());out.put("finalTargetHP",game.state.getPlayer(target).getHp());
            boolean complete=hits.size()==chain.size();
            for(int i=0;i<Math.min(hits.size(),chain.size());i++) { complete&=hits.get(i).path("move").asText().equals(chain.get(i).replace("GC ","")); }
            boolean zero=complete && hits.size()>=2;for(int i=1;i<hits.size();i++) { zero&=hits.get(i).path("combo").asText().equals("ZERO_WINDOW"); }
            out.put("completedRequestedChain",complete).put("observedZeroWindowInThisFixture",zero);return out;
        } finally { runtime.close(); }
    }
    private PlayerInput command(float x,boolean jump,boolean light,boolean heavy,boolean down,boolean dodge) {
        return new PlayerInput(attacker,sequence++,sequence,x,jump,false,light,false,false,false,false,new FighterInput(down,jump,false,heavy,false,dodge,false,false));
    }
    private void step(PlayerInput input) {
        if(runtime.isFinished()) { return; }
        if(input!=null) { runtime.submitInput(input); }
        if(evade && lastHit>0 && game.active(target)) { runtime.submitInput(new PlayerInput(target,targetSequence++,targetSequence,0,false,false,false,false,false,false,false,new FighterInput(false,false,false,false,false,true,false,false))); }
        runtime.tick();var f=game.snapshot().fighter().player(target.value());
        if(f.detail().hitSerial()>lastHit) {
            lastHit=f.detail().hitSerial();var a=game.bodies.getPosition(attacker);var b=game.bodies.getPosition(target);
            hits.addObject().put("tick",runtime.getMatchState().getServerTick()).put("move",game.progress.get(target).lastWeapon)
                    .put("hp",game.state.getPlayer(target).getHp()).put("hitstun",f.hitstunTicks()).put("dodgeFrames",f.detail().dodgeFrames()).put("combo",f.detail().combo())
                    .put("attackerX",a.xMeters()).put("attackerY",a.yMeters()).put("targetX",b.xMeters()).put("targetY",b.yMeters());
        }
    }
    public static void main(String[] args) throws Exception {
        Path output=Path.of(args.length==0?"build/gameplay/unarmed-combos.json":args[0]).toAbsolutePath();
        var root=ProtocolV1.object();root.put("model","HP prototype; not official Brawlhalla balance").put("classification","UNVERIFIED; observations apply only to their fixture");
        var catalogue=root.putArray("catalogue");for(var chain:CASES) { var row=catalogue.addObject();row.put("status","UNVERIFIED");var list=row.putArray("moves");chain.forEach(list::add); }
        var results=root.putArray("results");var runner=new UnarmedComboTraining();
        for(var chain:CASES) { for(int hp:new int[]{100,75,50,25}) { for(boolean dodge:new boolean[]{false,true}) { results.add(runner.scenario(chain,hp,dodge)); } } }
        Files.createDirectories(output.getParent());Files.writeString(output,root.toPrettyString(),StandardCharsets.UTF_8);
        System.out.println("UNARMED TRAINING PASS: "+results.size()+" native fixtures -> "+output);
    }
}

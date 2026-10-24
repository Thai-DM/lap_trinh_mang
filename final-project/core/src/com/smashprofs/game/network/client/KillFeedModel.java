package com.smashprofs.game.network.client;

import com.smashprofs.common.model.MatchEvent;
import java.util.*;
import java.util.function.LongSupplier;

/** Reliable sequence deduplication and a four-second render-only feed, independent of snapshots. */
public final class KillFeedModel {
    private record Timed(MatchEvent event,long received) { }
    public record Entry(MatchEvent event,float alpha) { }
    private final ArrayDeque<Timed> entries=new ArrayDeque<>();
    private final LongSupplier clock;
    private long sequence;
    public KillFeedModel() { this(System::nanoTime); }
    public KillFeedModel(LongSupplier clock) { this.clock=Objects.requireNonNull(clock); }
    public synchronized boolean accept(MatchEvent event) {
        if(event.sequence()<=sequence) { return false; }
        if(event.sequence()!=sequence+1 || event.sequence()>128 || event.tick()<0
                || !Set.of("PLAYER_KILLED","PLAYER_FELL","PLAYER_RESPAWNED","PLAYER_ELIMINATED","WEAPON_KILL","MATCH_END").contains(event.type())) { throw new IllegalArgumentException("Invalid combat event sequence/type"); }
        sequence=event.sequence();
        // PLAYER_KILLED/FELL already carries the weapon; WEAPON_KILL is retained in the reliable log sequence without a second death line.
        if(!event.type().equals("WEAPON_KILL")) {
            if(entries.size()==12) { entries.removeFirst(); }
            entries.addLast(new Timed(event,clock.getAsLong()));
        }
        return true;
    }
    public synchronized List<Entry> entries() {
        long now=clock.getAsLong();entries.removeIf(e->now-e.received>=4_000_000_000L);
        return entries.stream().map(e->new Entry(e.event,(float)Math.max(0,Math.min(1,(4_000_000_000L-(now-e.received))/1e9)))).toList();
    }
    public synchronized long sequence() { return sequence; }
    public synchronized void clear() { entries.clear(); }
}

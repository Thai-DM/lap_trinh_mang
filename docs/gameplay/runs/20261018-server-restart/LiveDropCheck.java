import com.smashprofs.game.network.client.*;
import com.smashprofs.common.model.MatchRules;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Two isolated real clients of the running server; no fixture injection or manual ticks. */
public final class LiveDropCheck {
    private static void until(BooleanSupplier condition,long millis) throws Exception {
        long end=System.nanoTime()+millis*1_000_000;
        while(!condition.getAsBoolean()) {
            if(System.nanoTime()>end) { throw new IllegalStateException("Timed out waiting for live server"); }
            Thread.sleep(10);
        }
    }
    public static void main(String[] args) throws Exception {
        String room="dropcheck_"+UUID.randomUUID().toString().replace("-","");
        var clients=new ArrayList<LobbyClient>();
        try {
            for(int i=0;i<2;i++) {
                var c=new LobbyClient(new LobbyConfiguration("127.0.0.1",54555,room,i==0?LobbyConfiguration.Mode.CREATE:LobbyConfiguration.Mode.JOIN));
                c.enableFighter();clients.add(c);c.startAsync();until(()->c.getSnapshot().canReady(),5000);
            }
            until(()->clients.stream().allMatch(c->c.getSettings()!=null && c.getSettings().playerCount()==2),5000);
            var rules=clients.get(0).getSettings().rules();
            System.out.println("LIVE_RULES fighter="+rules.fighter()+" drops="+rules.dropRate());
            if(!rules.fighter() || rules.dropRate()!=MatchRules.DropRate.MEDIUM) { throw new IllegalStateException("Unexpected default rules; server may still be old"); }
            for(var c:clients) { if(!c.ready("Alex")) { throw new IllegalStateException("Cannot ready"); } }
            until(()->clients.stream().allMatch(c->c.getSnapshot().phase()==LobbyClient.Phase.WAITING_FOR_START),5000);
            for(var c:clients) { c.markLoaded(); }
            until(()->clients.stream().allMatch(c->c.getMatchSession()!=null && c.getMatchSession().getSnapshotBuffer().getLatestSnapshot()!=null),7000);
            var session=clients.get(0).getMatchSession();
            until(()->!session.getSnapshotBuffer().getLatestSnapshot().gameplay().items().isEmpty(),25000);
            var first=session.getSnapshotBuffer().getLatestSnapshot();
            if(first.serverTick()<900) { throw new IllegalStateException("Item spawned earlier than configured"); }
            System.out.println("LIVE_DROP tick="+first.serverTick()+" items="+first.gameplay().items());
            until(()->clients.stream().allMatch(c->!c.getMatchSession().getSnapshotBuffer().getLatestSnapshot().gameplay().items().isEmpty()),2000);
            System.out.println("LIVE_DROPS_PASS realTcpPort=54555 clients=2 automaticTicks=true");
        } finally { for(var c:clients) { c.closeAndWait(5000); } }
    }
}

package com.smashprofs.game.profile;

import com.badlogic.gdx.*;
import com.smashprofs.common.protocol.ProtocolV1;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;

/** Real profile logic backed by isolated Preferences, never the user's saved profile. */
public class PlayerProfilePersistenceTest {
    private Application previous;
    private final Map<String,Map<String,String>> saved=new HashMap<>();
    private static final String ID="guest_00000000-0000-4000-a000-000000000001",TOKEN="a".repeat(64);
    @Before public void setup() {
        previous=Gdx.app;
        Gdx.app=(Application)Proxy.newProxyInstance(Application.class.getClassLoader(),new Class<?>[]{Application.class},(p,m,a)->{
            if(!m.getName().equals("getPreferences")) { throw new UnsupportedOperationException(m.getName()); }
            var values=saved.computeIfAbsent((String)a[0],k->new HashMap<>());
            return Proxy.newProxyInstance(Preferences.class.getClassLoader(),new Class<?>[]{Preferences.class},(proxy,method,args)->{
                switch(method.getName()) {
                    case "getString":return values.getOrDefault((String)args[0],args.length==2?(String)args[1]:"");
                    case "putString":values.put((String)args[0],(String)args[1]);return proxy;
                    case "clear":values.clear();return null;
                    case "flush":return null;
                    default:throw new UnsupportedOperationException(method.getName());
                }
            });
        });
    }
    @After public void restore() { Gdx.app=previous; }
    @Test public void newGuestRequestsIdentityWithoutClaimingLocalId() {
        var profile=new PlayerProfile();var request=ProtocolV1.object();profile.prepareConnection(request,"wss://game.test/ws",8090);
        assertTrue(request.path("guestProfile").asBoolean());assertFalse(request.has("profileToken"));
    }
    @Test public void reloadKeepsCredentialsNameAndAvatar() {
        var profile=new PlayerProfile();profile.setDisplayName("Nguyễn Việt");profile.setAvatar("Luca");profile.save();
        profile.acceptConnection(ProtocolV1.object().put("profileId",ID).put("profileToken",TOKEN),"wss://game.test/ws",8090);
        var reloaded=new PlayerProfile();assertEquals("Nguyễn Việt",reloaded.getDisplayName());assertEquals("Luca",reloaded.getAvatar());
        var request=ProtocolV1.object();reloaded.prepareConnection(request,"wss://game.test/ws",54555);
        assertEquals(TOKEN,request.path("profileToken").asText());assertEquals(ID,reloaded.getServerProfileId("wss://game.test/ws",8090));
    }
    @Test public void credentialNeverLeaksToAnotherServer() {
        var profile=new PlayerProfile();profile.acceptConnection(ProtocolV1.object().put("profileId",ID).put("profileToken",TOKEN),"wss://game.test/ws",8090);
        var request=ProtocolV1.object();profile.prepareConnection(request,"wss://other.test/ws",8090);assertFalse(request.has("profileToken"));
        profile.acceptConnection(ProtocolV1.object().put("profileId",ID).put("profileToken",TOKEN),"127.0.0.1",54555);
        request=ProtocolV1.object();profile.prepareConnection(request,"127.0.0.1",54556);assertFalse(request.has("profileToken"));
    }
    @Test public void malformedAckCannotOverwriteSavedCredential() {
        var profile=new PlayerProfile();profile.acceptConnection(ProtocolV1.object().put("profileId",ID).put("profileToken",TOKEN),"host",54555);
        try { profile.acceptConnection(ProtocolV1.object().put("profileId",ID).put("profileToken","bad"),"host",54555);fail(); }
        catch(IllegalArgumentException expected) { }
        var request=ProtocolV1.object();profile.prepareConnection(request,"host",54555);assertEquals(TOKEN,request.path("profileToken").asText());
    }
    @Test public void explicitNewProfileClearsSavedGuestCredential() {
        var profile=new PlayerProfile();profile.acceptConnection(ProtocolV1.object().put("profileId",ID).put("profileToken",TOKEN),"host",54555);profile.resetToNew();
        var request=ProtocolV1.object();new PlayerProfile().prepareConnection(request,"host",54555);assertFalse(request.has("profileToken"));
    }
    @Test public void longDisplayNameIsTruncatedOnSetAndReload() {
        var profile=new PlayerProfile();profile.setDisplayName("ChampionAlexander");
        assertEquals("ChampionAlex",profile.getDisplayName());
        profile.save();
        var reloaded=new PlayerProfile();assertEquals("ChampionAlex",reloaded.getDisplayName());
    }
}

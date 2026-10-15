package com.smashprofs.server.cluster;

import org.junit.Test;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class HttpJsonTest {
    @Test public void responseIsReadableBeforeDeferredShutdown() throws Exception {
        var accepted=new CountDownLatch(1); var holder=new HttpJson[1];
        try(var http=new HttpJson(0,(path,body,credential) -> new HttpJson.AfterResponse(Map.of("stopping",true),() -> {
            holder[0].close(); accepted.countDown();
        }))) {
            holder[0]=http;
            var response=HttpJson.call("http://127.0.0.1:"+http.port(),"/shutdown",Map.of(),null);
            assertTrue(response.path("stopping").asBoolean()); assertTrue(accepted.await(3,TimeUnit.SECONDS));
        }
    }
    @Test public void internalEndpointRejectsMissingSecret() throws Exception {
        try(var http=new HttpJson(0,(path,body,credential) -> {
            HttpJson.requireSecret(credential,"secret"); return Map.of("ok",true);
        })) {
            try { HttpJson.call("http://127.0.0.1:"+http.port(),"/internal",Map.of(),null); fail(); }
            catch(IOException expected) { assertTrue(expected.getMessage().contains("401")); }
        }
    }
}

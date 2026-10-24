package com.smashprofs.server.cluster;

import com.smashprofs.common.protocol.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Real entrypoint in a separate JVM, with no assets/current-directory dependency. */
public class StandaloneDeploymentTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private static final String SECRET="deployment-test-secret-01234567890123456789";
    @Test(timeout=20000) public void publicBindAcceptsTwoClientsOnAvailableNonLoopbackAddress() throws Exception { verify("0.0.0.0",externalAddress()); }
    @Test(timeout=20000) public void omittedBindKeepsLocalConfigurationWorking() throws Exception { verify(null,"127.0.0.1"); }

    private static String externalAddress() throws Exception {
        for(var nic:Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if(!nic.isUp() || nic.isLoopback()) { continue; }
            for(var address:Collections.list(nic.getInetAddresses())) {
                if(address instanceof Inet4Address && !address.isLoopbackAddress() && !address.isLinkLocalAddress()) { return address.getHostAddress(); }
            }
        }
        return "127.0.0.1";
    }
    private void verify(String bind,String address) throws Exception {
        var directory=temporary.newFolder().toPath();int admin;
        try(var available=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) { admin=available.getLocalPort(); }
        var config=new Properties();config.setProperty("tcp.port","0");config.setProperty("admin.port",Integer.toString(admin));
        config.setProperty("max.connections","8");config.setProperty("history.dir","run/history");
        if(bind!=null) { config.setProperty("tcp.host",bind); }
        var file=directory.resolve("server.properties");try(var out=Files.newOutputStream(file)) { config.store(out,"test"); }
        var output=directory.resolve("server.log");
        String executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        String classpath=Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                .map(p->Path.of(p).toAbsolutePath().toString()).collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
        var builder=new ProcessBuilder(executable,"-Xmx128m","-cp",classpath,ClusterMain.class.getName(),"standalone",file.toString());
        builder.directory(directory.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().put("SMASHPROFS_CLUSTER_SECRET",SECRET);
        var process=builder.start();
        try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);int port=0;
            while(port==0 && process.isAlive() && System.nanoTime()<deadline) {
                if(Files.exists(output)) { for(var line:Files.readAllLines(output)) {
                    if(line.startsWith("STANDALONE_READY ")) { port=Integer.parseInt(line.substring(17).strip()); }
                } }
                if(port==0) { Thread.sleep(20); }
            }
            assertTrue("Startup failed: "+Files.readString(output),port>0);
            try(var a=new Socket(address,port);var b=new Socket(address,port)) {
                a.setSoTimeout(5000);b.setSoTimeout(5000);
                var first=request(a,MessageType.CONNECT,ProtocolV1.object());
                var second=request(b,MessageType.CONNECT,ProtocolV1.object());
                assertEquals(MessageType.CONNECT_ACK,first.type());assertEquals(MessageType.CONNECT_ACK,second.type());
                assertNotEquals(first.payload().path("playerId"),second.payload().path("playerId"));
                assertNotEquals(MessageType.ERROR,request(a,MessageType.CREATE_ROOM,ProtocolV1.object().put("roomId","WAN1")).type());
                assertNotEquals(MessageType.ERROR,request(a,MessageType.JOIN_ROOM,ProtocolV1.object().put("roomId","WAN1")).type());
                assertNotEquals(MessageType.ERROR,request(b,MessageType.JOIN_ROOM,ProtocolV1.object().put("roomId","WAN1")).type());
            }
            assertTrue(HttpJson.call("http://127.0.0.1:"+admin,"/metrics",Map.of(),null).path("workers").isArray());
            HttpJson.call("http://127.0.0.1:"+admin,"/admin/shutdown",Map.of(),SECRET);
            assertTrue("Shutdown timed out",process.waitFor(5,TimeUnit.SECONDS));assertEquals(0,process.exitValue());
            System.out.println("PASS standalone bind="+(bind==null?"default":bind)+" connect="+address+" two-client room WAN1");
        } finally {
            if(process.isAlive()) { process.destroy();if(!process.waitFor(3,TimeUnit.SECONDS)) { process.destroyForcibly();process.waitFor(3,TimeUnit.SECONDS); } }
        }
    }
    private static ProtocolMessage request(Socket socket,MessageType type,ObjectNode payload) throws Exception {
        var codec=new JsonFrameCodec();String requestId=UUID.randomUUID().toString();
        socket.getOutputStream().write(codec.encode(new ProtocolMessage(type,requestId,payload)));socket.getOutputStream().flush();
        for(int i=0;i<32;i++) {
            var message=codec.readNext(socket.getInputStream());assertNotNull(message);
            if(requestId.equals(message.requestId())) { return message; }
        }
        throw new AssertionError("Missing reply: "+type);
    }
}

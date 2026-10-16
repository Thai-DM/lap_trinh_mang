package com.smashprofs.server.network;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GameServerTest {
    private GameServer server;

    @Before
    public void setUp() {
        server = new GameServer();
    }

    @After
    public void tearDown() {
        // Server đã sở hữu simulation thread thật; luôn cleanup kể cả khi assertion thất bại.
        server.stop();
    }

    @Test
    public void newServerIsStopped() {
        assertFalse(server.isRunning());
    }

    @Test
    public void serverStartsSuccessfully() {
        server.start();

        assertTrue(server.isRunning());
    }

    @Test
    public void startingServerTwiceIsSafe() {
        server.start();
        server.start();

        assertTrue(server.isRunning());
    }

    @Test
    public void serverStopsSuccessfully() {
        server.start();
        server.stop();

        assertFalse(server.isRunning());
    }

    @Test
    public void stoppingServerTwiceIsSafe() {
        server.start();
        server.stop();
        server.stop();

        assertFalse(server.isRunning());
    }

    @Test
    public void serverCanRestartAfterStop() {
        server.start();
        server.stop();
        server.start();

        assertTrue(server.isRunning());
    }
}

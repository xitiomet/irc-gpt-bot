package org.openstatic;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.websocket.api.Session;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class WebSocketHeartbeatTest
{
    @Test
    void authenticatedHeartbeatsKeepAnOtherwiseIdleConnectionOpen() throws Exception
    {
        JSONObject previousSettings = IRCGPTBotMain.settings;
        APIWebServer previousInstance = APIWebServer.instance;
        APIWebServer server = null;
        WebSocket client = null;
        try
        {
            HttpClient http = HttpClient.newHttpClient();
            IRCGPTBotMain.settings = new JSONObject().put("apiPort", 0).put("apiPassword", "test-password");
            server = new APIWebServer()
            {
                @Override
                public void shareBots(Session session)
                {
                    session.setIdleTimeout(Duration.ofMillis(1000));
                }
            };
            server.setState(true);
            assertTrue(server.isRunning());
            Field field = APIWebServer.class.getDeclaredField("httpServer");
            field.setAccessible(true);
            int port = ((ServerConnector) ((Server) field.get(server)).getConnectors()[0]).getLocalPort();
            BlockingQueue<String> messages = new LinkedBlockingQueue<>();
            client = http.newWebSocketBuilder().buildAsync(URI.create("ws://localhost:" + port + "/ircgptbot/"),
                new WebSocket.Listener()
                {
                    private final StringBuilder text = new StringBuilder();

                    @Override
                    public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last)
                    {
                        text.append(data);
                        if (last)
                        {
                            messages.add(text.toString());
                            text.setLength(0);
                        }
                        socket.request(1);
                        return null;
                    }
                }).get(5, TimeUnit.SECONDS);
            client.sendText("{\"command\":\"ping\"}", true).get(5, TimeUnit.SECONDS);
            JSONObject denied = new JSONObject(messages.poll(5, TimeUnit.SECONDS));
            assertTrue(denied.has("error"));
            client.sendText("{\"command\":\"auth\",\"password\":\"test-password\"}", true).get(5, TimeUnit.SECONDS);
            assertEquals("authOk", new JSONObject(messages.poll(5, TimeUnit.SECONDS)).getString("action"));
            long start = System.nanoTime();
            for (int i = 0; i < 8; i++)
            {
                Thread.sleep(250);
                client.sendText("{\"command\":\"ping\"}", true).get(5, TimeUnit.SECONDS);
                assertEquals("pong", new JSONObject(messages.poll(5, TimeUnit.SECONDS)).getString("action"));
            }
            assertTrue(System.nanoTime() - start > Duration.ofMillis(1000).toNanos());
            assertFalse(client.isInputClosed());
            client.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").get(5, TimeUnit.SECONDS);
        } finally {
            if (client != null)
                client.abort();
            if (server != null)
                server.setState(false);
            IRCGPTBotMain.settings = previousSettings;
            APIWebServer.instance = previousInstance;
        }
    }
}

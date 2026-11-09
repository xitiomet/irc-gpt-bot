package org.openstatic;

import static org.junit.jupiter.api.Assertions.*;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class APIWebServerRestartTest
{
    @Test
    void serverCanBeStoppedAndReplaced()
    {
        JSONObject previous = IRCGPTBotMain.settings;
        APIWebServer previousInstance = APIWebServer.instance;
        try
        {
            IRCGPTBotMain.settings = new JSONObject().put("apiPort", 0);
            APIWebServer first = new APIWebServer();
            first.setState(true);
            assertTrue(first.isRunning());
            first.setState(false);
            assertFalse(first.isRunning());
            APIWebServer second = new APIWebServer();
            second.setState(true);
            assertTrue(second.isRunning());
            second.setState(false);
        } finally {
            IRCGPTBotMain.settings = previous;
            APIWebServer.instance = previousInstance;
        }
    }
}

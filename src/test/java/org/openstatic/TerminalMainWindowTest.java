package org.openstatic;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.net.ServerSocket;
import java.nio.file.Path;

import com.googlecode.lanterna.gui2.Border;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.Component;
import com.googlecode.lanterna.gui2.Panel;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TerminalMainWindowTest
{
    @TempDir Path temporary;

    @Test
    void browserButtonIsBelowLoadedBotsOnlyWhileApiServerIsRunning() throws Exception
    {
        JSONObject previousSettings = IRCGPTBotMain.settings;
        File previousSettingsFile = IRCGPTBotMain.settingsFile;
        File previousLogs = IRCGPTBotMain.logsFolder;
        ConfigDirectory previousConfig = IRCGPTBotMain.configDirectory;
        UsageTracker previousUsage = IRCGPTBotMain.usageTracker;
        IRCGPTBotMain previousMain = IRCGPTBotMain.instance;
        APIWebServer previousApi = APIWebServer.instance;
        try
        {
            IRCGPTBotMain.settings = new JSONObject().put("headless", true).put("apiServer", false)
                .put("apiPassword", "test-password").put("bots", new JSONObject());
            IRCGPTBotMain.configDirectory = new ConfigDirectory(temporary.resolve("config"));
            IRCGPTBotMain.settingsFile = temporary.resolve("config/irc-gpt-bot.json").toFile();
            IRCGPTBotMain main = new IRCGPTBotMain();
            Panel panel = (Panel) main.getComponent();
            assertEquals(1, panel.getChildCount());
            assertInstanceOf(Border.class, panel.getChildren().iterator().next());
            assertFalse(main.isApiServerRunning());
            int port;
            try (ServerSocket socket = new ServerSocket(0))
            {
                port = socket.getLocalPort();
            }
            main.updateApiSettings(true, port, "");
            assertTrue(main.isApiServerRunning());
            assertEquals(2, panel.getChildCount());
            Component[] children = panel.getChildren().toArray(Component[]::new);
            assertInstanceOf(Border.class, children[0]);
            assertEquals("Open Web Interface", ((Button) children[1]).getLabel());
            main.updateApiSettings(true, port, "");
            assertEquals(2, panel.getChildCount(), "Restarting must not duplicate the button");
            main.updateApiSettings(false, port, "");
            assertFalse(main.isApiServerRunning());
            assertEquals(1, panel.getChildCount());
        } finally {
            if (APIWebServer.instance != null && APIWebServer.instance != previousApi)
                APIWebServer.instance.setState(false);
            IRCGPTBotMain.settings = previousSettings;
            IRCGPTBotMain.settingsFile = previousSettingsFile;
            IRCGPTBotMain.logsFolder = previousLogs;
            IRCGPTBotMain.configDirectory = previousConfig;
            IRCGPTBotMain.usageTracker = previousUsage;
            IRCGPTBotMain.instance = previousMain;
            APIWebServer.instance = previousApi;
        }
    }
}

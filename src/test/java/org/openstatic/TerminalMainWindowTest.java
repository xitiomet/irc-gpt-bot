package org.openstatic;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.net.ServerSocket;
import java.nio.file.Path;

import com.googlecode.lanterna.gui2.Border;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.Component;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
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
        try (DefaultVirtualTerminal terminal = new DefaultVirtualTerminal(new TerminalSize(80, 24));
             TerminalScreen screen = new TerminalScreen(terminal))
        {
            IRCGPTBotMain.settings = new JSONObject().put("headless", true).put("apiServer", false)
                .put("apiPassword", "test-password").put("bots", new JSONObject());
            IRCGPTBotMain.configDirectory = new ConfigDirectory(temporary.resolve("config"));
            IRCGPTBotMain.settingsFile = temporary.resolve("config/irc-gpt-bot.json").toFile();
            IRCGPTBotMain main = new IRCGPTBotMain();
            screen.startScreen();
            main.gui = new MultiWindowTextGUI(screen);
            main.gui.addWindow(main);
            Panel panel = (Panel) main.getComponent();
            assertEquals(1, panel.getChildCount());
            Thread urlsEditor = new Thread(() -> TerminalMasterConfig.editApiUrls(main));
            urlsEditor.start();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (main.gui.getWindows().size() < 2 && System.nanoTime() < deadline)
                Thread.sleep(10);
            assertEquals(2, main.gui.getWindows().size());
            Panel urlsPanel = (Panel) main.gui.getActiveWindow().getComponent();
            java.util.List<com.googlecode.lanterna.gui2.TextBox> inputs = new java.util.ArrayList<>();
            Button save = null;
            for (Component child : urlsPanel.getChildren())
            {
                if (child instanceof com.googlecode.lanterna.gui2.TextBox)
                    inputs.add((com.googlecode.lanterna.gui2.TextBox) child);
                if (child instanceof Button && "Save".equals(((Button) child).getLabel()))
                    save = (Button) child;
            }
            assertEquals(3, inputs.size());
            inputs.get(0).setText("https://terminal.example/models");
            inputs.get(1).setText("https://terminal.example/responses");
            assertNotNull(save);
            main.gui.getActiveWindow().setFocusedInteractable(save);
            main.gui.handleInput(new com.googlecode.lanterna.input.KeyStroke(com.googlecode.lanterna.input.KeyType.Enter));
            urlsEditor.join(5000);
            assertFalse(urlsEditor.isAlive());
            assertEquals("https://terminal.example/responses", ChatGPT.resolveResponsesApiUrl(new JSONObject()));
            assertEquals("https://terminal.example/models", JsonFiles.read(temporary.resolve("config/irc-gpt-bot.json"))
                .getString("modelsApiUrl"));
            assertInstanceOf(Border.class, panel.getChildren().iterator().next());
            assertFalse(main.isApiServerRunning());
            int port;
            try (ServerSocket socket = new ServerSocket(0))
            {
                port = socket.getLocalPort();
            }
            main.updateApiSettings(true, port, "");
            main.gui.updateScreen();
            assertTrue(main.isApiServerRunning());
            assertEquals(2, panel.getChildCount());
            Component[] children = panel.getChildren().toArray(Component[]::new);
            assertInstanceOf(Border.class, children[0]);
            assertEquals("Open Web Interface", ((Button) children[1]).getLabel());
            assertTrue(children[1].getSize().getColumns() > 0);
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

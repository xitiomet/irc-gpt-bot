package org.openstatic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.AsynchronousTextGUIThread;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.Component;
import com.googlecode.lanterna.gui2.ComboBox;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.Container;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.SeparateTextGUIThread;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;

class TerminalInterfaceTest
{
    @TempDir Path temporary;

    private TerminalBotEditor editor()
    {
        JSONObject options = BotEditorOptions.defaults("terminal-bot", true).put("server", "irc.example.net");
        return new TerminalBotEditor(options, "gpt-4o-mini", "Terminal prompt", false);
    }

    @Test
    void serializesWebEquivalentOptionsAndNeverPrefillsSavedPasswords()
    {
        JSONObject settings = new JSONObject().put("server", "irc.example.net").put("password", "saved-password")
            .put("operatorPassword", "saved-oper").put("aliases", new JSONArray().put("rat catcher"));
        TerminalBotEditor editor = new TerminalBotEditor(BotEditorOptions.view(settings, "terminal-bot"), "gpt-4o-mini", "Prompt", false);
        assertEquals("", editor.text.get("password").getText());
        assertEquals('*', editor.text.get("password").getMask());
        assertEquals('*', editor.text.get("operatorPassword").getMask());
        assertEquals("", editor.text.get("operatorPassword").getText());
        editor.text.get("aliases").setText(" rat catcher , helper bot\nrat catcher");
        editor.text.get("ignore").setText("one two,three");
        editor.text.get("operatorName").setText("oper");
        editor.text.get("operatorPassword").setText("replacement");
        editor.text.get("port").setText("6697");
        editor.checks.get("secure").setChecked(true);
        editor.checks.get("clearPassword").setChecked(true);
        editor.checks.get("reconnect").setChecked(true);
        editor.tools.values().forEach(control -> control.setChecked(false));
        editor.tools.get(GPTTools.SET_USER_MODE).setChecked(true);
        JSONObject update = IRCGPTBot.validateEditorUpdate(editor.state());
        assertEquals("[\"rat catcher\",\"helper bot\"]", update.getJSONArray("aliases").toString());
        assertEquals("[\"one\",\"two\",\"three\"]", update.getJSONArray("ignore").toString());
        assertEquals(6697, update.getInt("port"));
        assertTrue(update.getBoolean("secure"));
        assertTrue(update.isNull("password"));
        assertEquals("replacement", update.getString("operatorPassword"));
        assertEquals("[\"set_user_mode\"]", update.getJSONArray("gptTools").toString());
        assertTrue(editor.state().getBoolean("reconnect"));
        assertFalse(settings.toString().contains("replacement"));
    }

    @Test
    void invalidTerminalNumericAndSecretInputCannotBeSaved()
    {
        TerminalBotEditor editor = editor();
        for (String value : new String[] {"", "abc", "1.5", "65536", "-1"})
        {
            editor.text.get("port").setText(value);
            assertThrows(IllegalArgumentException.class, () -> IRCGPTBot.validateEditorUpdate(editor.state()), value);
        }
        editor.text.get("port").setText("6667");
        editor.text.get("password").setText("replacement");
        editor.checks.get("clearPassword").setChecked(true);
        assertThrows(IllegalArgumentException.class, () -> IRCGPTBot.validateEditorUpdate(editor.state()));
    }

    @Test
    void launchDefaultsMatchWebAndRequireAnExplicitModel()
    {
        TerminalBotEditor launch = new TerminalBotEditor(BotEditorOptions.defaults("new-bot", true), "", "", true);
        assertThrows(IllegalArgumentException.class, () -> IRCGPTBot.validateEditorUpdate(launch.state()));
        launch.text.get("model").setText("gpt-4.1-mini");
        launch.text.get("server").setText("irc.example.net");
        JSONObject update = IRCGPTBot.validateEditorUpdate(launch.state());
        assertEquals("gpt-4.1-mini", update.getString("model"));
        assertTrue(update.getBoolean("enabled"));
        assertTrue(update.getBoolean("privateMessages"));
        assertEquals(GPTTools.ALL_TOOLS.length, update.getJSONArray("gptTools").length());
        assertFalse(update.has("password"));
        assertEquals(15, update.getInt("contextDepth"));
    }

    @Test
    void channelSnapshotUpdatesTopicRosterAndMessagesWithoutMovingReaders()
    {
        TerminalChannelView view = new TerminalChannelView();
        JSONObject snapshot = new JSONObject().put("topic", "Original topic").put("rosterComplete", true)
            .put("roster", new JSONArray().put(new JSONObject().put("nickname", "Charlie").put("prefix", "@")))
            .put("messages", new JSONArray().put(new JSONObject().put("sender", "Charlie").put("body", "Hello").put("timestamp", 0)));
        view.update(snapshot);
        assertEquals("Original topic", view.topic.getText());
        assertTrue(view.roster.getText().contains("@Charlie"));
        assertTrue(view.messages.getText().contains("<Charlie> Hello"));
        for (int i = 0; i < 25; i++)
            snapshot.getJSONArray("messages").put(new JSONObject().put("sender", "Charlie").put("body", "Line " + i).put("timestamp", i));
        view.update(snapshot);
        assertTrue(view.messages.firstVisibleRow() > 0);
        view.messages.scrollToTop();
        snapshot.put("topic", "New topic").put("rosterComplete", false);
        snapshot.getJSONArray("messages").put(new JSONObject().put("sender", "Bob").put("body", "New message").put("timestamp", 1));
        snapshot.put("roster", new JSONArray().put(new JSONObject().put("nickname", "Bob").put("prefix", "+")));
        view.update(snapshot);
        assertEquals("New topic", view.topic.getText());
        assertTrue(view.roster.getText().contains("+Bob"));
        assertFalse(view.roster.getText().contains("Charlie"));
        assertTrue(view.messages.getText().contains("<Bob> New message"));
        assertEquals(0, view.messages.firstVisibleRow());
        assertTrue(view.status.getText().contains("loading"));
    }

    @Test
    @Timeout(20)
    void virtualTerminalReopensSectionsAndSavesOnlyOnExplicitSave() throws Exception
    {
        DefaultVirtualTerminal terminal = new DefaultVirtualTerminal(new TerminalSize(80, 24));
        TerminalScreen screen = new TerminalScreen(terminal);
        screen.startScreen();
        MultiWindowTextGUI gui = new MultiWindowTextGUI(new SeparateTextGUIThread.Factory(), screen);
        AsynchronousTextGUIThread guiThread = (AsynchronousTextGUIThread) gui.getGUIThread();
        guiThread.start();
        TerminalBotEditor editor = editor();
        AtomicReference<JSONObject> saved = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        guiThread.setExceptionHandler(new com.googlecode.lanterna.gui2.TextGUIThread.ExceptionHandler() {
            public boolean onIOException(java.io.IOException e) { failure.set(e); return false; }
            public boolean onRuntimeException(RuntimeException e) { failure.set(e); return false; }
        });
        Thread worker = new Thread(() -> editor.show(gui, "terminal-bot", saved::set));
        worker.start();
        try
        {
            waitFor(() -> gui.getActiveWindow() != null);
            for (String section : new String[] {"IRC Connection", "Timing / Context", "Built-in GPT Tools", "IRC Connection", "Built-in GPT Tools", "Model / Prompt", "Edit aliases"})
            {
                click(gui, terminal, section);
                waitFor(() -> gui.getWindows().size() == 2);
                if ("Timing / Context".equals(section))
                {
                    String fieldLabels = labels(gui.getActiveWindow().getComponent());
                    assertFalse(fieldLabels.contains("port"));
                    for (String field : new String[] {"contextDepth", "followUpSeconds", "replyCooldownSeconds", "openQuestionSeconds", "maxToolRounds"})
                        assertTrue(fieldLabels.contains(field), field);
                }
                if ("IRC Connection".equals(section))
                    assertTrue(labels(gui.getActiveWindow().getComponent()).contains("port"));
                assertTrue(gui.getActiveWindow().getSize().getColumns() <= 78, section + " exceeds terminal width");
                assertTrue(gui.getActiveWindow().getSize().getRows() <= 22, section + " exceeds terminal height");
                assertNull(failure.get());
                assertNull(saved.get());
                click(gui, terminal, "Done");
                waitFor(() -> gui.getWindows().size() == 1);
            }
            click(gui, terminal, "Save");
            worker.join(3000);
            assertFalse(worker.isAlive());
            assertNotNull(saved.get());
            assertEquals("gpt-4o-mini", saved.get().getString("model"));
            assertNull(failure.get());
            saved.set(null);
            Thread cancelled = new Thread(() -> editor().show(gui, "terminal-bot", saved::set));
            cancelled.start();
            waitFor(() -> gui.getActiveWindow() != null);
            click(gui, terminal, "Cancel");
            cancelled.join(3000);
            assertFalse(cancelled.isAlive());
            assertNull(saved.get());
        } finally {
            for (Window window : gui.getWindows().toArray(Window[]::new)) window.close();
            worker.join(1000);
            guiThread.stop();
            screen.stopScreen();
            terminal.close();
        }
    }

    private static Button button(Component component, String label)
    {
        if (component instanceof Button && label.equals(((Button) component).getLabel())) return (Button) component;
        if (component instanceof Container)
            for (Component child : ((Container) component).getChildren())
            {
                Button found = button(child, label);
                if (found != null) return found;
            }
        return null;
    }

    @Test
    @Timeout(20)
    void modelLoadingRemainsResponsiveAndHandlesUnauthorizedEmptyAndClosedSections() throws Exception
    {
        JSONObject previousSettings = IRCGPTBotMain.settings;
        ChatGPT previousChat = IRCGPTBotMain.chatGPT;
        java.io.File previousLogs = IRCGPTBotMain.logsFolder;
        IRCGPTBotMain.settings = new JSONObject().put("openAiKey", "test-only");
        IRCGPTBotMain.logsFolder = temporary.toFile();
        IRCGPTBotMain.chatGPT = new ChatGPT();
        CountDownLatch requested = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger status = new AtomicInteger(200);
        AtomicReference<String> response = new AtomicReference<>("{\"data\":[{\"id\":\"gpt-test\"}]}");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/models", exchange -> {
            if (requests.incrementAndGet() == 1)
            {
                requested.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        DefaultVirtualTerminal terminal = new DefaultVirtualTerminal(new TerminalSize(80, 24));
        TerminalScreen screen = new TerminalScreen(terminal);
        screen.startScreen();
        MultiWindowTextGUI gui = new MultiWindowTextGUI(new SeparateTextGUIThread.Factory(), screen);
        AsynchronousTextGUIThread guiThread = (AsynchronousTextGUIThread) gui.getGUIThread();
        guiThread.start();
        TerminalBotEditor editor = editor();
        editor.text.get("modelsApiUrl").setText("http://127.0.0.1:" + server.getAddress().getPort() + "/models");
        Thread worker = new Thread(() -> editor.show(gui, "terminal-bot", value -> {}));
        worker.start();
        try
        {
            waitFor(() -> gui.getActiveWindow() != null);
            click(gui, terminal, "Model / Prompt");
            waitFor(() -> gui.getWindows().size() == 2);
            Button firstLoad = button(gui.getActiveWindow().getComponent(), "Load available models");
            click(gui, terminal, "Load available models");
            assertTrue(requested.await(3, TimeUnit.SECONDS));
            assertFalse(firstLoad.isEnabled());
            click(gui, terminal, "Done");
            waitFor(() -> gui.getWindows().size() == 1);
            release.countDown();
            waitFor(firstLoad::isEnabled);
            assertEquals(1, gui.getWindows().size(), "Closed section must not open a stale model picker");
            click(gui, terminal, "Model / Prompt");
            waitFor(() -> gui.getWindows().size() == 2);
            click(gui, terminal, "Load available models");
            waitFor(() -> gui.getWindows().size() == 3);
            ComboBox<?> models = (ComboBox<?>) ((Container) gui.getActiveWindow().getComponent()).getChildren().iterator().next();
            assertEquals("gpt-4o-mini", models.getSelectedItem());
            models.setSelectedIndex(0);
            assertEquals("gpt-test", models.getSelectedItem());
            click(gui, terminal, "Use selected model");
            waitFor(() -> gui.getWindows().size() == 2);
            assertEquals("gpt-test", editor.text.get("model").getText());
            status.set(401);
            Button load = button(gui.getActiveWindow().getComponent(), "Load available models");
            click(gui, terminal, "Load available models");
            waitFor(() -> labels(gui.getActiveWindow().getComponent()).contains("401"));
            waitFor(load::isEnabled);
            assertEquals(2, gui.getWindows().size(), "HTTP errors must not open blocking dialogs");
            status.set(200);
            response.set("{\"data\":[]}");
            click(gui, terminal, "Load available models");
            waitFor(() -> labels(gui.getActiveWindow().getComponent()).contains("No models returned"));
            waitFor(load::isEnabled);
            assertEquals("gpt-test", editor.text.get("model").getText());
            click(gui, terminal, "Done");
            waitFor(() -> gui.getWindows().size() == 1);
        } finally {
            release.countDown();
            for (Window window : gui.getWindows().toArray(Window[]::new)) window.close();
            worker.join(1000);
            guiThread.stop();
            screen.stopScreen();
            terminal.close();
            server.stop(0);
            IRCGPTBotMain.chatGPT.shutdown();
            IRCGPTBotMain.chatGPT = previousChat;
            IRCGPTBotMain.settings = previousSettings;
            IRCGPTBotMain.logsFolder = previousLogs;
        }
    }

    private static String labels(Component component)
    {
        StringBuilder text = new StringBuilder(component instanceof Label ? ((Label) component).getText() : "");
        if (component instanceof Container)
            for (Component child : ((Container) component).getChildren()) text.append(labels(child));
        return text.toString();
    }

    private static void click(MultiWindowTextGUI gui, DefaultVirtualTerminal terminal, String label)
    {
        Button button = button(gui.getActiveWindow().getComponent(), label);
        assertNotNull(button, label);
        gui.getActiveWindow().setFocusedInteractable(button);
        terminal.addInput(new KeyStroke(KeyType.Enter));
    }

    private static void waitFor(BooleanSupplier condition) throws InterruptedException
    {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Timed out waiting for terminal UI");
    }
}

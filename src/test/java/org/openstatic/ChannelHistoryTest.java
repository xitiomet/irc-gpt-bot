package org.openstatic;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChannelHistoryTest
{
    @TempDir Path temporary;
    private File previousLogs;
    private IRCGPTBot bot;

    @BeforeEach
    void setup()
    {
        previousLogs = IRCGPTBotMain.logsFolder;
        IRCGPTBotMain.logsFolder = temporary.toFile();
        bot = new IRCGPTBot("history-bot", new JSONObject().put("enabled", false));
    }

    @AfterEach
    void cleanup() { IRCGPTBotMain.logsFolder = previousLogs; }

    private Path log() { return bot.getLogFolder().toPath().resolve("#lobby.log"); }
    private String entry(LocalDate day, String text) { return "[00:00:00 " + day + "] " + text + "\n"; }

    @Test
    void restoresTodaysLegacyMessagesAndIgnoresYesterdayDiagnosticsAndBotEchoes() throws Exception
    {
        LocalDate today = LocalDate.now();
        Files.writeString(log(), entry(today.minusDays(1), "Old: Yesterday")
            + entry(today, "(U=5 @B) Charlie: Hello: world") + "continued line\n"
            + entry(today, "(IGNORED) ignored: Still visible")
            + entry(today, "bot: Reply") + entry(today, "(BOT) bot: Reply")
            + entry(today, "(OQ) answering open question from Charlie"));
        ChannelHistory history = new ChannelHistory();
        JSONArray messages = history.snapshot(bot, "#lobby").getJSONArray("messages");
        assertEquals(3, messages.length());
        assertEquals("Hello: world\ncontinued line", messages.getJSONObject(0).getString("body"));
        assertEquals("ignored", messages.getJSONObject(1).getString("sender"));
        assertEquals("Reply", messages.getJSONObject(2).getString("body"));
        assertEquals(messages.toString(), history.snapshot(bot, "#lobby").getJSONArray("messages").toString());
    }

    @Test
    void restartRestoresStructuredMessagesExactlyOnceIncludingMultilineAndNotices() throws Exception
    {
        ChannelHistory original = new ChannelHistory();
        original.record(bot, "#lobby", "Charlie", "hello\nsecond line");
        bot.logAppend("#lobby.log", "(U=4) Charlie: hello\nsecond line");
        original.record(bot, "#lobby", "bot", "(notice) Welcome");
        bot.logAppend("#lobby.log", "(TOOL) bot: (NOTICE) Welcome");
        original.record(bot, "#lobby", "Charlie", "same");
        original.record(bot, "#lobby", "Charlie", "same");
        original.record(bot, "#other", "private", "OTHER CHANNEL");
        JSONObject snapshot = new ChannelHistory().snapshot(bot, "#lobby");
        JSONArray messages = snapshot.getJSONArray("messages");
        assertEquals(4, messages.length());
        assertEquals("hello\nsecond line", messages.getJSONObject(0).getString("body"));
        assertEquals("(notice) Welcome", messages.getJSONObject(1).getString("body"));
        assertEquals("same", messages.getJSONObject(3).getString("body"));
        assertFalse(snapshot.toString().contains("OTHER CHANNEL"));
        assertFalse(snapshot.getBoolean("historyTruncated"));
        for (int i = 1; i < messages.length(); i++)
            assertTrue(messages.getJSONObject(i).getLong("id") > messages.getJSONObject(i - 1).getLong("id"));
    }

    @Test
    void mergesLegacyAndStructuredHistoryAndCapsWithVisibleWarning() throws Exception
    {
        LocalDate today = LocalDate.now();
        StringBuilder content = new StringBuilder(entry(today, "Charlie: Before upgrade"));
        for (int i = 0; i < ChannelHistory.MAX_MESSAGES; i++)
            content.append(entry(today, ChannelHistory.MARKER + new JSONObject().put("sender", "bot")
                .put("body", "message " + i).put("timestamp", System.currentTimeMillis())));
        Files.writeString(log(), content);
        JSONObject snapshot = new ChannelHistory().snapshot(bot, "#lobby");
        assertTrue(snapshot.getBoolean("historyTruncated"));
        assertEquals(ChannelHistory.MAX_MESSAGES, snapshot.getJSONArray("messages").length());
        assertEquals("message 0", snapshot.getJSONArray("messages").getJSONObject(0).getString("body"));
    }

    @Test
    void reportsInvalidLogsAndNeverReadsSymlinkTargets() throws Exception
    {
        Path outside = temporary.resolve("private.log");
        Files.writeString(outside, entry(LocalDate.now(), "private: CONFIDENTIAL"));
        Files.createSymbolicLink(log(), outside);
        JSONObject snapshot = new ChannelHistory().snapshot(bot, "#lobby");
        assertTrue(snapshot.has("historyError"));
        assertTrue(snapshot.getJSONArray("messages").isEmpty());
        assertFalse(snapshot.toString().contains("CONFIDENTIAL"));
        Files.delete(log());
        Files.writeString(log(), entry(LocalDate.now(), ChannelHistory.MARKER + "{invalid"));
        assertTrue(new ChannelHistory().snapshot(bot, "#lobby").has("historyError"));
    }
}

package org.openstatic;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigDirectoryTest
{
    @TempDir Path temporary;

    @Test
    void migratesLegacyFileAndDeletesOriginalAndKeepsLogLocation() throws Exception
    {
        Path legacy = temporary.resolve("old.json");
        JSONObject original = new JSONObject().put("openAiKey", "test-key").put("headless", true)
            .put("bots", new JSONObject().put("alice", new JSONObject().put("nickname", "Alice").put("enabled", false))
                .put("bob", new JSONObject().put("nickname", "Bob")));
        JsonFiles.write(legacy, original);
        ConfigDirectory config = ConfigDirectory.open(legacy);
        JSONObject settings = config.load(legacy);
        assertEquals(temporary.resolve("old.d"), config.directory);
        assertFalse(Files.exists(legacy));
        JSONObject root = JsonFiles.read(config.directory.resolve("irc-gpt-bot.json"));
        assertFalse(root.has("bots"));
        assertFalse(root.has("headless"));
        assertEquals("test-key", root.getString("openAiKey"));
        assertEquals(temporary.resolve("irc-gpt-bot-logs").toString(), root.getString("logPath"));
        assertFalse(JsonFiles.read(config.directory.resolve("alice.json")).getBoolean("enabled"));
        assertTrue(new ConfigDirectory(config.directory).load(null).getJSONObject("bots")
            .similar(settings.getJSONObject("bots")));
    }

    @Test
    void migratesInlineRootWithBackupAndRetainsUnrelatedRootOptions() throws Exception
    {
        JSONObject original = new JSONObject().put("apiPort", 9000).put("logPath", "/tmp/existing")
            .put("bots", new JSONObject().put("bot", new JSONObject().put("model", "model")));
        JsonFiles.write(temporary.resolve("irc-gpt-bot.json"), original);
        ConfigDirectory config = new ConfigDirectory(temporary);
        config.load(null);
        assertTrue(JsonFiles.read(temporary.resolve("irc-gpt-bot.json.bak")).similar(original));
        assertEquals(9000, JsonFiles.read(temporary.resolve("irc-gpt-bot.json")).getInt("apiPort"));
        assertEquals("/tmp/existing", JsonFiles.read(temporary.resolve("irc-gpt-bot.json")).getString("logPath"));
    }

    @Test
    void migratesVeryOldSingleBotAndChannelKeys() throws Exception
    {
        JSONObject original = new JSONObject().put("nickname", "oldbot").put("openAiKey", "test-key").put("apiPort", 9000)
            .put("apiPassword", "test-password").put("responsesApiUrl", "http://localhost/responses")
            .put("channels", new JSONArray().put("#test"))
            .put("channelKeys", new JSONObject().put("#test", "test-key"));
        JsonFiles.write(temporary.resolve("irc-gpt-bot.json"), original);
        JSONObject loaded = new ConfigDirectory(temporary).load(null);
        JSONObject bot = loaded.getJSONObject("bots").getJSONObject("oldbot");
        assertEquals("test-key", bot.getJSONObject("channels").getJSONObject("#test").getString("key"));
        assertFalse(bot.has("openAiKey"));
        assertFalse(bot.has("apiPassword"));
        assertEquals(9000, loaded.getInt("apiPort"));
        assertEquals("http://localhost/responses", loaded.getString("responsesApiUrl"));
        assertTrue(JsonFiles.read(temporary.resolve("irc-gpt-bot.json.bak")).similar(original));
        assertDoesNotThrow(() -> IRCGPTBotMain.migrateConfigIfNeeded(
            new JSONObject().put("nickname", "bot").put("channels", new JSONObject())));
    }

    @Test
    void savesNewAndEditedBotsAndDeletesOnlyRemovedBots() throws Exception
    {
        ConfigDirectory config = new ConfigDirectory(temporary);
        JSONObject settings = config.load(null);
        JsonFiles.write(temporary.resolve("pricing.json"), new JSONObject().put("currency", "USD"));
        settings.getJSONObject("bots").put("alice", new JSONObject().put("nickname", "alice"));
        config.save(settings);
        settings.getJSONObject("bots").getJSONObject("alice").put("model", "new-model");
        settings.getJSONObject("bots").put("bob", new JSONObject());
        config.save(settings);
        assertEquals("new-model", JsonFiles.read(temporary.resolve("alice.json")).getString("model"));
        settings.getJSONObject("bots").remove("alice");
        config.save(settings);
        assertFalse(Files.exists(temporary.resolve("alice.json")));
        assertTrue(Files.exists(temporary.resolve("bob.json")));
        assertEquals("USD", JsonFiles.read(temporary.resolve("pricing.json")).getString("currency"));
        assertEquals(1, new ConfigDirectory(temporary).load(null).getJSONObject("bots").length());
    }

    @Test
    void failsOnCorruptConfigInsteadOfOverwritingIt() throws Exception
    {
        Path root = temporary.resolve("irc-gpt-bot.json");
        Files.writeString(root, "{ broken");
        assertThrows(IOException.class, () -> new ConfigDirectory(temporary).load(null));
        assertEquals("{ broken", Files.readString(root));
    }

    @Test
    void rejectsUnsafeReservedAndCaseCollidingNames() throws Exception
    {
        for (String id : new String[] {"../escape", "", ".", "pricing", "IRc-GPT-BOT", "usage", "a/b", "a\\b", "CON.txt", "LPT1", "trailing.", "pipe|"})
            assertThrows(IllegalArgumentException.class, () -> ConfigDirectory.validateBotId(id), id);
        assertDoesNotThrow(() -> ConfigDirectory.validateBotId("AliceGPT_[test]"));
        ConfigDirectory config = new ConfigDirectory(temporary);
        JSONObject settings = config.load(null);
        settings.getJSONObject("bots").put("Alice", new JSONObject()).put("alice", new JSONObject());
        assertThrows(IOException.class, () -> config.save(settings));
        assertFalse(Files.exists(temporary.resolve("Alice.json")));
    }

    @Test
    void detectsMigrationConflictsWithoutClobberingFiles() throws Exception
    {
        JsonFiles.write(temporary.resolve("irc-gpt-bot.json"), new JSONObject().put("bots",
            new JSONObject().put("bot", new JSONObject().put("nickname", "old"))));
        JsonFiles.write(temporary.resolve("bot.json"), new JSONObject().put("nickname", "new"));
        assertThrows(IOException.class, () -> new ConfigDirectory(temporary).load(null));
        assertEquals("new", JsonFiles.read(temporary.resolve("bot.json")).getString("nickname"));
        assertTrue(JsonFiles.read(temporary.resolve("irc-gpt-bot.json")).has("bots"));
    }

    @Test
    void preservesUnicodeSettings() throws Exception
    {
        ConfigDirectory config = new ConfigDirectory(temporary);
        JSONObject settings = config.load(null);
        String preamble = "\u3053\u3093\u306b\u3061\u306f \u00e9";
        settings.getJSONObject("bots").put("bot", new JSONObject().put("systemPreamble", preamble));
        config.save(settings);
        assertEquals(preamble, JsonFiles.read(temporary.resolve("bot.json")).getString("systemPreamble"));
    }
}

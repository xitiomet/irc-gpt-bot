package org.openstatic;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BotEditorSettingsTest
{
    @TempDir Path temporary;

    private static JSONObject input()
    {
        return new JSONObject().put("model", "gpt-4o-mini").put("preamble", "Prompt")
            .put("acceptInvites", true).put("greet", false).put("privateMessages", true)
            .put("gptTools", new JSONArray(GPTTools.ALL_TOOLS)).put("ignore", new JSONArray().put("ignored"))
            .put("aliases", new JSONArray().put("helper")).put("botOps", new JSONArray().put("operator"));
    }

    @Test
    void preservesRuntimeDefaultsAndExplicitDisabledTools()
    {
        var previous = IRCGPTBotMain.logsFolder;
        try
        {
            IRCGPTBotMain.logsFolder = temporary.toFile();
            IRCGPTBot bot = new IRCGPTBot("test", new JSONObject().put("enabled", false));
            JSONObject defaults = bot.getEditorOptions();
            assertEquals(GPTTools.ALL_TOOLS.length, defaults.getJSONArray("gptTools").length());
            for (String field : new String[] {"acceptInvites", "greet", "privateMessages"})
                assertFalse(defaults.getBoolean(field));
            for (String field : new String[] {"ignore", "aliases", "botOps"})
                assertTrue(defaults.getJSONArray(field).isEmpty());
            bot.botOptions.put("gptTools", new JSONArray());
            assertTrue(bot.getEditorOptions().getJSONArray("gptTools").isEmpty());
            bot.botOptions.put("gptTools", new JSONArray().put("write_note"));
            assertEquals("[\"write_note\"]", bot.getEditorOptions().getJSONArray("gptTools").toString());
            JSONObject copy = bot.getEditorOptions();
            copy.getJSONArray("gptTools").put("read_note");
            assertEquals(1, bot.botOptions.getJSONArray("gptTools").length());
        } finally {
            IRCGPTBotMain.logsFolder = previous;
        }
    }

    @Test
    void acceptsAllAndNoToolsAndNormalizesDuplicateListEntries()
    {
        JSONObject update = IRCGPTBot.validateEditorUpdate(input());
        assertEquals("Prompt", update.getString("systemPreamble"));
        assertFalse(update.has("preamble"));
        assertEquals(GPTTools.ALL_TOOLS.length, update.getJSONArray("gptTools").length());
        JSONObject none = input().put("gptTools", new JSONArray()).put("ignore", new JSONArray())
            .put("aliases", new JSONArray().put("helper").put("helper")).put("botOps", new JSONArray());
        update = IRCGPTBot.validateEditorUpdate(none);
        assertTrue(update.getJSONArray("gptTools").isEmpty());
        assertTrue(update.getJSONArray("botOps").isEmpty());
        assertEquals(1, update.getJSONArray("aliases").length());
    }

    @Test
    void exposesAllSupportedOptionsButNeverReturnsPasswords()
    {
        JSONObject settings = new JSONObject().put("password", "private-server-password").put("operatorPassword", "private-oper-password")
            .put("secure", true).put("port", 6697).put("greetPublic", true).put("replyCooldownSeconds", 40);
        JSONObject view = BotEditorOptions.view(settings, "testbot");
        assertTrue(view.getBoolean("hasPassword"));
        assertTrue(view.getBoolean("hasOperatorPassword"));
        assertFalse(view.toString().contains("private-server-password"));
        assertFalse(view.toString().contains("private-oper-password"));
        assertEquals(6697, view.getInt("port"));
        assertTrue(view.getBoolean("secure"));
        assertEquals("testbot", view.getString("nickname"));
        assertEquals(40, view.getInt("replyCooldownSeconds"));
        assertTrue(BotEditorOptions.defaults("", true).getBoolean("privateMessages"));
    }

    @Test
    void validatesConnectionOptionsAndExplicitPasswordReplacementOrClearing()
    {
        JSONObject full = input().put("server", "irc.example.net").put("nickname", "NewNick").put("port", 6697)
            .put("secure", true).put("user", "").put("password", "").put("operatorPassword", "new-oper-password")
            .put("greetPublic", true).put("contextDepth", 10).put("modelsApiUrl", "https://example.net/models");
        JSONObject update = IRCGPTBot.validateEditorUpdate(full);
        assertEquals("NewNick", update.getString("nickname"));
        assertEquals(6697, update.getInt("port"));
        assertTrue(update.isNull("user"));
        assertFalse(update.has("password"));
        assertEquals("new-oper-password", update.getString("operatorPassword"));
        assertTrue(IRCGPTBot.validateEditorUpdate(input().put("clearPassword", true)).isNull("password"));
        assertThrows(IllegalArgumentException.class, () -> IRCGPTBot.validateEditorUpdate(input().put("port", 0)));
        assertThrows(IllegalArgumentException.class, () -> IRCGPTBot.validateEditorUpdate(input().put("port", 65536)));
        assertThrows(IllegalArgumentException.class, () -> IRCGPTBot.validateEditorUpdate(input().put("nickname", "two names")));
        assertThrows(IllegalArgumentException.class, () -> IRCGPTBot.validateEditorUpdate(input().put("modelsApiUrl", "file:///etc/test")));
        assertThrows(IllegalArgumentException.class, () -> IRCGPTBot.validateEditorUpdate(input().put("password", "x").put("clearPassword", true)));
        assertThrows(IllegalArgumentException.class, () -> IRCGPTBot.validateEditorUpdate(input().put("password", "x\r\nQUIT")));
    }

    @Test
    void rejectsInvalidValuesBeforeApplyingAnySettings()
    {
        assertThrows(IllegalArgumentException.class, () -> IRCGPTBot.validateEditorUpdate(input().put("model", " ")));
        assertThrows(IllegalArgumentException.class, () -> IRCGPTBot.validateEditorUpdate(input().put("preamble", 4)));
        assertThrows(IllegalArgumentException.class, () -> IRCGPTBot.validateEditorUpdate(input().put("greet", "false")));
        assertThrows(IllegalArgumentException.class, () -> IRCGPTBot.validateEditorUpdate(input().put("gptTools", new JSONArray().put("bogus"))));
        for (String field : new String[] {"ignore", "aliases", "botOps"})
            for (Object value : new Object[] {"", "two names", "one,two", "bad\nname", 42})
                assertThrows(IllegalArgumentException.class, () -> IRCGPTBot.validateEditorUpdate(input().put(field, new JSONArray().put(value))));
        assertFalse(IRCGPTBot.validateEditorUpdate(input().put("apiPassword", "ignored")).has("apiPassword"));
    }
}

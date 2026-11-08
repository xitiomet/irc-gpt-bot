package org.openstatic;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kitteh.irc.client.library.Client;
import org.kitteh.irc.client.library.element.Channel;
import org.kitteh.irc.client.library.element.mode.ChannelMode;
import org.kitteh.irc.client.library.element.mode.ChannelUserMode;
import org.kitteh.irc.client.library.feature.ServerInfo;

class GPTToolsModesTest
{
    @TempDir Path temporary;
    private File previousLogs;
    private IRCGPTBot bot;
    private GPTTools tools;
    private boolean joined = true;
    private final List<String> sent = new ArrayList<>();

    private static ChannelMode mode(char letter, ChannelMode.Type type, boolean user)
    {
        Class<?> modeClass = user ? ChannelUserMode.class : ChannelMode.class;
        return (ChannelMode) Proxy.newProxyInstance(modeClass.getClassLoader(), new Class<?>[] {modeClass},
            (proxy, method, args) -> {
                switch (method.getName())
                {
                    case "getChar": return letter;
                    case "getType": return type;
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
    }

    @BeforeEach
    void setup() throws Exception
    {
        previousLogs = IRCGPTBotMain.logsFolder;
        IRCGPTBotMain.logsFolder = temporary.toFile();
        bot = new IRCGPTBot("mode-bot", new JSONObject().put("enabled", false).put("nickname", "mode-bot")
            .put("botOps", new JSONArray().put("Admin")));
        Map<Character, ChannelMode> modes = Map.of(
            'm', mode('m', ChannelMode.Type.D_PARAMETER_NEVER, false),
            'i', mode('i', ChannelMode.Type.D_PARAMETER_NEVER, false),
            'k', mode('k', ChannelMode.Type.B_PARAMETER_ALWAYS, false),
            'l', mode('l', ChannelMode.Type.C_PARAMETER_ON_SET, false),
            'b', mode('b', ChannelMode.Type.A_MASK, false));
        Map<Character, ChannelMode> userModes = Map.of(
            'o', mode('o', ChannelMode.Type.B_PARAMETER_ALWAYS, true),
            'v', mode('v', ChannelMode.Type.B_PARAMETER_ALWAYS, true),
            'h', mode('h', ChannelMode.Type.B_PARAMETER_ALWAYS, true));
        ServerInfo info = (ServerInfo) Proxy.newProxyInstance(ServerInfo.class.getClassLoader(), new Class<?>[] {ServerInfo.class},
            (proxy, method, args) -> {
                switch (method.getName())
                {
                    case "getChannelMode": return Optional.ofNullable(modes.get(args[0]));
                    case "getChannelUserMode": return Optional.ofNullable(userModes.get(args[0]));
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        Channel channel = (Channel) Proxy.newProxyInstance(Channel.class.getClassLoader(), new Class<?>[] {Channel.class},
            (proxy, method, args) -> {
                switch (method.getName())
                {
                    case "getName": return "#lobby";
                    case "getNicknames": return List.of("Charlie", "Admin", "mode-bot");
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[] {Client.class},
            (proxy, method, args) -> {
                switch (method.getName())
                {
                    case "getServerInfo": return info;
                    case "getChannel": return joined && "#lobby".equals(args[0]) ? Optional.of(channel) : Optional.empty();
                    case "sendRawLine": sent.add((String) args[0]); return null;
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        Field field = IRCGPTBot.class.getDeclaredField("client");
        field.setAccessible(true);
        field.set(bot, client);
        tools = new GPTTools(bot, "#lobby");
    }

    @AfterEach
    void cleanup()
    {
        IRCGPTBotMain.logsFolder = previousLogs;
    }

    private String execute(String name, JSONObject args)
    {
        return tools.execute(name, args.toString());
    }

    @Test
    void definitionsAndAllowlistIncludeBothToolsOnlyInChannels()
    {
        for (String name : new String[] {GPTTools.SET_CHANNEL_MODE, GPTTools.SET_USER_MODE})
        {
            assertTrue(List.of(GPTTools.ALL_TOOLS).contains(name));
            assertTrue(tools.getToolDefinitions().toList().stream()
                .anyMatch(value -> name.equals(((Map<?, ?>) value).get("name"))));
            GPTTools privateTools = new GPTTools(bot, "Charlie");
            assertFalse(privateTools.isToolEnabled(name));
            assertTrue(privateTools.execute(name, "{\"modes\":\"+m\"}").startsWith("error:"));
        }
        bot.getBotOptions().put("gptTools", new JSONArray().put(GPTTools.SET_CHANNEL_MODE));
        assertTrue(tools.isToolEnabled(GPTTools.SET_CHANNEL_MODE));
        assertFalse(tools.isToolEnabled(GPTTools.SET_USER_MODE));
        assertTrue(execute(GPTTools.SET_USER_MODE, new JSONObject().put("nickname", "Charlie").put("modes", "+o")).startsWith("error:"));
        assertTrue(sent.isEmpty());
    }

    @Test
    void channelModeParametersFollowServerRulesAndKeysAreRedacted() throws Exception
    {
        String result = execute(GPTTools.SET_CHANNEL_MODE, new JSONObject().put("modes", "+kl-m")
            .put("parameters", new JSONArray().put("channel-key").put("25")));
        assertTrue(result.startsWith("mode change requested"), result);
        assertTrue(result.contains("not confirmed"));
        assertEquals(List.of("MODE #lobby +kl-m channel-key 25"), sent);
        String log = Files.readString(bot.getLogFolder().toPath().resolve("#lobby.log"));
        assertFalse(log.contains("channel-key"));
        assertTrue(log.contains("[redacted]"));
        assertFalse(bot.getChatLog("#lobby").getGPTMessages().toString().contains("channel-key"));
        execute(GPTTools.SET_CHANNEL_MODE, new JSONObject().put("modes", "-l+i"));
        execute(GPTTools.SET_CHANNEL_MODE, new JSONObject().put("modes", "-b")
            .put("parameters", new JSONArray().put("*!*@abusive.example")));
        execute(GPTTools.SET_CHANNEL_MODE, new JSONObject().put("modes", "-k")
            .put("parameters", new JSONArray().put("channel-key")));
        assertEquals("MODE #lobby -l+i", sent.get(1));
        assertEquals("MODE #lobby -b *!*@abusive.example", sent.get(2));
        assertEquals("MODE #lobby -k channel-key", sent.get(3));
    }

    @Test
    void userModesRepeatCanonicalNicknameAndSupportServerSpecificPrivileges()
    {
        String result = execute(GPTTools.SET_USER_MODE, new JSONObject().put("nickname", "charlie").put("modes", "+oh-v"));
        assertTrue(result.startsWith("mode change requested"), result);
        assertEquals(List.of("MODE #lobby +oh-v Charlie Charlie Charlie"), sent);
    }

    @Test
    void refusesDemotingProtectedUsersAndCannotBypassThroughChannelModes()
    {
        for (String nickname : new String[] {"ADMIN", "mode-bot"})
        {
            assertTrue(execute(GPTTools.SET_USER_MODE, new JSONObject().put("nickname", nickname).put("modes", "-o")).startsWith("error:"));
            assertTrue(execute(GPTTools.SET_USER_MODE, new JSONObject().put("nickname", nickname).put("modes", "+v-o")).startsWith("error:"));
        }
        assertTrue(execute(GPTTools.SET_CHANNEL_MODE, new JSONObject().put("modes", "-o")
            .put("parameters", new JSONArray().put("Admin"))).startsWith("error:"));
        assertTrue(execute(GPTTools.SET_USER_MODE, new JSONObject().put("nickname", "Charlie").put("modes", "+m")).startsWith("error:"));
        assertTrue(sent.isEmpty());
        assertTrue(execute(GPTTools.SET_USER_MODE, new JSONObject().put("nickname", "Admin").put("modes", "+o")).startsWith("mode change requested"));
    }

    @Test
    void invalidModesAndParametersNeverSendPartialCommands()
    {
        for (String modes : new String[] {"m", "+", "++m", "+m-", "+m\nOPER evil", "+m -i", "+z", "+m+z"})
            assertTrue(execute(GPTTools.SET_CHANNEL_MODE, new JSONObject().put("modes", modes)).startsWith("error:"), modes);
        assertTrue(execute(GPTTools.SET_CHANNEL_MODE, new JSONObject().put("modes", "+k")).startsWith("error:"));
        assertTrue(execute(GPTTools.SET_CHANNEL_MODE, new JSONObject().put("modes", "+m")
            .put("parameters", new JSONArray().put("extra"))).startsWith("error:"));
        for (Object parameter : new Object[] {"two words", "key\r\nQUIT", "\u0000", ":trailing", "", 25, "x".repeat(510)})
            assertTrue(execute(GPTTools.SET_CHANNEL_MODE, new JSONObject().put("modes", "+k")
                .put("parameters", new JSONArray().put(parameter))).startsWith("error:"));
        assertTrue(execute(GPTTools.SET_CHANNEL_MODE, new JSONObject().put("modes", "+m").put("parameters", "bad")).startsWith("error:"));
        for (String nickname : new String[] {"absent", "#other", "Charlie,Admin", "Charlie\r\nQUIT"})
            assertTrue(execute(GPTTools.SET_USER_MODE, new JSONObject().put("nickname", nickname).put("modes", "+o")).startsWith("error:"));
        assertTrue(sent.isEmpty());
    }

    @Test
    void rejectsUnjoinedChannels()
    {
        joined = false;
        assertTrue(execute(GPTTools.SET_CHANNEL_MODE, new JSONObject().put("modes", "+m")).startsWith("error:"));
        assertTrue(execute(GPTTools.SET_USER_MODE, new JSONObject().put("nickname", "Charlie").put("modes", "+o")).startsWith("error:"));
        assertTrue(sent.isEmpty());
    }
}

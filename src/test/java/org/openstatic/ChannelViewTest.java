package org.openstatic;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kitteh.irc.client.library.Client;
import org.kitteh.irc.client.library.element.Channel;
import org.kitteh.irc.client.library.element.mode.ChannelUserMode;

class ChannelViewTest
{
    @TempDir Path temporary;
    private File previousLogs;
    private IRCGPTBot bot;
    private final AtomicReference<String> topic = new AtomicReference<>("Initial topic");
    private final AtomicReference<List<String>> names = new AtomicReference<>(List.of("zoe", "Alice"));
    private boolean joined = true;

    @BeforeEach
    void setup() throws Exception
    {
        previousLogs = IRCGPTBotMain.logsFolder;
        IRCGPTBotMain.logsFolder = temporary.toFile();
        bot = new IRCGPTBot("view-bot", new JSONObject().put("enabled", false).put("nickname", "view-bot"));
        Channel.Topic channelTopic = (Channel.Topic) Proxy.newProxyInstance(Channel.Topic.class.getClassLoader(),
            new Class<?>[] { Channel.Topic.class }, (proxy, method, args) -> Optional.ofNullable(topic.get()));
        ChannelUserMode mode = (ChannelUserMode) Proxy.newProxyInstance(ChannelUserMode.class.getClassLoader(),
            new Class<?>[] { ChannelUserMode.class }, (proxy, method, args) -> {
                if ("getNickPrefix".equals(method.getName())) return '@';
                throw new UnsupportedOperationException(method.getName());
            });
        Channel channel = (Channel) Proxy.newProxyInstance(Channel.class.getClassLoader(), new Class<?>[] { Channel.class },
            (proxy, method, args) -> {
                switch (method.getName())
                {
                    case "getName": return "#lobby";
                    case "getTopic": return channelTopic;
                    case "getNicknames": return names.get();
                    case "hasCompleteUserData": return true;
                    case "getUserModes":
                        SortedSet<ChannelUserMode> modes = new TreeSet<>((a, b) -> 0);
                        if ("Alice".equals(args[0])) modes.add(mode);
                        return Optional.of(modes);
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[] { Client.class },
            (proxy, method, args) -> {
                if ("getChannel".equals(method.getName()))
                    return joined && "#lobby".equalsIgnoreCase((String) args[0]) ? Optional.of(channel) : Optional.empty();
                if ("sendMultiLineMessage".equals(method.getName()) || "sendMultiLineNotice".equals(method.getName()))
                    return null;
                throw new UnsupportedOperationException(method.getName());
            });
        field("client", client);
        field("statusLine", "CONNECTED");
    }

    private void field(String name, Object value) throws Exception
    {
        Field field = IRCGPTBot.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(bot, value);
    }

    @AfterEach
    void cleanup()
    {
        IRCGPTBotMain.logsFolder = previousLogs;
    }

    @Test
    void snapshotUpdatesMessagesTopicAndRosterAndDoesNotExposePrivateChats()
    {
        bot.recordChannelMessage("#lobby", "Alice", "<script>hello</script>");
        bot.recordChannelMessage("#other", "other", "OTHER CHANNEL");
        bot.recordChannelMessage("private-user", "private", "PRIVATE CONTENT");
        JSONObject snapshot = bot.getChannelView("#LOBBY");
        assertEquals("Initial topic", snapshot.getString("topic"));
        assertEquals("Alice", snapshot.getJSONArray("roster").getJSONObject(0).getString("nickname"));
        assertEquals("@", snapshot.getJSONArray("roster").getJSONObject(0).getString("prefix"));
        assertEquals(1, snapshot.getJSONArray("messages").length());
        assertFalse(snapshot.toString().contains("PRIVATE CONTENT"));
        assertFalse(snapshot.toString().contains("OTHER CHANNEL"));
        topic.set("Changed topic");
        names.set(List.of("bob"));
        bot.recordChannelMessage("#lobby", "bob", "New message");
        snapshot = bot.getChannelView("#lobby");
        assertEquals("Changed topic", snapshot.getString("topic"));
        assertEquals(1, snapshot.getJSONArray("roster").length());
        assertEquals("bob", snapshot.getJSONArray("roster").getJSONObject(0).getString("nickname"));
        assertEquals(2, snapshot.getJSONArray("messages").length());
        snapshot.getJSONArray("messages").getJSONObject(0).put("body", "MUTATED");
        assertFalse(bot.getChannelView("#lobby").toString().contains("MUTATED"));
    }

    @Test
    void keepsAtMost255MessagesAndIncludesLocallySentMessages()
    {
        for (int i = 0; i < 260; i++)
            bot.recordChannelMessage("#lobby", "sender", "message " + i);
        JSONArray messages = bot.getChannelView("#lobby").getJSONArray("messages");
        assertEquals(255, messages.length());
        assertEquals("message 5", messages.getJSONObject(0).getString("body"));
        assertTrue(messages.getJSONObject(0).getLong("id") < messages.getJSONObject(254).getLong("id"));
        bot.sendPrivateMessage("#lobby", "Local reply");
        bot.sendNotice("#lobby", "Local notice");
        messages = bot.getChannelView("#lobby").getJSONArray("messages");
        assertEquals("Local reply", messages.getJSONObject(253).getString("body"));
        assertEquals("(notice) Local notice", messages.getJSONObject(254).getString("body"));
    }

    @Test
    void refusesNonChannelUnjoinedAndDisconnectedTargets() throws Exception
    {
        for (String target : new String[] {"someone", "../notes", "#bad,name", "#other"})
            assertThrows(IllegalArgumentException.class, () -> bot.getChannelView(target));
        joined = false;
        assertThrows(IllegalArgumentException.class, () -> bot.getChannelView("#lobby"));
        joined = true;
        field("statusLine", "DISCONNECTED");
        assertThrows(IllegalArgumentException.class, () -> bot.getChannelView("#lobby"));
    }
}

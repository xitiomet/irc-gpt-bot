package org.openstatic;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kitteh.irc.client.library.Client;
import org.kitteh.irc.client.library.element.Actor;
import org.kitteh.irc.client.library.element.Channel;
import org.kitteh.irc.client.library.element.ServerMessage;
import org.kitteh.irc.client.library.element.mode.ChannelUserMode;
import org.kitteh.irc.client.library.event.channel.ChannelNamesUpdatedEvent;
import org.kitteh.irc.client.library.event.client.ClientReceiveNumericEvent;

class IrcOperatorSelfOpTest
{
    @TempDir Path temporary;
    private File previousLogs;
    private IRCGPTBot bot;
    private Client client;
    private Channel channel;
    private final List<String> sent = new ArrayList<>();
    private final AtomicBoolean channelOp = new AtomicBoolean();

    @BeforeEach
    void setup() throws Exception
    {
        previousLogs = IRCGPTBotMain.logsFolder;
        IRCGPTBotMain.logsFolder = temporary.toFile();
        bot = new IRCGPTBot("oper-bot", new JSONObject().put("enabled", false).put("nickname", "oper-bot"));
        ChannelUserMode op = (ChannelUserMode) Proxy.newProxyInstance(ChannelUserMode.class.getClassLoader(),
            new Class<?>[] { ChannelUserMode.class }, (proxy, method, args) -> {
                if ("getChar".equals(method.getName())) return 'o';
                throw new UnsupportedOperationException(method.getName());
            });
        channel = (Channel) Proxy.newProxyInstance(Channel.class.getClassLoader(), new Class<?>[] { Channel.class },
            (proxy, method, args) -> {
                switch (method.getName())
                {
                    case "getName": return "#lobby";
                    case "hasCompleteUserData": return true;
                    case "getUserModes":
                        SortedSet<ChannelUserMode> modes = new TreeSet<>((a, b) -> 0);
                        if (channelOp.get()) modes.add(op);
                        return Optional.of(modes);
                    case "getClient": return client;
                    case "hashCode": return 1;
                    case "equals": return proxy == args[0];
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[] { Client.class },
            (proxy, method, args) -> {
                switch (method.getName())
                {
                    case "getNick": return "oper-bot_";
                    case "getUserModes": return Optional.empty();
                    case "getChannels": return Set.of(channel);
                    case "sendRawLine": sent.add((String) args[0]); return null;
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        Field field = IRCGPTBot.class.getDeclaredField("client");
        field.setAccessible(true);
        field.set(bot, client);
    }

    @AfterEach
    void cleanup()
    {
        IRCGPTBotMain.logsFolder = previousLogs;
    }

    private static ServerMessage serverMessage(String raw)
    {
        return (ServerMessage) Proxy.newProxyInstance(ServerMessage.class.getClassLoader(), new Class<?>[] { ServerMessage.class },
            (proxy, method, args) -> {
                if ("getMessage".equals(method.getName())) return raw;
                if ("getTags".equals(method.getName())) return List.of();
                throw new UnsupportedOperationException(method.getName());
            });
    }

    private ClientReceiveNumericEvent numeric(int numeric, String... params)
    {
        ServerMessage message = serverMessage(":server " + numeric + " " + String.join(" ", params));
        Actor server = (Actor) Proxy.newProxyInstance(Actor.class.getClassLoader(), new Class<?>[] { Actor.class },
            (proxy, method, args) -> {
                if ("getName".equals(method.getName())) return "server";
                if ("getClient".equals(method.getName())) return client;
                throw new UnsupportedOperationException(method.getName());
            });
        return new ClientReceiveNumericEvent(client, message, server, String.valueOf(numeric), numeric, List.of(params));
    }

    private void namesUpdated()
    {
        ServerMessage message = serverMessage(":server 366 oper-bot_ #lobby :End of NAMES list");
        bot.onChannelNamesUpdated(new ChannelNamesUpdatedEvent(client, List.of(message), channel));
    }

    @Test
    void doesNothingUnlessTheBotIsAnIrcOperator()
    {
        namesUpdated();
        assertEquals(Collections.emptyList(), sent);
    }

    @Test
    void becomingIrcOperatorClaimsOpThenFallsBackToSamodeAndLogsWhenUnsupported() throws Exception
    {
        bot.onNumeric(numeric(381, "oper-bot_", "You are now an IRC Operator"));
        assertEquals(List.of("MODE #lobby +o oper-bot_"), sent);
        namesUpdated();
        assertEquals(1, sent.size(), "pending request must not be repeated");
        bot.onNumeric(numeric(482, "oper-bot_", "#lobby", "You are not channel operator"));
        assertEquals(List.of("MODE #lobby +o oper-bot_", "SAMODE #lobby +o oper-bot_"), sent);
        bot.onNumeric(numeric(421, "oper-bot_", "SAMODE", "Unknown command"));
        assertEquals(2, sent.size());
        String log = Files.readString(temporary.resolve("oper-bot").resolve("exceptions.log"));
        assertTrue(log.contains("Unable to become channel operator in #lobby"), log);
        assertTrue(log.contains("OperCanUseMode"), log);
    }

    @Test
    void skipsChannelsWhereTheBotIsAlreadyOperator()
    {
        channelOp.set(true);
        bot.onNumeric(numeric(381, "oper-bot_", "You are now an IRC Operator"));
        namesUpdated();
        assertEquals(Collections.emptyList(), sent);
    }
}

package org.openstatic;

import java.util.Date;
import java.util.Optional;

import org.json.JSONArray;
import org.json.JSONObject;
import org.kitteh.irc.client.library.Client;
import org.kitteh.irc.client.library.element.Channel;
import org.kitteh.irc.client.library.element.User;

/**
 * IRC actions exposed to the model as Responses API function tools, bound to
 * the conversation (channel or private chat) that triggered the request.
 */
public class GPTTools
{
    public static final String SEND_PRIVATE_MESSAGE = "send_private_message";
    public static final String SEND_NOTICE = "send_notice";
    public static final String SEND_ACTION = "send_action";
    public static final String KICK_USER = "kick_user";
    public static final String SET_TOPIC = "set_topic";
    public static final String[] ALL_TOOLS = { SEND_PRIVATE_MESSAGE, SEND_NOTICE, SEND_ACTION, KICK_USER, SET_TOPIC };

    private IRCGPTBot bot;
    private String target;
    private boolean targetIsChannel;

    public GPTTools(IRCGPTBot bot, String target)
    {
        this.bot = bot;
        this.target = target;
        this.targetIsChannel = isChannelName(target);
    }

    private static boolean isChannelName(String name)
    {
        return name != null && !name.isEmpty() && "#&+!".indexOf(name.charAt(0)) >= 0;
    }

    public boolean isToolEnabled(String name)
    {
        // Channel moderation tools only make sense (and are only safe) inside the channel being discussed
        if ((KICK_USER.equals(name) || SET_TOPIC.equals(name)) && !this.targetIsChannel)
            return false;
        JSONArray enabled = this.bot.getBotOptions().optJSONArray("gptTools");
        if (enabled == null)
            return true;
        return IRCGPTBotMain.inJSONArray(enabled, name);
    }

    private static JSONObject stringProperty(String description)
    {
        JSONObject p = new JSONObject();
        p.put("type", "string");
        p.put("description", description);
        return p;
    }

    private static JSONObject functionTool(String name, String description, JSONObject properties, String... required)
    {
        JSONObject parameters = new JSONObject();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", new JSONArray(required));
        parameters.put("additionalProperties", false);
        JSONObject tool = new JSONObject();
        tool.put("type", "function");
        tool.put("name", name);
        tool.put("description", description);
        tool.put("parameters", parameters);
        return tool;
    }

    public JSONArray getToolDefinitions()
    {
        JSONArray tools = new JSONArray();
        if (isToolEnabled(SEND_PRIVATE_MESSAGE))
        {
            JSONObject props = new JSONObject();
            props.put("nickname", stringProperty("Nickname of the IRC user to privately message"));
            props.put("message", stringProperty("Text of the private message"));
            tools.put(functionTool(SEND_PRIVATE_MESSAGE, "Send a private message (PRIVMSG) to an IRC user.", props, "nickname", "message"));
        }
        if (isToolEnabled(SEND_NOTICE))
        {
            JSONObject props = new JSONObject();
            props.put("target", stringProperty("Nickname or channel to send the notice to. Defaults to the current conversation (" + this.target + ")."));
            props.put("message", stringProperty("Text of the notice"));
            tools.put(functionTool(SEND_NOTICE, "Send an IRC NOTICE to a user or channel.", props, "message"));
        }
        if (isToolEnabled(SEND_ACTION))
        {
            JSONObject props = new JSONObject();
            props.put("action", stringProperty("The action text, without the bot's name, e.g. 'waves hello'"));
            props.put("target", stringProperty("Nickname or channel to perform the action in. Defaults to the current conversation (" + this.target + ")."));
            tools.put(functionTool(SEND_ACTION, "Perform an IRC /me action (CTCP ACTION), e.g. '* bot waves hello'.", props, "action"));
        }
        if (isToolEnabled(KICK_USER))
        {
            JSONObject props = new JSONObject();
            props.put("nickname", stringProperty("Nickname of the user to kick"));
            props.put("reason", stringProperty("Reason for the kick"));
            tools.put(functionTool(KICK_USER, "Kick a user from the current channel (" + this.target + "). Requires the bot to have operator status. Only use for clear abuse or when an operator asks.", props, "nickname"));
        }
        if (isToolEnabled(SET_TOPIC))
        {
            JSONObject props = new JSONObject();
            props.put("topic", stringProperty("The new channel topic"));
            tools.put(functionTool(SET_TOPIC, "Change the topic of the current channel (" + this.target + ").", props, "topic"));
        }
        return tools;
    }

    public String getInstructions()
    {
        String where = this.targetIsChannel ? "the IRC channel " + this.target : "a private IRC conversation with " + this.target;
        return "You are " + this.bot.getNickname() + ", an IRC bot currently in " + where + ". "
             + "Your plain text reply is automatically sent to " + this.target + ", so only use the messaging tools to reach someone else "
             + "or to respond in a different way (a notice or a /me action). If an action alone is a sufficient response you may reply with no text. "
             + "Use tools only when it is clearly appropriate or requested, and do not describe the tool calls in your reply.";
    }

    private Client client()
    {
        Client client = this.bot.getClient();
        if (client == null)
            throw new IllegalStateException("bot is not connected");
        return client;
    }

    private static String requireArg(JSONObject args, String key)
    {
        String value = args.optString(key, "").replace("\r", " ").replace("\n", " ").trim();
        if (value.isEmpty())
            throw new IllegalArgumentException("missing required argument '" + key + "'");
        return value;
    }

    private String optTarget(JSONObject args)
    {
        String value = args.optString("target", "").trim();
        return value.isEmpty() ? this.target : value;
    }

    private Channel currentChannel()
    {
        Optional<Channel> channel = client().getChannel(this.target);
        if (!channel.isPresent())
            throw new IllegalStateException("bot is not in channel " + this.target);
        return channel.get();
    }

    private boolean isBotOp(String nickname)
    {
        JSONArray botOps = this.bot.getBotOptions().optJSONArray("botOps");
        if (botOps != null)
        {
            for (Object op : botOps)
            {
                if (op instanceof String && nickname.equalsIgnoreCase((String) op))
                    return true;
            }
        }
        return false;
    }

    private void record(String to, String logLine, String chatLogBody)
    {
        this.bot.logAppend(to + ".log", "(TOOL) " + this.bot.getNickname() + ": " + logLine);
        // Only the active conversation's log is touched; its handler is blocked waiting on this response
        if (to.equalsIgnoreCase(this.target))
            this.bot.getChatLog(this.target).add(new ChatMessage(this.bot.getNickname(), to, chatLogBody, new Date(System.currentTimeMillis())));
    }

    /** Executes a tool call and returns a short result string for the model. */
    public String execute(String name, String argumentsJson)
    {
        try
        {
            if (!isToolEnabled(name))
                return "error: tool '" + name + "' is not available";
            JSONObject args = (argumentsJson == null || argumentsJson.trim().isEmpty()) ? new JSONObject() : new JSONObject(argumentsJson);
            switch (name)
            {
                case SEND_PRIVATE_MESSAGE:
                {
                    String nickname = requireArg(args, "nickname");
                    String message = requireArg(args, "message");
                    if (isChannelName(nickname))
                        return "error: send_private_message requires a nickname, not a channel";
                    client().sendMultiLineMessage(nickname, message);
                    record(nickname, message, message);
                    return "private message sent to " + nickname;
                }
                case SEND_NOTICE:
                {
                    String to = optTarget(args);
                    String message = requireArg(args, "message");
                    client().sendMultiLineNotice(to, message);
                    record(to, "(NOTICE) " + message, "(notice) " + message);
                    return "notice sent to " + to;
                }
                case SEND_ACTION:
                {
                    String to = optTarget(args);
                    String action = requireArg(args, "action");
                    client().sendCtcpMessage(to, "ACTION " + action);
                    record(to, "* " + action, "/me " + action);
                    return "action performed in " + to;
                }
                case KICK_USER:
                {
                    String nickname = requireArg(args, "nickname");
                    String reason = args.optString("reason", "").replace("\r", " ").replace("\n", " ").trim();
                    if (nickname.equalsIgnoreCase(this.bot.getNickname()))
                        return "error: the bot cannot kick itself";
                    if (isBotOp(nickname))
                        return "error: " + nickname + " is a protected bot operator and cannot be kicked";
                    Channel channel = currentChannel();
                    Optional<User> user = channel.getUser(nickname);
                    if (!user.isPresent())
                        return "error: " + nickname + " is not in " + this.target;
                    if (reason.isEmpty())
                        channel.kick(user.get());
                    else
                        channel.kick(user.get(), reason);
                    record(this.target, "(KICK) " + nickname + (reason.isEmpty() ? "" : " - " + reason), "/kick " + nickname + (reason.isEmpty() ? "" : " " + reason));
                    return "kick requested for " + nickname + " in " + this.target;
                }
                case SET_TOPIC:
                {
                    String topic = requireArg(args, "topic");
                    currentChannel().setTopic(topic);
                    record(this.target, "(TOPIC) " + topic, "/topic " + topic);
                    return "topic change requested for " + this.target;
                }
                default:
                    return "error: unknown tool '" + name + "'";
            }
        } catch (Exception e) {
            this.bot.log("Tool " + name + " failed - ", e);
            return "error: " + e.getMessage();
        }
    }
}

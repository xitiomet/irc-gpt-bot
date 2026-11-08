package org.openstatic;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.SortedSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.json.JSONObject;
import org.kitteh.irc.client.library.Client;
import org.kitteh.irc.client.library.element.Channel;
import org.kitteh.irc.client.library.element.User;
import org.kitteh.irc.client.library.element.mode.ChannelUserMode;
import org.kitteh.irc.client.library.event.client.ClientReceiveNumericEvent;

import net.engio.mbassy.listener.Handler;

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
    public static final String GET_NAMES = "get_names";
    public static final String LIST_CHANNELS = "list_channels";
    public static final String INVITE_USER = "invite_user";
    public static final String LIST_NOTES = "list_notes";
    public static final String READ_NOTE = "read_note";
    public static final String WRITE_NOTE = "write_note";
    public static final String DELETE_NOTE = "delete_note";
    public static final String LIST_LOG_FILES = "list_log_files";
    public static final String READ_LOG_FILE = "read_log_file";
    public static final String[] ALL_TOOLS = { SEND_PRIVATE_MESSAGE, SEND_NOTICE, SEND_ACTION, KICK_USER, SET_TOPIC, GET_NAMES, LIST_CHANNELS, INVITE_USER,
                                               LIST_NOTES, READ_NOTE, WRITE_NOTE, DELETE_NOTE, LIST_LOG_FILES, READ_LOG_FILE };

    private static final int RPL_LIST = 322;
    private static final int RPL_LISTEND = 323;
    private static final int MAX_LIST_ENTRIES = 10000;
    private static final int MAX_TOOL_URL_RESPONSE = 8000;
    private static final int MAX_FILE_READ = 8000;
    private static final int MAX_FILE_LIST = 500;
    private static final int MAX_NOTE_SIZE = 65536;

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
        if (isToolEnabled(GET_NAMES))
        {
            JSONObject props = new JSONObject();
            props.put("channel", stringProperty("Channel to list users for (must be one the bot has joined)." + channelDefaultHint()));
            tools.put(functionTool(GET_NAMES, "Get the names list (NAMES) of a channel: the nicknames present, with status prefixes like @ for operators and + for voiced users.", props));
        }
        if (isToolEnabled(LIST_CHANNELS))
        {
            JSONObject props = new JSONObject();
            JSONObject scope = stringProperty("'joined' for channels the bot is in (default), or 'server' for the public channel list from the IRC server (LIST).");
            scope.put("enum", new JSONArray().put("joined").put("server"));
            props.put("scope", scope);
            props.put("match", stringProperty("Optional case-insensitive text that the channel name or topic must contain"));
            JSONObject limit = new JSONObject();
            limit.put("type", "integer");
            limit.put("description", "Maximum number of server channels to return, largest first (default 25, max 100)");
            props.put("limit", limit);
            tools.put(functionTool(LIST_CHANNELS, "Get a list of IRC channels with their user counts and topics.", props));
        }
        if (isToolEnabled(INVITE_USER))
        {
            JSONObject props = new JSONObject();
            props.put("nickname", stringProperty("Nickname of the user to invite"));
            props.put("channel", stringProperty("Channel to invite the user to (must be one the bot has joined)." + channelDefaultHint()));
            tools.put(functionTool(INVITE_USER, "Invite a user to a channel (INVITE).", props, "nickname"));
        }
        if (isToolEnabled(LIST_NOTES))
        {
            tools.put(functionTool(LIST_NOTES, "List the files in your notes directory, with their sizes and last-modified times.", new JSONObject()));
        }
        if (isToolEnabled(READ_NOTE))
        {
            JSONObject props = new JSONObject();
            props.put("name", stringProperty("File name of the note, as returned by " + LIST_NOTES));
            JSONObject offset = new JSONObject();
            offset.put("type", "integer");
            offset.put("description", "Optional character offset to start reading from, for notes too long to return at once (default 0)");
            props.put("offset", offset);
            tools.put(functionTool(READ_NOTE, "Read the contents of a file in your notes directory. Long notes are returned in " + MAX_FILE_READ + "-character chunks.", props, "name"));
        }
        if (isToolEnabled(WRITE_NOTE))
        {
            JSONObject props = new JSONObject();
            props.put("name", stringProperty("File name of the note, e.g. 'todo.txt' (no directories)"));
            props.put("content", stringProperty("Text to write to the note"));
            JSONObject append = new JSONObject();
            append.put("type", "boolean");
            append.put("description", "true to add the content to the end of the note; false (default) to create or replace the whole note");
            props.put("append", append);
            tools.put(functionTool(WRITE_NOTE, "Create, overwrite or append to a file in your notes directory. Use notes to remember things between conversations.", props, "name", "content"));
        }
        if (isToolEnabled(DELETE_NOTE))
        {
            JSONObject props = new JSONObject();
            props.put("name", stringProperty("File name of the note to delete, as returned by " + LIST_NOTES));
            tools.put(functionTool(DELETE_NOTE, "Permanently delete a file from your notes directory.", props, "name"));
        }
        if (isToolEnabled(LIST_LOG_FILES))
        {
            JSONObject props = new JSONObject();
            props.put("path", stringProperty("Optional subdirectory of your log directory to list (e.g. 'notes'). Defaults to the top of your log directory."));
            tools.put(functionTool(LIST_LOG_FILES, "List the files and subdirectories in your own log directory (channel logs, private chat logs, tools.log, notes/...).", props));
        }
        if (isToolEnabled(READ_LOG_FILE))
        {
            JSONObject props = new JSONObject();
            props.put("path", stringProperty("Path of the file relative to your log directory, as returned by " + LIST_LOG_FILES + " (e.g. '#channel.log' or 'notes/todo.txt')"));
            JSONObject lines = new JSONObject();
            lines.put("type", "integer");
            lines.put("description", "Optional: only return the last N lines of the file");
            props.put("lines", lines);
            tools.put(functionTool(READ_LOG_FILE, "Read a file from your own log directory (read-only). Large files return their most recent content.", props, "path"));
        }
        JSONArray toolUrls = getToolUrls();
        for (int i = 0; i < toolUrls.length(); i++)
        {
            JSONObject toolUrl = toolUrls.getJSONObject(i);
            JSONObject params = toolUrl.getJSONObject("params");
            JSONObject props = new JSONObject();
            for (String param : params.keySet())
                props.put(param, stringProperty(params.optString(param, param)));
            String description = toolUrl.getString("purpose") + " (fetches information from a configured web service and returns its response)";
            tools.put(functionTool(urlToolName(i, toolUrl), description, props, params.keySet().toArray(new String[0])));
        }
        return tools;
    }

    /**
     * Converts "params" in any supported shape into {"name": "description"}:
     * an object, a list of names, a list of {"name","description"} objects, or a list of single-key objects.
     */
    public static JSONObject normalizeParams(Object params)
    {
        JSONObject normalized = new JSONObject();
        if (params instanceof JSONObject)
        {
            JSONObject p = (JSONObject) params;
            for (String key : p.keySet())
                putParam(normalized, key, p.optString(key, ""));
        } else if (params instanceof JSONArray) {
            JSONArray p = (JSONArray) params;
            for (int i = 0; i < p.length(); i++)
            {
                Object item = p.get(i);
                if (item instanceof String)
                {
                    putParam(normalized, (String) item, "");
                } else if (item instanceof JSONObject) {
                    JSONObject o = (JSONObject) item;
                    if (o.has("name"))
                        putParam(normalized, o.optString("name"), o.optString("description", ""));
                    else
                        for (String key : o.keySet())
                            putParam(normalized, key, o.optString(key, ""));
                }
            }
        }
        return normalized;
    }

    private static void putParam(JSONObject params, String name, String description)
    {
        name = name == null ? "" : name.trim();
        if (name.matches("[A-Za-z0-9_\\-]{1,64}"))
            params.put(name, description == null || description.trim().isEmpty() ? name : description.trim());
    }

    /** Returns a validated, normalized copy of a toolUrls array; invalid entries are dropped. */
    public static JSONArray normalizeToolUrls(JSONArray toolUrls)
    {
        JSONArray normalized = new JSONArray();
        if (toolUrls == null)
            return normalized;
        for (int i = 0; i < toolUrls.length(); i++)
        {
            JSONObject entry = toolUrls.optJSONObject(i);
            if (entry == null)
                continue;
            String purpose = entry.optString("purpose", "").trim();
            String url = entry.optString("url", "").trim();
            if (purpose.isEmpty() || !(url.startsWith("http://") || url.startsWith("https://")))
                continue;
            JSONObject clean = new JSONObject();
            clean.put("purpose", purpose);
            clean.put("url", url);
            clean.put("params", normalizeParams(entry.opt("params")));
            String method = entry.optString("method", "").trim().toUpperCase();
            if ("GET".equals(method) || "POST".equals(method))
                clean.put("method", method);
            normalized.put(clean);
        }
        return normalized;
    }

    private JSONArray getToolUrls()
    {
        return normalizeToolUrls(this.bot.getBotOptions().optJSONArray("toolUrls"));
    }

    private static String urlToolName(int index, JSONObject toolUrl)
    {
        String slug = toolUrl.getString("purpose").toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        String name = "url_" + index + (slug.isEmpty() ? "" : "_" + slug);
        return name.length() > 64 ? name.substring(0, 64) : name;
    }

    private String executeToolUrl(JSONObject toolUrl, JSONObject args) throws IOException
    {
        JSONObject params = toolUrl.getJSONObject("params");
        String method = toolUrl.optString("method", params.length() > 0 ? "POST" : "GET");
        String url = toolUrl.getString("url");
        JSONObject values = new JSONObject();
        for (String param : params.keySet())
            values.put(param, args.optString(param, ""));
        if ("GET".equals(method) && values.length() > 0)
        {
            StringBuilder query = new StringBuilder();
            for (String param : values.keySet())
            {
                query.append(query.length() == 0 ? "" : "&");
                query.append(URLEncoder.encode(param, "UTF-8")).append("=").append(URLEncoder.encode(values.getString(param), "UTF-8"));
            }
            url += (url.contains("?") ? "&" : "?") + query;
        }
        String requestBody = "POST".equals(method) ? values.toString() : null;
        String request = method + " " + url + (requestBody != null ? " body=" + requestBody : "");
        try
        {
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(10000);
            con.setReadTimeout(20000);
            con.setRequestMethod(method);
            con.setRequestProperty("Accept", "application/json, text/plain, */*");
            con.setRequestProperty("User-Agent", "irc-gpt-bot");
            if (requestBody != null)
            {
                con.setDoOutput(true);
                con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                con.getOutputStream().write(requestBody.getBytes(StandardCharsets.UTF_8));
            }
            int status = con.getResponseCode();
            InputStream is = status >= 400 ? con.getErrorStream() : con.getInputStream();
            StringBuilder body = new StringBuilder();
            if (is != null)
            {
                try (InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8))
                {
                    char[] buf = new char[4096];
                    int n;
                    while ((n = reader.read(buf)) > 0 && body.length() < MAX_TOOL_URL_RESPONSE)
                        body.append(buf, 0, n);
                }
            }
            String text = body.length() > MAX_TOOL_URL_RESPONSE ? body.substring(0, MAX_TOOL_URL_RESPONSE) + "...(truncated)" : body.toString();
            String statusLine = "HTTP " + status + (con.getResponseMessage() != null ? " " + con.getResponseMessage() : "");
            this.bot.logAppend("tools.log", "\"" + toolUrl.getString("purpose") + "\" " + request
                + "\n    -> " + statusLine + " (" + con.getContentType() + ")"
                + "\n    " + text.replace("\n", "\n    "));
            return statusLine + "\n" + text;
        } catch (IOException e) {
            this.bot.logAppend("tools.log", "\"" + toolUrl.getString("purpose") + "\" " + request + "\n    -> FAILED: " + e);
            throw e;
        }
    }

    private String channelDefaultHint()
    {
        return this.targetIsChannel ? " Defaults to the current channel (" + this.target + ")." : "";
    }

    public String getInstructions()
    {
        String where = this.targetIsChannel ? "the IRC channel " + this.target : "a private IRC conversation with " + this.target;
        return "You are " + this.bot.getNickname() + ", an IRC bot currently in " + where + ". "
             + "Your plain text reply is automatically sent to " + this.target + ", so only use the messaging tools to reach someone else "
             + "or to respond in a different way (a notice or a /me action). If an action alone is a sufficient response you may reply with no text. "
             + "Use tools only when it is clearly appropriate or requested, and do not describe the tool calls in your reply."
             + (isToolEnabled(LIST_LOG_FILES) || isToolEnabled(READ_LOG_FILE) ? " " + PRIVACY_INSTRUCTIONS : "");
    }

    private static final String PRIVACY_INSTRUCTIONS =
        "Your log directory holds channel logs (#channel.log) and private conversation logs (nickname.log, one per user who messaged you privately; "
        + "the listing marks these \"private\": true). Private logs are confidential: you may read them and freely use what they contain to reason, "
        + "remember context and make better decisions, but never quote, reveal, summarize or hint at their contents, or say who you talked to privately, "
        + "to anyone other than the user that private conversation was with. Logs of secret, private, invite-only or keyed channels are confidential "
        + "to that channel in the same way.";

    /** Returns the nickname a private-conversation log belongs to, or null if the file isn't a private log. */
    private static String privateLogOwner(java.nio.file.Path relative)
    {
        if (relative.getNameCount() != 1)
            return null;
        String name = relative.getFileName().toString();
        if (!name.endsWith(".log") || name.equals("tools.log") || name.equals("exceptions.log"))
            return null;
        String owner = name.substring(0, name.length() - 4);
        return owner.isEmpty() || isChannelName(owner) ? null : owner;
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

    private Channel resolveJoinedChannel(JSONObject args)
    {
        String name = args.optString("channel", "").trim();
        if (name.isEmpty())
        {
            if (!this.targetIsChannel)
                throw new IllegalArgumentException("missing required argument 'channel'");
            name = this.target;
        }
        Optional<Channel> channel = client().getChannel(name);
        if (!channel.isPresent())
            throw new IllegalStateException("bot is not in channel " + name);
        return channel.get();
    }

    // Secret/private/invite-only/keyed channels may only be inspected or invited to from inside that channel
    private boolean isRestricted(Channel channel)
    {
        if (this.targetIsChannel && channel.getName().equalsIgnoreCase(this.target))
            return false;
        for (char mode : new char[] { 's', 'p', 'i', 'k' })
        {
            if (channel.getModes().containsMode(mode))
                return true;
        }
        return false;
    }

    private static String prefixedNick(Channel channel, String nick)
    {
        Optional<SortedSet<ChannelUserMode>> modes = channel.getUserModes(nick);
        if (modes.isPresent() && !modes.get().isEmpty())
            return modes.get().first().getNickPrefix() + nick;
        return nick;
    }

    private String getNames(JSONObject args)
    {
        Channel channel = resolveJoinedChannel(args);
        if (isRestricted(channel))
            return "error: the names list of " + channel.getName() + " cannot be shared outside that channel";
        JSONArray users = new JSONArray();
        for (String nick : channel.getNicknames())
            users.put(prefixedNick(channel, nick));
        JSONObject result = new JSONObject();
        result.put("channel", channel.getName());
        result.put("count", users.length());
        result.put("users", users);
        return result.toString();
    }

    private static JSONObject channelEntry(String name, int users, String topic)
    {
        JSONObject entry = new JSONObject();
        entry.put("channel", name);
        entry.put("users", users);
        entry.put("topic", topic);
        return entry;
    }

    private static boolean matches(JSONObject entry, String match)
    {
        if (match.isEmpty())
            return true;
        String m = match.toLowerCase();
        return entry.optString("channel").toLowerCase().contains(m) || entry.optString("topic").toLowerCase().contains(m);
    }

    /** Collects RPL_LIST replies until RPL_LISTEND. */
    public static class ListCollector
    {
        private final List<JSONObject> entries = Collections.synchronizedList(new ArrayList<JSONObject>());
        private final CountDownLatch done = new CountDownLatch(1);

        @Handler
        public void onNumeric(ClientReceiveNumericEvent event)
        {
            List<String> params = event.getParameters();
            if (event.getNumeric() == RPL_LIST && params.size() >= 3 && this.entries.size() < MAX_LIST_ENTRIES)
            {
                int users = 0;
                try { users = Integer.parseInt(params.get(2).trim()); } catch (NumberFormatException e) {}
                this.entries.add(channelEntry(params.get(1), users, params.size() > 3 ? params.get(3) : ""));
            } else if (event.getNumeric() == RPL_LISTEND) {
                this.done.countDown();
            }
        }
    }

    private String listChannels(JSONObject args) throws InterruptedException
    {
        String scope = args.optString("scope", "joined").trim().toLowerCase();
        String match = args.optString("match", "").trim();
        JSONArray channels = new JSONArray();
        JSONObject result = new JSONObject();
        if ("server".equals(scope))
        {
            int limit = Math.max(1, Math.min(100, args.optInt("limit", 25)));
            Client client = client();
            ListCollector collector = new ListCollector();
            boolean complete;
            synchronized (this.bot)
            {
                client.getEventManager().registerEventListener(collector);
                try
                {
                    client.sendRawLine("LIST");
                    complete = collector.done.await(15, TimeUnit.SECONDS);
                } finally {
                    client.getEventManager().unregisterEventListener(collector);
                }
            }
            List<JSONObject> found = new ArrayList<JSONObject>();
            synchronized (collector.entries)
            {
                for (JSONObject entry : collector.entries)
                    if (matches(entry, match))
                        found.add(entry);
            }
            found.sort((a, b) -> Integer.compare(b.optInt("users"), a.optInt("users")));
            for (int i = 0; i < found.size() && i < limit; i++)
                channels.put(found.get(i));
            result.put("totalMatching", found.size());
            if (!complete)
                result.put("note", "the server did not finish the channel list in time; results may be partial");
        } else {
            for (Channel channel : client().getChannels())
            {
                if (isRestricted(channel))
                    continue;
                JSONObject entry = channelEntry(channel.getName(), channel.getNicknames().size(), channel.getTopic().getValue().orElse(""));
                if (matches(entry, match))
                    channels.put(entry);
            }
        }
        result.put("scope", "server".equals(scope) ? "server" : "joined");
        result.put("channels", channels);
        return result.toString();
    }

    private String inviteUser(JSONObject args)
    {
        String nickname = requireArg(args, "nickname");
        if (isChannelName(nickname) || nickname.contains(" "))
            return "error: invalid nickname '" + nickname + "'";
        Channel channel = resolveJoinedChannel(args);
        if (isRestricted(channel))
            return "error: invites to " + channel.getName() + " can only be sent from inside that channel";
        if (channel.getNicknames().stream().anyMatch((n) -> n.equalsIgnoreCase(nickname)))
            return "error: " + nickname + " is already in " + channel.getName();
        client().sendRawLine("INVITE " + nickname + " " + channel.getName());
        record(channel.getName(), "(INVITE) " + nickname, "/invite " + nickname + " " + channel.getName());
        return "invite sent to " + nickname + " for " + channel.getName();
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
        // Only the active conversation's history is updated so the model sees what it did there
        if (to.equalsIgnoreCase(this.target))
            this.bot.getChatLog(this.target).add(new ChatMessage(this.bot.getNickname(), to, chatLogBody, new Date(System.currentTimeMillis())));
    }

    /** Resolves a path relative to the bot's log directory, refusing anything that escapes it. */
    private File resolveInLogFolder(String path) throws IOException
    {
        File base = this.bot.getLogFolder().getCanonicalFile();
        File file = new File(base, path).getCanonicalFile();
        if (!file.toPath().startsWith(base.toPath()))
            throw new IllegalArgumentException("path '" + path + "' is outside your log directory");
        if (!file.exists())
            throw new IllegalArgumentException("'" + path + "' does not exist");
        return file;
    }

    private File resolveNote(JSONObject args) throws IOException
    {
        return resolveNote(args, false);
    }

    private File resolveNote(JSONObject args, boolean allowNew) throws IOException
    {
        String name = requireArg(args, "name");
        if (name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..") || name.length() > 128)
            throw new IllegalArgumentException("'" + name + "' is not a valid note name");
        File notes = this.bot.getNotesFolder().getCanonicalFile();
        File file = new File(notes, name).getCanonicalFile();
        if (!notes.equals(file.getParentFile()))
            throw new IllegalArgumentException("'" + name + "' is not a valid note name");
        if (file.isDirectory() || (!allowNew && !file.isFile()))
            throw new IllegalArgumentException("note '" + name + "' does not exist");
        return file;
    }

    private String writeNote(JSONObject args) throws IOException
    {
        File file = resolveNote(args, true);
        if (!args.has("content"))
            throw new IllegalArgumentException("missing required argument 'content'");
        String content = args.optString("content", "");
        boolean append = args.optBoolean("append", false);
        long existing = (append && file.isFile()) ? file.length() : 0;
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (existing + bytes.length > MAX_NOTE_SIZE)
            return "error: notes are limited to " + MAX_NOTE_SIZE + " bytes";
        boolean created = !file.exists();
        if (append && existing > 0 && !content.startsWith("\n"))
        {
            String current = new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (!current.endsWith("\n"))
                bytes = ("\n" + content).getBytes(StandardCharsets.UTF_8);
        }
        if (append)
            java.nio.file.Files.write(file.toPath(), bytes, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        else
            java.nio.file.Files.write(file.toPath(), bytes);
        this.bot.logAppend("tools.log", "(" + (append ? "APPEND" : "WRITE") + " NOTE) " + file.getName() + " (" + bytes.length + " bytes) requested in " + this.target);
        return "note '" + file.getName() + "' " + (created ? "created" : (append ? "appended to" : "overwritten")) + " (" + file.length() + " bytes)";
    }

    private String listFiles(File base, File dir) throws IOException
    {
        if (!dir.isDirectory())
            return "error: '" + dir.getName() + "' is not a directory";
        java.nio.file.Path basePath = base.getCanonicalFile().toPath();
        java.nio.file.Path dirPath = dir.getCanonicalFile().toPath();
        File[] files = dir.listFiles();
        JSONArray entries = new JSONArray();
        if (files != null)
        {
            java.util.Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (int i = 0; i < files.length && i < MAX_FILE_LIST; i++)
            {
                File f = files[i];
                JSONObject entry = new JSONObject();
                java.nio.file.Path relative = basePath.relativize(dirPath.resolve(f.getName()));
                entry.put("path", relative.toString().replace('\\', '/'));
                entry.put("type", f.isDirectory() ? "directory" : "file");
                if (f.isFile() && base.getCanonicalFile().equals(this.bot.getLogFolder().getCanonicalFile()) && privateLogOwner(relative) != null)
                    entry.put("private", true);
                if (f.isFile())
                    entry.put("size", f.length());
                entry.put("modified", new Date(f.lastModified()).toString());
                entries.put(entry);
            }
        }
        JSONObject result = new JSONObject();
        result.put("directory", basePath.relativize(dirPath).toString().replace('\\', '/'));
        result.put("count", files == null ? 0 : files.length);
        result.put("files", entries);
        return result.toString();
    }

    private String readLogFile(JSONObject args) throws IOException
    {
        File file = resolveInLogFolder(requireArg(args, "path"));
        String text = readFile(file, args.optInt("lines", 0), true);
        java.nio.file.Path relative = this.bot.getLogFolder().getCanonicalFile().toPath().relativize(file.toPath());
        String owner = privateLogOwner(relative);
        if (owner == null || text.startsWith("error:"))
            return text;
        String notice = owner.equalsIgnoreCase(this.target)
            ? "(PRIVATE LOG of your private conversation with " + owner + ", the user you are talking to now. You may discuss it with them, but not with anyone else.)"
            : "(PRIVATE LOG of your private conversation with " + owner + ". Confidential: use it only to inform your own reasoning. Do not quote, reveal, summarize or hint at it, or mention that this conversation exists, in " + this.target + ".)";
        return notice + "\n" + text;
    }

    private String readNote(JSONObject args) throws IOException
    {
        File file = resolveNote(args);
        String text = new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        if (text.isEmpty())
            return "(empty file)";
        int offset = Math.max(0, args.optInt("offset", 0));
        if (offset >= text.length())
            return "error: offset " + offset + " is past the end of the note (" + text.length() + " characters)";
        int end = Math.min(text.length(), offset + MAX_FILE_READ);
        String chunk = text.substring(offset, end);
        if (offset == 0 && end == text.length())
            return chunk;
        return chunk + "\n...(characters " + offset + "-" + end + " of " + text.length()
            + (end < text.length() ? "; call " + READ_NOTE + " with offset " + end + " to continue)" : ")");
    }

    /** Returns file text capped at MAX_FILE_READ chars; tail keeps the end (newest log lines), otherwise the start. */
    private String readFile(File file, int lines, boolean tail) throws IOException
    {
        if (!file.isFile())
            return "error: '" + file.getName() + "' is not a file";
        String text = new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        if (lines > 0)
        {
            String[] all = text.split("\r?\n");
            int from = Math.max(0, all.length - lines);
            text = String.join("\n", java.util.Arrays.copyOfRange(all, from, all.length));
        }
        if (text.length() > MAX_FILE_READ)
            text = tail ? "(truncated)..." + text.substring(text.length() - MAX_FILE_READ) : text.substring(0, MAX_FILE_READ) + "...(truncated)";
        return text.isEmpty() ? "(empty file)" : text;
    }

    private String deleteNote(JSONObject args) throws IOException
    {
        File file = resolveNote(args);
        if (!file.delete())
            return "error: could not delete note '" + file.getName() + "'";
        this.bot.logAppend("tools.log", "(DELETE NOTE) " + file.getName() + " requested in " + this.target);
        return "note '" + file.getName() + "' deleted";
    }

    /** Executes a tool call and returns a short result string for the model. */
    public String execute(String name, String argumentsJson)
    {
        try
        {
            if (name != null && name.startsWith("url_"))
            {
                JSONArray toolUrls = getToolUrls();
                for (int i = 0; i < toolUrls.length(); i++)
                {
                    if (urlToolName(i, toolUrls.getJSONObject(i)).equals(name))
                    {
                        JSONObject args = (argumentsJson == null || argumentsJson.trim().isEmpty()) ? new JSONObject() : new JSONObject(argumentsJson);
                        return executeToolUrl(toolUrls.getJSONObject(i), args);
                    }
                }
                return "error: tool '" + name + "' is not available";
            }
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
                case GET_NAMES:
                    return getNames(args);
                case LIST_CHANNELS:
                    return listChannels(args);
                case INVITE_USER:
                    return inviteUser(args);
                case LIST_NOTES:
                    return listFiles(this.bot.getNotesFolder(), this.bot.getNotesFolder());
                case READ_NOTE:
                    return readNote(args);
                case WRITE_NOTE:
                    return writeNote(args);
                case DELETE_NOTE:
                    return deleteNote(args);
                case LIST_LOG_FILES:
                {
                    String path = args.optString("path", "").trim();
                    File dir = path.isEmpty() ? this.bot.getLogFolder() : resolveInLogFolder(path);
                    return listFiles(this.bot.getLogFolder(), dir);
                }
                case READ_LOG_FILE:
                    return readLogFile(args);
                default:
                    return "error: unknown tool '" + name + "'";
            }
        } catch (Exception e) {
            this.bot.log("Tool " + name + " failed - ", e);
            return "error: " + e.getMessage();
        }
    }
}

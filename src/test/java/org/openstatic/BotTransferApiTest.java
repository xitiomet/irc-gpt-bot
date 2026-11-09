package org.openstatic;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BotTransferApiTest
{
    @TempDir Path temporary;
    private JSONObject previousSettings;
    private File previousFile;
    private File previousLogs;
    private ConfigDirectory previousConfig;
    private UsageTracker previousUsage;
    private IRCGPTBotMain previousMain;
    private APIWebServer previousApi;
    private IRCGPTBotMain main;
    private final APIWebServer.ApiServlet servlet = new APIWebServer.ApiServlet();
    private final Map<String, Object> responseMetadata = new java.util.HashMap<>();

    @BeforeEach
    void setup() throws Exception
    {
        previousSettings = IRCGPTBotMain.settings;
        previousFile = IRCGPTBotMain.settingsFile;
        previousLogs = IRCGPTBotMain.logsFolder;
        previousConfig = IRCGPTBotMain.configDirectory;
        previousUsage = IRCGPTBotMain.usageTracker;
        previousMain = IRCGPTBotMain.instance;
        previousApi = APIWebServer.instance;
        IRCGPTBotMain.settings = new JSONObject().put("headless", true).put("apiServer", false)
            .put("apiPassword", "test-password").put("openAiKey", "master-key").put("bots", new JSONObject());
        IRCGPTBotMain.configDirectory = new ConfigDirectory(temporary.resolve("config"));
        IRCGPTBotMain.settingsFile = temporary.resolve("config/irc-gpt-bot.json").toFile();
        main = new IRCGPTBotMain();
        main.listeners.clear();
    }

    @AfterEach
    void cleanup() throws Exception
    {
        for (IRCGPTBot bot : main.getBots())
        {
            Field field = IRCGPTBot.class.getDeclaredField("mainThread");
            field.setAccessible(true);
            Thread thread = (Thread) field.get(bot);
            bot.shutdown();
            if (thread != null)
                thread.join(2000);
        }
        APIWebServer.instance.setState(false);
        IRCGPTBotMain.settings = previousSettings;
        IRCGPTBotMain.settingsFile = previousFile;
        IRCGPTBotMain.logsFolder = previousLogs;
        IRCGPTBotMain.configDirectory = previousConfig;
        IRCGPTBotMain.usageTracker = previousUsage;
        IRCGPTBotMain.instance = previousMain;
        APIWebServer.instance = previousApi;
    }

    private JSONObject request(String target, String password, JSONObject body) throws Exception
    {
        responseMetadata.clear();
        HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),
            new Class<?>[] {HttpServletRequest.class}, (proxy, method, args) -> {
                switch (method.getName())
                {
                    case "getHeader": return password;
                    case "getParameter": return null;
                    case "getParameterMap": return Map.of();
                    case "getPathInfo": return target;
                    case "getReader": return new BufferedReader(new StringReader(body == null ? "" : body.toString()));
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        StringWriter output = new StringWriter();
        HttpServletResponse response = (HttpServletResponse) Proxy.newProxyInstance(HttpServletResponse.class.getClassLoader(),
            new Class<?>[] {HttpServletResponse.class}, (proxy, method, args) -> {
                if ("getWriter".equals(method.getName()))
                    return new PrintWriter(output);
                if (method.getName().startsWith("set"))
                {
                    responseMetadata.put(method.getName().equals("setHeader") ? (String) args[0] : method.getName(),
                        args[args.length - 1]);
                    return null;
                }
                throw new UnsupportedOperationException(method.getName());
            });
        if (body == null)
            servlet.doGet(request, response);
        else
            servlet.doPost(request, response);
        return new JSONObject(output.toString());
    }

    private static JSONObject config()
    {
        return new JSONObject().put("server", "irc.example.net").put("nickname", "TransferBot")
            .put("enabled", false).put("model", "gpt-test").put("systemPreamble", "Hello \u00e9")
            .put("password", "irc-secret").put("operatorPassword", "oper-secret")
            .put("channels", new JSONObject().put("#private", new JSONObject().put("join", true).put("key", "channel-secret")))
            .put("gptTools", new JSONArray().put(GPTTools.SET_USER_MODE)).put("aliases", new JSONArray().put("helper bot"))
            .put("toolUrls", new JSONArray().put(new JSONObject().put("purpose", "lookup").put("url", "https://example.net/tool")
                .put("params", new JSONObject()))).put("customOption", "preserved");
    }

    @Test
    void importExportRoundTripsCompleteConfigAndNeverIncludesMasterCredentials() throws Exception
    {
        JSONObject original = config();
        JSONObject imported = request("/bots/import/", "test-password",
            new JSONObject().put("id", "transfer_[test]").put("botOptions", original));
        assertFalse(imported.has("error"), imported.toString());
        assertEquals("transfer_[test]", imported.getString("id"));
        assertFalse(main.getBotbyIdentifier("transfer_[test]").isEnabled());
        JSONObject exported = request("/bot/transfer_[test]/export/", "test-password", null);
        for (String field : original.keySet())
            assertEquals(original.get(field).toString(), exported.get(field).toString(), field);
        assertEquals("application/json", responseMetadata.get("setContentType"));
        assertEquals("UTF-8", responseMetadata.get("setCharacterEncoding"));
        assertEquals("no-store", responseMetadata.get("Cache-Control"));
        assertFalse(exported.has("openAiKey"));
        assertFalse(exported.has("apiPassword"));
        assertTrue(JsonFiles.read(temporary.resolve("config/transfer_[test].json")).similar(original));
        JSONObject copy = request("/bots/import/", "test-password",
            new JSONObject().put("id", "copy").put("botOptions", exported));
        assertFalse(copy.has("error"), copy.toString());
        assertTrue(request("/bot/copy/export/", "test-password", null).similar(exported));
    }

    @Test
    void rejectsUnauthorizedMalformedDuplicateAndReservedImportsWithoutOverwriting() throws Exception
    {
        JSONObject body = new JSONObject().put("id", "transfer").put("botOptions", config());
        assertTrue(request("/bots/import/", "wrong", body).has("error"));
        assertTrue(main.getBots().isEmpty());
        assertFalse(request("/bots/import/", "test-password", body).has("error"));
        assertTrue(request("/bot/transfer/export/", "wrong", null).has("error"));
        assertTrue(request("/bot/missing/export/", "test-password", null).has("error"));
        assertEquals(404, responseMetadata.get("setStatus"));
        for (String id : new String[] {"transfer", "TRANSFER", "../escape", "pricing", "CON"})
            assertTrue(request("/bots/import/", "test-password", new JSONObject(body.toString()).put("id", id)).has("error"), id);
        for (JSONObject bad : new JSONObject[] {new JSONObject(), config().put("bots", new JSONObject()),
            config().put("port", 70000), config().put("channels", new JSONArray()), config().put("enabled", "false"),
            config().put("toolUrls", new JSONArray().put("bad")), config().put("responsesApiUrl", "file:///tmp/api")})
            assertTrue(request("/bots/import/", "test-password",
                new JSONObject().put("id", "invalid").put("botOptions", bad)).has("error"), bad.toString());
        assertTrue(request("/bots/import/", "test-password", new JSONObject().put("id", "invalid")).has("error"));
        assertEquals(1, main.getBots().size());
        assertEquals("irc-secret", JsonFiles.read(temporary.resolve("config/transfer.json")).getString("password"));
        assertFalse(java.nio.file.Files.exists(temporary.resolve("config/invalid.json")));
    }
}

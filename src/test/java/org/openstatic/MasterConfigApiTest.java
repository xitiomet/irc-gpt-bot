package org.openstatic;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
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

class MasterConfigApiTest
{
    @TempDir Path temporary;
    private JSONObject previousSettings;
    private File previousLogs;
    private UsageTracker previousTracker;
    private final APIWebServer.ApiServlet servlet = new APIWebServer.ApiServlet();

    @BeforeEach
    void setup() throws Exception
    {
        previousSettings = IRCGPTBotMain.settings;
        previousLogs = IRCGPTBotMain.logsFolder;
        previousTracker = IRCGPTBotMain.usageTracker;
        IRCGPTBotMain.settings = new JSONObject().put("apiPassword", "test-password").put("openAiKey", "private-test-key");
        IRCGPTBotMain.logsFolder = temporary.resolve("logs").toFile();
        IRCGPTBotMain.usageTracker = new UsageTracker(temporary.resolve("config"), temporary.resolve("logs"));
    }

    @AfterEach
    void cleanup()
    {
        IRCGPTBotMain.settings = previousSettings;
        IRCGPTBotMain.logsFolder = previousLogs;
        IRCGPTBotMain.usageTracker = previousTracker;
    }

    private JSONObject request(String password, JSONObject body) throws Exception
    {
        HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),
            new Class<?>[] {HttpServletRequest.class}, (proxy, method, arguments) -> {
                switch (method.getName())
                {
                    case "getHeader": return password;
                    case "getParameter": return null;
                    case "getParameterMap": return Map.of();
                    case "getPathInfo": return "/master/";
                    case "getReader": return new BufferedReader(new StringReader(body == null ? "" : body.toString()));
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        StringWriter output = new StringWriter();
        HttpServletResponse response = (HttpServletResponse) Proxy.newProxyInstance(HttpServletResponse.class.getClassLoader(),
            new Class<?>[] {HttpServletResponse.class}, (proxy, method, arguments) -> {
                if ("getWriter".equals(method.getName()))
                    return new PrintWriter(output);
                if (method.getName().startsWith("set"))
                    return null;
                throw new UnsupportedOperationException(method.getName());
            });
        if (body == null)
            servlet.doGet(request, response);
        else
            servlet.doPost(request, response);
        return new JSONObject(output.toString());
    }

    @Test
    void getReportsConfigurationWithoutExposingEitherCredential() throws Exception
    {
        JSONObject response = request("test-password", null);
        assertTrue(response.getBoolean("hasOpenAiKey"));
        assertTrue(response.getJSONObject("pricing").getInt("modelCount") > 0);
        assertFalse(response.toString().contains("private-test-key"));
        assertFalse(response.toString().contains("test-password"));
        assertFalse(response.has("openAiKey"));
        assertFalse(response.has("apiPassword"));
    }

    @Test
    void rejectsUnauthenticatedReadsAndWrites() throws Exception
    {
        assertTrue(request("wrong", null).has("error"));
        assertFalse(request(null, null).has("hasOpenAiKey"));
        assertTrue(request("wrong", new JSONObject().put("action", "previewPricing")).has("error"));
    }

    @Test
    void appliesOnlyServerPreviewAndReportsInvalidActionsAndTokens() throws Exception
    {
        JSONObject rates = new JSONObject().put("inputPerMillion", 1).put("cachedInputPerMillion", 0.1).put("outputPerMillion", 5);
        JSONObject preview = IRCGPTBotMain.usageTracker.previewPrices(new JSONObject().put("currency", "USD")
            .put("source", OpenAIPriceUpdater.SOURCE).put("models", new JSONObject().put("gpt-test", rates)).put("skipped", new JSONArray()));
        assertTrue(request("test-password", new JSONObject().put("action", "applyPricing").put("token", "wrong")).has("error"));
        JSONObject response = request("test-password", new JSONObject().put("action", "applyPricing").put("token", preview.getString("token"))
            .put("models", new JSONObject().put("gpt-test", new JSONObject().put("inputPerMillion", 0))));
        assertEquals(1, response.getJSONObject("pricing").getInt("updated"));
        assertEquals(1, JsonFiles.read(temporary.resolve("config/pricing.json")).getJSONObject("models")
            .getJSONObject("gpt-test").getInt("inputPerMillion"));
        assertTrue(request("test-password", new JSONObject().put("action", "unknown")).has("error"));
    }
}

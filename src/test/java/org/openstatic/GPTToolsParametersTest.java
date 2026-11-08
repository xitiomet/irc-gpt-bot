package org.openstatic;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GPTToolsParametersTest
{
    @TempDir Path temporary;
    private HttpServer server;
    private String url;
    private final AtomicReference<JSONObject> received = new AtomicReference<>();
    private final AtomicReference<String> method = new AtomicReference<>();

    @BeforeEach
    void setup() throws Exception
    {
        IRCGPTBotMain.logsFolder = temporary.resolve("logs").toFile();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/notify", exchange -> {
            method.set(exchange.getRequestMethod());
            JSONObject values = new JSONObject();
            if ("GET".equals(exchange.getRequestMethod()))
            {
                String query = exchange.getRequestURI().getRawQuery();
                if (query != null)
                    for (String pair : query.split("&"))
                    {
                        String[] parts = pair.split("=", 2);
                        values.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                            parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
                    }
            } else {
                values = new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            }
            received.set(values);
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody())
            {
                output.write(body);
            }
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/notify";
    }

    @AfterEach
    void cleanup()
    {
        server.stop(0);
    }

    private static JSONObject exactly(String value)
    {
        return new JSONObject().put("mode", "exactly").put("value", value);
    }

    private GPTTools tools(JSONObject params, String requestMethod)
    {
        JSONObject tool = new JSONObject().put("purpose", "Notify administrator").put("url", url).put("params", params);
        if (requestMethod != null)
            tool.put("method", requestMethod);
        JSONObject options = new JSONObject().put("gptTools", new JSONArray()).put("enabled", false)
            .put("toolUrls", new JSONArray().put(tool));
        return new GPTTools(new IRCGPTBot("testbot", options), "#test");
    }

    private static String execute(GPTTools tools, JSONObject args)
    {
        String name = tools.getToolDefinitions().getJSONObject(0).getString("name");
        return tools.execute(name, args.toString());
    }

    @Test
    void interpretedParametersKeepExistingSchemaAndExactParametersAreNotAiArguments()
    {
        GPTTools tools = tools(new JSONObject().put("_do", exactly("notification")).put("subject", "Notification subject"), "GET");
        JSONObject schema = tools.getToolDefinitions().getJSONObject(0).getJSONObject("parameters");
        assertFalse(schema.getJSONObject("properties").has("_do"));
        assertEquals("Notification subject", schema.getJSONObject("properties").getJSONObject("subject").getString("description"));
        assertEquals(1, schema.getJSONArray("required").length());
        assertEquals("subject", schema.getJSONArray("required").getString(0));
        assertFalse(schema.getBoolean("additionalProperties"));
    }

    @Test
    void getEnforcesExactValueAndInterpretsOtherFields()
    {
        GPTTools tools = tools(new JSONObject().put("_do", exactly("notification"))
            .put("subject", "Notification subject").put("body", "Notification body"), "GET");
        String result = execute(tools, new JSONObject().put("_do", "wrong-action")
            .put("subject", "AI subject & details").put("body", "AI body").put("unexpected", "ignored"));
        assertTrue(result.startsWith("HTTP 200"), result);
        assertEquals("GET", method.get());
        assertEquals("notification", received.get().getString("_do"));
        assertEquals("AI subject & details", received.get().getString("subject"));
        assertEquals("AI body", received.get().getString("body"));
        assertFalse(received.get().has("unexpected"));
    }

    @Test
    void postEnforcesExactValuesEvenWhenAiOmitsThem()
    {
        GPTTools tools = tools(new JSONObject().put("_do", exactly("notification"))
            .put("subject", "Subject").put("blank", exactly("")), "POST");
        assertTrue(execute(tools, new JSONObject().put("subject", "AI subject")).startsWith("HTTP 200"));
        assertEquals("POST", method.get());
        assertEquals("notification", received.get().getString("_do"));
        assertEquals("", received.get().getString("blank"));
        assertEquals("AI subject", received.get().getString("subject"));
    }

    @Test
    void exactOnlyToolsRemainCallableAndAutoSelectPost()
    {
        GPTTools tools = tools(new JSONObject().put("_do", exactly("notification")), null);
        JSONObject schema = tools.getToolDefinitions().getJSONObject(0).getJSONObject("parameters");
        assertEquals(0, schema.getJSONObject("properties").length());
        assertEquals(0, schema.getJSONArray("required").length());
        assertTrue(execute(tools, new JSONObject()).startsWith("HTTP 200"));
        assertEquals("POST", method.get());
        assertEquals("notification", received.get().getString("_do"));
    }

    @Test
    void exactWhitespaceAndSpecialCharactersSurviveNormalizationAndBothTransports()
    {
        String value = "  literal + & = / \u00e9 \n ";
        for (String requestMethod : new String[] {"GET", "POST"})
        {
            GPTTools tools = tools(new JSONObject().put("literal", exactly(value)), requestMethod);
            assertTrue(execute(tools, new JSONObject().put("literal", "changed")).startsWith("HTTP 200"));
            assertEquals(value, received.get().getString("literal"));
        }
    }

    @Test
    void legacyParameterShapesStillNormalizeToDescriptions()
    {
        assertEquals("description", GPTTools.normalizeParams(new JSONObject().put("field", " description ")).getString("field"));
        JSONObject params = GPTTools.normalizeParams(new JSONArray().put("name")
            .put(new JSONObject().put("name", "named").put("description", "Named description"))
            .put(new JSONObject().put("single", "Single description")));
        assertEquals("name", params.getString("name"));
        assertEquals("Named description", params.getString("named"));
        assertEquals("Single description", params.getString("single"));
        assertEquals("field", GPTTools.normalizeParams(new JSONObject().put("field", "")).getString("field"));
        assertEquals("123", GPTTools.normalizeParams(new JSONObject().put("field", 123)).getString("field"));
        assertEquals("description", GPTTools.normalizeParams(new JSONObject().put("field",
            new JSONObject().put("mode", "interpret").put("description", "description"))).getString("field"));
    }

    @Test
    void exactDescriptorsRoundTripThroughListsAndConfigurationFiles() throws Exception
    {
        JSONObject params = GPTTools.normalizeParams(new JSONArray()
            .put(new JSONObject().put("name", "_do").put("mode", "exactly").put("value", "notification"))
            .put(new JSONObject().put("blank", exactly(""))));
        assertEquals("notification", params.getJSONObject("_do").getString("value"));
        assertEquals("", params.getJSONObject("blank").getString("value"));
        JSONArray normalized = GPTTools.normalizeToolUrls(new JSONArray().put(new JSONObject().put("purpose", "Notify")
            .put("url", url).put("method", "GET").put("params", params)));
        assertTrue(normalized.similar(GPTTools.normalizeToolUrls(normalized)));
        ConfigDirectory config = new ConfigDirectory(temporary.resolve("config"));
        JSONObject settings = config.load(null);
        settings.getJSONObject("bots").put("testbot", new JSONObject().put("toolUrls", normalized));
        config.save(settings);
        JSONArray reloaded = new ConfigDirectory(config.directory).load(null).getJSONObject("bots")
            .getJSONObject("testbot").getJSONArray("toolUrls");
        assertTrue(normalized.similar(reloaded));
    }

    @Test
    void malformedDescriptorsFailExplicitlyInsteadOfBecomingAiDescriptions()
    {
        for (JSONObject definition : new JSONObject[] {
            new JSONObject().put("mode", "exactly"),
            new JSONObject().put("mode", "exactly").put("value", 123),
            new JSONObject().put("mode", "unknown").put("value", "notification"),
            new JSONObject().put("value", "notification")
        })
            assertThrows(IllegalArgumentException.class,
                () -> GPTTools.normalizeParams(new JSONObject().put("_do", definition)));
    }
}

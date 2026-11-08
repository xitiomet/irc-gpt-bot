package org.openstatic;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChatGPTUsageTest
{
    @TempDir Path temporary;
    private HttpServer server;
    private ChatGPT chat;
    private JSONObject options;
    private final List<JSONObject> requests = new ArrayList<>();
    private final List<JSONObject> responses = new ArrayList<>();
    private boolean failSecond;

    @BeforeEach
    void setup() throws Exception
    {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", exchange -> {
            requests.add(new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            int index = requests.size() - 1;
            int status = failSecond && index == 1 ? 500 : 200;
            byte[] body = (status == 500 ? new JSONObject().put("error", "test failure") : responses.get(index))
                .toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody())
            {
                output.write(body);
            }
        });
        server.start();
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/responses";
        IRCGPTBotMain.instance = null;
        IRCGPTBotMain.logsFolder = temporary.resolve("logs").toFile();
        IRCGPTBotMain.settings = new JSONObject().put("openAiKey", "test").put("responsesApiUrl", endpoint);
        IRCGPTBotMain.usageTracker = new UsageTracker(temporary.resolve("config"), temporary.resolve("logs"));
        JSONObject pricing = JsonFiles.read(temporary.resolve("config/pricing.json"));
        pricing.getJSONObject("endpoints").put(endpoint,
            new JSONObject().put("gpt-4o-mini", pricing.getJSONObject("models").getJSONObject("gpt-4o-mini")));
        JsonFiles.write(temporary.resolve("config/pricing.json"), pricing);
        options = new JSONObject().put("nickname", "shared-nickname").put("model", "requested-model");
        chat = new ChatGPT();
    }

    @AfterEach
    void cleanup()
    {
        chat.shutdown();
        server.stop(0);
        IRCGPTBotMain.usageTracker = null;
        IRCGPTBotMain.instance = null;
    }

    private static JSONObject textResponse()
    {
        return UsageTrackerTest.response("gpt-4o-mini-2024-07-18", 1000, 200, 100)
            .put("output", new JSONArray().put(new JSONObject().put("type", "message").put("content",
                new JSONArray().put(new JSONObject().put("type", "output_text").put("text", "Hello there")))));
    }

    private static JSONObject toolResponse()
    {
        return UsageTrackerTest.response("gpt-4o-mini-2024-07-18", 1000, 200, 100).put("output",
            new JSONArray().put(new JSONObject().put("type", "function_call").put("name", "test_tool")
                .put("call_id", "test-call").put("arguments", "{}")));
    }

    private static GPTTools tools()
    {
        return new GPTTools(null, "#test") {
            @Override public JSONArray getToolDefinitions()
            {
                return new JSONArray().put(new JSONObject().put("type", "function").put("name", "test_tool")
                    .put("parameters", new JSONObject().put("type", "object")));
            }
            @Override public String getInstructions() { return "Test instructions"; }
            @Override public String execute(String name, String arguments) { return "Tool result"; }
        };
    }

    @Test
    void tracksGreetingArrayCallsByBotIdAndActualResponseModel() throws Exception
    {
        responses.add(textResponse());
        ChatMessage reply = chat.callChatGPT("bot-id", options, new JSONArray()).get(5, TimeUnit.SECONDS);
        assertEquals("Hello there", reply.getBody());
        JSONObject totals = IRCGPTBotMain.usageTracker.forBot("bot-id");
        assertEquals(1100, totals.getLong("tokensSpent"));
        assertEquals(1, totals.getLong("requests"));
        assertTrue(totals.getJSONObject("models").has("gpt-4o-mini-2024-07-18"));
        assertEquals(0, IRCGPTBotMain.usageTracker.forBot("shared-nickname").getLong("requests"));
        assertEquals("requested-model", requests.get(0).getString("model"));
    }

    @Test
    void tracksEachToolRoundAndPreservesToolOutputs() throws Exception
    {
        responses.add(toolResponse());
        responses.add(textResponse());
        ChatMessage reply = chat.callChatGPT("bot", options, new JSONArray(), "user", tools()).get(5, TimeUnit.SECONDS);
        assertEquals("Hello there", reply.getBody());
        assertEquals(2, requests.size());
        assertEquals("user", requests.get(1).getString("user"));
        assertEquals("function_call_output", requests.get(1).getJSONArray("input").getJSONObject(1).getString("type"));
        JSONObject totals = IRCGPTBotMain.usageTracker.forBot("bot");
        assertEquals(2, totals.getLong("requests"));
        assertEquals(2200, totals.getLong("tokensSpent"));
        assertEquals(0, totals.getLong("unpricedTokensSpent"));
        assertEquals(0, new java.math.BigDecimal("0.00039").compareTo(totals.getBigDecimal("costEstimate")));
    }

    @Test
    void tracksChannelAndPrivateChatLogCallsIndependently() throws Exception
    {
        ChatLog messages = new ChatLog(null) {
            @Override public JSONArray getGPTMessages()
            {
                return new JSONArray().put(new JSONObject().put("role", "user").put("content", "hello"));
            }
            @Override public ChatMessage getLastMessage()
            {
                return new ChatMessage("sender", null, "hello", new java.util.Date());
            }
        };
        responses.add(textResponse());
        responses.add(textResponse());
        assertEquals("Hello there", chat.callChatGPT("channel-bot", options, messages, tools()).get(5, TimeUnit.SECONDS).getBody());
        assertEquals("Hello there", chat.callChatGPT("private-bot", options, messages, tools()).get(5, TimeUnit.SECONDS).getBody());
        assertEquals(1100, IRCGPTBotMain.usageTracker.forBot("channel-bot").getLong("tokensSpent"));
        assertEquals(1100, IRCGPTBotMain.usageTracker.forBot("private-bot").getLong("tokensSpent"));
        assertEquals("sender", requests.get(0).getString("user"));
        assertEquals("sender", requests.get(1).getString("user"));
    }

    @Test
    void deliversTriggeredMemoriesInChannelAndPrivateApiRequests() throws Exception
    {
        IRCGPTBot bot = new IRCGPTBot("memory-bot", new JSONObject().put("enabled", false)
            .put("nickname", "assistant").put("gptTools", new JSONArray()));
        bot.saveNote("nickname.charlie.txt", "NICKNAME FACT");
        bot.saveNote("token.flumbar.txt", "TOKEN FACT");
        for (String target : new String[] { "#lobby", "assistant" })
        {
            ChatLog log = new ChatLog(bot);
            ChatMessage trigger = new ChatMessage("charlie", target, "flumbar injury", new java.util.Date());
            log.add(trigger);
            log.add(new ChatMessage("dana", target, "later unrelated message", new java.util.Date()));
            responses.add(textResponse());
            assertEquals("Hello there", chat.callChatGPT("memory-bot", options, log,
                new GPTTools(bot, target), trigger).get(5, TimeUnit.SECONDS).getBody());
            JSONObject request = requests.get(requests.size() - 1);
            assertEquals("charlie", request.getString("user"));
            JSONObject system = request.getJSONArray("input").getJSONObject(0);
            assertEquals("system", system.getString("role"));
            assertTrue(system.getString("content").contains("NICKNAME FACT"));
            assertTrue(system.getString("content").contains("TOKEN FACT"));
            assertFalse(request.toString().contains("later unrelated message"));
        }
    }

    @Test
    void retainsPaidRoundsWhenLaterApiRequestFails() throws Exception
    {
        responses.add(toolResponse());
        failSecond = true;
        ChatMessage reply = chat.callChatGPT("bot", options, new JSONArray(), null, tools()).get(5, TimeUnit.SECONDS);
        assertTrue(reply.getBody().contains("trouble thinking"));
        assertEquals(2, requests.size());
        assertEquals(1, IRCGPTBotMain.usageTracker.forBot("bot").getLong("requests"));
        assertEquals(1100, new UsageTracker(temporary.resolve("config"), temporary.resolve("logs"))
            .forBot("bot").getLong("tokensSpent"));
    }

    @Test
    void missingUsageStillDeliversReplyAndReportsUntrackedRequest() throws Exception
    {
        JSONObject response = textResponse();
        response.remove("usage");
        responses.add(response);
        assertEquals("Hello there", chat.callChatGPT("bot", options, new JSONArray()).get(5, TimeUnit.SECONDS).getBody());
        assertEquals(1, IRCGPTBotMain.usageTracker.forBot("bot").getLong("untrackedRequests"));
    }

    @Test
    void legacyOverloadResolvesConfiguredBotWithoutUsingNickname() throws Exception
    {
        IRCGPTBotMain.settings.put("bots", new JSONObject().put("bot-id", options));
        responses.add(textResponse());
        assertEquals("Hello there", chat.callChatGPT(options, new JSONArray()).get(5, TimeUnit.SECONDS).getBody());
        assertEquals(1, IRCGPTBotMain.usageTracker.forBot("bot-id").getLong("requests"));
    }

    @Test
    void noIdentifierDoesNotSendUnattributablePaidRequest() throws Exception
    {
        assertTrue(chat.callChatGPT(options, new JSONArray()).get(5, TimeUnit.SECONDS).getBody().contains("trouble thinking"));
        assertTrue(requests.isEmpty());
    }
}

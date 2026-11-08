package org.openstatic;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UsageTrackerTest
{
    private static final String OPENAI = "https://api.openai.com/v1/responses";
    @TempDir Path temporary;
    private Path config;
    private Path logs;
    private UsageTracker tracker;

    @BeforeEach
    void setup() throws Exception
    {
        config = temporary.resolve("config");
        logs = temporary.resolve("logs");
        IRCGPTBotMain.logsFolder = logs.toFile();
        tracker = new UsageTracker(config, logs);
    }

    static JSONObject response(String model, long input, long cached, long output)
    {
        return new JSONObject().put("model", model).put("service_tier", "default")
            .put("usage", new JSONObject().put("input_tokens", input).put("output_tokens", output)
                .put("total_tokens", input + output)
                .put("input_tokens_details", new JSONObject().put("cached_tokens", cached)));
    }

    private static void assertCost(String expected, JSONObject totals)
    {
        assertEquals(0, new BigDecimal(expected).compareTo(totals.getBigDecimal("costEstimate")));
    }

    @Test
    void countsCachedTokensOnceAndPersistsPerBotAndModelTotals() throws Exception
    {
        tracker.record("alice", OPENAI, response("gpt-4o-mini", 1000, 200, 100));
        JSONObject totals = tracker.forBot("alice");
        assertEquals(1100, totals.getLong("tokensSpent"));
        assertEquals(1000, totals.getLong("inputTokensSpent"));
        assertEquals(100, totals.getLong("outputTokensSpent"));
        assertEquals(200, totals.getLong("cachedTokensSpent"));
        assertEquals(0, totals.getLong("unpricedTokensSpent"));
        assertCost("0.000195", totals);
        tracker.record("bob", OPENAI, response("gpt-4.1-mini", 10, 0, 10));
        assertEquals(1100, tracker.forBot("alice").getLong("tokensSpent"));
        assertEquals(20, tracker.forBot("bob").getLong("tokensSpent"));
        assertCost("0.000195", new UsageTracker(config, logs).forBot("alice"));
        assertCost("0.000195", totals.getJSONObject("models").getJSONObject("gpt-4o-mini"));
        assertEquals(0, tracker.forBot("not-created").getLong("tokensSpent"));
        assertFalse(JsonFiles.read(logs.resolve("usage.json")).getJSONObject("bots").has("not-created"));
    }

    @Test
    void supportsExactSnapshotOverridesAndBaseModelFallback() throws Exception
    {
        tracker.record("bot", OPENAI, response("gpt-4o-mini-2024-07-18", 1000000, 0, 0));
        assertCost("0.15", tracker.forBot("bot"));
        JSONObject pricing = JsonFiles.read(config.resolve("pricing.json"));
        pricing.getJSONObject("models").put("gpt-4o-mini-2024-07-18",
            new JSONObject().put("inputPerMillion", 1).put("outputPerMillion", 2));
        JsonFiles.write(config.resolve("pricing.json"), pricing);
        tracker.record("bot", OPENAI, response("gpt-4o-mini-2024-07-18", 1000000, 0, 0));
        assertCost("1.15", tracker.forBot("bot"));
    }

    @Test
    void marksUnknownModelsNonDefaultTiersAndCustomEndpointsUnpriced()
    {
        tracker.record("bot", OPENAI, response("unknown", 100, 0, 10));
        tracker.record("bot", OPENAI, response("gpt-4o-mini", 100, 0, 10).put("service_tier", "priority"));
        tracker.record("bot", "http://localhost/responses", response("gpt-4o-mini", 100, 0, 10));
        JSONObject totals = tracker.forBot("bot");
        assertEquals(330, totals.getLong("unpricedTokensSpent"));
        assertEquals(3, totals.getLong("requests"));
        assertCost("0", totals);
    }

    @Test
    void supportsExplicitCustomEndpointPricingAndKeepsHistoricalCosts() throws Exception
    {
        String endpoint = "http://localhost/responses";
        JSONObject pricing = JsonFiles.read(config.resolve("pricing.json"));
        pricing.getJSONObject("endpoints").put(endpoint,
            new JSONObject().put("local", new JSONObject().put("inputPerMillion", 1).put("outputPerMillion", 2)));
        JsonFiles.write(config.resolve("pricing.json"), pricing);
        tracker.record("bot", endpoint, response("local", 1000000, 0, 1000000));
        assertCost("3", tracker.forBot("bot"));
        pricing.getJSONObject("endpoints").getJSONObject(endpoint).getJSONObject("local").put("inputPerMillion", 2);
        JsonFiles.write(config.resolve("pricing.json"), pricing);
        tracker.record("bot", endpoint, response("local", 1000000, 0, 1000000));
        assertCost("7", tracker.forBot("bot"));
        assertCost("7", new UsageTracker(config, logs).forBot("bot"));
    }

    @Test
    void appliesLongContextMultipliersOnlyAboveThreshold() throws Exception
    {
        JSONObject pricing = JsonFiles.read(config.resolve("pricing.json"));
        pricing.getJSONObject("models").put("long", new JSONObject().put("inputPerMillion", 1)
            .put("cachedInputPerMillion", 0.5).put("outputPerMillion", 2)
            .put("longContextThreshold", 100).put("longContextInputMultiplier", 2).put("longContextOutputMultiplier", 3));
        JsonFiles.write(config.resolve("pricing.json"), pricing);
        tracker.record("at", OPENAI, response("long", 100, 20, 10));
        tracker.record("above", OPENAI, response("long", 101, 20, 10));
        assertCost("0.000110", tracker.forBot("at"));
        assertCost("0.000242", tracker.forBot("above"));
    }

    @Test
    void missingAndInvalidUsageAreVisibleWithoutInventedTokens()
    {
        tracker.record("bot", OPENAI, new JSONObject().put("model", "gpt-4o-mini"));
        tracker.record("bot", OPENAI, response("gpt-4o-mini", 100, 101, 10));
        tracker.record("bot", OPENAI, response("gpt-4o-mini", 100, 0, 10)
            .put("usage", new JSONObject().put("input_tokens", 1.5).put("output_tokens", 0).put("total_tokens", 1.5)));
        JSONObject totals = tracker.forBot("bot");
        assertEquals(3, totals.getLong("requests"));
        assertEquals(3, totals.getLong("untrackedRequests"));
        assertEquals(0, totals.getLong("tokensSpent"));
        assertTrue(totals.has("lastTrackingError"));
    }

    @Test
    void supportsCompletionsUsageShapeAndRejectsNegativeOrInconsistentCounts()
    {
        JSONObject response = new JSONObject().put("usage", new JSONObject().put("prompt_tokens", 10)
            .put("completion_tokens", 5).put("total_tokens", 15)
            .put("prompt_tokens_details", new JSONObject().put("cached_tokens", 2)));
        assertEquals(2, OpenAIUsage.tokens(response).getLong("cachedTokensSpent"));
        assertThrows(IllegalArgumentException.class, () -> OpenAIUsage.tokens(response("model", -1, 0, 10)));
        assertThrows(IllegalArgumentException.class, () -> OpenAIUsage.tokens(response("model", 1, 0, 10)
            .put("usage", new JSONObject().put("input_tokens", 1).put("output_tokens", 10).put("total_tokens", 12))));
    }

    @Test
    void corruptOrInvalidPricingDoesNotDiscardUsage() throws Exception
    {
        Files.writeString(config.resolve("pricing.json"), "{ invalid");
        tracker.record("bot", OPENAI, response("gpt-4o-mini", 10, 0, 10));
        JsonFiles.write(config.resolve("pricing.json"), new JSONObject().put("currency", "USD").put("models",
            new JSONObject().put("gpt-4o-mini", new JSONObject().put("inputPerMillion", -1).put("outputPerMillion", 1))));
        tracker.record("bot", OPENAI, response("gpt-4o-mini", 10, 0, 10));
        assertEquals(40, tracker.forBot("bot").getLong("tokensSpent"));
        assertEquals(40, tracker.forBot("bot").getLong("unpricedTokensSpent"));
    }

    @Test
    void corruptUsageIsNotReplaced() throws Exception
    {
        Files.writeString(logs.resolve("usage.json"), "{ invalid");
        assertThrows(IOException.class, () -> new UsageTracker(config, logs));
        assertEquals("{ invalid", Files.readString(logs.resolve("usage.json")));
    }

    @Test
    void retainsPendingTotalsAndReportsFailedWritesThenRetries() throws Exception
    {
        Files.delete(logs.resolve("usage.json"));
        Files.createDirectory(logs.resolve("usage.json"));
        tracker.record("bot", OPENAI, response("gpt-4o-mini", 10, 0, 10));
        assertTrue(tracker.forBot("bot").has("trackingError"));
        assertEquals(20, tracker.forBot("bot").getLong("tokensSpent"));
        Files.delete(logs.resolve("usage.json"));
        tracker.record("bot", OPENAI, response("gpt-4o-mini", 10, 0, 10));
        assertFalse(tracker.forBot("bot").has("trackingError"));
        assertEquals(40, new UsageTracker(config, logs).forBot("bot").getLong("tokensSpent"));
    }

    @Test
    void concurrentRequestsDoNotLoseIncrements() throws Exception
    {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try
        {
            for (int i = 0; i < 40; i++)
                executor.submit(() -> tracker.record("bot", OPENAI, response("gpt-4o-mini", 10, 0, 10)));
        } finally {
            executor.shutdown();
        }
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(40, tracker.forBot("bot").getLong("requests"));
        assertEquals(800, new UsageTracker(config, logs).forBot("bot").getLong("tokensSpent"));
    }
}

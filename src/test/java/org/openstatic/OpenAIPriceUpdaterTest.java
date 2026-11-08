package org.openstatic;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenAIPriceUpdaterTest
{
    @TempDir Path temporary;
    private static final String SIMPLE = "| Model | Input | Output | Cached input |\n| --- | --- | --- | --- |\n";
    private static final String LONG = "| Model | Short context input | Short context output | Short context cached input | Short context 30m write | Short context 12h write | Long context input | Long context output | Long context cached input | Long context 30m write | Long context 12h write |\n";

    private static String document(String rows)
    {
        return "Prices per 1M tokens\n### Standard pricing data\n" + rows
            + "\n### Batch pricing data\n" + SIMPLE + "| gpt-5-mini | $0.01 | $0.02 | $0.001 |\n";
    }

    private static JSONObject fetched() throws IOException
    {
        return OpenAIPriceUpdater.parse(document(SIMPLE
            + "| gpt-5-mini | $0.25 | $2.00 | $0.025 |\n"
            + "| gpt-5-pro | $15.00 | $120.00 | - |\n"), url -> "");
    }

    @Test
    void parsesOnlyStandardRatesAndReportsUnsupportedModels() throws Exception
    {
        JSONObject result = fetched();
        JSONObject row = result.getJSONObject("models").getJSONObject("gpt-5-mini");
        assertEquals(1, result.getJSONObject("models").length());
        assertEquals(0, new BigDecimal("0.25").compareTo(row.getBigDecimal("inputPerMillion")));
        assertEquals(0, new BigDecimal("2.00").compareTo(row.getBigDecimal("outputPerMillion")));
        assertEquals(0, new BigDecimal("0.025").compareTo(row.getBigDecimal("cachedInputPerMillion")));
        assertTrue(result.getJSONArray("skipped").getString(0).contains("gpt-5-pro"));
        assertEquals(OpenAIPriceUpdater.SOURCE, result.getString("source"));
    }

    @Test
    void verifiesLongContextThresholdAndUsesItsRates() throws Exception
    {
        JSONObject result = OpenAIPriceUpdater.parse(document(LONG
            + "| gpt-6-luna | $0.10 | $0.50 | $0.01 | $0.125 | $0.25 | $0.20 | $0.75 | $0.02 | $0.25 | $0.50 |\n"),
            url -> {
                assertEquals("https://developers.openai.com/api/docs/models/gpt-6-luna", url);
                return "Prompts with more than 272K input tokens are priced at higher rates.";
            });
        JSONObject row = result.getJSONObject("models").getJSONObject("gpt-6-luna");
        assertEquals(272000, row.getLong("longContextThreshold"));
        assertEquals(2, row.getDouble("longContextInputMultiplier"));
        assertEquals(1.5, row.getDouble("longContextOutputMultiplier"));
        JSONObject below = OpenAIUsage.tokens(UsageTrackerTest.response("gpt-6-luna", 272000, 20000, 100));
        JSONObject above = OpenAIUsage.tokens(UsageTrackerTest.response("gpt-6-luna", 272001, 20000, 100));
        OpenAIUsage.price(below, row);
        OpenAIUsage.price(above, row);
        assertEquals(0, new BigDecimal("0.02545").compareTo(below.getBigDecimal("costEstimate")));
        assertEquals(0, new BigDecimal("0.0508752").compareTo(above.getBigDecimal("costEstimate")));
    }

    @Test
    void failsClosedOnChangedOrMalformedDocumentation()
    {
        for (String markdown : new String[] {
            "No pricing section",
            document("| Model | Output | Input | Cached input |\n| gpt-5 | $1 | $2 | $0.1 |\n"),
            document(SIMPLE + "| gpt-5 | USD 1 | $2 | $0.1 |\n"),
            document(SIMPLE + "| gpt-5 | $-1 | $2 | $0.1 |\n"),
            document(SIMPLE + "| gpt-5 | $1 | $2 |\n"),
            document(SIMPLE + "| gpt-5 | $1 | $2 | $0.1\n"),
            document(SIMPLE + "| gpt-5 | $1 | $2 | $0.1 |\n| gpt-5 | $1 | $2 | $0.1 |\n"),
            document(SIMPLE + "| bad/name | $1 | $2 | $0.1 |\n")
        })
            assertThrows(IOException.class, () -> OpenAIPriceUpdater.parse(markdown, url -> ""), markdown);
    }

    @Test
    void refusesToGuessLongContextThreshold()
    {
        assertThrows(IOException.class, () -> OpenAIPriceUpdater.parse(document(LONG
            + "| gpt-6-luna | $0.10 | $0.50 | $0.01 | $0.125 | $0.25 | $0.20 | $0.75 | $0.02 | $0.25 | $0.50 |\n"), url -> ""));
    }

    @Test
    void previewsWithoutWritingThenAppliesAtomicallyPreservingCustomAndHistoricalData() throws Exception
    {
        Path config = temporary.resolve("config");
        UsageTracker tracker = new UsageTracker(config, temporary.resolve("logs"));
        Path pricingFile = config.resolve("pricing.json");
        JSONObject before = JsonFiles.read(pricingFile);
        before.getJSONObject("models").put("gpt-5-mini",
            new JSONObject().put("inputPerMillion", 9).put("cachedInputPerMillion", 1).put("outputPerMillion", 99))
            .put("custom-model", new JSONObject().put("inputPerMillion", 3).put("cachedInputPerMillion", 1).put("outputPerMillion", 5));
        before.getJSONObject("endpoints").put("https://custom.invalid/responses", new JSONObject().put("custom", new JSONObject().put("inputPerMillion", 7)));
        JsonFiles.write(pricingFile, before);
        tracker.record("bot", "https://api.openai.com/v1/responses", UsageTrackerTest.response("gpt-5-mini", 1000, 200, 100));
        JSONObject historical = tracker.forBot("bot");
        JSONObject preview = tracker.previewPrices(fetched());
        assertEquals(1, preview.getJSONArray("changes").length());
        assertTrue(before.similar(JsonFiles.read(pricingFile)));
        // The browser cannot alter the server's stored preview.
        preview.getJSONArray("changes").getJSONObject(0).getJSONObject("after").put("inputPerMillion", 0);
        JSONObject applied = tracker.applyPrices(preview.getString("token"));
        assertEquals(1, applied.getInt("updated"));
        JSONObject after = JsonFiles.read(pricingFile);
        assertEquals(0.25, after.getJSONObject("models").getJSONObject("gpt-5-mini").getDouble("inputPerMillion"));
        assertTrue(before.getJSONObject("endpoints").similar(after.getJSONObject("endpoints")));
        assertTrue(before.getJSONObject("models").getJSONObject("custom-model").similar(after.getJSONObject("models").getJSONObject("custom-model")));
        assertTrue(historical.similar(tracker.forBot("bot")));
        assertThrows(IllegalArgumentException.class, () -> tracker.applyPrices(preview.getString("token")));
        assertEquals(0, tracker.previewPrices(fetched()).getJSONArray("changes").length());
    }

    @Test
    void rejectsWrongReplacedAndStalePreviewsWithoutOverwritingPrices() throws Exception
    {
        UsageTracker tracker = new UsageTracker(temporary.resolve("config"), temporary.resolve("logs"));
        JSONObject first = tracker.previewPrices(fetched());
        assertThrows(IllegalArgumentException.class, () -> tracker.applyPrices("not-the-token"));
        JSONObject second = tracker.previewPrices(fetched());
        assertThrows(IllegalArgumentException.class, () -> tracker.applyPrices(first.getString("token")));
        Path pricingFile = temporary.resolve("config/pricing.json");
        JSONObject modified = JsonFiles.read(pricingFile).put("manualChange", true);
        JsonFiles.write(pricingFile, modified);
        assertThrows(IllegalArgumentException.class, () -> tracker.applyPrices(second.getString("token")));
        assertTrue(modified.similar(JsonFiles.read(pricingFile)));
    }

    @Test
    void rootOnlySaveDoesNotRewriteBotFiles() throws Exception
    {
        ConfigDirectory config = new ConfigDirectory(temporary.resolve("config"));
        JSONObject settings = new JSONObject().put("bots", new JSONObject().put("bot",
            new JSONObject().put("enabled", false))).put("apiPassword", "test-only");
        config.save(settings);
        Path bot = config.directory.resolve("bot.json");
        Files.writeString(bot, "{\"enabled\":false,\"externallyEdited\":true}");
        config.saveMaster(new JSONObject(settings.toString()).put("openAiKey", "test-key"));
        assertEquals("test-key", JsonFiles.read(config.directory.resolve("irc-gpt-bot.json")).getString("openAiKey"));
        assertTrue(JsonFiles.read(bot).getBoolean("externallyEdited"));
        assertFalse(JsonFiles.read(config.directory.resolve("irc-gpt-bot.json")).has("bots"));
    }

    @Test
    void liveDocumentationValidationWhenRequested() throws Exception
    {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("pricing.liveCheck"));
        JSONObject fetched = OpenAIPriceUpdater.fetch();
        assertFalse(fetched.getJSONObject("models").isEmpty());
        for (String model : fetched.getJSONObject("models").keySet())
            OpenAIUsage.price(OpenAIUsage.emptyTotals(), fetched.getJSONObject("models").getJSONObject(model));
    }
}

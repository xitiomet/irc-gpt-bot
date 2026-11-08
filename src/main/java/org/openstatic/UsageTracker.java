package org.openstatic;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class UsageTracker
{
    private final Path usageFile;
    private final Path pricingFile;
    private JSONObject usage;
    private String persistenceError;
    private JSONObject pricingPreview;
    private JSONObject previewBaseline;

    UsageTracker(Path configDirectory, Path logDirectory) throws IOException
    {
        usageFile = logDirectory.resolve("usage.json");
        pricingFile = configDirectory.resolve("pricing.json");
        if (!Files.exists(pricingFile))
        {
            try (InputStream input = UsageTracker.class.getResourceAsStream("/irc-gpt-bot/default-pricing.json"))
            {
                if (input == null)
                    throw new IOException("Bundled default pricing is missing");
                JsonFiles.write(pricingFile, new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8)));
            }
        }
        usage = Files.exists(usageFile) ? JsonFiles.read(usageFile)
            : new JSONObject().put("version", 1).put("currency", "USD").put("bots", new JSONObject());
        if (usage.getInt("version") != 1 || !"USD".equals(usage.getString("currency")))
            throw new IOException("Unsupported usage format in " + usageFile);
        JSONObject bots = usage.getJSONObject("bots");
        for (String id : bots.keySet())
        {
            JSONObject totals = bots.getJSONObject(id);
            validateTotals(totals);
            JSONObject models = totals.getJSONObject("models");
            for (String model : models.keySet())
                validateTotals(models.getJSONObject(model));
        }
        JsonFiles.write(usageFile, usage);
    }

    private static void validateTotals(JSONObject totals)
    {
        for (String field : OpenAIUsage.TOKEN_FIELDS)
            OpenAIUsage.integer(totals, field);
        OpenAIUsage.integer(totals, "requests");
        OpenAIUsage.integer(totals, "untrackedRequests");
        if (OpenAIUsage.number(totals, "costEstimate").signum() < 0)
            throw new IllegalArgumentException("Negative historical cost estimate");
    }

    synchronized JSONObject forBot(String id)
    {
        JSONObject totals = usage.getJSONObject("bots").optJSONObject(id);
        JSONObject result = totals == null ? OpenAIUsage.emptyTotals().put("models", new JSONObject())
            : new JSONObject(totals.toString());
        result.put("currency", "USD");
        if (persistenceError != null)
            result.put("trackingError", persistenceError);
        return result;
    }

    synchronized JSONObject pricingStatus() throws IOException
    {
        return pricingStatus(JsonFiles.read(pricingFile));
    }

    private static JSONObject pricingStatus(JSONObject pricing)
    {
        return new JSONObject().put("currency", pricing.getString("currency"))
            .put("source", pricing.optString("source", "Manually configured"))
            .put("verifiedAt", pricing.optString("verifiedAt", "Not recorded"))
            .put("modelCount", pricing.getJSONObject("models").length());
    }

    synchronized JSONObject previewPrices(JSONObject fetched) throws IOException
    {
        JSONObject baseline = JsonFiles.read(pricingFile);
        if (!"USD".equals(baseline.getString("currency")) || !"USD".equals(fetched.getString("currency")))
            throw new IllegalArgumentException("Pricing currency must be USD");
        JSONObject models = fetched.getJSONObject("models");
        if (models.isEmpty() || models.length() > 100)
            throw new IllegalArgumentException("Invalid pricing model count");
        JSONArray changes = new JSONArray();
        JSONObject existing = baseline.getJSONObject("models");
        for (String model : new java.util.TreeSet<>(models.keySet()))
        {
            JSONObject row = models.getJSONObject(model);
            OpenAIUsage.price(OpenAIUsage.emptyTotals(), row);
            JSONObject old = existing.optJSONObject(model);
            if (old == null || !old.similar(row))
                changes.put(new JSONObject().put("model", model).put("before", old == null ? JSONObject.NULL : old)
                    .put("after", row));
        }
        previewBaseline = baseline;
        pricingPreview = new JSONObject().put("source", fetched.getString("source")).put("changes", changes)
            .put("skipped", fetched.getJSONArray("skipped")).put("modelCount", models.length())
            .put("expiresAt", System.currentTimeMillis() + java.util.concurrent.TimeUnit.MINUTES.toMillis(10))
            .put("token", java.util.UUID.randomUUID().toString());
        return new JSONObject(pricingPreview.toString());
    }

    synchronized JSONObject applyPrices(String token) throws IOException
    {
        if (pricingPreview == null || !pricingPreview.getString("token").equals(token)
            || System.currentTimeMillis() > pricingPreview.getLong("expiresAt"))
            throw new IllegalArgumentException("Pricing preview expired or was replaced; fetch a new preview.");
        JSONObject current = JsonFiles.read(pricingFile);
        if (!current.similar(previewBaseline))
            throw new IllegalArgumentException("pricing.json changed since preview; fetch a new preview.");
        JSONObject models = current.getJSONObject("models");
        JSONArray changes = pricingPreview.getJSONArray("changes");
        for (int i = 0; i < changes.length(); i++)
        {
            JSONObject change = changes.getJSONObject(i);
            models.put(change.getString("model"), change.getJSONObject("after"));
        }
        current.put("source", pricingPreview.getString("source")).put("verifiedAt", java.time.Instant.now().toString());
        JsonFiles.write(pricingFile, current);
        int updated = changes.length();
        pricingPreview = null;
        previewBaseline = null;
        return pricingStatus(current).put("updated", updated);
    }

    synchronized void record(String id, String endpoint, JSONObject response)
    {
        JSONObject increment;
        String trackingError = null;
        try
        {
            increment = OpenAIUsage.tokens(response);
        } catch (JSONException | IllegalArgumentException | ArithmeticException e) {
            trackingError = "Missing or invalid token usage for bot " + id + ": " + e.getMessage();
            IRCGPTBotMain.log(new IllegalArgumentException(trackingError, e));
            increment = OpenAIUsage.emptyTotals().put("requests", 1).put("untrackedRequests", 1);
        }
        String model = response.optString("model", "");
        if (trackingError == null)
        {
            try
            {
                JSONObject pricing = JsonFiles.read(pricingFile);
                if (!"USD".equals(pricing.getString("currency")))
                    throw new IllegalArgumentException("pricing.json currency must be USD");
                JSONObject models;
                if ("https://api.openai.com/v1/responses".equals(endpoint))
                    models = pricing.getJSONObject("models");
                else
                {
                    JSONObject endpoints = pricing.optJSONObject("endpoints");
                    models = endpoints == null ? null : endpoints.optJSONObject(endpoint);
                }
                JSONObject row = models == null ? null : models.optJSONObject(model);
                if (row == null && models != null)
                    row = models.optJSONObject(model.replaceFirst("-\\d{4}-\\d{2}-\\d{2}$", ""));
                String tier = response.optString("service_tier", "default");
                if (row == null || model.isEmpty() || !"default".equals(tier))
                    throw new IllegalArgumentException("No standard pricing for " + model + " at " + endpoint + " (tier " + tier + ")");
                OpenAIUsage.price(increment, row);
            } catch (IOException | JSONException | IllegalArgumentException | ArithmeticException e) {
                IRCGPTBotMain.log(new IllegalArgumentException("Usage recorded without a price for bot " + id + ": " + e.getMessage(), e));
            }
        }
        JSONObject next = new JSONObject(usage.toString());
        JSONObject bots = next.getJSONObject("bots");
        JSONObject totals = bots.optJSONObject(id);
        if (totals == null)
        {
            totals = OpenAIUsage.emptyTotals().put("models", new JSONObject());
            bots.put(id, totals);
        }
        OpenAIUsage.add(totals, increment);
        JSONObject models = totals.getJSONObject("models");
        if (model.isEmpty())
            model = "(unknown)";
        JSONObject modelTotals = models.optJSONObject(model);
        if (modelTotals == null)
        {
            modelTotals = OpenAIUsage.emptyTotals();
            models.put(model, modelTotals);
        }
        OpenAIUsage.add(modelTotals, increment);
        totals.put("lastResponseAt", java.time.Instant.now().toString());
        if (trackingError != null)
            totals.put("lastTrackingError", trackingError);
        usage = next;
        try
        {
            JsonFiles.write(usageFile, usage);
            persistenceError = null;
        } catch (IOException e) {
            persistenceError = "Unable to persist usage: " + e.getMessage();
            IRCGPTBotMain.log(e);
            e.printStackTrace(System.err);
        }
    }
}

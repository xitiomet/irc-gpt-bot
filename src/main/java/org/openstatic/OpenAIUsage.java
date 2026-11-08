package org.openstatic;

import java.math.BigDecimal;

import org.json.JSONObject;

final class OpenAIUsage
{
    static final String[] TOKEN_FIELDS = {
        "tokensSpent", "inputTokensSpent", "outputTokensSpent", "cachedTokensSpent", "unpricedTokensSpent"
    };

    static JSONObject emptyTotals()
    {
        JSONObject totals = new JSONObject().put("requests", 0).put("untrackedRequests", 0)
            .put("costEstimate", BigDecimal.ZERO);
        for (String field : TOKEN_FIELDS)
            totals.put(field, 0);
        return totals;
    }

    static JSONObject tokens(JSONObject response)
    {
        JSONObject usage = response.getJSONObject("usage");
        boolean responses = usage.has("input_tokens");
        long input = integer(usage, responses ? "input_tokens" : "prompt_tokens");
        long output = integer(usage, responses ? "output_tokens" : "completion_tokens");
        long total = integer(usage, "total_tokens");
        JSONObject details = usage.optJSONObject(responses ? "input_tokens_details" : "prompt_tokens_details");
        long cached = details == null || !details.has("cached_tokens") ? 0 : integer(details, "cached_tokens");
        if (cached > input || total != Math.addExact(input, output))
            throw new IllegalArgumentException("Invalid OpenAI token usage");
        return emptyTotals().put("requests", 1).put("tokensSpent", total).put("inputTokensSpent", input)
            .put("outputTokensSpent", output).put("cachedTokensSpent", cached).put("unpricedTokensSpent", total);
    }

    static long integer(JSONObject data, String field)
    {
        long value = number(data, field).longValueExact();
        if (value < 0)
            throw new IllegalArgumentException("Negative " + field);
        return value;
    }

    static BigDecimal number(JSONObject data, String field)
    {
        Object value = data.get(field);
        if (!(value instanceof Number))
            throw new IllegalArgumentException(field + " must be a number");
        return new BigDecimal(value.toString());
    }

    private static BigDecimal rate(JSONObject pricing, String field)
    {
        BigDecimal value = number(pricing, field);
        if (value.signum() < 0)
            throw new IllegalArgumentException("Negative price: " + field);
        return value;
    }

    static void price(JSONObject totals, JSONObject pricing)
    {
        long input = totals.getLong("inputTokensSpent");
        long cached = totals.getLong("cachedTokensSpent");
        long output = totals.getLong("outputTokensSpent");
        BigDecimal inputRate = rate(pricing, "inputPerMillion");
        BigDecimal cachedRate = cached == 0 && !pricing.has("cachedInputPerMillion")
            ? inputRate : rate(pricing, "cachedInputPerMillion");
        BigDecimal outputRate = rate(pricing, "outputPerMillion");
        if (pricing.has("longContextThreshold"))
        {
            long threshold = integer(pricing, "longContextThreshold");
            if (threshold == 0)
                throw new IllegalArgumentException("longContextThreshold must be positive");
            BigDecimal inputMultiplier = rate(pricing, "longContextInputMultiplier");
            BigDecimal outputMultiplier = rate(pricing, "longContextOutputMultiplier");
            if (input > threshold)
            {
                inputRate = inputRate.multiply(inputMultiplier);
                cachedRate = cachedRate.multiply(inputMultiplier);
                outputRate = outputRate.multiply(outputMultiplier);
            }
        }
        BigDecimal cost = inputRate.multiply(BigDecimal.valueOf(input - cached))
            .add(cachedRate.multiply(BigDecimal.valueOf(cached)))
            .add(outputRate.multiply(BigDecimal.valueOf(output)))
            .divide(BigDecimal.valueOf(1000000));
        totals.put("costEstimate", cost).put("unpricedTokensSpent", 0);
    }

    static void add(JSONObject totals, JSONObject increment)
    {
        for (String field : TOKEN_FIELDS)
            totals.put(field, Math.addExact(integer(totals, field), integer(increment, field)));
        for (String field : new String[] {"requests", "untrackedRequests"})
            totals.put(field, Math.addExact(integer(totals, field), integer(increment, field)));
        totals.put("costEstimate", number(totals, "costEstimate").add(number(increment, "costEstimate")));
    }
}

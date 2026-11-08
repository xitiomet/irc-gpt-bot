package org.openstatic;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

final class OpenAIPriceUpdater
{
    static final String SOURCE = "https://developers.openai.com/api/docs/pricing.md";
    private static final String SIMPLE_HEADER = "| Model | Input | Output | Cached input |";
    private static final String LONG_HEADER = "| Model | Short context input | Short context output | Short context cached input | Short context 30m write | Short context 12h write | Long context input | Long context output | Long context cached input | Long context 30m write | Long context 12h write |";

    interface Documents
    {
        String get(String url) throws IOException;
    }

    static JSONObject fetch() throws IOException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        Documents documents = url -> {
            if (System.nanoTime() > deadline)
                throw new IOException("Pricing update timed out; existing prices are unchanged.");
            return fetchDocument(url);
        };
        return parse(documents.get(SOURCE), documents);
    }

    private static String fetchDocument(String url) throws IOException
    {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "text/markdown, text/plain");
        try
        {
            if (connection.getResponseCode() != 200)
                throw new IOException("OpenAI documentation returned HTTP " + connection.getResponseCode());
            try (var input = connection.getInputStream())
            {
                byte[] bytes = input.readNBytes(1000001);
                if (bytes.length > 1000000)
                    throw new IOException("OpenAI pricing document is too large");
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    static JSONObject parse(String markdown, Documents documents) throws IOException
    {
        String marker = "### Standard pricing data";
        int start = markdown.indexOf(marker);
        int end = start < 0 ? -1 : markdown.indexOf("### Batch pricing data", start);
        if (start < 0 || end < 0)
            throw new IOException("OpenAI pricing format changed: standard section not found");
        JSONObject models = new JSONObject();
        JSONArray skipped = new JSONArray();
        Set<String> seen = new HashSet<>();
        boolean longTable = false;
        int columns = 0;
        for (String raw : markdown.substring(start + marker.length(), end).split("\\r?\\n"))
        {
            String line = raw.trim();
            if (!line.startsWith("|"))
                continue;
            if (line.equals(SIMPLE_HEADER) || line.equals(LONG_HEADER))
            {
                longTable = line.equals(LONG_HEADER);
                columns = longTable ? 11 : 4;
                continue;
            }
            if (line.startsWith("| ---"))
                continue;
            if (columns == 0 || line.startsWith("| Model"))
                throw new IOException("OpenAI pricing format changed: unsupported table header");
            if (!line.endsWith("|"))
                throw new IOException("OpenAI pricing format changed: unterminated row");
            String[] cells = line.substring(1, line.length() - 1).split("\\|", -1);
            if (cells.length != columns)
                throw new IOException("OpenAI pricing format changed: unexpected columns");
            for (int i = 0; i < cells.length; i++)
                cells[i] = cells[i].trim();
            String model = cells[0].replace(" (<272K context length)", "");
            if (!model.matches("(gpt|o)[a-z0-9.-]*") || !seen.add(model) || seen.size() > 100)
                throw new IOException("Unexpected, duplicate or excessive models in OpenAI pricing: " + model);
            if ("-".equals(cells[3]))
            {
                skipped.put(model + ": no cached-input price published");
                continue;
            }
            BigDecimal input = dollars(cells[1]);
            BigDecimal output = dollars(cells[2]);
            BigDecimal cached = dollars(cells[3]);
            JSONObject row = new JSONObject().put("inputPerMillion", input)
                .put("cachedInputPerMillion", cached).put("outputPerMillion", output);
            if (longTable)
            {
                BigDecimal longInput = dollars(cells[6]);
                BigDecimal longOutput = dollars(cells[7]);
                BigDecimal longCached = dollars(cells[8]);
                try
                {
                    BigDecimal inputMultiplier = longInput.divide(input);
                    BigDecimal outputMultiplier = longOutput.divide(output);
                    if (cached.multiply(inputMultiplier).compareTo(longCached) != 0)
                        throw new ArithmeticException("Cached-input multiplier differs");
                    String document = documents.get("https://developers.openai.com/api/docs/models/" + model);
                    Matcher threshold = Pattern.compile("(?i)prompts with (?:more than\\s+|over\\s+|>\\s*|&gt;\\s*)([0-9]+)K input tokens").matcher(document);
                    if (!threshold.find())
                        throw new IOException("No explicit long-context threshold published for " + model);
                    long tokens = Math.multiplyExact(Long.parseLong(threshold.group(1)), 1000);
                    if (tokens == 0)
                        throw new IOException("Invalid long-context threshold for " + model);
                    row.put("longContextThreshold", tokens).put("longContextInputMultiplier", inputMultiplier)
                        .put("longContextOutputMultiplier", outputMultiplier);
                } catch (ArithmeticException e) {
                    skipped.put(model + ": unsupported long-context rates");
                    continue;
                }
            }
            models.put(model, row);
        }
        if (models.isEmpty())
            throw new IOException("OpenAI pricing format changed: no supported models");
        return new JSONObject().put("currency", "USD").put("source", SOURCE).put("models", models).put("skipped", skipped);
    }

    private static BigDecimal dollars(String cell) throws IOException
    {
        if (!cell.matches("\\$[0-9]+(?:\\.[0-9]+)?"))
            throw new IOException("Invalid OpenAI price: " + cell);
        return new BigDecimal(cell.substring(1));
    }
}

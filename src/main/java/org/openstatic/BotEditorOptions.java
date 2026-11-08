package org.openstatic;

import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

final class BotEditorOptions
{
    static final String[] BOOLEAN_FIELDS = {"acceptInvites", "greet", "privateMessages", "greetPublic", "secure", "gptEnabled", "enabled"};
    static final String[] TEXT_FIELDS = {"server", "nickname", "user", "realName", "operatorName", "definitionServer", "modelsApiUrl", "responsesApiUrl"};
    static final String[] NUMBER_FIELDS = {"port", "contextDepth", "followUpSeconds", "replyCooldownSeconds", "openQuestionSeconds", "maxToolRounds"};
    static final String[] SECRET_FIELDS = {"password", "operatorPassword"};

    static JSONObject defaults(String id, boolean launching)
    {
        JSONObject options = new JSONObject().put("server", "").put("nickname", id).put("user", "").put("realName", "")
            .put("operatorName", "").put("definitionServer", "").put("modelsApiUrl", "").put("responsesApiUrl", "")
            .put("port", 6667).put("contextDepth", launching ? 15 : 5).put("followUpSeconds", 120).put("replyCooldownSeconds", 15)
            .put("openQuestionSeconds", 60).put("maxToolRounds", 5);
        for (String field : BOOLEAN_FIELDS)
            options.put(field, Set.of("enabled", "gptEnabled").contains(field)
                || (launching && Set.of("acceptInvites", "greet", "greetPublic", "privateMessages").contains(field)));
        for (String field : new String[] {"ignore", "aliases", "botOps"})
            options.put(field, new JSONArray());
        options.put("gptTools", new JSONArray(GPTTools.ALL_TOOLS));
        return options;
    }

    static JSONObject view(JSONObject settings, String id)
    {
        JSONObject options = defaults(id, false);
        for (String field : options.keySet())
            if (settings.has(field) && !settings.isNull(field))
                options.put(field, settings.get(field));
        for (String field : SECRET_FIELDS)
            options.put("has" + Character.toUpperCase(field.charAt(0)) + field.substring(1), !settings.optString(field, "").isEmpty());
        return new JSONObject(options.toString());
    }

    static void validateAdditional(JSONObject input, JSONObject update)
    {
        if (input.has("reconnect") && !(input.get("reconnect") instanceof Boolean))
            throw new IllegalArgumentException("reconnect must be true or false.");
        for (String field : BOOLEAN_FIELDS)
        {
            if (!input.has(field)) continue;
            if (!(input.get(field) instanceof Boolean))
                throw new IllegalArgumentException(field + " must be true or false.");
            update.put(field, input.getBoolean(field));
        }
        for (String field : TEXT_FIELDS)
        {
            if (!input.has(field)) continue;
            if (!(input.get(field) instanceof String))
                throw new IllegalArgumentException(field + " must be text.");
            String value = input.getString(field).trim();
            if (value.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException(field + " cannot contain control characters.");
            if (Set.of("server", "nickname", "user", "operatorName").contains(field)
                && value.chars().anyMatch(c -> Character.isWhitespace(c) || c == ','))
                throw new IllegalArgumentException(field + " must not contain spaces or commas.");
            if (("server".equals(field) || "nickname".equals(field)) && value.isEmpty())
                throw new IllegalArgumentException("Enter " + field + ".");
            if (field.endsWith("ApiUrl") || "definitionServer".equals(field))
            {
                if (!value.isEmpty())
                {
                    java.net.URI uri;
                    try { uri = java.net.URI.create(value); }
                    catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid URL for " + field + "."); }
                    if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null)
                        throw new IllegalArgumentException(field + " must be an HTTP or HTTPS URL.");
                }
            }
            update.put(field, value.isEmpty() ? JSONObject.NULL : value);
        }
        for (String field : NUMBER_FIELDS)
        {
            if (!input.has(field)) continue;
            long value = OpenAIUsage.integer(input, field);
            long min = "port".equals(field) || "maxToolRounds".equals(field) ? 1 : 0;
            long max = "port".equals(field) ? 65535 : "contextDepth".equals(field) ? 255 : Integer.MAX_VALUE;
            if (value < min || value > max)
                throw new IllegalArgumentException(field + " must be between " + min + " and " + max + ".");
            update.put(field, value);
        }
        for (String field : SECRET_FIELDS)
        {
            String clear = "clear" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
            if (input.has(clear) && !(input.get(clear) instanceof Boolean))
                throw new IllegalArgumentException(clear + " must be true or false.");
            if (input.has(field) && !(input.get(field) instanceof String))
                throw new IllegalArgumentException(field + " must be text.");
            String value = input.optString(field, "");
            if (value.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException(field + " cannot contain control characters.");
            if (input.optBoolean(clear) && !value.isEmpty())
                throw new IllegalArgumentException("Choose either replacing or clearing " + field + ".");
            if (input.optBoolean(clear)) update.put(field, JSONObject.NULL);
            else if (!value.isEmpty()) update.put(field, value);
        }
    }
}

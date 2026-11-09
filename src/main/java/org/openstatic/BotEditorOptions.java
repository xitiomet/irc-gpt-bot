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
    static final String[] API_URL_FIELDS = {"modelsApiUrl", "responsesApiUrl", "completionsApiUrl"};

    static void validateUrl(String field, String value)
    {
        if (value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(field + " cannot contain control characters.");
        if (value.isEmpty())
            return;
        java.net.URI uri;
        try { uri = java.net.URI.create(value); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid URL for " + field + "."); }
        if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null)
            throw new IllegalArgumentException(field + " must be an HTTP or HTTPS URL.");
    }

    static JSONObject apiUrls(JSONObject settings)
    {
        JSONObject urls = new JSONObject();
        for (String field : API_URL_FIELDS)
            urls.put(field, settings.optString(field, ""));
        return urls;
    }

    static JSONObject importedConfig(JSONObject config, String id)
    {
        ConfigDirectory.validateBotId(id);
        for (String field : new String[] {"bots", "openAiKey", "apiPassword", "apiServer", "apiPort", "logPath"})
            if (config.has(field))
                throw new IllegalArgumentException("Import a single bot configuration, not master settings (" + field + ").");
        if (config.isEmpty())
            throw new IllegalArgumentException("The bot configuration is empty.");
        JSONObject input = defaults(id, false).put("model", "gpt-3.5-turbo").put("preamble", "");
        for (String field : config.keySet())
            input.put(field, config.get(field));
        // Older and hand-edited configs may store numbers as strings; the runtime already accepts them.
        for (String field : NUMBER_FIELDS)
        {
            if (input.get(field) instanceof String)
            {
                String text = input.getString(field).trim();
                if (!text.matches("[0-9]{1,10}"))
                    throw new IllegalArgumentException(field + " must be a whole number.");
                input.put(field, Long.parseLong(text));
            }
        }
        if (config.has("systemPreamble"))
            input.put("preamble", config.get("systemPreamble"));
        JSONObject validated = IRCGPTBot.validateEditorUpdate(input);
        JSONObject result = new JSONObject(config.toString());
        for (String field : validated.keySet())
        {
            if (!config.has(field))
                continue;
            if (validated.isNull(field))
                result.remove(field);
            else
                result.put(field, validated.get(field));
        }
        if (config.has("completionsApiUrl"))
            validateUrl("completionsApiUrl", config.getString("completionsApiUrl").trim());
        if (config.has("channels"))
        {
            JSONObject channels = config.getJSONObject("channels");
            for (String name : channels.keySet())
            {
                if (!name.matches("[#&+!][^\\s,\\x00\\x07:]+"))
                    throw new IllegalArgumentException("Invalid channel name: " + name);
                JSONObject channel = channels.getJSONObject(name);
                if (channel.has("join") && !(channel.get("join") instanceof Boolean))
                    throw new IllegalArgumentException("Channel join must be true or false.");
                if (channel.has("key") && (!(channel.get("key") instanceof String)
                    || channel.getString("key").chars().anyMatch(Character::isISOControl)))
                    throw new IllegalArgumentException("Channel key must be text without control characters.");
            }
        }
        if (config.has("toolUrls"))
        {
            JSONArray tools = config.getJSONArray("toolUrls");
            if (GPTTools.normalizeToolUrls(tools).length() != tools.length())
                throw new IllegalArgumentException("Invalid URL tool configuration.");
        }
        return result;
    }

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
                validateUrl(field, value);
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

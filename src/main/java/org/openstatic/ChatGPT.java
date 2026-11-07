package org.openstatic;

import org.json.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ChatGPT 
{
    private ExecutorService executorService;
    
    public ChatGPT()
    {
        ThreadFactory tf = new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread x = new Thread(r);
                x.setPriority(Thread.MAX_PRIORITY);
                x.setName("ChatGPT Realtime");
                return x;
              }
        };
        this.executorService = Executors.newSingleThreadExecutor(tf);
    }

    public Future<ChatMessage> callChatGPT(JSONObject botOptions, final ChatLog messages) 
    {
        return callChatGPT(botOptions, messages, (GPTTools) null);
    }

    public Future<ChatMessage> callChatGPT(JSONObject botOptions, final ChatLog messages, GPTTools tools)
    {
        String user = null;
        ChatMessage lastMessage = messages.getLastMessage();
        if (lastMessage != null)
        {
            user = lastMessage.getSender();
        }
        return callChatGPT(botOptions, messages.getGPTMessages(), user, tools);
    }

    private void log(String text)
    {
        IRCGPTBotMain.logAppend("openai.log", text);
    }

    private static String resolveApiUrl(JSONObject botOptions, String key, String defaultValue)
    {
        if (botOptions.has(key))
            return botOptions.optString(key, defaultValue);
        return IRCGPTBotMain.settings.optString(key, defaultValue);
    }

    public static String resolveResponsesApiUrl(JSONObject botOptions)
    {
        String url = resolveApiUrl(botOptions, "responsesApiUrl", null);
        if (url == null || url.trim().isEmpty())
        {
            // Backwards compatibility: derive the Responses endpoint from a legacy chat completions URL
            String legacy = resolveApiUrl(botOptions, "completionsApiUrl", null);
            if (legacy != null && legacy.contains("/chat/completions"))
                url = legacy.replace("/chat/completions", "/responses");
            else
                url = "https://api.openai.com/v1/responses";
        }
        return url;
    }

    public Future<ChatMessage> callChatGPT(JSONObject botOptions, JSONArray messages)
    {
        return callChatGPT(botOptions, messages, null, null);
    } 

    public JSONArray listModels(JSONObject botOptions)
    {
        JSONArray models = new JSONArray();
        try
        {
            String url = resolveApiUrl(botOptions, "modelsApiUrl", "https://api.openai.com/v1/models");
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setRequestMethod("GET");
            con.setRequestProperty("Content-Type", "application/json");
            con.setRequestProperty("Authorization", "Bearer " + IRCGPTBotMain.settings.optString("openAiKey"));
            String output = new BufferedReader(new InputStreamReader(con.getInputStream())).lines()
                .reduce((a, b) -> a + b).get();
            JSONObject response = new JSONObject(output);
            JSONArray data = response.optJSONArray("data");
            if (data != null)
            {
                ArrayList<String> ids = new ArrayList<String>();
                for (int i = 0; i < data.length(); i++)
                {
                    JSONObject model = data.optJSONObject(i);
                    if (model != null)
                    {
                        String id = model.optString("id", null);
                        if (id != null && !ids.contains(id))
                            ids.add(id);
                    }
                }
                Collections.sort(ids);
                for (String id : ids)
                    models.put(id);
            }
        } catch (Exception e) {
            IRCGPTBotMain.log(e);
        }
        return models;
    }


    private JSONObject postResponse(String url, JSONObject data) throws Exception
    {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "application/json");
        con.setRequestProperty("Authorization", "Bearer " + IRCGPTBotMain.settings.optString("openAiKey"));
        ChatGPT.this.log("\033[0;92mSending Payload to " + url + "..\033[0m");
        ChatGPT.this.log(data.toString(2));
        con.setDoOutput(true);
        con.getOutputStream().write(data.toString().getBytes(StandardCharsets.UTF_8));
        int status = con.getResponseCode();
        InputStream is = (status >= 400) ? con.getErrorStream() : con.getInputStream();
        String output = "";
        if (is != null)
        {
            output = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8)).lines()
                .collect(Collectors.joining("\n"));
        }
        ChatGPT.this.log("\033[0;93mAPI RESPONSE (HTTP " + status + "):\033[0m");
        if (status >= 400)
        {
            ChatGPT.this.log(output);
            throw new IOException("Responses API returned HTTP " + status + ": " + output);
        }
        JSONObject response = new JSONObject(output);
        ChatGPT.this.log(response.toString(2));
        if (response.optJSONObject("error") != null)
            throw new IOException("Responses API error: " + response.getJSONObject("error").toString());
        return response;
    }

    private static String extractOutputText(JSONArray output)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < output.length(); i++)
        {
            JSONObject item = output.optJSONObject(i);
            if (item == null || !"message".equals(item.optString("type")))
                continue;
            JSONArray content = item.optJSONArray("content");
            if (content == null)
                continue;
            for (int c = 0; c < content.length(); c++)
            {
                JSONObject part = content.optJSONObject(c);
                if (part != null && "output_text".equals(part.optString("type")))
                {
                    if (sb.length() > 0)
                        sb.append(" ");
                    sb.append(part.optString("text", ""));
                }
            }
        }
        return sb.toString();
    }

    private String cleanResponseText(String respBody)
    {
        respBody = respBody.replace("\n", " ").replace("\r", "").replace("\0", "").trim();
        if (respBody.toUpperCase().startsWith("GREETING: "))
        {
            respBody = respBody.substring(10);
            ChatGPT.this.log("Removed GREETING:");
        }
        if (respBody.length() > 1 && respBody.startsWith("\"") && respBody.endsWith("\""))
        {
            respBody = respBody.substring(1, respBody.length() - 2);
            ChatGPT.this.log("Removed Silly Quotes!");
        }
        respBody = respBody.replaceAll(Pattern.quote("As an AI language model,"), "");
        respBody = respBody.replaceAll(Pattern.quote("as an AI language model,"), "");
        respBody = respBody.replaceAll(Pattern.quote("an AI language model"), "");
        respBody = respBody.replaceAll(Pattern.quote("OpenAI"), "OpenAI (bot available at https://openstatic.org/projects/ircgptbot/)");
        return respBody.trim();
    }

    public Future<ChatMessage> callChatGPT(JSONObject botOptions, JSONArray messages, String user, GPTTools tools)
    {
        Callable<ChatMessage> callable = new Callable<ChatMessage>() 
        {
            public ChatMessage call()
            {
                try
                {
                    String url = resolveResponsesApiUrl(botOptions);
                    JSONArray input = new JSONArray();
                    for (int i = 0; i < messages.length(); i++)
                        input.put(messages.get(i));
                    JSONArray toolDefinitions = (tools != null) ? tools.getToolDefinitions() : new JSONArray();
                    int maxRounds = Math.max(1, botOptions.optInt("maxToolRounds", 5));
                    StringBuilder replyText = new StringBuilder();
                    for (int round = 0; round < maxRounds; round++)
                    {
                        JSONObject data = new JSONObject();
                        data.put("model", botOptions.optString("model", "gpt-3.5-turbo"));
                        data.put("input", input);
                        if (user != null)
                            data.put("user", user);
                        if (toolDefinitions.length() > 0)
                        {
                            data.put("instructions", tools.getInstructions());
                            data.put("tools", toolDefinitions);
                            // On the final round force a text answer so the loop always terminates
                            data.put("tool_choice", (round == maxRounds - 1) ? "none" : "auto");
                        }
                        JSONObject response = postResponse(url, data);
                        JSONArray output = response.optJSONArray("output");
                        if (output == null)
                            output = new JSONArray();
                        String text = extractOutputText(output).trim();
                        if (!text.isEmpty())
                        {
                            if (replyText.length() > 0)
                                replyText.append(" ");
                            replyText.append(text);
                        }
                        ArrayList<JSONObject> functionCalls = new ArrayList<JSONObject>();
                        for (int i = 0; i < output.length(); i++)
                        {
                            JSONObject item = output.optJSONObject(i);
                            if (item == null)
                                continue;
                            // Echo every output item back (reasoning, messages, calls) so the model keeps its context
                            input.put(item);
                            if ("function_call".equals(item.optString("type")))
                                functionCalls.add(item);
                        }
                        if (functionCalls.isEmpty() || tools == null)
                            break;
                        for (JSONObject fc : functionCalls)
                        {
                            String name = fc.optString("name");
                            String arguments = fc.optString("arguments", "{}");
                            ChatGPT.this.log("\033[0;96mTool call: " + name + " " + arguments + "\033[0m");
                            String result = tools.execute(name, arguments);
                            ChatGPT.this.log("\033[0;96mTool result: " + result + "\033[0m");
                            JSONObject fco = new JSONObject();
                            fco.put("type", "function_call_output");
                            fco.put("call_id", fc.optString("call_id"));
                            fco.put("output", result);
                            input.put(fco);
                        }
                    }
                    String respBody = cleanResponseText(replyText.toString());
                    return new ChatMessage(botOptions.optString("nickname"), null, respBody, new Date(System.currentTimeMillis()));
                } catch (Exception e) {
                    IRCGPTBotMain.log(e);
                    return new ChatMessage(botOptions.optString("nickname"), null, "I'm sorry, I'm having trouble thinking right now. Give me a few minutes and try again.", new Date(System.currentTimeMillis()));
                }
            }
        };
        return this.executorService.submit(callable);
    }
}

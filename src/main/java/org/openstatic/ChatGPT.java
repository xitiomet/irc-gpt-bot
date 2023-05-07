package org.openstatic;

import org.json.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.regex.Pattern;

public class ChatGPT 
{
    private ExecutorService executorService;
    private JSONObject settings;
    
    public ChatGPT(JSONObject settings)
    {
        this.settings = settings;
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

    public Future<ChatMessage> callChatGPT(ChatMessage message, String system) 
    {
        ChatLog cl = new ChatLog(this.settings);
        cl.add(message);
        return callChatGPT(cl);
    }

    public Future<ChatMessage> callChatGPT(final ChatLog messages) 
    {
        return callChatGPT(messages.getGPTMessages());
    }

    private void log(String text)
    {
        IRCGPTBotMain.logAppend("openai.log", text);
    }

    public Future<ChatMessage> callChatGPT(JSONArray messages) 
    {
        Callable<ChatMessage> callable = new Callable<ChatMessage>() 
        {
            public ChatMessage call()
            {
                try
                {
                    String url = "https://api.openai.com/v1/chat/completions";
                    HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
                    con.setRequestMethod("POST");
                    con.setRequestProperty("Content-Type", "application/json");
                    con.setRequestProperty("Authorization", "Bearer " + ChatGPT.this.settings.optString("openAiKey"));
                    JSONObject data = new JSONObject();
                    data.put("model", ChatGPT.this.settings.optString("model", "gpt-3.5-turbo"));
                    data.put("messages", messages);
                    ChatGPT.this.log("\033[0;92mSending Payload to chatGPT..\033[0m");
                    ChatGPT.this.log(data.toString(2));
                    con.setDoOutput(true);
                    con.getOutputStream().write(data.toString().getBytes());
                    String output = new BufferedReader(new InputStreamReader(con.getInputStream())).lines()
                        .reduce((a, b) -> a + b).get();
                    JSONObject response = new JSONObject(output);
                    ChatGPT.this.log("\033[0;93mchatGPT RESPONSE:\033[0m");
                    ChatGPT.this.log(response.toString(2));
                    JSONArray choices = response.getJSONArray("choices");
                    JSONObject choice_zero = choices.getJSONObject(0);
                    JSONObject respMessage = choice_zero.getJSONObject("message");
                    String respBody = respMessage.getString("content").replace("\n", " ").replace("\r", "").replace("\0", "").trim();
                    if (respBody.toUpperCase().startsWith("GREETING: "))
                    {
                        respBody = respBody.substring(10);
                        ChatGPT.this.log("Removed GREETING:");
                    }
                    if (respBody.startsWith("\"") && respBody.endsWith("\""))
                    {
                        respBody = respBody.substring(1, respBody.length() - 2);
                        ChatGPT.this.log("Removed Silly Quotes!");
                    }
                    respBody = respBody.replaceAll(Pattern.quote("As an AI language model,"), "");
                    respBody = respBody.replaceAll(Pattern.quote("as an AI language model,"), "");
                    respBody = respBody.replaceAll(Pattern.quote("an AI language model"), "");
                    ChatMessage respMsg = new ChatMessage(ChatGPT.this.settings.optString("nickname"), null, respBody, new Date(System.currentTimeMillis()));
                    return respMsg;
                } catch (Exception e) {
                    IRCGPTBotMain.log(e);
                    return new ChatMessage(ChatGPT.this.settings.optString("nickname"), null, "I'm sorry, I'm having trouble thinking right now. (" + e.getLocalizedMessage() + ")", new Date(System.currentTimeMillis()));
                }
            }
        };
        return this.executorService.submit(callable);
    }
}

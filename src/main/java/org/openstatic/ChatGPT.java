package org.openstatic;

import org.json.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Date;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

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
        ChatLog cl = new ChatLog(this.settings, null);
        cl.add(message);
        return callChatGPT(cl);
    }

    public Future<ChatMessage> callChatGPT(final ChatLog messages) 
    {
        Callable<ChatMessage> callable = new Callable<ChatMessage>() {
            public ChatMessage call() {
                try
                {
                    String url = "https://api.openai.com/v1/chat/completions";
                    HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
                    con.setRequestMethod("POST");
                    con.setRequestProperty("Content-Type", "application/json");
                    con.setRequestProperty("Authorization", "Bearer " + ChatGPT.this.settings.optString("openAiKey"));
                    JSONObject data = new JSONObject();
                    data.put("model", "gpt-3.5-turbo");
                    data.put("messages", messages.getGPTMessages());
                    //System.err.println("\033[0;92mSending Payload to chatGPT..\033[0m");
                    //System.err.println(data.toString(2));
                    con.setDoOutput(true);
                    con.getOutputStream().write(data.toString().getBytes());
                    String output = new BufferedReader(new InputStreamReader(con.getInputStream())).lines()
                        .reduce((a, b) -> a + b).get();
                    JSONObject response = new JSONObject(output);
                    //System.err.println("\033[0;93mchatGPT RESPONSE:\033[0m");
                    //System.err.println(response.toString(2));
                    JSONArray choices = response.getJSONArray("choices");
                    JSONObject choice_zero = choices.getJSONObject(0);
                    JSONObject respMessage = choice_zero.getJSONObject("message");
                    String respBody = respMessage.getString("content").replace("\n", " ").replace("\r", "").replace("\0", "");
                    ChatMessage respMsg = new ChatMessage(ChatGPT.this.settings.optString("nickname"), null, respBody, new Date(System.currentTimeMillis()));
                    return respMsg;
                } catch (Exception e) {
                    e.printStackTrace(System.err);
                    return new ChatMessage(ChatGPT.this.settings.optString("nickname"), null, "I'm sorry, I'm having trouble thinking right now. (" + e.getLocalizedMessage() + ")", new Date(System.currentTimeMillis()));
                }
            }
        };
        return this.executorService.submit(callable);
    }
}

package org.openstatic;

import org.json.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

public class ChatGPT 
{
    private String openAIKey;
    private ExecutorService executorService;

    public ChatGPT(String key)
    {
        this.openAIKey = key;
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

    public Future<String> callChatGPT(String assistantNick, ChatMessage message, String system) 
    {
        ChatLog cl = new ChatLog(system);
        cl.add(message);
        return callChatGPT(assistantNick, cl);
    }

    public Future<String> callChatGPT(final String assistant, final ChatLog messages) 
    {
        Callable<String> callable = new Callable<String>() {
            public String call() {
                try
                {
                    String url = "https://api.openai.com/v1/chat/completions";
                    HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
                    con.setRequestMethod("POST");
                    con.setRequestProperty("Content-Type", "application/json");
                    con.setRequestProperty("Authorization", "Bearer " + ChatGPT.this.openAIKey);
                    JSONObject data = new JSONObject();
                    data.put("model", "gpt-3.5-turbo");
                    data.put("messages", messages.getGPTMessages(assistant));
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
                    return respMessage.getString("content").replace("\n", " ").replace("\r", "").replace("\0", "");
                } catch (Exception e) {
                    e.printStackTrace(System.err);
                    return "I'm sorry, I'm having trouble thinking right now. (" + e.getLocalizedMessage() + ")";
                }
            }
        };
        return this.executorService.submit(callable);
    }
}

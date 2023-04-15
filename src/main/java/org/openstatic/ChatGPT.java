package org.openstatic;

import org.json.*;
import java.io.BufferedReader;
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

public class ChatGPT 
{
    private ExecutorService executorService;
    private JSONObject settings;
    private FileOutputStream fos;
    private PrintWriter pw;
    private SimpleDateFormat simpleDateFormat;

    public ChatGPT(JSONObject settings)
    {
        String pattern = "yyyy-MM-dd HH:mm:ss";
        this.simpleDateFormat = new SimpleDateFormat(pattern);
        this.settings = settings;
        if (settings.has("gptLog"))
        {
            try
            {
                this.fos = new FileOutputStream(settings.optString("gptLog", "gpt.log"), true);
                this.pw = new PrintWriter(fos, true, Charset.forName("UTF-8"));
            } catch (Exception e) {}
        }
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
        if (this.pw != null)
        {
            this.pw.println("[" + this.simpleDateFormat.format(new Date(System.currentTimeMillis())) + "] " + text);
            this.pw.flush();
        }
    }

    public Future<ChatMessage> callChatGPT(JSONArray messages) 
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

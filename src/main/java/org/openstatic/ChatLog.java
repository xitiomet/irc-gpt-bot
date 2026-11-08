package org.openstatic;

import java.net.HttpURLConnection;
import java.net.URL;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.json.JSONArray;
import org.json.JSONObject;

public class ChatLog
{
    private ArrayList<ChatMessage> messages;
    private IRCGPTBot bot;

    public ChatLog(IRCGPTBot bot)
    {
        this.messages = new ArrayList<ChatMessage>();
        this.bot = bot;
    }

    public synchronized void add(ChatMessage cm)
    {
        this.messages.add(cm);
        if (this.messages.size() > 255)
            this.messages.remove(0);
    }

    public synchronized ChatMessage getLastMessage()
    {
        if (this.messages.size() > 0)
        {
            return this.messages.get(this.messages.size() - 1);
        } else {
            return null;
        }
    }

    // Returns a snapshot so callers on other threads can iterate safely
    public synchronized ArrayList<ChatMessage> getMessages()
    {
        return new ArrayList<ChatMessage>(this.messages);
    }

    public String getConversationalContext()
    {
        ArrayList<ChatMessage> snapshot = this.getMessages();
        int skipAmt = snapshot.size() - bot.getBotOptions().optInt("contextDepth", 5);
        if (skipAmt < 0) skipAmt = 0;
        Stream<ChatMessage> rMessages = snapshot.stream().skip(skipAmt);
        return rMessages.map((msg) -> {
            return msg.getSender() + " said \"" + msg.getBody() + "\"";
        }).collect(Collectors.joining("\n"));
    }

    private String getPreambleFromContextServer(String message)
    {
        JSONObject botOptions = this.bot.getBotOptions();
        StringBuffer returnContext = new StringBuffer();
        if (botOptions.has("definitionServer"))
        {
            try
            {
                HttpURLConnection con = (HttpURLConnection) new URL(botOptions.optString("definitionServer", null)).openConnection();
                con.setRequestMethod("POST");
                con.setRequestProperty("Content-Type", "application/json");
                JSONObject data = new JSONObject();
                data.put("keywords", new JSONArray(IRCGPTBotMain.getUncommonWords(message)));
                con.setDoOutput(true);
                con.getOutputStream().write(data.toString().getBytes());
                String output = new BufferedReader(new InputStreamReader(con.getInputStream())).lines()
                    .reduce((a, b) -> a + b).get();
                JSONObject response = new JSONObject(output);
                Set<String> respKeys = response.keySet();
                if (respKeys.size() > 0)
                {
                    returnContext.append("\n\nDefinitions Below this line\n");
                }
                respKeys.forEach((key) -> {
                    returnContext.append(key + ":\n\t" + response.optString(key).replaceAll(Pattern.quote("\n"), "\n\t") + "\n\n"); 
                });
            } catch (Exception e) {

            }
        }
        return returnContext.toString();
    }

    public JSONArray getGPTMessages()
    {
        return getGPTMessages(null);
    }

    public JSONArray getGPTMessages(ChatMessage trigger)
    {
        JSONObject botOptions = this.bot.getBotOptions();
        ArrayList<ChatMessage> snapshot = this.getMessages();
        if (trigger != null)
        {
            // Queued replies must not take context from messages arriving after their trigger.
            int index = snapshot.indexOf(trigger);
            snapshot = index >= 0 ? new ArrayList<>(snapshot.subList(0, index + 1)) : new ArrayList<>(List.of(trigger));
        }
        JSONArray ra = new JSONArray();
        String systemPreamble = botOptions.optString("systemPreamble", "");
        if (trigger == null)
        {
            if (!snapshot.isEmpty())
                trigger = snapshot.get(snapshot.size() - 1);
        }
        systemPreamble += "\n\n" + ContextNotes.forMessage(this.bot, trigger);
        if (botOptions.has("definitionServer"))
        {
            String logBody = snapshot.stream().map((msg) -> msg.getBody()).collect(Collectors.joining(" ")).toLowerCase();
            systemPreamble += getPreambleFromContextServer(logBody);
        }
        if (!systemPreamble.equals(""))
        {     
            JSONObject msgS = new JSONObject();
            msgS.put("role", "system");
            msgS.put("content", systemPreamble);
            ra.put(msgS);
        }
        int skipAmt = (snapshot.size() - botOptions.optInt("contextDepth", 5));
        if (skipAmt < 0) skipAmt = 0;
        Stream<ChatMessage> rMessages = snapshot.stream().skip(skipAmt);
        rMessages.forEach((msg) -> {
            String role = "user";
            if (msg.getSender().equals(botOptions.optString("nickname")))
                role = "assistant";
            ra.put(msg.toGPTMessage(role));
        });
        JSONObject postableInstruct = new JSONObject();
        postableInstruct.put("role","user");
        postableInstruct.put("content","respond as " + botOptions.optString("nickname") + ":");
        ra.put(postableInstruct);
        return ra;
    }
}
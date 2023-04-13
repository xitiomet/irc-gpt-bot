package org.openstatic;

import java.util.ArrayList;
import java.util.stream.Stream;

import org.json.JSONArray;
import org.json.JSONObject;

public class ChatLog
{
    private ArrayList<ChatMessage> messages;
    private JSONObject settings;

    public ChatLog(JSONObject settings)
    {
        this.messages = new ArrayList<ChatMessage>();
        this.settings = settings;
    }

    public void add(ChatMessage cm)
    {
        this.messages.add(cm);
    }

    public ArrayList<ChatMessage> getMessages()
    {
        return this.messages;
    }

    public JSONArray getGPTMessages()
    {
        JSONArray ra = new JSONArray();
        if (this.settings.has("systemPreamble"))
        {
            JSONObject msgS = new JSONObject();
            msgS.put("role", "system");
            msgS.put("content", this.settings.optString("systemPreamble"));
            ra.put(msgS);
        }
        int skipAmt = this.messages.size() - settings.optInt("contextDepth", 5);
        if (skipAmt < 0) skipAmt = 0;
        Stream<ChatMessage> rMessages = this.messages.stream().skip(skipAmt);
        rMessages.forEach((msg) -> {
            String role = "user";
            if (msg.getSender().equals(this.settings.optString("nickname")))
                role = "assistant";
            ra.put(msg.toGPTMessage(role));
        });
        return ra;
    }
}
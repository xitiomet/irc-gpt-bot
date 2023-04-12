package org.openstatic;

import java.util.ArrayList;

import org.json.JSONArray;
import org.json.JSONObject;

public class ChatLog
{
    private ArrayList<ChatMessage> messages;
    private String system;

    public ChatLog()
    {
        this.messages = new ArrayList<ChatMessage>();
        this.system = null;
    }

    public ChatLog(String system)
    {
        this.messages = new ArrayList<ChatMessage>();
        this.system = system;
    }

    public void setSystem(String system)
    {
        this.system = system;
    }

    public void add(ChatMessage cm)
    {
        this.messages.add(cm);
    }

    public ArrayList<ChatMessage> getMessages()
    {
        return this.messages;
    }

    public JSONArray getGPTMessages(String assistant)
    {
        JSONArray ra = new JSONArray();
        if (this.system != null)
        {
            JSONObject msgS = new JSONObject();
            msgS.put("role", "system");
            msgS.put("content", system);
            ra.put(msgS);
        }
        this.messages.forEach((msg) -> {
            String role = "user";
            if (msg.getSender().equals(assistant))
                role = "assistant";
            ra.put(msg.toGPTMessage(role));
        });
        return ra;
    }
}
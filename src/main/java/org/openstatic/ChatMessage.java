package org.openstatic;

import java.util.Date;

import org.json.JSONObject;

public class ChatMessage 
{
    private String sender;
    private String body;
    private Date timestamp;
    private String recipient;

    public ChatMessage(String sender, String recipient, String body, Date timestamp)
    {
        this.sender = sender;
        this.recipient = recipient;
        this.body = body;
        this.timestamp = timestamp;
    }

    public String getSender()
    {
        return this.sender;
    }

    public void setRecipient(String recipient)
    {
        this.recipient = recipient;
    }

    public String getRecipient()
    {
        return this.recipient;
    }

    public String getBody()
    {
        return this.body;
    }

    public Date getTimestamp()
    {
        return this.timestamp;
    }

    public JSONObject toGPTMessage(String role)
    {
        JSONObject msgU = new JSONObject();
        msgU.put("role", role);
        if (role.equals("user"))
        {
            msgU.put("content", this.getSender() + " says \"" + this.body + "\"");
        } else {
            msgU.put("content", this.body);
        }
        return msgU;
    }

    public String toSimpleLine()
    {
        return this.sender + ": " + this.body;
    }
}

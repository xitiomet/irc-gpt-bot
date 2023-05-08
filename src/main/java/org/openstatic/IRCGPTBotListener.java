package org.openstatic;

public interface IRCGPTBotListener
{
    public void onBotRemoved(IRCGPTBot bot);
    public void onBotAdded(IRCGPTBot bot);
    public void onBotStats(IRCGPTBot bot);
    public void onPreambleChange(IRCGPTBot bot);
}
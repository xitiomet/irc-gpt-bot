package org.openstatic;

import org.kitteh.irc.client.library.event.channel.ChannelMessageEvent;
import org.kitteh.irc.client.library.event.user.PrivateMessageEvent;

public interface IRCGPTBotListener
{
    public void onBotRemoved(IRCGPTBot bot);
    public void onBotAdded(IRCGPTBot bot);
    public void onBotStats(IRCGPTBot bot);
    public void onPreambleChange(IRCGPTBot bot);
    public void onPrivateMessage(IRCGPTBot bot, PrivateMessageEvent pme);
    public void onChannelMessage(IRCGPTBot bot, ChannelMessageEvent cme);
}
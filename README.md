## IRC GPT Bot

An IRC Bot powered by chatGPT

This is not your typical IRC bot, it does not manage operator status, channel voice or anything administrative. It answers questions, greets users and provides summaries using chatGPT

Features:
 * Ability to modify system preamble instructions without restarting bot
    * "only respond the way somebody from the victorian era would"
    * "talk like character X from show Y"
 * Can adjust context depth to provide bot with more conversational awareness
 * Can respond to private messages
 * Can greet users with a brief summary of the conversation before they joined
 * Lanterna Interface - runs well in "screen" environment, no desktop required

![](https://openstatic.org/projects/ircgptbot/irc-gpt-bot-ss.png)

To compile this project please run:
```bash
$ mvn package
```
from a terminal.

NOTE: You can also find the latest builds here: [https://openstatic.org/projects/ircgptbot/](https://openstatic.org/projects/ircgptbot/) (scroll to bottom)

copy "default-config.json" to "~/.irc-gpt-bot.json" and fill in the blanks with your information. You can also create a config file anywhere and use the -f option.

```json
{
    "nickname": "chatGPT",   // Nickname bot should use
    "channels": ["#lobby"],  // Channels bot should join
    "channelKeys": {         // provide keys for channels that require them
        "#lobby": "password"
    },
    "user": null,            // IRC User field (for bot)
    "realName": null,        // IRC Real Name field (for bot)
    "password": null,        // IRC Password (for bot)
    "server": "127.0.0.1",   // Server to connect to
    "openAiKey": "",         // Your Open-AI API Key
    "contextDepth": 5,       // How much history to provide chatGPT for context
    "systemPreamble": "Only respond in l33t speak",  // Some rules for chatGPT to follow
    "port": 6667,            // IRC Port
    "secure": false,         // Does this server require a secure connection
    "greet": false,          // Should the bot greet people joining the channel?
    "acceptInvites": true,    // Should the bot accept channel invites?
    "model": "gpt-3.5-turbo", // ChatGPT model to use
    "privateMessages": true,  // Respond to private messages
    "logPath": "./irc-gpt-bot-logs/" // Log path
}
```
NOTE: The bot will only join the channels in "channels" if you add a keyed channel it will need to appear in both "channels" and "channelKeys"

You may also start the bot using only command line arguments (for scripting purposes)
```bash
usage: irc-gpt-bot
IRC GPT Bot: An IRC Bot for chatGPT
 -?,--help                    Shows help
 -a,--system-preamble <arg>   Provide a set of instructions for the bot to
                              follow
 -c,--channels <arg>          List of channels to join (separated by
                              comma)
 -d,--debug                   Turn on Debug mode
 -e,--secure                  Use Secure connection
 -f,--config <arg>            Specify a config file (.json) to use
 -k,--key                     Set the openAI key for this bot
 -l,--log-output <arg>        Specify log output path
 -n,--nickname <arg>          Set Bot Nickname
 -p,--port <arg>              Specify connection port
 -s,--server <arg>            Connect to server
 -x,--context-depth <arg>     How many messages to provide chatGPT for
                              context
```

When directly messaging this bot it will respond to all messages, however in a channel a user needs to use the bots name or be responding to a question the bot asked.

### Greeting mode
if greeting is enabled the bot will greet new users entering the channel along with a summary of the conversation

Example with preamble set to "Respond to all messages in a victorian style":

    (11:51:56 AM) brian: did you know that pencils are made of lead?
    (11:52:08 AM) Bob: not anymore they started making them out of graphite
    (11:52:15 AM) brian: oh really? thats interesting
    (11:52:21 AM) xitiomet [~xitiomet@Xi04.lan] entered the room.
    (11:52:25 AM) chatGPT: Greetings and salutations, dear xitiomet! Pray tell, how doth thee fare on this fine day? To recapitulate the discourse heretofore, brian didst proclaim that pencils were made of lead whilst Bob doth rebut that pencils are instead fashioned from graphite. This revelation didst strike brian with interest and awe.

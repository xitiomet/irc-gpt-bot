## IRC GPT Bot

An IRC Bot powered by chatGPT

This is not your typical IRC bot, It answers questions, greets users and provides summaries using chatGPT's API. If the bot has operator status on a channel you can specify a list of nicknames that should also be granted operator status.

Features:
 * Ability to modify system preamble instructions without restarting bot
    * "only respond the way somebody from the victorian era would"
    * "talk like character X from show Y"
 * Can adjust context depth to provide bot with more conversational awareness
 * Can respond to private messages
 * Can greet users with a brief summary of the conversation before they joined
 * Lanterna Interface - runs well in "screen" environment, no desktop required
 * Launch multiple bots on multiple servers.

if you would like to play around with my instance of irc-gpt-bot goto <a href="https://irc.openstatic.org/">irc.openstatic.org</a> and join #lobby. @charlieGPT is running on irc-gpt-bot.

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
    "bots": {
        "chatGPT": { // Bot Identifier
            "nickname": "chatGPT",   // Nickname bot should use
            "botOps": ["xitiomet"],  // IF the bot is the channel operator, what other operators should get op status?
            "channels": {
                "#lobby": {
                    "join": true, // Channel bot should join
                    "key": null // only include if channel requires a key
                }
            },  
            "ignore": ["AnnoyingUser"], // Bot should completely ignore these nicknames (can be used for other bots or abusive users)
            "user": null,            // IRC User field (for bot)
            "realName": null,        // IRC Real Name field (for bot)
            "password": null,        // IRC Password (for bot)
            "server": "127.0.0.1",   // Server to connect to
            "contextDepth": 5,       // How much history to provide chatGPT for context
            "systemPreamble": "Only respond in l33t speak",  // Some rules for chatGPT to follow
            "definitionServer": "https://...", // A url for providing dynamic context to the systemPreamble
            "port": 6667,            // IRC Port
            "secure": false,         // Does this server require a secure connection
            "greet": false,          // Should the bot greet people joining the channel?
            "acceptInvites": true,    // Should the bot accept channel invites?
            "gptEnabled": true,       // Is this bot connected to chatGPT (set to false for api only bot)
            "model": "gpt-3.5-turbo", // ChatGPT model to use
            "privateMessages": true,  // Respond to private messages
        }
    },
    "openAiKey": "",         // Your Open-AI API Key
    "logPath": "./irc-gpt-bot-logs/" // Log path
```
NOTE: Make sure you either remove the default "botOps" or edit it to reflect your own nickname!

You may also start the bot using only command line arguments (for scripting purposes)
```bash
usage: irc-gpt-bot
IRC GPT Bot: An IRC Bot for chatGPT
 -?,--help                    Shows help
 -f,--config <arg>            Specify a config file (.json) to use
 -l,--log-output <arg>        Specify log output path
 -g,--gui                     Turn on GUI mode
```

When directly messaging this bot it will respond to all messages, however in a channel a user needs to use the bots name or be responding to a question the bot asked.


### Greeting mode
if greeting is enabled the bot will greet new users entering the channel along with a summary of the conversation

Example with preamble set to "Respond to all messages in a victorian style":
```text
(11:51:56 AM) brian: did you know that pencils are made of lead?
(11:52:08 AM) Bob: not anymore they started making them out of graphite
(11:52:15 AM) brian: oh really? thats interesting
(11:52:21 AM) xitiomet [~xitiomet@Xi04.lan] entered the room.
(11:52:25 AM) chatGPT: Greetings and salutations, dear xitiomet! Pray tell, how doth thee fare on this fine day? To recapitulate the discourse heretofore, brian didst proclaim that pencils were made of lead whilst Bob doth rebut that pencils are instead fashioned from graphite. This revelation didst strike brian with interest and awe.
```


### Definition Server
In order to make the bot more dynamic without training my own custom model, i came up with a pretty simple solution, keywords that add context.
if you add a "definitionServer" url to your bot, every request will be broken down to a list of keywords within the "contextDepth" of the conversation, commmon words are also removed.
You obviously shouldn't define words that chatGPT already understands, but lets say you want it to know about something like your website.


As a simple example lets say the user says "The quick brown fox jumps over the lazy dog"

"The Quick brown fox jumps over the lazy dog" would become:

```json
{
    "keywords": ["quick", "brown", "fox", "jumps", "lazy", "dog"]
}
```
This object would then be POST'ed to your "definitionServer" URL:

You're servers response should look something like this:
```json
{
    "quick": "to move fast or with haste",
    "brown": "a color",
}
```

Then the systemPreamble is updated for this request with something like this:
```text
Definitions below this line

quick:
    to move fast or with haste

brown:
    a color
```
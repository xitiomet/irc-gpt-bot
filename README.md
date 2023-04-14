## IRC GPT Bot

An IRC Bot for chatGPT using [KittehIRC](https://github.com/KittehOrg/KittehIRCClientLib)

To compile this project please run:
```bash
$ mvn package
```
from a terminal.

copy "default-config.json" to "config.json" and fill in the blanks with your information.

```json
{
    "nickname": "chatGPT",   // Nickname bot should use
    "channels": ["#lobby"],  // Channels bot should join
    "user": null,            // IRC User field (for bot)
    "realName": null,        // IRC Real Name field (for bot)
    "password": null,        // IRC Password (for bot)
    "server": "127.0.0.1",   // Server to connect to
    "openAiKey": "",         // Your Open-AI API Key
    "contextDepth": 5,       // How much history to provide chatGPT for context
    "systemPreamble": "Only respond in hacker speak",  // Some rules for chatGPT to follow
    "port": 6667,            // IRC Port
    "secure": false          // Does this server require a secure connection
}
```

When directly messaging this bot it will respond to all messages, however in a channel a user needs to use the bots name or be responding to a question the bot asked.


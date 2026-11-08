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
 * Persistent per-bot token usage and estimated USD costs, including cached input and tool-calling rounds.

if you would like to play around with my instance of irc-gpt-bot goto <a href="https://irc.openstatic.org/">irc.openstatic.org</a> and join #lobby. @charlieGPT is running on irc-gpt-bot.

![](https://openstatic.org/projects/ircgptbot/irc-gpt-bot-ss.png)

### Setting up irc-gpt-bot
At the bottom of this page you will find a few different options for installing this project. If you are using a debian based x86-64 linux environment i highly suggest using the native package. 
It will automatically setup irc-gpt-bot as a headless service with config files located in /etc/irc-gpt-bot

```bash
$ sudo dpkg -i irc-gpt-bot-native.deb
```
To disable the service simply remove /etc/init.d/irc-gpt-bot or remove its executable flag

If you wish to run irc-gpt-bot with its terminal interface simply run "irc-gpt-bot"

To compile this project from source please run:
```bash
$ mvn package
```
from a terminal. The build uses a GraalVM JDK (25+) and picks an operating system profile automatically:

- **linux**: native executable, native and Java `.deb` packages, and an AppImage
- **windows**: native `irc-gpt-bot.exe` and an NSIS installer (`target/IrcGPTBotSetup-<version>.exe`); see [NSIS.md](NSIS.md)

Add `-DskipNative=true` to build only the Java jar and skip the native executable and its packages.

Configuration now lives in `~/.irc-gpt-bot/` (on Windows, `%USERPROFILE%\.irc-gpt-bot\`).
Use `-f /path/to/config-directory` to select another directory. Each bot has its own
`<bot-id>.json` file, and `irc-gpt-bot.json` contains only root settings such as API keys,
web server settings, endpoint defaults, and `logPath`. The bot identifier comes from
the filename, not its IRC nickname.

For an initial setup, copy `default-config.json` to
`~/.irc-gpt-bot/irc-gpt-bot.json`, fill in your settings, and start the program.
Its inline `bots` object is automatically split into separate files, with the
original master saved as `irc-gpt-bot.json.bak`. Existing `~/.irc-gpt-bot.json`
is automatically imported when the new master does not exist; the original is
left untouched. `-f /path/to/old.json` remains supported and imports into
`/path/to/old.d/`. Continue using that directory afterwards; the legacy file is
no longer updated. Existing default log locations are retained on import.

The following legacy-format example is also accepted for migration.
DO NOT COPY AND PASTE FROM BELOW, the comments i added for clarity are invalid JSON.

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
            "aliases": ["chat", "gpt"], // Extra names (besides the nickname) the bot should respond to when mentioned
            "followUpSeconds": 120,  // How long after the bot speaks follow-ups (questions, replies) still get an answer
            "replyCooldownSeconds": 15, // Minimum gap between unprompted replies (U?, RE, OQ); 0 disables
            "openQuestionSeconds": 60,  // Answer "anyone know...?" questions nobody else answered within this time; 0 disables
            "user": null,            // IRC User field (for bot)
            "realName": null,        // IRC Real Name field (for bot)
            "password": null,        // IRC Password (for bot)
            "operatorName": null,    // IRC operator name used with OPER (defaults to nickname)
            "operatorPassword": null, // If set, bot sends OPER on connect/reconnect to gain IRC operator privileges
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
            "enabled": true,          // Optional: false keeps the bot disconnected (toggled by the dashboard Enable/Disable button)
            "gptTools": ["send_private_message", "send_notice", "send_action", "kick_user", "set_topic", "get_names", "list_channels", "invite_user", "list_notes", "read_note", "write_note", "delete_note", "list_log_files", "read_log_file"], // Optional: IRC tools the AI may use (omit for all, [] for none)
            "maxToolRounds": 5,       // Optional: max tool-calling round trips per reply
            "toolUrls": [             // Optional: web services the AI can call (also editable with the dashboard "Tools" button)
                {"purpose": "Find out what song is playing", "url": "https://example.com/now-playing", "params": {}},
                {"purpose": "Make a blog post", "url": "https://example.com/post", "params": {"subject": "the subject of the post", "body": "the post body"}}
            ],
        }
    },
    "openAiKey": "",         // Your Open-AI API Key
    "logPath": "./irc-gpt-bot-logs/", // Log path
    "apiServer": true, // provide api server and web interface
    "apiPort": 6553, // port for api server and web interface
    "apiPassword": "changeme", // password for api server and web interface
    "responsesApiUrl": "https://api.openai.com/v1/responses", //optional for local ai server (must support the OpenAI Responses API) can also be in bot config
    "modelsApiUrl": "https://api.openai.com/v1/models" //optional, used to populate the model picker, can also be in bot config
}
```
NOTE: Make sure you either remove the default "botOps" or edit it to reflect your own nickname!

You may also start the bot using only command line arguments (for scripting purposes)
```bash
usage: irc-gpt-bot
IRC GPT Bot: An IRC Bot for chatGPT
 -?,--help               Shows help
 -f,--config <arg>       Specify a config directory (or legacy .json file)
 -g,--gui                Turn on GUI mode
 -h,--headless           Turn on Headless mode
 -l,--log-output <arg>   Specify log output path
```

### Token usage and price estimates

The layout is:

```text
~/.irc-gpt-bot/
    irc-gpt-bot.json       Root settings (no inline bots after migration)
    chatGPT.json           Settings for bot identifier chatGPT
    anotherBot.json        Settings for another bot
    pricing.json           Editable model prices in USD per million tokens
<logPath>/
    usage.json             Lifetime usage totals for all bots
    chatGPT/              That bot's logs and notes
```

Bot IDs must be filename-safe: letters, numbers, `_`, `-`, `.`, and IRC nickname
characters `[]{}^` plus backtick. Leading/trailing dots, path separators, `|`,
Windows device names, and `irc-gpt-bot`, `pricing`, and `usage` are reserved.
IDs must be unique ignoring case. Invalid configurations fail explicitly rather
than being silently replaced.

Usage is recorded after each successful Responses API response, including
greetings, private replies, channel replies, and every tool round. It persists
across restarts, disabling/enabling, model changes, and bot deletion. Reusing a
bot ID resumes its historical totals. Removing a bot deletes its configuration,
not its usage, logs, or notes. JSON writes use temporary files and atomic
replacement where supported. Run only one process against a given config/log
directory; these files are not a shared multi-process database.

The dashboard and terminal show tokens and estimated costs. The authenticated
bot API and WebSocket statistics include a `usage` object containing `requests`,
`tokensSpent`, `inputTokensSpent`, `outputTokensSpent`, `cachedTokensSpent`,
`costEstimate`, `currency`, `unpricedTokensSpent`, `untrackedRequests`, and a
`models` breakdown. Cached tokens are a subset of input, not extra tokens;
reasoning tokens are already included in output. Missing or invalid provider
usage is logged and counted as an untracked request without inventing counts.
Write failures are logged and shown in statistics; pending totals are retained
in memory for retry on the next response.

`pricing.json` is created with a small set of verified standard OpenAI prices
and is reloaded on every response, so edits do not require a restart:

```json
{
    "currency": "USD",
    "models": {
        "gpt-4o-mini": {
            "inputPerMillion": 0.15,
            "cachedInputPerMillion": 0.075,
            "outputPerMillion": 0.60
        }
    },
    "endpoints": {}
}
```

The estimate is `(uncached input * input rate + cached input * cached rate +
output * output rate) / 1,000,000`, summed at the prices in effect for each
response; editing prices does not recalculate past costs. The response's actual
model is used, with an exact match preferred over a dated model's base name.
Unknown models (including legacy models absent from the bundled price list),
invalid prices, and non-default service tiers remain explicitly unpriced.
An estimate with unpriced tokens or untracked requests is incomplete, **not
an assertion that those requests were free**.

The `models` prices apply only to `https://api.openai.com/v1/responses`. For
local or alternative providers, explicitly configure prices under
`"endpoints": {"https://your-provider/v1/responses": {"model-name": {...rates...}}}`.
Zero rates may be used for a genuinely free local model. Cached rates may be
omitted only when no cached tokens are reported. Rows may also include a positive
integer `longContextThreshold`, `longContextInputMultiplier`, and
`longContextOutputMultiplier`; multipliers apply only when input exceeds the
threshold. Prices must be nonnegative JSON numbers, not strings.

These are token-cost estimates, not billing totals: taxes, discounts, provider
tool charges, and other non-token fees are excluded. Check and update prices
against [OpenAI's pricing documentation](https://developers.openai.com/api/docs/pricing).
No automatic price scraping or network refresh is performed.

To run the migration, pricing, and request-integration tests:

```bash
mvn -DskipNative=true test
```

When directly messaging this bot it will respond to all messages. In a channel it responds when any of these apply (the flag is shown in the channel log):

| Flag | Rule |
|------|------|
| `@B` | The message contains the bot's nickname or one of its `aliases` as a whole word |
| `U2` | The bot and the sender are the only (non-ignored) users in the channel |
| `B?` | The bot's last message was a question |
| `U?` | The bot spoke last and the message is a question (ends in `?` or starts with a question word) |
| `RE` | The bot spoke last and the sender is who it was answering, or was named in its reply |
| `OQ` | A question to the whole channel ("anyone know...", "does anybody...", "can someone...") that nobody else answers within `openQuestionSeconds` (default 60) |

`B?`, `U?` and `RE` only apply within `followUpSeconds` (default 120) of the bot's last message.

`@B`, `U2` and `B?` are always answered. The unprompted rules (`U?`, `RE`, `OQ`) are limited to one reply per `replyCooldownSeconds` (default 15) per channel, so the bot can't dominate a busy channel; a suppressed reply is logged as `CD`.

### AI Tools

The bot uses the OpenAI Responses API with function calling, so the model can act on IRC as well as reply:

| Tool | Description |
|------|-------------|
| `send_private_message` | Send a private message to a user |
| `send_notice` | Send a NOTICE to a user or channel |
| `send_action` | Perform a `/me` action |
| `kick_user` | Kick a user from the current channel (requires op; never kicks the bot itself or anyone in `botOps`) |
| `set_topic` | Change the current channel's topic |
| `get_names` | Get the names list (with `@`/`+` prefixes) of a channel the bot is in |
| `list_channels` | List the channels the bot is in, or the server's public channel list (`LIST`) |
| `invite_user` | Invite a user to a channel the bot is in |
| `list_notes` | List the files in the bot's `notes/` directory |
| `read_note` | Read a file from the bot's `notes/` directory (long notes are read in 8000-character chunks using `offset`) |
| `write_note` | Create, overwrite or append to a file in the bot's `notes/` directory (max 64 KB per note) |
| `delete_note` | Delete a file from the bot's `notes/` directory |
| `list_log_files` | List files in the bot's own log directory (`logPath/<botId>/`) |
| `read_log_file` | Read a file from the bot's own log directory (read-only; large files return their newest 8000 characters, or the last `lines` lines) |

Each bot gets a `notes/` directory inside its log directory (`logPath/<botId>/notes/`), created at startup. The file tools can't reach outside the bot's own log directory, and only files directly inside `notes/` can be written or deleted. Note writes and deletions are logged to `tools.log`. You can also view, create, edit and delete a bot's notes from the web dashboard with its **Notes** button (the list refreshes automatically), or from the bot's window in the console interface with its **Notes** button.

Private conversation logs (`nickname.log`) are flagged `"private": true` in `list_log_files`, and `read_log_file` puts a confidentiality notice at the top of them. The AI is told it may use what's in private logs to inform its own reasoning, but must not reveal, quote or hint at them to anyone except the user the conversation was with.

`kick_user` and `set_topic` only apply to the channel the conversation is happening in and are not offered in private messages. `get_names`, `invite_user` and the joined channel list won't expose secret, private, invite-only or keyed channels (`+s`, `+p`, `+i`, `+k`) unless the request comes from inside that channel. Limit the available tools per bot with `"gptTools"`. Legacy `completionsApiUrl` settings are converted automatically by replacing `/chat/completions` with `/responses`.

#### URL Tools (`toolUrls`)

Each `toolUrls` entry becomes an extra tool. The AI sees the `purpose` and calls the tool when it needs that information or action; the bot then requests the `url` and gives the response (truncated to 8000 characters) back to the AI.

- `params` maps parameter names to descriptions; the AI fills in the values. A list of names or of `{"name": ..., "description": ...}` objects is also accepted.
- Without params the URL is fetched with `GET`. With params they are sent as a JSON `POST` body. Set `"method": "GET"` to send them as a query string instead, or `"method": "POST"` to always POST.
- URL tools are always available when configured; `gptTools` only limits the built-in IRC tools. Calls are logged to `tools.log`.
- Manage these from the web dashboard with each bot's **Tools** button.

### API Server / Web interface

irc-gpt-bot also provides a web interface and api server for ease of use. By default this is configured for port 6553 with password "changeme"
Each bot's **Disable** / **Enable** button disconnects it from IRC or reconnects it. The choice is saved as `"enabled"` in the bot's settings, so a disabled bot stays offline after a restart.
If you plan on exposing this interface to the internet, i highly encourage wrapping it with SSL on nginx.

first you should create an upstream for ircgptbot
```
upstream ircgptbot {
   server 127.0.0.1:6553;
}
```

then in the server section of your config
```
  location ~ ^/bot(/?)(.*) {
              proxy_pass http://ircgptbot/$2;
              proxy_http_version 1.1;
              proxy_set_header Host irc.openstatic.org;
              proxy_set_header X-Real-IP $remote_addr;
  }

  location ~ ^/ircgptbot(/?)(.*) {
              proxy_pass http://ircgptbot;
              proxy_http_version 1.1;
              proxy_set_header Upgrade $http_upgrade;
              proxy_set_header Connection "upgrade";
              proxy_set_header Host irc.openstatic.org;
              proxy_set_header X-Real-IP $remote_addr;
  }
```
NOTE: the first entry can be set to whatever path you like, but the websocket must be mounted at /ircgptbot on the server

The web interface will provide you with the ability to launch and control bots.
Each bot's **Channels** button opens a live list of its joined channels. Enter a channel name to join it, or click **Leave** beside a channel and confirm to leave it.
Use **Edit Model/Prompt** to select a model from the dropdown above the system preamble, then **Save** to apply both settings together.
![](https://openstatic.org/projects/ircgptbot/ircgptweb.png)

Native builds embed web assets using the allowlist in [resource-config.json](src/main/resources/META-INF/native-image/resource-config.json).
When adding a web page, include its `irc-gpt-bot/` resource path in that file, then rebuild with `mvn package` and restart using the rebuilt executable. Otherwise the native executable will return 404 for the missing page.

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

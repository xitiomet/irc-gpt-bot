var connection;
var debugMode = false;
var reconnectTimeout;
var hostname = location.hostname;
var protocol = location.protocol;
var port = location.port;
var wsProtocol = 'ws';
var httpUrl = '';
var preambles = {};

function getParameterByName(name, url = window.location.href) 
{
    name = name.replace(/[\[\]]/g, '\\$&');
    var regex = new RegExp('[?&]' + name + '(=([^&#]*)|&|#|$)'),
        results = regex.exec(url);
    if (!results) return null;
    if (!results[2]) return '';
    return decodeURIComponent(results[2].replace(/\+/g, ' '));
}

function sendEvent(wsEvent)
{
    var out_event = JSON.stringify(wsEvent);
    if (debugMode)
        console.log("Transmit: " + out_event);
    try
    {
        connection.send(out_event);
    } catch (err) {
        console.log(err);
    }
}

function savePreamble(objectId)
{
    var preamble = document.getElementById('preambleText_' + objectId).value;
    if (preamble != null)
    {
        if (preamble != preambles[objectId])
        {
            if (confirm("Save Changes to preamble?"))
            {
                sendEvent({"command":"preamble", "id": objectId, "preamble": preamble});
            } else {
                document.getElementById('preambleText_' + objectId).value = preambles[objectId];
            }
        }
    }
}

function reconnectBot(objectId)
{
    sendEvent({"command":"reconnect", "id": objectId});
}

function shutdownBot(objectId)
{
    if (confirm("Are you sure you want to terminate this bot?"))
        sendEvent({"command":"shutdown", "id": objectId});
}

function deleteBot(objectId)
{
    if (confirm("Are you sure you want to delete this bot?"))
        sendEvent({"command":"remove", "id": objectId});
}

function launchBot()
{
    var botId = prompt("Enter Bot Identifier");
    if (botId != null)
    {
        sendEvent({"command":"launch", "botOptions": {}, "id": botId});
    }
}

function doAuth()
{
    sendEvent({
        "command": "auth",
        "password": document.getElementById('password').value
    });
}

function removeBot(botId)
{
    var tag = "bot_" + botId;
    var botTag = document.getElementById(tag);
    if (botTag != null)
    {
        document.getElementById("botTable").removeChild(botTag);
    }
}

function addOrUpdateBot(json)
{
    var objectId = json.id;
    var tag = "bot_" + objectId;
    var avatarTagId = "avatar_" + objectId;
    var avatarImgTagId = "avatar_img_" + objectId;
    var nicknameTagId = "nickname_" + objectId;
    var statsTagId = "stats_" + objectId;
    var preambleTagId = "preamble_" + objectId;
    var actionsTagId  = "actions_" + objectId;

    var avatarTag, botTag, nicknameTag, statsTag, actionsTag, preambleTag;
    botTag = document.getElementById(tag);
    if (botTag == null)
    {
        botTag = document.createElement("tr");
        botTag.id = tag;
        avatarTag = document.createElement("td");
        avatarTag.id = avatarTagId;
        avatarImgTag = document.createElement("img")
        avatarImgTag.id = avatarImgTagId;
        nicknameTag = document.createElement("td");
        avatarTag.id = nicknameTagId;
        statsTag = document.createElement("td");
        statsTag.id = statsTagId;
        preambleTag = document.createElement("td");
        preambleTag.id = preambleTagId;
        actionsTag = document.createElement("td");
        actionsTag.id = actionsTagId;
        botTag.appendChild(avatarTag);
        botTag.appendChild(nicknameTag);
        botTag.appendChild(statsTag);
        botTag.appendChild(preambleTag);
        botTag.appendChild(actionsTag);
        avatarTag.appendChild(avatarImgTag);
        avatarImgTag.src = json.stats.status + ".svg";
        avatarImgTag.style.height = '64px';
        avatarImgTag.style.width = '64px';
        nicknameTag.innerHTML = "<b style=\"font-size: 18px;\">" + json.stats.nickname + "</b><br />" + json.stats.model;
        actionsTag.innerHTML = "<button style=\"width: 92px;\" onClick=\"reconnectBot('" + objectId + "')\">Reconnect</button><br /><button style=\"width: 92px;\" onClick=\"shutdownBot('" + objectId + "')\">Shutdown</button><br /><button style=\"width: 92px;\" onClick=\"deleteBot('" + objectId + "')\">Delete</button><br />";
        var preambleText = '';
        if (json.hasOwnProperty('preamble'))
            preambleText = json.preamble;
        preambleTag.innerHTML = "<textarea style=\"width: 550px; height: 80px;\" onBlur=\"savePreamble('" + objectId + "')\" id=\"preambleText_" + objectId + "\">" + preambleText + "</textarea>";
        preambles[objectId] = preambleText;
        document.getElementById("botTable").appendChild(botTag);
    } else {
        avatarTag = document.getElementById(avatarTagId);
        avatarImgTag = document.getElementById(avatarImgTagId);
        nicknameTag = document.getElementById(nicknameTagId);
        statsTag = document.getElementById(statsTagId);
        actionsTag = document.getElementById(actionsTagId);
    }
    statsTag.innerHTML = "<b>Messages Handled</b> " + json.stats.messagesHandled + "<br /><b>Messages Seen</b> " + json.stats.messagesSeen + "<br /><b>Errors</b> " + json.stats.errorCount;
    var targetStatusIcon = json.stats.status + ".svg";
    if (avatarImgTag.src != targetStatusIcon)
        avatarImgTag.src = targetStatusIcon;
}

function setupWebsocket()
{
    try
    {
        if (hostname == '')
        {
            debugMode = true;
            hostname = '127.0.0.1';
            protocol = 'http';
            port = 6553;
            httpUrl = "http://127.0.0.1:6553/ircgptbot/";
        }
        if (protocol.startsWith('https'))
        {
            wsProtocol = 'wss';
        }
        connection = new WebSocket(wsProtocol + '://' + hostname + ':' + port + '/ircgptbot/');
        
        connection.onopen = function () {
            if (document.getElementById('login').style.display == 'none')
            {
                doAuth();
            }
        };
        
        connection.onerror = function (error) {

        };

        //Code for handling incoming Websocket messages from the server
        connection.onmessage = function (e) {
            if (debugMode)
            {
                console.log("Receive: " + e.data);
            }
            var jsonObject = JSON.parse(e.data);
            if (jsonObject.hasOwnProperty("type") && jsonObject.hasOwnProperty("action"))
            {
                var type = jsonObject.type;
                var action = jsonObject.action;
                if (type == 'bot')
                {
                    if (action == 'removed')
                    {
                        removeBot(jsonObject.id);
                    } else if (action == 'preamble') {
                        document.getElementById('preambleText_' + jsonObject.id).value = jsonObject.preamble;
                        preambles[jsonObject.id] = jsonObject.preamble;
                    } else {
                        addOrUpdateBot(jsonObject);
                    }
                } else if (type == 'user') {
                    if (action == 'authok')
                    {
                        document.getElementById('login').style.display = 'none';
                        document.getElementById('console').style.display = 'block';
                        document.getElementById('launchBotButton').style.display = 'inline-block';
                    } else if (action == 'authfail') {
                        document.getElementById('errorMsg').innerHTML = jsonObject.error;
                    }
                }
            }
        };
        
        connection.onclose = function () {
          console.log('WebSocket connection closed');
          reconnectTimeout = setTimeout(setupWebsocket, 10000);
        };
    } catch (err) {
        console.log(err);
    }
}

window.onload = function() {
    setupWebsocket();
};


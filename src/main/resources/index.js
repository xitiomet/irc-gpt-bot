var connection;
var debugMode = false;
var reconnectTimeout;
var hostname = location.hostname;
var protocol = location.protocol;
var port = location.port;
var wsProtocol = 'ws';
var httpUrl = '';

function getParameterByName(name, url = window.location.href) 
{
    name = name.replace(/[\[\]]/g, '\\$&');
    var regex = new RegExp('[?&]' + name + '(=([^&#]*)|&|#|$)'),
        results = regex.exec(url);
    if (!results) return null;
    if (!results[2]) return '';
    return decodeURIComponent(results[2].replace(/\+/g, ' '));
}

function editPreambleWindow(botId)
{
    var myWindow = window.open('preamble.html?apiPassword=' + encodeURIComponent(document.getElementById('password').value) + '&id=' + encodeURIComponent(botId), "Edit Preamble", "width=455,height=635");
}

function launchBotWindow()
{
    var myWindow = window.open('launch.html?apiPassword=' + encodeURIComponent(document.getElementById('password').value), "LaunchBot", "width=455,height=635");
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

function notice(objectId)
{
    var channelName = prompt("Enter Channel Name or Nickname (ex: #lobby)");
    var notice = prompt("Enter Notice Message");
    if (channelName != undefined && channelName != '' && notice != undefined)
    {
        sendEvent({"command":"notice", "id": objectId, "to": channelName, "message": notice});
    }
}

function joinChannel(objectId)
{
    var channelName = prompt("Enter Channel Name (ex: #lobby)");
    if (channelName != undefined && channelName != '')
    {
        sendEvent({"command":"join", "id": objectId, "channel": channelName});
    }
}

function partChannel(objectId)
{
    var channelName = prompt("Enter Channel Name (ex: #lobby)");
    if (channelName != undefined && channelName != '')
    {
        sendEvent({"command":"part", "id": objectId, "channel": channelName});
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

function addBot(json)
{
    var objectId = json.id;
    var tag = "bot_" + objectId;
    var avatarTagId = "avatar_" + objectId;
    var avatarImgTagId = "avatar_img_" + objectId;
    var nicknameTagId = "nickname_" + objectId;
    var statsTagId = "stats_" + objectId;
    var actionsTagId  = "actions_" + objectId;

    var avatarTag, botTag, nicknameTag, statsTag, actionsTag;
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
        nicknameTag.style.minWidth = '138px';
        avatarTag.id = nicknameTagId;
        statsTag = document.createElement("td");
        statsTag.id = statsTagId;
        statsTag.style.minWidth = '160px';
        actionsTag = document.createElement("td");
        actionsTag.id = actionsTagId;
        botTag.appendChild(avatarTag);
        botTag.appendChild(nicknameTag);
        botTag.appendChild(statsTag);
        botTag.appendChild(actionsTag);
        avatarTag.appendChild(avatarImgTag);
        avatarImgTag.src = json.stats.status + ".svg";
        avatarImgTag.style.height = '64px';
        avatarImgTag.style.width = '64px';
        nicknameTag.innerHTML = "<b style=\"font-size: 18px;\">" + json.stats.nickname + "</b><br />" + json.stats.model;
        actionsTag.innerHTML = "<table><tr><td><button style=\"width: 128px;\" onClick=\"editPreambleWindow('" + objectId + "')\">Edit Preamble</button><br /><button style=\"width: 128px;\" onClick=\"reconnectBot('" + objectId + "')\">Reconnect</button><br /><button style=\"width: 128px;\" onClick=\"joinChannel('" + objectId + "')\">Join Channel</button><br /><button style=\"width: 128px;\" onClick=\"partChannel('" + objectId + "')\">Leave Channel</button></td><td><button style=\"width: 128px;\" onClick=\"notice('" + objectId + "')\">Notice</button><br /><button style=\"width: 128px;\" onClick=\"shutdownBot('" + objectId + "')\">Shutdown</button><br /><button style=\"width: 128px;\" onClick=\"deleteBot('" + objectId + "')\">Delete</button></td></tr></table>";
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


function updateBot(json)
{
    var objectId = json.id;
    var tag = "bot_" + objectId;
    var avatarTagId = "avatar_" + objectId;
    var avatarImgTagId = "avatar_img_" + objectId;
    var nicknameTagId = "nickname_" + objectId;
    var statsTagId = "stats_" + objectId;
    var actionsTagId  = "actions_" + objectId;

    var avatarTag, botTag, nicknameTag, statsTag, actionsTag;
    botTag = document.getElementById(tag);
    if (botTag == null)
    {
        return;
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
            if (jsonObject.hasOwnProperty("action"))
            {
                var action = jsonObject.action;
                if (action == 'botRemoved')
                {
                    removeBot(jsonObject.id);
                } else if (action == 'botAdded') {
                    addBot(jsonObject);
                } else if (action == 'botStats') {
                    updateBot(jsonObject);
                } else if (action == 'authOk') {
                    document.getElementById('login').style.display = 'none';
                    document.getElementById('console').style.display = 'block';
                    document.getElementById('launchBotButton').style.display = 'inline-block';
                } else if (action == 'authFail') {
                    document.getElementById('errorMsg').innerHTML = jsonObject.error;
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


var connection;
var debugMode = false;
var reconnectTimeout;
var hostname = location.hostname;
var protocol = location.protocol;
var port = location.port;
var wsProtocol = 'ws';
var httpUrl = '';
var authenticated = false;

function openEditor(page, title, botId)
{
    if (!authenticated)
        return;
    var dialog = document.getElementById('editorDialog');
    var frame = document.getElementById('editorFrame');
    if (dialog.open && !editorCanClose())
        return;
    document.getElementById('editorTitle').textContent = title;
    frame.title = title;
    frame.src = page + (botId == null ? '' : '?id=' + encodeURIComponent(botId));
    if (!dialog.open)
        dialog.showModal();
}

function getEditorPassword(source)
{
    var frame = document.getElementById('editorFrame');
    if (!authenticated || !document.getElementById('editorDialog').open || source !== frame.contentWindow)
        throw new Error('Editor is not authenticated.');
    return document.getElementById('password').value;
}

function editorCanClose()
{
    var editor = document.getElementById('editorFrame').contentWindow;
    if (typeof editor.editorCanClose === 'function' && !editor.editorCanClose())
        return false;
    editor.onbeforeunload = null;
    return true;
}

function closeEditor(source, force)
{
    if (source != null && source !== document.getElementById('editorFrame').contentWindow)
        return;
    if (force || editorCanClose())
    {
        document.getElementById('editorFrame').contentWindow.onbeforeunload = null;
        document.getElementById('editorDialog').close();
    }
}

function editModelPromptWindow(botId)
{
    openEditor('preamble.html', 'Edit Model/Prompt', botId);
}

function launchBotWindow()
{
    openEditor('launch.html', 'Launch Bot');
}

function sendEvent(wsEvent)
{
    var out_event = JSON.stringify(wsEvent);
    if (debugMode && wsEvent.command !== 'auth')
        console.log("Transmit: " + out_event);
    try
    {
        connection.send(out_event);
    } catch (err) {
        console.log(err);
    }
}

function channelsWindow(botId)
{
    openEditor('channels.html', 'Channels', botId);
}

function toolsWindow(botId)
{
    openEditor('tools.html', 'Tools', botId);
}

function notesWindow(botId)
{
    openEditor('notes.html', 'Notes', botId);
}

function toggleBot(objectId)
{
    var button = document.getElementById("toggle_" + objectId);
    if (button.dataset.enabled == "true")
    {
        if (confirm("Are you sure you want to disable this bot? It will disconnect from IRC until enabled again."))
            sendEvent({"command":"disable", "id": objectId});
    } else {
        sendEvent({"command":"enable", "id": objectId});
    }
}

function updateToggleButton(objectId, enabled)
{
    var button = document.getElementById("toggle_" + objectId);
    if (button == null)
        return;
    enabled = (enabled !== false);
    button.dataset.enabled = enabled ? "true" : "false";
    button.textContent = enabled ? "Disable" : "Enable";
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

function renderBotStats(stats)
{
    var text = "<b>Messages Handled</b> " + stats.messagesHandled + "<br /><b>Messages Seen</b> " + stats.messagesSeen + "<br /><b>Errors</b> " + stats.errorCount;
    if (stats.usage)
    {
        var usage = stats.usage;
        text += "<br /><b>AI Tokens</b> " + usage.tokensSpent.toLocaleString()
            + "<br /><b>Input / Output</b> " + usage.inputTokensSpent.toLocaleString() + " / " + usage.outputTokensSpent.toLocaleString()
            + "<br /><b>Cached Input</b> " + usage.cachedTokensSpent.toLocaleString()
            + "<br /><b>Est. Cost (USD)</b> $" + Number(usage.costEstimate).toFixed(6)
            + "<br /><b>AI Requests</b> " + usage.requests.toLocaleString();
        if (usage.unpricedTokensSpent > 0)
            text += "<br /><b>Unpriced Tokens</b> " + usage.unpricedTokensSpent.toLocaleString();
        if (usage.untrackedRequests > 0)
            text += "<br /><b>Missing Usage</b> " + usage.untrackedRequests.toLocaleString() + " request(s)";
        if (usage.trackingError)
            text += "<br /><b>Usage save failed; check server logs</b>";
    }
    return text;
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
        nicknameTag.id = nicknameTagId;
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
        actionsTag.innerHTML = "<table><tr><td><button style=\"width: 128px;\" onClick=\"editModelPromptWindow('" + objectId + "')\">Edit Model/Prompt</button><br /><button style=\"width: 128px;\" onClick=\"notesWindow('" + objectId + "')\">Notes</button><br /><button style=\"width: 128px;\" onClick=\"channelsWindow('" + objectId + "')\">Channels</button></td><td><button style=\"width: 128px;\" onClick=\"toolsWindow('" + objectId + "')\">Tools</button><br /><button style=\"width: 128px;\" id=\"toggle_" + objectId + "\" onClick=\"toggleBot('" + objectId + "')\">Disable</button><br /><button style=\"width: 128px;\" onClick=\"deleteBot('" + objectId + "')\">Delete</button></td></tr></table>";
        document.getElementById("botTable").appendChild(botTag);
    } else {
        avatarTag = document.getElementById(avatarTagId);
        avatarImgTag = document.getElementById(avatarImgTagId);
        nicknameTag = document.getElementById(nicknameTagId);
        statsTag = document.getElementById(statsTagId);
        actionsTag = document.getElementById(actionsTagId);
    }
    statsTag.innerHTML = renderBotStats(json.stats);
    updateToggleButton(objectId, json.stats.enabled);
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
    if (nicknameTag != null)
        nicknameTag.innerHTML = "<b style=\"font-size: 18px;\">" + json.stats.nickname + "</b><br />" + json.stats.model;
    statsTag.innerHTML = renderBotStats(json.stats);
    updateToggleButton(objectId, json.stats.enabled);
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
                    authenticated = true;
                    document.getElementById('login').style.display = 'none';
                    document.getElementById('console').style.display = 'block';
                    document.getElementById('launchBotButton').style.display = 'inline-block';
                } else if (action == 'authFail') {
                    authenticated = false;
                    closeEditor(null, true);
                    document.getElementById('login').style.display = 'block';
                    document.getElementById('console').style.display = 'none';
                    document.getElementById('launchBotButton').style.display = 'none';
                    document.getElementById('errorMsg').textContent = jsonObject.error;
                } else if (action == 'launch' && jsonObject.error) {
                    alert("Unable to launch bot: " + jsonObject.error);
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
    var dialog = document.getElementById('editorDialog');
    dialog.addEventListener('close', function() {
        document.getElementById('editorFrame').src = 'about:blank';
    });
    dialog.addEventListener('cancel', function(event) {
        event.preventDefault();
        closeEditor();
    });
    document.getElementById('closeEditorButton').onclick = function() { closeEditor(); };
    setupWebsocket();
};

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

function masterConfigWindow()
{
    openEditor('master.html', 'Master Config');
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
    updateBotCount();
}

function escapeHtml(value)
{
    return String(value == null ? '' : value).replace(/[&<>"']/g, function(character) {
        return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[character];
    });
}

function formatCount(value)
{
    return Number(value || 0).toLocaleString();
}

function stat(label, value, className)
{
    return '<div class="stat ' + (className || '') + '"><dt>' + label + '</dt><dd>' + escapeHtml(value) + '</dd></div>';
}

function renderBotStats(stats)
{
    var text = '<dl class="stats-grid">' + stat('Messages Handled', formatCount(stats.messagesHandled));
    text += stat('Messages Seen', formatCount(stats.messagesSeen));
    if (stats.usage)
    {
        var usage = stats.usage;
        text += stat('AI Tokens', formatCount(usage.tokensSpent))
            + stat('Est. Cost (USD)', '$' + Number(usage.costEstimate || 0).toFixed(6), 'cost');
    }
    text += '</dl><details class="usage-details"><summary>Activity &amp; usage details'
        + (stats.errorCount > 0 ? ' <span class="error-message">(' + formatCount(stats.errorCount) + ' errors)</span>' : '')
        + '</summary><dl class="detail-list">'
        + '<dt>Errors</dt><dd>' + formatCount(stats.errorCount) + '</dd>';
    if (stats.usage)
    {
        text += '<dt>Input / Output</dt><dd>' + formatCount(usage.inputTokensSpent) + ' / ' + formatCount(usage.outputTokensSpent)
            + '</dd><dt>Cached Input</dt><dd>' + formatCount(usage.cachedTokensSpent)
            + '</dd><dt>AI Requests</dt><dd>' + formatCount(usage.requests) + '</dd>';
    }
    text += '</dl></details>';
    if (stats.usage)
    {
        if (usage.unpricedTokensSpent > 0)
            text += '<p class="usage-warning">Unpriced Tokens: ' + formatCount(usage.unpricedTokensSpent) + '. Cost estimate is incomplete.</p>';
        if (usage.untrackedRequests > 0)
            text += '<p class="usage-warning">Missing Usage: ' + formatCount(usage.untrackedRequests) + ' request(s).</p>';
        if (usage.trackingError)
            text += '<p class="usage-warning">Usage save failed; check server logs.</p>';
    }
    return text;
}

function updateBotCount()
{
    var count = document.getElementById('botTable').children.length;
    document.getElementById('botCount').textContent = count + (count === 1 ? ' bot' : ' bots');
    document.getElementById('emptyBots').hidden = count > 0;
}

function botButton(label, action, objectId, className)
{
    var button = document.createElement('button');
    button.type = 'button';
    button.textContent = label;
    button.className = className || '';
    button.onclick = function() { action(objectId); };
    return button;
}

function addBot(json)
{
    var objectId = json.id;
    var botTag = document.getElementById('bot_' + objectId);
    if (botTag == null)
    {
        botTag = document.createElement('article');
        botTag.id = 'bot_' + objectId;
        botTag.className = 'bot-card';
        botTag.setAttribute('aria-labelledby', 'nickname_' + objectId);
        var header = document.createElement('div');
        header.className = 'bot-header';
        var avatar = document.createElement('img');
        avatar.id = 'avatar_img_' + objectId;
        avatar.className = 'bot-avatar';
        avatar.alt = '';
        var identity = document.createElement('div');
        identity.className = 'bot-identity';
        var nickname = document.createElement('h2');
        nickname.id = 'nickname_' + objectId;
        var model = document.createElement('p');
        model.id = 'model_' + objectId;
        model.className = 'bot-meta';
        var server = document.createElement('p');
        server.id = 'server_' + objectId;
        server.className = 'bot-meta';
        var status = document.createElement('span');
        status.id = 'status_' + objectId;
        status.className = 'bot-status';
        identity.append(nickname, model, server, status);
        header.append(avatar, identity);
        botTag.appendChild(header);
        var statsTag = document.createElement('div');
        statsTag.id = 'stats_' + objectId;
        botTag.appendChild(statsTag);
        var actionsTag = document.createElement('div');
        actionsTag.className = 'bot-actions';
        actionsTag.append(
            botButton('Edit Model/Prompt', editModelPromptWindow, objectId),
            botButton('Notes', notesWindow, objectId),
            botButton('Channels', channelsWindow, objectId),
            botButton('Tools', toolsWindow, objectId));
        botTag.appendChild(actionsTag);
        var lifecycle = document.createElement('div');
        lifecycle.className = 'bot-lifecycle';
        var toggle = botButton('Disable', toggleBot, objectId);
        toggle.id = 'toggle_' + objectId;
        lifecycle.append(toggle, botButton('Delete', deleteBot, objectId, 'danger'));
        botTag.appendChild(lifecycle);
        document.getElementById('botTable').appendChild(botTag);
    }
    updateBot(json);
    updateBotCount();
}

function updateBot(json)
{
    var objectId = json.id;
    if (document.getElementById('bot_' + objectId) == null)
        return;
    document.getElementById('nickname_' + objectId).textContent = json.stats.nickname;
    document.getElementById('model_' + objectId).textContent = json.stats.model;
    document.getElementById('server_' + objectId).textContent = json.stats.ircServer || objectId;
    var connected = json.stats.enabled !== false && json.stats.status === 'CONNECTED';
    var status = document.getElementById('status_' + objectId);
    status.textContent = json.stats.enabled === false ? 'Disabled' : connected ? 'Connected' : json.stats.status === 'ERROR' ? 'Connection error' : 'Disconnected';
    status.dataset.state = connected ? 'connected' : json.stats.status === 'ERROR' && json.stats.enabled !== false ? 'error' : 'disconnected';
    var statsTag = document.getElementById('stats_' + objectId);
    var details = statsTag.querySelector('details');
    var detailsOpen = details && details.open;
    statsTag.innerHTML = renderBotStats(json.stats);
    statsTag.querySelector('details').open = detailsOpen;
    updateToggleButton(objectId, json.stats.enabled);
    document.getElementById('avatar_img_' + objectId).src = (connected ? 'CONNECTED' : json.stats.status === 'ERROR' ? 'ERROR' : 'DISCONNECTED') + '.svg';
}

function setConnectionState(text, state)
{
    var indicator = document.getElementById('connectionState');
    indicator.textContent = text;
    indicator.dataset.state = state;
}

function updateTotalCost(usage)
{
    var total = document.getElementById('totalCostEstimate');
    var incomplete = usage.unpricedTokensSpent > 0 || usage.untrackedRequests > 0 || !!usage.trackingError;
    total.textContent = 'Total Cost Estimate: $' + Number(usage.costEstimate).toFixed(6) + ' USD' + (incomplete ? ' (incomplete)' : '');
    total.title = 'Historical estimate across all bots, including removed bots. Not a billing total.'
        + (incomplete ? ' Some usage is unpriced, untracked, or not persisted.' : '');
    total.hidden = !authenticated;
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
            setConnectionState('Ready to sign in', 'connected');
            if (document.getElementById('login').style.display == 'none')
            {
                doAuth();
            }
        };
        
        connection.onerror = function (error) {
            setConnectionState('Connection unavailable', 'disconnected');
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
                if (action == 'usageTotals')
                {
                    updateTotalCost(jsonObject.usage);
                } else if (action == 'botRemoved')
                {
                    removeBot(jsonObject.id);
                } else if (action == 'botAdded') {
                    addBot(jsonObject);
                } else if (action == 'botStats') {
                    updateBot(jsonObject);
                } else if (action == 'authOk') {
                    authenticated = true;
                    document.getElementById('totalCostEstimate').textContent = 'Total Cost Estimate: loading...';
                    document.getElementById('totalCostEstimate').hidden = false;
                    document.getElementById('botTable').textContent = '';
                    updateBotCount();
                    setConnectionState('Live updates', 'connected');
                    document.getElementById('errorMsg').textContent = '';
                    document.getElementById('login').style.display = 'none';
                    document.getElementById('console').style.display = 'block';
                    document.getElementById('launchBotButton').style.display = 'inline-block';
                    document.getElementById('masterConfigButton').style.display = 'inline-block';
                } else if (action == 'authFail') {
                    authenticated = false;
                    document.getElementById('totalCostEstimate').hidden = true;
                    closeEditor(null, true);
                    document.getElementById('login').style.display = 'block';
                    document.getElementById('console').style.display = 'none';
                    document.getElementById('launchBotButton').style.display = 'none';
                    document.getElementById('masterConfigButton').style.display = 'none';
                    document.getElementById('errorMsg').textContent = jsonObject.error;
                    setConnectionState('Sign in required', 'connected');
                } else if (action == 'launch' && jsonObject.error) {
                    alert("Unable to launch bot: " + jsonObject.error);
                }
            }
        };
        
        connection.onclose = function () {
          setConnectionState('Reconnecting...', 'disconnected');
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

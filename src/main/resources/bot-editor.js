var ready = false;
var busy = false;
var savedState = '';
var launching = !getParameterByName('id');
var manualModel = false;
var booleanFields = ['acceptInvites', 'greet', 'privateMessages', 'greetPublic', 'secure', 'gptEnabled', 'enabled'];
var textFields = ['server', 'nickname', 'user', 'realName', 'operatorName', 'definitionServer', 'modelsApiUrl', 'responsesApiUrl'];
var numberFields = ['port', 'contextDepth', 'followUpSeconds', 'replyCooldownSeconds', 'openQuestionSeconds', 'maxToolRounds'];

function listValues(id)
{
    var separator = id === 'aliases' ? /[\r\n,]+/ : /[\s,]+/;
    return Array.from(new Set(document.getElementById(id).value.split(separator).map(function(value) { return value.trim(); }).filter(Boolean)));
}

function editorState()
{
    var state = {
        model: document.getElementById(manualModel ? 'manualModel' : 'model').value,
        preamble: document.getElementById('systemPreamble').value,
        gptTools: Array.from(document.querySelectorAll('#builtInTools input:checked'), function(input) { return input.value; })
    };
    booleanFields.forEach(function(id) { state[id] = document.getElementById(id).checked; });
    textFields.forEach(function(id) { state[id] = document.getElementById(id).value.trim(); });
    numberFields.forEach(function(id) { state[id] = Number(document.getElementById(id).value); });
    ['ignore', 'aliases', 'botOps'].forEach(function(id) { state[id] = listValues(id); });
    ['password', 'operatorPassword'].forEach(function(id) { state[id] = document.getElementById(id).value; });
    ['clearPassword', 'clearOperatorPassword', 'reconnect'].forEach(function(id) { state[id] = document.getElementById(id).checked; });
    if (launching)
        state.botId = document.getElementById('botId').value.trim();
    return state;
}

function setBusy(value)
{
    busy = value;
    document.querySelectorAll('input, select, textarea, button').forEach(function(element) { element.disabled = busy || !ready; });
}

function selectTools(enabled)
{
    document.querySelectorAll('#builtInTools input').forEach(function(input) { input.checked = enabled; });
}

function editorCanClose()
{
    if (busy)
        document.getElementById('status').textContent = 'Please wait for saving to finish.';
    return !busy && (!ready || JSON.stringify(editorState()) === savedState || confirm('Discard unsaved bot settings?'));
}

function save()
{
    if (busy || !ready)
        return;
    var state = editorState();
    if (!state.model.trim() || !state.server || !state.nickname || (launching && !state.botId))
    {
        document.getElementById('status').textContent = 'Enter a bot identifier (when launching), server, nickname, and model.';
        return;
    }
    var invalid = numberFields.find(function(id) {
        var input = document.getElementById(id);
        return input.value === '' || !Number.isInteger(state[id]) || !input.checkValidity();
    });
    if (invalid)
    {
        document.getElementById('status').textContent = 'Enter a valid value for ' + invalid + '.';
        return;
    }
    var body = state;
    var url = '/ircgptbot/api/bot/' + encodeURIComponent(getParameterByName('id')) + '/settings/';
    if (launching)
    {
        var options = Object.assign({}, state, { systemPreamble: state.preamble });
        delete options.botId;
        delete options.preamble;
        delete options.reconnect;
        body = { id: state.botId, botOptions: options, editor: true };
        url = '/ircgptbot/api/bots/add/';
    }
    setBusy(true);
    document.getElementById('status').textContent = launching ? 'Launching...' : 'Saving...';
    $.ajax({
        url: url, type: 'POST', data: JSON.stringify(body),
        contentType: 'application/json; charset=utf-8', dataType: 'json', timeout: 15000
    }).done(function(json) {
        if (json.error || !json.bot)
        {
            document.getElementById('status').textContent = json.error || 'Unable to save bot settings.';
            return;
        }
        var fieldsMatch = launching || (json.options && booleanFields.concat(numberFields, ['gptTools', 'ignore', 'aliases', 'botOps'])
            .every(function(field) { return JSON.stringify(json.options[field]) === JSON.stringify(state[field]); }));
        if (!launching && (!fieldsMatch || json.model !== state.model || json.preamble !== state.preamble))
        {
            document.getElementById('status').textContent = 'Unable to confirm saved settings.';
            return;
        }
        ['password', 'operatorPassword'].forEach(function(id) { document.getElementById(id).value = ''; });
        savedState = JSON.stringify(editorState());
        busy = false;
        closeEditor();
    }).fail(function() {
        document.getElementById('status').textContent = 'Unable to save. Check the bot list before retrying a launch.';
    }).always(function() { setBusy(false); });
}

function populate(json)
{
    if (json.error || !Array.isArray(json.models) || !json.options || !Array.isArray(json.builtInTools)
        || (!launching && (!json.bot || typeof json.preamble !== 'string' || !json.model)))
    {
        document.getElementById('status').textContent = json.error || 'Unable to load bot settings. Reopen to retry.';
        return;
    }
    document.getElementById('botTitle').textContent = launching ? 'Launch Bot' : json.bot.id + ' - Edit Bot';
    document.getElementById('launchIdentity').hidden = !launching;
    document.getElementById('reconnectOption').hidden = launching;
    document.getElementById('systemPreamble').value = launching ? '' : json.preamble;
    booleanFields.forEach(function(id) { document.getElementById(id).checked = json.options[id]; });
    textFields.concat(numberFields).forEach(function(id) { document.getElementById(id).value = json.options[id]; });
    ['ignore', 'aliases', 'botOps'].forEach(function(id) { document.getElementById(id).value = json.options[id].join('\n'); });
    ['password', 'operatorPassword'].forEach(function(id) {
        var has = 'has' + id.charAt(0).toUpperCase() + id.substring(1);
        document.getElementById(id + 'Status').textContent = json.options[has] ? 'A password is saved. Blank keeps it.' : 'No password saved.';
    });
    var current = launching ? '' : json.model;
    var models = json.models.slice();
    if (current && models.indexOf(current) === -1)
        models.push(current);
    var select = document.getElementById('model');
    select.textContent = '';
    models.forEach(function(model) {
        var option = document.createElement('option');
        option.value = model;
        option.textContent = model;
        option.selected = model === current;
        select.appendChild(option);
    });
    manualModel = launching && models.length === 0;
    document.getElementById('manualModelField').hidden = !manualModel;
    var tools = document.getElementById('builtInTools');
    tools.textContent = '';
    json.builtInTools.forEach(function(name) {
        var label = document.createElement('label');
        label.className = 'check-field';
        var input = document.createElement('input');
        input.type = 'checkbox';
        input.value = name;
        input.checked = json.options.gptTools.indexOf(name) !== -1;
        label.appendChild(input);
        label.appendChild(document.createTextNode(name));
        tools.appendChild(label);
    });
    ready = true;
    savedState = JSON.stringify(editorState());
    setBusy(false);
    document.getElementById('status').textContent = json.models.length ? ''
        : launching ? 'No models returned. Enter an explicit model ID or check your API key and modelsApiUrl.'
        : 'No models returned. Keeping the current model; check your API key and modelsApiUrl.';
}

function loaded()
{
    $('#botFields').load('bot-fields.html', function(response, status) {
        if (status === 'error')
        {
            document.getElementById('status').textContent = 'Unable to load the settings form. Reopen to retry.';
            return;
        }
        $.ajax({
            url: launching ? '/ircgptbot/api/models/' : '/ircgptbot/api/bot/' + encodeURIComponent(getParameterByName('id')) + '/models/',
            dataType: 'json', cache: false, timeout: 30000
        }).done(populate).fail(function() {
            document.getElementById('status').textContent = 'Unable to load models and settings. Reopen to retry.';
        });
    });
    window.onbeforeunload = function() {
        if (busy || (ready && JSON.stringify(editorState()) !== savedState))
            return 'You have unsaved bot settings.';
    };
}

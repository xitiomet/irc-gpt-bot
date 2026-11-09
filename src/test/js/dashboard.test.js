const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

function dashboard()
{
    const elements = new Map();
    function element(tag)
    {
        return {
            tag, children: [], style: {}, dataset: {}, open: false,
            set id(value) { this._id = value; elements.set(value, this); },
            get id() { return this._id; },
            setAttribute() {},
            append(...children) { this.children.push(...children); },
            appendChild(child) { this.append(child); },
            removeChild(child) { this.children.splice(this.children.indexOf(child), 1); },
            set textContent(value) {
                this.text = value;
                for (const child of this.children)
                    unregister(child);
                this.children = [];
            },
            get textContent() { return this.text; },
            set innerHTML(value) {
                this.html = value;
                this.children = value.includes('<details') ? [element('details')] : [];
            },
            querySelector(selector) {
                assert.equal(selector, 'details');
                for (const child of this.children)
                {
                    if (child.tag === 'details')
                        return child;
                    const match = child.querySelector(selector);
                    if (match)
                        return match;
                }
                return null;
            }
        };
    }
    function unregister(node)
    {
        if (node.id)
            elements.delete(node.id);
        node.children.forEach(unregister);
    }
    for (const id of ['botTable', 'botCount', 'emptyBots', 'connectionState', 'totalCostEstimate',
        'login', 'console', 'launchBotButton', 'masterConfigButton', 'importBotButton', 'importBotFile', 'transferStatus', 'errorMsg', 'password'])
    {
        const node = element('div');
        node.id = id;
    }
    elements.get('password').value = 'test-password';
    const sockets = [];
    class Socket
    {
        static CONNECTING = 0;
        static OPEN = 1;
        constructor(url) { this.url = url; this.readyState = 0; this.sent = []; sockets.push(this); }
        send(message) { this.sent.push(JSON.parse(message)); }
        open() { this.readyState = 1; this.onopen(); }
        message(message) { this.onmessage({ data: JSON.stringify(message) }); }
        close() { this.readyState = 3; this.onclose({ code: 1006, reason: 'test disconnect' }); }
    }
    const intervals = new Map();
    const timeouts = new Map();
    let timerId = 0;
    const context = {
        location: { hostname: 'localhost', protocol: 'http:', port: '6553' },
        window: {}, console: { log() {} }, WebSocket: Socket,
        document: { getElementById: id => elements.get(id), createElement: element },
        setInterval(fn, delay) { intervals.set(++timerId, { fn, delay }); return timerId; },
        clearInterval(id) { intervals.delete(id); },
        setTimeout(fn, delay) { timeouts.set(++timerId, { fn, delay }); return timerId; },
        clearTimeout(id) { timeouts.delete(id); }
    };
    vm.createContext(context);
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../../main/resources/index.js'), 'utf8'), context);
    context.setupWebsocket();
    sockets[0].open();
    return { context, elements, sockets, intervals, timeouts };
}

function bot(id)
{
    return { action: 'botAdded', id, stats: {
        nickname: id, model: 'test-model', status: 'CONNECTED', usage: { tokensSpent: 12 }
    } };
}

test('expanded and collapsed details survive updates and reconnect snapshots independently', () => {
    const { context, elements, sockets, timeouts } = dashboard();
    const first = sockets[0];
    first.message({ action: 'authOk' });
    first.message(bot('expanded'));
    first.message(bot('collapsed'));
    elements.get('stats_expanded').querySelector('details').open = true;
    first.message({ ...bot('expanded'), action: 'botStats' });
    assert.equal(elements.get('stats_expanded').querySelector('details').open, true);
    first.close();
    assert.equal(timeouts.size, 1);
    [...timeouts.values()][0].fn();
    const second = sockets[1];
    second.open();
    assert.equal(second.sent[0].command, 'auth');
    second.message({ action: 'authOk' });
    assert.equal(elements.get('botTable').children.length, 0);
    second.message(bot('expanded'));
    second.message(bot('collapsed'));
    assert.equal(elements.get('stats_expanded').querySelector('details').open, true);
    assert.equal(elements.get('stats_collapsed').querySelector('details').open, false);
    elements.get('stats_expanded').querySelector('details').open = false;
    second.message({ ...bot('expanded'), action: 'botStats' });
    assert.equal(elements.get('stats_expanded').querySelector('details').open, false);
    second.message({ action: 'botRemoved', id: 'expanded' });
    assert.equal(context.activityDetailsState.has('expanded'), false);
});

test('snapshot removes bots deleted while offline and retains state across interrupted snapshots', () => {
    const { elements, sockets } = dashboard();
    const socket = sockets[0];
    socket.message({ action: 'authOk' });
    socket.message(bot('kept'));
    socket.message(bot('removed-offline'));
    elements.get('stats_kept').querySelector('details').open = true;
    socket.message({ action: 'authOk' });
    socket.message({ action: 'authOk' });
    socket.message(bot('kept'));
    assert.equal(elements.get('stats_kept').querySelector('details').open, true);
    assert.equal(elements.get('botTable').children.length, 1);
    assert.equal(elements.get('botCount').textContent, '1 bot');
});

test('one authenticated heartbeat runs every 20 seconds and stops on disconnect', () => {
    const { context, sockets, intervals, timeouts } = dashboard();
    const socket = sockets[0];
    assert.equal(intervals.size, 0);
    socket.message({ action: 'authOk' });
    socket.message({ action: 'authOk' });
    assert.equal(intervals.size, 1);
    const heartbeat = [...intervals.values()][0];
    assert.equal(heartbeat.delay, 20000);
    heartbeat.fn();
    assert.deepEqual(socket.sent.at(-1), { command: 'ping' });
    socket.message({ action: 'pong' });
    context.setupWebsocket();
    assert.equal(sockets.length, 1);
    socket.close();
    assert.equal(intervals.size, 0);
    assert.equal(timeouts.size, 1);
    const sent = socket.sent.length;
    heartbeat.fn();
    assert.equal(socket.sent.length, sent);
});

test('failed authentication stops heartbeat and clears remembered details state', () => {
    const { context, sockets, intervals, elements } = dashboard();
    const socket = sockets[0];
    socket.message({ action: 'authOk' });
    context.activityDetailsState.set('bot', true);
    elements.set('editorFrame', { contentWindow: {} });
    elements.set('editorDialog', { close() {} });
    socket.message({ action: 'authFail', error: 'Invalid Password!' });
    assert.equal(intervals.size, 0);
    assert.equal(context.activityDetailsState.size, 0);
    assert.equal(context.authenticated, false);
    assert.equal(elements.get('errorMsg').textContent, 'Invalid Password!');
});

test('import button is left of launch and export button is next to delete', () => {
    const html = fs.readFileSync(path.join(__dirname, '../../main/resources/index.html'), 'utf8');
    assert(html.indexOf('id="importBotButton"') < html.indexOf('id="launchBotButton"'));
    assert(html.includes('accept=".json,application/json"'));
    const { elements, sockets } = dashboard();
    sockets[0].message({ action: 'authOk' });
    assert.equal(elements.get('importBotButton').style.display, 'inline-block');
    sockets[0].message(bot('test'));
    const lifecycle = elements.get('bot_test').children.at(-1);
    assert.deepEqual(Array.from(lifecycle.children, child => child.textContent), ['Disable', 'Export Bot', 'Delete']);
});

test('export downloads raw configuration as JSON using header authentication and revokes blob URL', async () => {
    const { context, elements, timeouts } = dashboard();
    const config = { enabled: false, password: 'irc-secret', channels: { '#private': { key: 'secret' } } };
    const requests = [];
    context.authenticated = true;
    context.confirm = () => true;
    context.fetch = async (url, options) => {
        requests.push({ url, options });
        return { ok: true, json: async () => config };
    };
    let downloadBlob;
    let revoked;
    let clicks = 0;
    let removed = false;
    context.Blob = Blob;
    context.URL = {
        createObjectURL(blob) { downloadBlob = blob; return 'blob:test'; },
        revokeObjectURL(url) { revoked = url; }
    };
    const link = { click() { clicks++; }, remove() { removed = true; } };
    context.document.createElement = tag => { assert.equal(tag, 'a'); return link; };
    context.document.body = { appendChild(child) { assert.equal(child, link); } };
    await context.exportBot('bot_[test]');
    assert.equal(requests[0].url, '/ircgptbot/api/bot/bot_%5Btest%5D/export/');
    assert.equal(requests[0].options.headers['X-API-Password'], 'test-password');
    assert(!requests[0].url.includes('password'));
    assert.deepEqual(JSON.parse(await downloadBlob.text()), config);
    assert.equal(downloadBlob.type, 'application/json');
    assert.equal(link.download, 'bot_[test].json');
    assert.equal(clicks, 1);
    assert(removed);
    [...timeouts.values()][0].fn();
    assert.equal(revoked, 'blob:test');
    assert(elements.get('transferStatus').textContent.includes('Keep this file private'));
});

test('import reads BOM JSON, asks for ID, posts complete config and updates its tile', async () => {
    const { context, elements } = dashboard();
    context.authenticated = true;
    const config = { enabled: false, server: 'irc.example', channels: { '#private': { key: 'secret' } } };
    let requested;
    context.prompt = (message, value) => { assert.equal(value, 'mybot'); return 'new-bot'; };
    context.confirm = () => true;
    context.fetch = async (url, options) => {
        requested = { url, options };
        assert.equal(elements.get('importBotButton').disabled, true);
        return { ok: true, json: async () => ({ id: 'new-bot', bot: bot('new-bot').stats }) };
    };
    await context.importBotFile({ name: 'mybot.json', text: async () => '\uFEFF' + JSON.stringify(config) });
    assert.equal(requested.url, '/ircgptbot/api/bots/import/');
    assert.deepEqual(JSON.parse(requested.options.body), { id: 'new-bot', botOptions: config });
    assert.equal(requested.options.headers['X-API-Password'], 'test-password');
    assert.equal(elements.get('botCount').textContent, '1 bot');
    assert.equal(elements.get('transferStatus').textContent, 'Imported new-bot.');
    assert.equal(elements.get('importBotButton').disabled, false);
    assert.equal(context.importingBot, false);
});

test('invalid files, duplicate imports and network export failures show explicit errors', async () => {
    const { context, elements } = dashboard();
    context.authenticated = true;
    context.prompt = () => 'duplicate';
    context.confirm = () => true;
    let requests = 0;
    context.fetch = async () => {
        requests++;
        return { ok: true, json: async () => ({ error: 'Bot already exists: duplicate' }) };
    };
    for (const json of ['{broken', '[]', 'null', '42'])
    {
        await context.importBotFile({ name: 'bad.json', text: async () => json });
        assert.equal(elements.get('transferStatus').className, 'error-message');
        assert.equal(elements.get('importBotButton').disabled, false);
    }
    assert.equal(requests, 0);
    await context.importBotFile({ name: 'duplicate.json', text: async () => '{"enabled":false}' });
    assert.equal(requests, 1);
    assert(elements.get('transferStatus').textContent.includes('Bot already exists'));
    assert.equal(elements.get('botTable').children.length, 0);
    context.fetch = async () => { throw new Error('Network unavailable'); };
    await context.exportBot('bot');
    assert(elements.get('transferStatus').textContent.includes('Network unavailable'));
});

test('file picker supports reselecting files; cancelled or unauthenticated transfers do not issue requests', async () => {
    const { context, elements } = dashboard();
    let clicks = 0;
    let requests = 0;
    const input = elements.get('importBotFile');
    input.click = () => { clicks++; };
    input.value = 'old.json';
    context.fetch = async () => { requests++; };
    context.importBotWindow();
    assert.equal(clicks, 0);
    context.authenticated = true;
    context.importBotWindow();
    assert.equal(input.value, '');
    assert.equal(clicks, 1);
    context.prompt = () => null;
    await context.importBotFile({ name: 'cancelled.json', text: async () => '{}' });
    assert.equal(requests, 0);
    assert.equal(elements.get('importBotButton').disabled, false);
    context.authenticated = false;
    await context.importBotFile({ name: 'test.json', text: async () => { throw new Error('Should not read'); } });
    await context.exportBot('bot');
    assert.equal(requests, 0);
});

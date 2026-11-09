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
        'login', 'console', 'launchBotButton', 'masterConfigButton', 'errorMsg', 'password'])
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

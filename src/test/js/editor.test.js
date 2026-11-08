const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const resources = path.join(__dirname, '../../main/resources');
const source = name => fs.readFileSync(path.join(resources, name), 'utf8');

function dashboard()
{
    const editor = {};
    const elements = {
        editorDialog: { open: false, showModal() { this.open = true; }, close() { this.open = false; } },
        editorFrame: { contentWindow: editor },
        editorTitle: {},
        password: { value: 'test-password' }
    };
    const context = {
        location: { hostname: 'localhost', protocol: 'http:', port: '6553' },
        window: {},
        document: { getElementById: id => elements[id] }
    };
    vm.createContext(context);
    vm.runInContext(source('index.js'), context);
    return { context, elements, editor };
}

test('all dashboard actions open modals with bot ID only', () => {
    const { context, elements } = dashboard();
    context.authenticated = true;
    for (const [action, page] of [
        ['editModelPromptWindow', 'preamble.html'], ['channelsWindow', 'channels.html'],
        ['toolsWindow', 'tools.html'], ['notesWindow', 'notes.html']
    ])
    {
        context[action]('bot with spaces');
        assert.equal(elements.editorFrame.src, page + '?id=bot%20with%20spaces');
        assert.equal(elements.editorDialog.open, true);
    }
    context.launchBotWindow();
    assert.equal(elements.editorFrame.src, 'launch.html');
    context.masterConfigWindow();
    assert.equal(elements.editorFrame.src, 'master.html');
    assert.equal(elements.editorTitle.textContent, 'Master Config');
    assert(source('index.html').indexOf('id="masterConfigButton"') < source('index.html').indexOf('id="launchBotButton"'));
    assert(!source('index.js').includes('window.open'));
});

test('only the active authenticated editor can obtain a password', () => {
    const { context, elements, editor } = dashboard();
    assert.throws(() => context.getEditorPassword(editor));
    context.authenticated = true;
    context.toolsWindow('bot');
    assert.equal(context.getEditorPassword(editor), 'test-password');
    assert.throws(() => context.getEditorPassword({}));
    context.closeEditor({});
    assert.equal(elements.editorDialog.open, true);
    context.closeEditor(editor);
    assert.equal(elements.editorDialog.open, false);
    assert.throws(() => context.getEditorPassword(editor));
});

test('cancelled discard keeps editor open; accepted discard unloads without a second prompt', () => {
    const { context, elements, editor } = dashboard();
    context.authenticated = true;
    context.notesWindow('bot');
    const beforeUnload = () => 'Unsaved changes';
    editor.onbeforeunload = beforeUnload;
    editor.editorCanClose = () => false;
    context.closeEditor();
    assert.equal(elements.editorDialog.open, true);
    assert.equal(editor.onbeforeunload, beforeUnload);
    context.toolsWindow('other');
    assert.equal(elements.editorFrame.src, 'notes.html?id=bot');
    editor.editorCanClose = () => true;
    context.closeEditor();
    assert.equal(elements.editorDialog.open, false);
    assert.equal(editor.onbeforeunload, null);
});

test('notes exposes its discard hook at global scope', () => {
    const elements = { editor: { style: { display: 'flex' } }, notePrefix: { value: '' }, noteName: { value: 'note.txt' }, noteContent: { value: 'changed' } };
    const context = {
        URLSearchParams,
        window: { location: { search: '?id=bot' } },
        document: { getElementById: id => elements[id] },
        confirm: () => false
    };
    vm.createContext(context);
    const script = source('notes.html').match(/<script type="text\/javascript">([\s\S]*?)<\/script>/)[1];
    vm.runInContext(script, context);
    assert.equal(typeof context.editorCanClose, 'function');
    assert.equal(context.editorCanClose(), false);
    context.confirm = () => true;
    assert.equal(context.editorCanClose(), true);
});

function notesEditor()
{
    const elements = {};
    for (const id of ['editor', 'notePrefix', 'prefixHelp', 'noteName', 'noteContent', 'noteList',
        'empty', 'deleteNote', 'saveNote', 'newNote', 'refresh', 'status'])
        elements[id] = { value: '', style: {}, disabled: false, textContent: '', focus() {} };
    const context = {
        URLSearchParams, window: { location: { search: '?id=bot' } }, confirm: () => true,
        document: {
            getElementById: id => elements[id],
            querySelectorAll: selector => {
                assert.equal(selector, 'button, input, textarea, select');
                return Object.values(elements);
            }
        }
    };
    vm.createContext(context);
    vm.runInContext(source('notes.html').match(/<script type="text\/javascript">([\s\S]*?)<\/script>/)[1], context);
    context.renderList = () => {};
    return { context, elements };
}

test('notes split existing prefixes, preserve casing and track selector changes as dirty', () => {
    const { context, elements } = notesEditor();
    for (const [name, prefix, remainder] of [
        ['todo.txt', '', 'todo.txt'], ['nickname.charlie.txt', 'nickname.', 'charlie.txt'],
        ['token.flumbar', 'token.', 'flumbar'], ['TOKEN.flumbar.notes.md', 'token.', 'flumbar.notes.md']
    ])
    {
        context.showEditor(name, 'Saved content');
        assert.equal(elements.notePrefix.value, prefix);
        assert.equal(elements.noteName.value, remainder);
        assert.equal(context.noteFileName(), name);
        assert.equal(context.isDirty(), false);
        elements.notePrefix.value = prefix === '' ? 'nickname.' : '';
        assert.equal(context.isDirty(), true);
        context.confirm = () => false;
        assert.equal(context.editorCanClose(), false);
    }
    context.showEditor(null, '');
    assert.equal(elements.notePrefix.value, '');
    assert.equal(context.isDirty(), false);
    context.setBusy(true);
    assert.equal(elements.notePrefix.disabled, true);
    context.setBusy(false);
    assert.equal(elements.notePrefix.disabled, false);
});

test('notes save composed names and preserve overwrite, copy and delete behavior', () => {
    const { context, elements } = notesEditor();
    const writes = [];
    context.postNotes = (body, done) => { writes.push(body); done(); };
    context.showEditor('todo.txt', 'Content');
    elements.notePrefix.value = 'token.';
    elements.noteName.value = ' flumbar.txt ';
    context.saveNote();
    assert.equal(writes[0].name, 'token.flumbar.txt');
    assert.equal(writes[0].content, 'Content');
    assert.equal(context.currentName, 'token.flumbar.txt');
    assert.equal(context.isDirty(), false);
    context.deleteNote();
    assert.equal(writes[1].action, 'delete');
    assert.equal(writes[1].name, 'token.flumbar.txt');
    context.showEditor(null, '');
    elements.notePrefix.value = 'nickname.';
    elements.noteName.value = '';
    context.saveNote();
    assert.equal(writes.length, 2);
    elements.noteName.value = '../escape';
    context.saveNote();
    assert.equal(writes.length, 2);
    elements.noteName.value = 'charlie.txt';
    context.notes = [{ name: 'nickname.charlie.txt' }];
    context.confirm = () => false;
    context.saveNote();
    assert.equal(writes.length, 2);
    context.confirm = () => true;
    context.saveNote();
    assert.equal(writes[2].name, 'nickname.charlie.txt');
});

test('editor authenticates only same-origin API requests through headers', () => {
    let prefilter;
    const headers = {};
    const context = {
        URL, location: { href: 'http://localhost/tools.html?id=bot', origin: 'http://localhost' },
        window: { parent: { getEditorPassword: () => 'test-password' } },
        document: { addEventListener() {} },
        $: { ajaxPrefilter: fn => { prefilter = fn; } }
    };
    vm.createContext(context);
    vm.runInContext(source('editor.js'), context);
    const xhr = { setRequestHeader: (name, value) => { headers[name] = value; } };
    prefilter({ url: '/ircgptbot/api/bot/bot/tools/' }, {}, xhr);
    assert.equal(headers['X-API-Password'], 'test-password');
    delete headers['X-API-Password'];
    prefilter({ url: 'https://example.com/ircgptbot/api/' }, {}, xhr);
    prefilter({ url: '/jquery.min.js' }, {}, xhr);
    assert.deepEqual(headers, {});
    for (const name of ['channels', 'tools', 'notes', 'preamble', 'model', 'launch', 'master'])
    {
        assert(source(name + '.html').includes('src="editor.js"'));
        assert(source(name + '.html').includes('href="theme.css"'));
        assert(!source(name + '.html').includes('apiPassword'));
    }
});

test('master editor keeps replacement keys private and pricing apply gated behind a preview', () => {
    const elements = {};
    for (const id of ['status', 'applyPricing', 'openAiKey', 'keyStatus', 'pricingStatus',
        'pricingSource', 'priceChanges', 'previewSummary', 'skippedPrices', 'pricingPreview'])
        elements[id] = { value: '', disabled: false, textContent: '', hidden: false, children: [], appendChild(child) { this.children.push(child); } };
    const context = {
        window: {}, confirm: () => false,
        document: {
            getElementById: id => elements[id],
            querySelectorAll: () => Object.values(elements),
            createElement: () => ({ textContent: '', children: [], appendChild(child) { this.children.push(child); } })
        }
    };
    vm.createContext(context);
    vm.runInContext(source('master.html').match(/<script>([\s\S]*?)<\/script>/)[1], context);
    context.setBusy(false);
    assert.equal(elements.applyPricing.disabled, true);
    const requests = [];
    context.request = (body, done) => { requests.push(body); done({ hasOpenAiKey: true }); };
    elements.openAiKey.value = ' test-new-key ';
    assert.equal(context.editorCanClose(), false);
    context.saveKey();
    assert.equal(requests[0].openAiKey, 'test-new-key');
    assert.equal(elements.openAiKey.value, '');
    assert(!elements.keyStatus.textContent.includes('test-new-key'));
    assert.equal(context.editorCanClose(), true);
    context.busy = true;
    assert.equal(context.editorCanClose(), false);
    context.busy = false;
    const preview = { token: 'server-token', skipped: ['unsupported'], changes: [{
        model: 'gpt-new', before: null, after: { inputPerMillion: 1, cachedInputPerMillion: 0.1, outputPerMillion: 5 }
    }] };
    context.showPreview(preview);
    context.setBusy(false);
    assert.equal(elements.applyPricing.disabled, false);
    assert.equal(elements.priceChanges.children[0].children[0].textContent, 'gpt-new');
    assert.equal(elements.priceChanges.children[0].children[1].textContent, '(new) -> $1');
    context.request = (body, done) => {
        requests.push(body);
        done({ pricing: { modelCount: 1, verifiedAt: 'now', source: 'official', updated: 1 } });
    };
    context.applyPrices();
    assert.equal(requests[1].action, 'applyPricing');
    assert.equal(requests[1].token, 'server-token');
    assert.equal(context.previewToken, null);
    assert.equal(elements.pricingPreview.hidden, true);
    assert(elements.status.textContent.includes('Historical cost estimates were preserved'));
});

test('cards retain identity, actions, live statistics, details, and empty state', () => {
    const nodes = {};
    function element(tag)
    {
        const node = {
            tag, children: [], dataset: {}, textContent: '', details: null,
            append(...items) { this.children.push(...items); },
            appendChild(item) { this.children.push(item); },
            removeChild(item) { this.children.splice(this.children.indexOf(item), 1); delete nodes[item.id]; },
            setAttribute() {},
            querySelector(selector) { return selector === 'details' ? this.details : null; }
        };
        Object.defineProperty(node, 'id', { set(value) { this.identifier = value; nodes[value] = this; }, get() { return this.identifier; } });
        Object.defineProperty(node, 'innerHTML', { set(value) { this.html = value; this.details = { open: false }; }, get() { return this.html; } });
        return node;
    }
    nodes.botTable = element('div');
    nodes.botCount = element('span');
    nodes.emptyBots = element('div');
    const context = {
        location: { hostname: 'localhost', protocol: 'http:', port: '6553' }, window: {},
        document: { getElementById: id => nodes[id] || null, createElement: element }
    };
    vm.createContext(context);
    vm.runInContext(source('index.js'), context);
    const data = { id: 'bot_[test]', stats: {
        nickname: '<test>', model: 'gpt-4o-mini', ircServer: 'irc.example.com', enabled: true, status: 'CONNECTED',
        messagesHandled: 12, messagesSeen: 40, errorCount: 2,
        usage: { tokensSpent: 1100, inputTokensSpent: 1000, outputTokensSpent: 100, cachedTokensSpent: 200,
            costEstimate: 0.000195, requests: 1, unpricedTokensSpent: 1, untrackedRequests: 1, trackingError: 'error' }
    } };
    context.addBot(data);
    assert.equal(nodes.botTable.children[0].tag, 'article');
    assert.equal(nodes['nickname_bot_[test]'].textContent, '<test>');
    assert.equal(nodes['status_bot_[test]'].textContent, 'Connected');
    assert.equal(nodes['server_bot_[test]'].textContent, 'irc.example.com');
    assert.equal(nodes.botCount.textContent, '1 bot');
    assert.equal(nodes.emptyBots.hidden, true);
    const stats = nodes['stats_bot_[test]'];
    for (const expected of ['Messages Handled', 'Messages Seen', 'Errors', 'AI Tokens', 'Input / Output', 'Cached Input',
        '$0.000195', 'AI Requests', 'Unpriced Tokens', 'Missing Usage', 'Usage save failed'])
        assert(stats.innerHTML.includes(expected), expected);
    stats.details.open = true;
    data.stats.enabled = false;
    context.updateBot(data);
    assert.equal(stats.details.open, true);
    assert.equal(nodes['status_bot_[test]'].textContent, 'Disabled');
    assert.equal(nodes['toggle_bot_[test]'].textContent, 'Enable');
    context.addBot(data);
    assert.equal(nodes.botTable.children.length, 1);
    const actions = nodes['bot_bot_[test]'].children[2].children;
    assert.deepEqual(Array.from(actions, item => item.textContent), ['Edit Model/Prompt', 'Notes', 'Channels', 'Tools']);
    context.notesWindow = () => {};
    context.removeBot(data.id);
    assert.equal(nodes.botCount.textContent, '0 bots');
    assert.equal(nodes.emptyBots.hidden, false);
});

test('bot settings serialize all tool selections, toggles and nickname lists and guard dirty edits', () => {
    const elements = {};
    for (const id of ['model', 'systemPreamble', 'ignore', 'aliases', 'botOps', 'acceptInvites', 'greet', 'privateMessages', 'status', 'saveButton'])
        elements[id] = { value: '', checked: false, disabled: false };
    const tools = [{ value: 'write_note', checked: true }, { value: 'read_note', checked: false }];
    const context = {
        window: {}, confirm: () => false,
        document: {
            getElementById: id => elements[id],
            querySelectorAll: selector => selector === '#builtInTools input:checked' ? tools.filter(tool => tool.checked)
                : selector === '#builtInTools input' ? tools : [...Object.values(elements), ...tools]
        }
    };
    vm.createContext(context);
    vm.runInContext(source('preamble.html').match(/<script type="text\/javascript">([\s\S]*?)<\/script>/)[1], context);
    elements.model.value = 'gpt-4o-mini';
    elements.systemPreamble.value = 'Prompt';
    elements.ignore.value = 'one, two\none';
    elements.aliases.value = 'helper chat';
    elements.botOps.value = '';
    elements.acceptInvites.checked = true;
    const state = context.editorState();
    assert.deepEqual(Array.from(state.ignore), ['one', 'two']);
    assert.deepEqual(Array.from(state.aliases), ['helper', 'chat']);
    assert.deepEqual(Array.from(state.botOps), []);
    assert.deepEqual(Array.from(state.gptTools), ['write_note']);
    assert.equal(state.acceptInvites, true);
    assert.equal(state.greet, false);
    assert.equal(state.privateMessages, false);
    context.ready = true;
    context.savedState = JSON.stringify(state);
    assert.equal(context.editorCanClose(), true);
    context.selectTools(true);
    assert.deepEqual(Array.from(context.editorState().gptTools), ['write_note', 'read_note']);
    assert.equal(context.editorCanClose(), false);
    context.selectTools(false);
    assert.equal(context.editorState().gptTools.length, 0);
    context.setBusy(true);
    assert.equal(elements.ignore.disabled, true);
    assert.equal(context.editorCanClose(), false);
    context.setBusy(false);
    assert.equal(elements.ignore.disabled, false);
});

test('header shows server-wide historical estimate and flags incomplete usage only when authenticated', () => {
    const {context, elements} = dashboard();
    elements.totalCostEstimate = {};
    context.updateTotalCost({costEstimate: 0.00039, unpricedTokensSpent: 0, untrackedRequests: 0});
    assert.equal(elements.totalCostEstimate.hidden, true);
    context.authenticated = true;
    context.updateTotalCost({costEstimate: 0.00039, unpricedTokensSpent: 0, untrackedRequests: 0});
    assert.equal(elements.totalCostEstimate.textContent, 'Total Cost Estimate: $0.000390 USD');
    assert.equal(elements.totalCostEstimate.hidden, false);
    context.updateTotalCost({costEstimate: 12, unpricedTokensSpent: 10, untrackedRequests: 0});
    assert.equal(elements.totalCostEstimate.textContent, 'Total Cost Estimate: $12.000000 USD (incomplete)');
    context.updateTotalCost({costEstimate: 12, unpricedTokensSpent: 0, untrackedRequests: 1});
    assert(elements.totalCostEstimate.textContent.endsWith('(incomplete)'));
    assert(elements.totalCostEstimate.title.includes('removed bots'));
});

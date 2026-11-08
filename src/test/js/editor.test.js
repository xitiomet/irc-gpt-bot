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
    const elements = { editor: { style: { display: 'flex' } }, noteName: { value: 'note.txt' }, noteContent: { value: 'changed' } };
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
    for (const name of ['channels', 'tools', 'notes', 'preamble', 'model', 'launch'])
    {
        assert(source(name + '.html').includes('src="editor.js"'));
        assert(!source(name + '.html').includes('apiPassword'));
    }
});

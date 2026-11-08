function getParameterByName(name, url)
{
    return new URL(url || location.href).searchParams.get(name);
}

function closeEditor()
{
    if (window.parent !== window)
        window.parent.closeEditor(window);
}

function getEditorPassword()
{
    if (window.parent === window)
        throw new Error('Open this editor from the authenticated dashboard.');
    return window.parent.getEditorPassword(window);
}

$.ajaxPrefilter(function(options, originalOptions, xhr) {
    var url = new URL(options.url, location.href);
    if (url.origin === location.origin && url.pathname.startsWith('/ircgptbot/api/'))
        xhr.setRequestHeader('X-API-Password', getEditorPassword());
});

document.addEventListener('keydown', function(event) {
    if (event.key === 'Escape')
    {
        event.preventDefault();
        closeEditor();
    }
});

document.addEventListener('DOMContentLoaded', function() {
    if (window.parent === window)
    {
        window.loaded = function() {};
        document.body.textContent = 'Open this editor from the authenticated bot dashboard.';
    }
});

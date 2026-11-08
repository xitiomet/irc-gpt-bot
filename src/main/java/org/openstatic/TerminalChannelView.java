package org.openstatic;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.json.JSONObject;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.gui2.Window;

final class TerminalChannelView
{
    final TextBox topic = new TextBox(new TerminalSize(65, 2), TextBox.Style.MULTI_LINE).setReadOnly(true);
    final TextBox messages = new TextBox(new TerminalSize(44, 12), TextBox.Style.MULTI_LINE).setReadOnly(true);
    final TextBox roster = new TextBox(new TerminalSize(20, 12), TextBox.Style.MULTI_LINE).setReadOnly(true);
    final Label status = new Label("Loading...");
    private boolean loaded;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    void update(JSONObject snapshot)
    {
        topic.setText(snapshot.getString("topic"));
        StringBuilder transcript = new StringBuilder();
        for (Object value : snapshot.getJSONArray("messages"))
        {
            JSONObject message = (JSONObject) value;
            transcript.append(TIME.format(Instant.ofEpochMilli(message.getLong("timestamp")))).append(" ")
                .append(message.getString("sender")).append(": ").append(message.getString("body")).append("\n");
        }
        TerminalPosition previous = messages.getRenderer().getViewTopLeft();
        int visibleRows = messages.getSize().getRows() > 0 ? messages.getSize().getRows() : messages.getPreferredSize().getRows();
        boolean atBottom = !loaded || previous.getRow() + visibleRows >= messages.getLineCount();
        if (!messages.getText().equals(transcript.toString()))
        {
            messages.setText(transcript.toString());
            int lastPage = Math.max(0, messages.getLineCount() - visibleRows);
            messages.getRenderer().setViewTopLeft(new TerminalPosition(previous.getColumn(),
                atBottom ? lastPage : Math.min(previous.getRow(), lastPage)));
        }
        StringBuilder names = new StringBuilder();
        for (Object value : snapshot.getJSONArray("roster"))
        {
            JSONObject user = (JSONObject) value;
            names.append(user.getString("prefix")).append(user.getString("nickname")).append("\n");
        }
        roster.setText(names.toString());
        status.setText(snapshot.getBoolean("rosterComplete") ? "Live updates (every 2 seconds)" : "Live updates; roster still loading");
        loaded = true;
    }

    static void show(IRCGPTBot bot, String name)
    {
        TerminalChannelView view = new TerminalChannelView();
        BasicWindow window = new BasicWindow(name + " - Live Channel");
        window.setHints(Arrays.asList(Window.Hint.CENTERED));
        Panel panel = new Panel();
        panel.addComponent(new Label("Topic"));
        panel.addComponent(view.topic);
        Panel columns = new Panel(new LinearLayout(Direction.HORIZONTAL));
        columns.addComponent(view.messages);
        columns.addComponent(view.roster);
        panel.addComponent(columns);
        panel.addComponent(view.status);
        panel.addComponent(new Button("Back", window::close));
        window.setComponent(panel);
        ScheduledExecutorService refresh = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "Terminal channel refresh");
            thread.setDaemon(true);
            return thread;
        });
        IRCGPTBotMain.instance.gui.addWindow(window);
        refresh.scheduleWithFixedDelay(() -> {
            if (!window.isVisible()) return;
            try { view.update(bot.getChannelView(name)); }
            catch (RuntimeException e) {
                if (!view.status.getText().startsWith("Unable")) bot.log(e);
                view.status.setText("Unable to refresh: " + e.getMessage());
            }
        }, 0, 2, TimeUnit.SECONDS);
        try { window.waitUntilClosed(); }
        finally { refresh.shutdownNow(); }
    }
}

package org.openstatic;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONObject;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TerminalTextUtils;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.SimpleTheme;
import com.googlecode.lanterna.gui2.AbstractComponent;
import com.googlecode.lanterna.gui2.AbstractInteractableComponent;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.BorderLayout;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.ComponentRenderer;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.InteractableRenderer;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.gui2.WindowListenerAdapter;
import com.googlecode.lanterna.gui2.dialogs.MessageDialog;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton;
import com.googlecode.lanterna.gui2.dialogs.TextInputDialogBuilder;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;

final class TerminalChannelView
{
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    private static final TextColor BAR_BG = TextColor.ANSI.RED;
    private static final TextColor[] NICK_COLORS = {
        TextColor.ANSI.CYAN_BRIGHT, TextColor.ANSI.GREEN_BRIGHT, TextColor.ANSI.MAGENTA_BRIGHT, TextColor.ANSI.YELLOW_BRIGHT,
        TextColor.ANSI.RED_BRIGHT, TextColor.ANSI.BLUE_BRIGHT, TextColor.ANSI.CYAN, TextColor.ANSI.GREEN, TextColor.ANSI.MAGENTA,
        TextColor.ANSI.YELLOW };

    final Bar topic = new Bar();
    final ChatPane messages = new ChatPane();
    final NickList roster = new NickList();
    final Bar status = new Bar();
    private final String channelName;
    private final String selfNick;

    TerminalChannelView()
    {
        this("", null);
    }

    TerminalChannelView(String channelName, String selfNick)
    {
        this.channelName = channelName;
        this.selfNick = selfNick;
        messages.selfNick = selfNick;
        status.setText("Loading...");
    }

    static TextColor nickColor(String nick)
    {
        return NICK_COLORS[Math.floorMod(nick.toLowerCase().hashCode(), NICK_COLORS.length)];
    }

    static String clean(String text)
    {
        return text == null ? "" : text.replace('\t', ' ').replaceAll("[\\p{Cntrl}&&[^\n]]", "");
    }

    void update(JSONObject snapshot)
    {
        topic.setText(clean(snapshot.getString("topic")).replace('\n', ' '));
        List<ChatPane.Entry> entries = new ArrayList<>();
        for (Object value : snapshot.getJSONArray("messages"))
        {
            JSONObject message = (JSONObject) value;
            entries.add(new ChatPane.Entry(TIME.format(Instant.ofEpochMilli(message.getLong("timestamp"))),
                clean(message.getString("sender")), clean(message.getString("body"))));
        }
        messages.setEntries(entries);
        List<String[]> nicks = new ArrayList<>();
        for (Object value : snapshot.getJSONArray("roster"))
        {
            JSONObject user = (JSONObject) value;
            nicks.add(new String[] { user.getString("prefix"), user.getString("nickname") });
        }
        roster.setNicks(nicks);
        String text = "[" + TIME.format(Instant.now()) + "]" + (selfNick == null ? "" : " [" + selfNick + "]")
            + " [" + snapshot.optString("name", channelName) + "] [" + nicks.size() + " nicks]"
            + (snapshot.getBoolean("rosterComplete") ? "" : " [roster loading]");
        if (snapshot.optBoolean("historyTruncated")) text += " [earlier history omitted]";
        if (messages.getScrollOffset() > 0) text += " [-- more --]";
        if (snapshot.has("historyError")) text = snapshot.getString("historyError");
        status.setText(text);
    }

    static void show(IRCGPTBot bot, String name)
    {
        TerminalChannelView view = new TerminalChannelView(name, bot.getNickname());
        BasicWindow window = new BasicWindow(name);
        window.setHints(Arrays.asList(Window.Hint.FULL_SCREEN, Window.Hint.NO_DECORATIONS));
        window.setTheme(SimpleTheme.makeTheme(true, TextColor.ANSI.WHITE, TextColor.ANSI.BLACK, TextColor.ANSI.WHITE,
            TextColor.ANSI.BLACK, TextColor.ANSI.BLACK, TextColor.ANSI.RED, TextColor.ANSI.BLACK));
        Panel panel = new Panel(new BorderLayout());
        panel.addComponent(view.topic, BorderLayout.Location.TOP);
        Panel columns = new Panel(new BorderLayout());
        columns.addComponent(view.messages, BorderLayout.Location.CENTER);
        columns.addComponent(view.roster, BorderLayout.Location.RIGHT);
        panel.addComponent(columns, BorderLayout.Location.CENTER);
        Panel footer = new Panel();
        footer.addComponent(view.status, LinearLayout.createLayoutData(LinearLayout.Alignment.Fill));
        Panel actions = new Panel(new LinearLayout(Direction.HORIZONTAL));
        actions.addComponent(new Button("Set Operator", () -> {
            if (!bot.botIsOp(name))
            {
                MessageDialog.showMessageDialog(IRCGPTBotMain.instance.gui, "Set Operator",
                    bot.getNickname() + " is not an operator in " + name + ", so it can't give operator status.");
                return;
            }
            String nickname = new TextInputDialogBuilder().setTitle("Enter Nickname").setInitialContent("")
                .setTextBoxSize(new TerminalSize(30, 1)).build().showDialog(IRCGPTBotMain.instance.gui);
            if (nickname != null && !nickname.isEmpty() && !bot.giveOp(name, nickname))
                MessageDialog.showMessageDialog(IRCGPTBotMain.instance.gui, "Set Operator", nickname + " is not in " + name);
        }));
        actions.addComponent(new Button("Leave Channel", () -> {
            if (MessageDialog.showMessageDialog(IRCGPTBotMain.instance.gui, "Leave Channel",
                "Leave " + name + "?", MessageDialogButton.Yes, MessageDialogButton.No) == MessageDialogButton.Yes)
            {
                bot.leaveChannel(name);
                window.close();
            }
        }));
        actions.addComponent(new Button("Back", window::close));
        footer.addComponent(actions);
        panel.addComponent(footer, BorderLayout.Location.BOTTOM);
        window.setComponent(panel);
        window.setFocusedInteractable(view.messages);
        window.addWindowListener(new WindowListenerAdapter() {
            @Override
            public void onUnhandledInput(Window basePane, KeyStroke keyStroke, AtomicBoolean hasBeenHandled)
            {
                if (keyStroke.getKeyType() == KeyType.Escape)
                {
                    hasBeenHandled.set(true);
                    window.close();
                }
            }
        });
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

    /** A single-row, full-width colored bar like irssi's topic and status lines. */
    static final class Bar extends AbstractComponent<Bar>
    {
        private volatile String text = "";

        String getText() { return text; }

        void setText(String text)
        {
            if (!this.text.equals(text)) { this.text = text; invalidate(); }
        }

        @Override
        protected ComponentRenderer<Bar> createDefaultRenderer()
        {
            return new ComponentRenderer<Bar>() {
                @Override
                public TerminalSize getPreferredSize(Bar bar)
                {
                    return new TerminalSize(TerminalTextUtils.getColumnWidth(bar.text) + 1, 1);
                }

                @Override
                public void drawComponent(TextGUIGraphics graphics, Bar bar)
                {
                    graphics.setBackgroundColor(BAR_BG).setForegroundColor(TextColor.ANSI.BLACK);
                    graphics.fill(' ');
                    graphics.putString(0, 0, TerminalTextUtils.fitString(" " + bar.text, graphics.getSize().getColumns()));
                }
            };
        }
    }

    /** Right-hand nick list separated from the chat by a vertical rule. */
    static final class NickList extends AbstractComponent<NickList>
    {
        private volatile List<String[]> nicks = List.of();

        String getText()
        {
            StringBuilder text = new StringBuilder();
            for (String[] nick : nicks) text.append(nick[0]).append(nick[1]).append("\n");
            return text.toString();
        }

        void setNicks(List<String[]> nicks)
        {
            this.nicks = List.copyOf(nicks);
            invalidate();
        }

        @Override
        protected ComponentRenderer<NickList> createDefaultRenderer()
        {
            return new ComponentRenderer<NickList>() {
                @Override
                public TerminalSize getPreferredSize(NickList list)
                {
                    int width = 10;
                    for (String[] nick : list.nicks)
                        width = Math.max(width, TerminalTextUtils.getColumnWidth(nick[0] + nick[1]) + 2);
                    return new TerminalSize(Math.min(width, 22), 1);
                }

                @Override
                public void drawComponent(TextGUIGraphics graphics, NickList list)
                {
                    graphics.setBackgroundColor(TextColor.ANSI.BLACK).fill(' ');
                    int rows = graphics.getSize().getRows();
                    int width = graphics.getSize().getColumns() - 2;
                    graphics.setForegroundColor(TextColor.ANSI.RED);
                    for (int row = 0; row < rows; row++) graphics.setCharacter(0, row, '│');
                    List<String[]> nicks = list.nicks;
                    for (int row = 0; row < rows && row < nicks.size(); row++)
                    {
                        String prefix = nicks.get(row)[0];
                        graphics.setForegroundColor(TextColor.ANSI.WHITE_BRIGHT).enableModifiers(SGR.BOLD);
                        graphics.putString(2, row, prefix.isEmpty() ? " " : prefix.substring(0, 1));
                        graphics.disableModifiers(SGR.BOLD).setForegroundColor(TextColor.ANSI.WHITE);
                        graphics.putString(3, row, TerminalTextUtils.fitString(nicks.get(row)[1], Math.max(0, width - 1)));
                    }
                }
            };
        }
    }

    /** Scrollable, word-wrapped transcript rendered in the irssi "HH:mm <nick> text" style. */
    static final class ChatPane extends AbstractInteractableComponent<ChatPane>
    {
        record Entry(String time, String sender, String body) {}
        private record Row(Entry entry, String text, boolean first, int indent) {}

        private List<Entry> entries = List.of();
        private int scrollOffset;
        private int lastWidth = 80;
        private int lastHeight = 20;
        volatile String selfNick;

        synchronized String getText()
        {
            StringBuilder text = new StringBuilder();
            for (Entry entry : entries)
                text.append(entry.time()).append(" <").append(entry.sender()).append("> ").append(entry.body()).append("\n");
            return text.toString();
        }

        synchronized int getScrollOffset() { return scrollOffset; }

        synchronized void setEntries(List<Entry> entries)
        {
            if (entries.equals(this.entries)) return;
            int before = wrap(this.entries, lastWidth).size();
            this.entries = List.copyOf(entries);
            if (scrollOffset > 0)
                scrollOffset = Math.max(0, scrollOffset + wrap(this.entries, lastWidth).size() - before);
            clampScroll();
            invalidate();
        }

        synchronized void scrollBy(int rows)
        {
            scrollOffset += rows;
            clampScroll();
            invalidate();
        }

        synchronized void scrollToTop()
        {
            scrollOffset = Integer.MAX_VALUE;
            clampScroll();
            invalidate();
        }

        synchronized int firstVisibleRow()
        {
            return Math.max(0, wrap(entries, lastWidth).size() - lastHeight - scrollOffset);
        }

        private void clampScroll()
        {
            scrollOffset = Math.max(0, Math.min(scrollOffset, wrap(entries, lastWidth).size() - lastHeight));
        }

        private static List<Row> wrap(List<Entry> entries, int width)
        {
            List<Row> rows = new ArrayList<>();
            for (Entry entry : entries)
            {
                String prefix = entry.time() + " <" + entry.sender() + "> ";
                int indent = Math.min(TerminalTextUtils.getColumnWidth(prefix), Math.max(1, width / 2));
                boolean first = true;
                for (String paragraph : entry.body().split("\n", -1))
                {
                    for (String line : TerminalTextUtils.getWordWrappedText(Math.max(1, width - indent), paragraph))
                    {
                        rows.add(new Row(entry, line, first, indent));
                        first = false;
                    }
                }
                if (first) rows.add(new Row(entry, "", true, indent));
            }
            return rows;
        }

        @Override
        protected Result handleKeyStroke(KeyStroke keyStroke)
        {
            if (keyStroke instanceof MouseAction mouse)
            {
                if (mouse.getActionType() == MouseActionType.SCROLL_UP) { scrollBy(3); return Result.HANDLED; }
                if (mouse.getActionType() == MouseActionType.SCROLL_DOWN) { scrollBy(-3); return Result.HANDLED; }
                return super.handleKeyStroke(keyStroke);
            }
            int page = Math.max(1, lastHeight - 1);
            switch (keyStroke.getKeyType())
            {
                case ArrowUp: scrollBy(1); return Result.HANDLED;
                case ArrowDown: scrollBy(-1); return Result.HANDLED;
                case PageUp: scrollBy(page); return Result.HANDLED;
                case PageDown: scrollBy(-page); return Result.HANDLED;
                case Home: scrollToTop(); return Result.HANDLED;
                case End: scrollBy(-Integer.MAX_VALUE / 2); return Result.HANDLED;
                default: return super.handleKeyStroke(keyStroke);
            }
        }

        @Override
        protected InteractableRenderer<ChatPane> createDefaultRenderer()
        {
            return new InteractableRenderer<ChatPane>() {
                @Override
                public TerminalPosition getCursorLocation(ChatPane pane) { return null; }

                @Override
                public TerminalSize getPreferredSize(ChatPane pane) { return new TerminalSize(40, 10); }

                @Override
                public void drawComponent(TextGUIGraphics graphics, ChatPane pane)
                {
                    synchronized (pane)
                    {
                        pane.lastWidth = Math.max(1, graphics.getSize().getColumns());
                        pane.lastHeight = Math.max(1, graphics.getSize().getRows());
                        pane.clampScroll();
                        graphics.setBackgroundColor(TextColor.ANSI.BLACK).setForegroundColor(TextColor.ANSI.WHITE).fill(' ');
                        List<Row> rows = wrap(pane.entries, pane.lastWidth);
                        int start = Math.max(0, rows.size() - pane.lastHeight - pane.scrollOffset);
                        for (int y = 0; y < pane.lastHeight && start + y < rows.size(); y++)
                        {
                            Row row = rows.get(start + y);
                            if (row.first())
                            {
                                Entry e = row.entry();
                                boolean self = e.sender().equalsIgnoreCase(pane.selfNick == null ? "" : pane.selfNick);
                                int x = 0;
                                graphics.setForegroundColor(TextColor.ANSI.WHITE);
                                graphics.putString(x, y, e.time()); x += e.time().length() + 1;
                                graphics.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT).putString(x, y, "<"); x++;
                                graphics.setForegroundColor(self ? TextColor.ANSI.WHITE_BRIGHT : nickColor(e.sender()));
                                if (self) graphics.enableModifiers(SGR.BOLD);
                                String nick = TerminalTextUtils.fitString(e.sender(), Math.max(0, row.indent() - x - 2));
                                graphics.putString(x, y, nick); x += TerminalTextUtils.getColumnWidth(nick);
                                graphics.disableModifiers(SGR.BOLD).setForegroundColor(TextColor.ANSI.BLACK_BRIGHT).putString(x, y, ">");
                            }
                            graphics.setForegroundColor(TextColor.ANSI.WHITE).putString(row.indent(), y, row.text());
                        }
                    }
                }
            };
        }
    }
}

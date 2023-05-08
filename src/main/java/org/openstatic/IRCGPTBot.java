package org.openstatic;

import org.kitteh.irc.client.library.Client;
import org.kitteh.irc.client.library.Client.Builder;
import org.kitteh.irc.client.library.Client.Builder.Server;
import org.kitteh.irc.client.library.Client.Builder.Server.SecurityType;
import org.kitteh.irc.client.library.command.ChannelModeCommand;
import org.kitteh.irc.client.library.defaults.element.mode.DefaultChannelUserMode;
import org.kitteh.irc.client.library.element.Channel;
import org.kitteh.irc.client.library.element.User;
import org.kitteh.irc.client.library.element.mode.ChannelUserMode;
import org.kitteh.irc.client.library.element.mode.ModeStatus.Action;
import org.kitteh.irc.client.library.event.channel.ChannelInviteEvent;
import org.kitteh.irc.client.library.event.channel.ChannelJoinEvent;
import org.kitteh.irc.client.library.event.channel.ChannelMessageEvent;
import org.kitteh.irc.client.library.event.connection.ClientConnectionClosedEvent;
import org.kitteh.irc.client.library.event.connection.ClientConnectionEndedEvent;
import org.kitteh.irc.client.library.event.connection.ClientConnectionEstablishedEvent;
import org.kitteh.irc.client.library.event.connection.ClientConnectionFailedEvent;
import org.kitteh.irc.client.library.event.helper.ConnectionEvent;
import org.kitteh.irc.client.library.event.user.PrivateMessageEvent;
import org.kitteh.irc.client.library.exception.KittehNagException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.StringTokenizer;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.json.*;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor.ANSI;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Borders;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.GridLayout;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.gui2.dialogs.ListSelectDialog;
import com.googlecode.lanterna.gui2.dialogs.ListSelectDialogBuilder;
import com.googlecode.lanterna.gui2.CheckBox;
import com.googlecode.lanterna.gui2.dialogs.TextInputDialog;
import com.googlecode.lanterna.gui2.dialogs.TextInputDialogBuilder;

import net.engio.mbassy.listener.Handler;

public class IRCGPTBot extends BasicWindow implements Runnable, Consumer<Exception>
{
    public JSONObject botOptions;
    private Client client;
    private Thread mainThread;
    private boolean keepRunning;
    private String botNickname;
    private String botId;
    private HashMap<String, ChatLog> logs;
    private ArrayList<String> privateChats;
    private Panel mainPanel;
    private Label joinedChannelsLabel;
    private Label messagesHandledLabel;
    private Label errorCountLabel;
    private Label timeLabel;
    private Label connectionLabel;
    private Button serverButton;
    private Button channelsButton;
    private Button optionsButton;
    private Button reconnectButton;
    private Button exitButton;
    private long errorCount;
    private long messagesSeen;
    private long messagesHandled;
    public static File logsFolder;
    private String statusLine;

    public IRCGPTBot(String botId, JSONObject botOptions)
    {
        super();
        this.statusLine = "Starting";
        this.messagesHandled = 0;
        this.messagesSeen = 0;
        this.setHints(Arrays.asList(Window.Hint.CENTERED));
        this.keepRunning = true;
        this.botId = botId;
        this.botOptions = botOptions;
        if (!botOptions.has("channels"))
            botOptions.put("channels", new JSONObject());
        if (!botOptions.has("ignore"))
            botOptions.put("ignore", new JSONArray());
        
        this.logs = new HashMap<String, ChatLog>();
        this.privateChats = new ArrayList<String>();
        try
        {
            this.mainPanel = new Panel();
            this.timeLabel = new Label("");
            this.joinedChannelsLabel = new Label("");
            this.messagesHandledLabel = new Label("");
            this.errorCountLabel = new Label("");
            this.connectionLabel = new Label(" LAUNCHING ");
            this.connectionLabel.setBackgroundColor(ANSI.BLACK);
            this.connectionLabel.setForegroundColor(ANSI.RED_BRIGHT);
            this.mainPanel.addComponent(this.connectionLabel);
            this.mainPanel.addComponent(this.timeLabel);
            this.mainPanel.addComponent(this.joinedChannelsLabel);
            this.mainPanel.addComponent(this.messagesHandledLabel);
            this.mainPanel.addComponent(this.errorCountLabel);
            this.serverButton = new Button("Server", new Runnable() {
                @Override
                public void run() {
                    IRCGPTBot.this.serverOptions();
                }
            });
            this.channelsButton = new Button("Channels", new Runnable() {
                @Override
                public void run() {
                    Thread z = new Thread(() -> {
                        try
                        {
                            Set<Channel> channels = IRCGPTBot.this.client.getChannels();
                            Set<String> channelNames = channels.stream().map((c) -> c.getName()).collect(Collectors.toSet());
                            ListSelectDialogBuilder<String> lsdb = new ListSelectDialogBuilder<String>();
                            lsdb.addListItem("(Join A Channel)");
                            lsdb.addListItem("(Join A Channel with Key)");
                            for (String channelName : channelNames) {
                                lsdb.addListItem(channelName);
                            }
                            lsdb.setListBoxSize(new TerminalSize(50, 10));
                            lsdb.setTitle("Channels");
                            ListSelectDialog<String> lsd = (ListSelectDialog<String>) lsdb.build();
                            String channelSelected = lsd.showDialog(IRCGPTBotMain.instance.gui);
                            if ("(Join A Channel)".equals(channelSelected))
                            {
                                TextInputDialogBuilder mdb = new TextInputDialogBuilder();
                                mdb.setTitle("Enter Channel Name");
                                mdb.setInitialContent("");
                                mdb.setTextBoxSize(new TerminalSize(30, 1));
                                TextInputDialog md = mdb.build();
                                String channelName = md.showDialog(IRCGPTBotMain.instance.gui);
                                if (channelName != null && !"".equals(channelName))
                                {
                                    IRCGPTBot.this.client.addChannel(channelName);
                                    IRCGPTBot.this.addChannelToSettings(channelName);
                                }
                            } else if ("(Join A Channel with Key)".equals(channelSelected)) {
                                TextInputDialogBuilder mdb = new TextInputDialogBuilder();
                                mdb.setTitle("Enter Channel Name");
                                mdb.setInitialContent("");
                                mdb.setTextBoxSize(new TerminalSize(30, 1));
                                TextInputDialog md = mdb.build();
                                String channelName = md.showDialog(IRCGPTBotMain.instance.gui);
                                if (channelName != null && !"".equals(channelName))
                                {
                                    TextInputDialogBuilder mdb2 = new TextInputDialogBuilder();
                                    mdb2.setTitle("Enter Channel Key");
                                    mdb2.setInitialContent("");
                                    mdb2.setTextBoxSize(new TerminalSize(30, 1));
                                    TextInputDialog md2 = mdb2.build();
                                    String key = md2.showDialog(IRCGPTBotMain.instance.gui);
                                    if (key != null && !"".equals(key))
                                    {
                                        IRCGPTBot.this.client.addKeyProtectedChannel(channelName, key);
                                        IRCGPTBot.this.addKeyChannelToSettings(channelName, key);
                                    }
                                }
                            } else if (channelSelected != null) {
                                Optional<Channel> channelOptional = client.getChannel(channelSelected);
                                if (channelOptional.isPresent())
                                {
                                    Channel channel = channelOptional.get();
                                    ListSelectDialogBuilder<String> lsdb2 = new ListSelectDialogBuilder<String>();
                                    lsdb2.addListItem("View Messages");
                                    lsdb2.addListItem("Show Members");
                                    if (IRCGPTBot.this.botIsOp(channel))
                                    {
                                        lsdb2.addListItem("Set Operator");
                                    }
                                    lsdb2.addListItem("Leave Channel");
                                    lsdb2.setDescription("Select an action");
                                    lsdb2.setListBoxSize(new TerminalSize(30, 7));
                                    lsdb2.setTitle(channelSelected);
                                    ListSelectDialog<String> lsd2 = (ListSelectDialog<String>) lsdb2.build();
                                    String optionSelected = lsd2.showDialog(IRCGPTBotMain.instance.gui);
                                    if ("Leave Channel".equals(optionSelected))
                                    {
                                        channel.part();
                                        IRCGPTBot.this.removeChannelFromSettings(channelSelected);
                                    } else if ("View Messages".equals(optionSelected)) {
                                        int messageCount = 0;
                                        String pattern = "yyyy-MM-dd HH:mm:ss";
                                        SimpleDateFormat simpleDateFormat = new SimpleDateFormat(pattern);
                                        ChatLog optThread = IRCGPTBot.this.getChatLog(channelSelected);
                                        Collection<ChatMessage> messages = optThread.getMessages();
                                        messageCount = messages.size();
                                        String convoText = optThread.getMessages().stream().map((m) -> ( m.getSender() + " (" + simpleDateFormat.format(m.getTimestamp()) + ")\n" + m.getBody() + "\n")).collect(Collectors.joining("\n"));
                                        TextInputDialogBuilder mdb = new TextInputDialogBuilder();
                                        mdb.setTitle(channel.getName() + " - " + String.valueOf(messageCount) + " Messages");
                                        mdb.setInitialContent(convoText);
                                        mdb.setTextBoxSize(new TerminalSize(60, 15));
                                        TextInputDialog md = mdb.build();
                                        md.showDialog(IRCGPTBotMain.instance.gui);
                                    } else if ("Show Members".equals(optionSelected)) {
                                        ListSelectDialogBuilder<String> lsdb3 = new ListSelectDialogBuilder<String>();
                                        channel.getUsers().forEach((u) -> {
                                            lsdb3.addListItem(IRCGPTBotMain.createSizedString(u.getNick(), 15) + " " + IRCGPTBotMain.createSizedString(u.getUserString(), 15));
                                        });
                                        lsdb3.setListBoxSize(new TerminalSize(45, 7));
                                        lsdb3.setTitle(channelSelected);
                                        ListSelectDialog<String> lsd3 = (ListSelectDialog<String>) lsdb3.build();
                                        lsd3.showDialog(IRCGPTBotMain.instance.gui);
                                    } else if ("Set Operator".equals(optionSelected)) {
                                        TextInputDialogBuilder mdb2 = new TextInputDialogBuilder();
                                        mdb2.setTitle("Enter Nickname");
                                        mdb2.setInitialContent("");
                                        mdb2.setTextBoxSize(new TerminalSize(30, 1));
                                        TextInputDialog md2 = mdb2.build();
                                        String nickname = md2.showDialog(IRCGPTBotMain.instance.gui);
                                        if (nickname != null && !"".equals(nickname))
                                        {
                                            Optional<User> usr = channel.getUser(nickname);
                                            if (usr.isPresent())
                                                IRCGPTBot.this.giveOp(channel, usr.get());
                                        }
                                    }
                                }
                            }
                        } catch (Exception ex) {
                            log(ex);
                        }
                    });
                    z.start();
                }
            });
            this.optionsButton = new Button("Options", new Runnable() {
                @Override
                public void run() {
                    Thread t = new Thread(() -> {
                        final BasicWindow optionsWindow = new BasicWindow("Options");
                        Panel optionsPanel = new Panel(new LinearLayout(Direction.VERTICAL));
                        
                        CheckBox greetingOption = new CheckBox("Greet users that join a channel");
                        greetingOption.setChecked(IRCGPTBot.this.botOptions.optBoolean("greet"));

                        CheckBox greetPublicOption = new CheckBox("Greetings should be visible to channel");
                        greetPublicOption.setChecked(IRCGPTBot.this.botOptions.optBoolean("greetPublic"));

                        CheckBox acceptInvitesOption = new CheckBox("Accept all Channel Invites");
                        acceptInvitesOption.setChecked(IRCGPTBot.this.botOptions.optBoolean("acceptInvites"));

                        CheckBox gptEnabledOption = new CheckBox("ChatGPT Enabled");
                        gptEnabledOption.setChecked(IRCGPTBot.this.botOptions.optBoolean("gptEnabled", true));

                        CheckBox respondToPrivateMessagesOption = new CheckBox("Respond to private messages");
                        respondToPrivateMessagesOption.setChecked(IRCGPTBot.this.botOptions.optBoolean("privateMessages"));

                        optionsPanel.addComponent(greetingOption);
                        optionsPanel.addComponent(greetPublicOption);
                        optionsPanel.addComponent(acceptInvitesOption);
                        optionsPanel.addComponent(respondToPrivateMessagesOption);
                        optionsPanel.addComponent(gptEnabledOption);

                        Button saveButton = new Button("Save", new Runnable() {
                            @Override
                            public void run() {
                                optionsWindow.close();
                            }
                        });
                        Button changeNickButton = new Button("Change Nickname", new Runnable() {
                            @Override
                            public void run() {
                                Thread z = new Thread(() -> {
                                    try
                                    {
                                        TextInputDialogBuilder mdb = new TextInputDialogBuilder();
                                        mdb.setTitle("Enter new nickname");
                                        mdb.setInitialContent(IRCGPTBot.this.botNickname);
                                        mdb.setTextBoxSize(new TerminalSize(30, 1));
                                        TextInputDialog md = mdb.build();
                                        String newNickname = md.showDialog(IRCGPTBotMain.instance.gui);
                                        if (newNickname != null && !"".equals(newNickname))
                                            IRCGPTBot.this.changeNickname(newNickname);
                                    } catch (Exception e2) {

                                    }
                                });
                                z.start();
                            }
                        });
                        Button changeContextDepth = new Button("Change Context Depth", new Runnable() {
                            @Override
                            public void run() {
                                Thread z = new Thread(() -> {
                                    try
                                    {
                                        TextInputDialogBuilder mdb = new TextInputDialogBuilder();
                                        mdb.setTitle("Enter new value for context depth (0-255)");
                                        mdb.setInitialContent(String.valueOf(IRCGPTBot.this.botOptions.optInt("contextDepth", 15)));
                                        mdb.setTextBoxSize(new TerminalSize(30, 1));
                                        TextInputDialog md = mdb.build();
                                        String newDepth = md.showDialog(IRCGPTBotMain.instance.gui);
                                        if (newDepth != null && !"".equals(newDepth))
                                            IRCGPTBot.this.botOptions.put("contextDepth", Integer.valueOf(newDepth).intValue());
                                    } catch (Exception e2) {
                                        log(e2);
                                    }
                                });
                                z.start();
                            }
                        });
                        Button editPreamble = new Button("Edit Preamble", new Runnable() {
                            @Override
                            public void run() {
                                Thread z = new Thread(() -> {
                                    try
                                    {
                                        TextInputDialogBuilder mdb = new TextInputDialogBuilder();
                                        mdb.setTitle("System Preamble");
                                        mdb.setInitialContent(IRCGPTBot.this.botOptions.optString("systemPreamble",""));
                                        mdb.setTextBoxSize(new TerminalSize(60, 15));
                                        TextInputDialog md = mdb.build();
                                        String preamble = md.showDialog(IRCGPTBotMain.instance.gui);
                                        IRCGPTBot.this.botOptions.put("systemPreamble", preamble);
                                    } catch (Exception e2) {
                                        log(e2);
                                    }
                                });
                                z.start();
                            }
                        });
                        optionsPanel.addComponent(changeNickButton);
                        optionsPanel.addComponent(changeContextDepth);
                        optionsPanel.addComponent(editPreamble);
                        optionsPanel.addComponent(saveButton);
                        optionsWindow.setComponent(optionsPanel);
                        IRCGPTBotMain.instance.gui.addWindowAndWait(optionsWindow);
                        IRCGPTBot.this.botOptions.put("greet", greetingOption.isChecked());
                        IRCGPTBot.this.botOptions.put("greetPublic", greetPublicOption.isChecked());
                        IRCGPTBot.this.botOptions.put("gptEnabled", gptEnabledOption.isChecked());
                        IRCGPTBot.this.botOptions.put("acceptInvites", acceptInvitesOption.isChecked());
                        IRCGPTBot.this.botOptions.put("privateMessages", respondToPrivateMessagesOption.isChecked());
                        IRCGPTBotMain.instance.saveSettings();
                    });
                    t.start();
                }
            });
            this.reconnectButton = new Button("Reconnect", new Runnable() {
                @Override
                public void run() {
                    IRCGPTBot.this.connect();
                }
            });
            this.exitButton = new Button("Shutdown Bot", new Runnable() {
                @Override
                public void run() {
                    IRCGPTBot.this.shutdown();
                }
            });
            this.mainPanel.addComponent(this.serverButton);
            this.mainPanel.addComponent(this.channelsButton);
            this.mainPanel.addComponent(this.optionsButton);
            this.mainPanel.addComponent(this.reconnectButton);
            this.mainPanel.addComponent(this.exitButton);
            this.channelsButton.takeFocus();
            mainPanel.setLayoutManager(new LinearLayout(Direction.VERTICAL));
            this.setComponent(this.mainPanel.withBorder(Borders.singleLine(this.botId)));

            //IRCGPTBotMain.instance.gui.addWindow(IRCGPTBot.this);
        } catch (Exception wex) {
            log(wex);
        }
        this.botNickname = botOptions.optString("nickname", this.botId);
    }

    private void serverOptions()
    {
        Thread t = new Thread(() -> {
            final BasicWindow serverWindow = new BasicWindow("Server");
            GridLayout gl = new GridLayout(2);
            Panel serverPanel = new Panel(gl);
            TerminalSize tSize = new TerminalSize(40, 1);

            TextBox serverAddrBox = new TextBox(IRCGPTBot.this.botOptions.optString("server", "127.0.0.1"));
            TextBox portBox = new TextBox(IRCGPTBot.this.botOptions.optString("port","6667"));
            TextBox nickBox = new TextBox(IRCGPTBot.this.botOptions.optString("nickname",this.botId));
            TextBox userBox = new TextBox(IRCGPTBot.this.botOptions.optString("user",this.botId));
            TextBox realNameBox = new TextBox(IRCGPTBot.this.botOptions.optString("realName",""));
            TextBox passBox = new TextBox(IRCGPTBot.this.botOptions.optString("password",""));

            serverAddrBox.setSize(tSize);
            nickBox.setSize(tSize);
            userBox.setSize(tSize);
            realNameBox.setSize(tSize);
            passBox.setSize(tSize);
            serverAddrBox.setPreferredSize(tSize);
            nickBox.setPreferredSize(tSize);
            userBox.setPreferredSize(tSize);
            realNameBox.setPreferredSize(tSize);
            passBox.setPreferredSize(tSize);

            CheckBox secureOption = new CheckBox("");
            secureOption.setChecked(IRCGPTBot.this.botOptions.optBoolean("secure",false));

            serverPanel.addComponent(new Label("Server"));
            serverPanel.addComponent(serverAddrBox);

            serverPanel.addComponent(new Label("Port"));
            serverPanel.addComponent(portBox);

            serverPanel.addComponent(new Label("Nickname"));
            serverPanel.addComponent(nickBox);

            serverPanel.addComponent(new Label("USER"));
            serverPanel.addComponent(userBox);

            serverPanel.addComponent(new Label("Real Name"));
            serverPanel.addComponent(realNameBox);

            serverPanel.addComponent(new Label("Password"));
            serverPanel.addComponent(passBox);

            serverPanel.addComponent(new Label("Secure Connection"));
            serverPanel.addComponent(secureOption);

            Button saveButton = new Button("Save", new Runnable() {
                @Override
                public void run() {
                    serverWindow.close();
                }
            });
            serverPanel.addComponent(new Label(""));
            serverPanel.addComponent(saveButton);
            serverWindow.setComponent(serverPanel);
            IRCGPTBotMain.instance.gui.addWindowAndWait(serverWindow);
            IRCGPTBot.this.botOptions.put("server", serverAddrBox.getText());
            IRCGPTBot.this.botOptions.put("port", Integer.valueOf(portBox.getText()).intValue());
            IRCGPTBot.this.botOptions.put("nickname", nickBox.getText());
            if ("".equals(passBox.getText()))
            {
                if (IRCGPTBot.this.botOptions.has("password"))
                    IRCGPTBot.this.botOptions.remove("password");
            } else {
                IRCGPTBot.this.botOptions.put("password", passBox.getText());
            }
            if ("".equals(userBox.getText()))
            {
                if (IRCGPTBot.this.botOptions.has("user"))
                    IRCGPTBot.this.botOptions.remove("user");
            } else {
                IRCGPTBot.this.botOptions.put("user", userBox.getText());
            }
            if ("".equals(realNameBox.getText()))
            {
                if (IRCGPTBot.this.botOptions.has("realName"))
                    IRCGPTBot.this.botOptions.remove("realName");
            } else {
                IRCGPTBot.this.botOptions.put("realName", realNameBox.getText());
            }
            IRCGPTBot.this.botOptions.put("secure", secureOption.isChecked());
            IRCGPTBotMain.instance.saveSettings();
        });
        t.start();
    }

    private void connect()
    {
        try
        {
            if (this.client != null)
            {
                this.client.shutdown();
            }
        } catch (Exception ne) {
            log(ne);
        }
        try
        {
            if (botOptions.has("server"))
            {
                Builder builder = Client.builder()
                                    .nick(botNickname)
                                    .realName(botOptions.optString("realName", botNickname))
                                    .user(botOptions.optString("user", botNickname));
                builder.listeners().exception(this);
                SecurityType sType = SecurityType.INSECURE;
                if (botOptions.optBoolean("secure"))
                    sType = SecurityType.SECURE;
                Server server =  builder.server()
                                .port(botOptions.optInt("port", 6667), sType)
                                .host(botOptions.optString("server"));
                if (botOptions.has("password"))
                {
                    server = server.password(botOptions.optString("password"));
                }
                this.client = server.then().build();
                this.client.setExceptionListener(this);
                this.client.connect();
                this.client.getEventManager().registerEventListener(this);
                final JSONObject channels = botOptions.getJSONObject("channels");
                Set<String> channelNames = channels.keySet();
                for(String channelName : channelNames)
                {
                    JSONObject channelObject = channels.getJSONObject(channelName);
                    if (channelObject.optBoolean("join", false))
                    {
                        if (channelObject.has("key"))
                        {
                            IRCGPTBot.this.client.addKeyProtectedChannel(channelName, channelObject.optString("key"));
                        } else {
                            IRCGPTBot.this.client.addChannel(channelName);
                        }
                    }
                }
            } else {
                IRCGPTBot.this.serverOptions();
            }
        } catch (Exception ne) {
            log(ne);
        }
    }

    private void changeNickname(String newNick)
    {
        this.client.setNick(newNick);
        this.botNickname = newNick;
        this.botOptions.put("nickname", newNick);
        //this.topLabel.setText("IRC GPT BOT - " + settings.optString("nickname") + " / " + settings.optString("server"));
    }

    public String getStatusLine()
    {
        return this.statusLine + " " + String.valueOf(this.messagesHandled) + "/" + String.valueOf(this.messagesSeen) + "/" + String.valueOf(this.errorCount);
    }

    public JSONObject getBotOptions()
    {
        return this.botOptions;
    }

    public String getBotId()
    {
        return this.botId;
    }

    public String getNickname()
    {
        return this.botNickname;
    }

    public String getNicknameAtServer()
    {
        return this.botNickname + "@" + this.botOptions.get("server");
    }

    private void addKeyChannelToSettings(String channelName, String key)
    {
        if (channelName != null && key != null)
        {
            if (!"".equals(channelName))
            {
                JSONArray channelsSettings = IRCGPTBot.this.botOptions.getJSONArray("channels");
                List<Object> channelNames = channelsSettings.toList();
                if (!channelNames.contains(channelName))
                    channelsSettings.put(channelName);
                IRCGPTBot.this.botOptions.put("channels", channelsSettings);
                JSONObject channelKeys = IRCGPTBot.this.botOptions.getJSONObject("channelKeys");
                channelKeys.put(channelName, key);
                IRCGPTBot.this.botOptions.put("channelKeys", channelKeys);
                IRCGPTBotMain.instance.saveSettings();
            }
        }
    }

    private void removeChannelFromSettings(String channelName)
    {
        JSONObject channels = IRCGPTBot.this.botOptions.getJSONObject("channels");
        if (channels.has(channelName))
        {
            JSONObject channelObject = channels.optJSONObject(channelName);
            if (channelObject.has("key"))
            {
                channelObject.put("join", false);
                channels.put(channelName, channelObject);
            } else {
                channels.remove(channelName);
            }
        }
        IRCGPTBot.this.botOptions.put("channels", channels);
    }

    private void addChannelToSettings(String channelName)
    {
        if (channelName != null)
        {
            if (!"".equals(channelName))
            {
                JSONObject channels = IRCGPTBot.this.botOptions.getJSONObject("channels");
                if (!channels.has(channelName))
                {
                    JSONObject channelObject = new JSONObject();
                    channelObject.put("join", true);
                    channels.put(channelName, channelObject);
                }
                IRCGPTBot.this.botOptions.put("channels", channels);
                IRCGPTBotMain.instance.saveSettings();
            }
        }
    }

    public void log(Exception e)
    {

        String msg = e.getMessage();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos, true, Charset.forName("UTF-8"));
        e.printStackTrace(ps);
        ps.flush();
        this.logAppend("exceptions.log", msg + "\n" + baos.toString());
    }

    public synchronized void logAppend(String filename, String text)
    {
        IRCGPTBotMain.logAppend(this.getBotId() +  "/" + filename, text);
    }

    public void shutdown()
    {
        this.keepRunning = false;
        if (this.client != null)
            this.client.shutdown();
    }

    public void start()
    {
        this.keepRunning = true;
        if (this.mainThread == null)
        {
            this.mainThread = new Thread(this);
        }
        this.mainThread.start();
        this.connect();
    }

    public ChatLog getChatLog(String target)
    {
        if (this.logs.containsKey(target))
        {
            return this.logs.get(target);
        } else {
            ChatLog cl = new ChatLog(this.botOptions);
            this.logs.put(target,cl);
            return cl;
        }
    }

    @Handler 
    public void onConnectedEvent(ClientConnectionEstablishedEvent connectedEvent)
    {
        this.statusLine = "CONNECTED";
        this.connectionLabel.setText("  CONNECTED   ");
        this.connectionLabel.setBackgroundColor(ANSI.BLACK);
        this.connectionLabel.setForegroundColor(ANSI.GREEN_BRIGHT);
    }

    @Handler 
    public void onClientConnectionEndedEvent(ClientConnectionEndedEvent connectionEndedEvent)
    {
        this.statusLine = "DISCONNECTED";
        this.connectionLabel.setText(" DISCONNECTED ");
        this.connectionLabel.setBackgroundColor(ANSI.BLACK);
        this.connectionLabel.setForegroundColor(ANSI.RED_BRIGHT);
    }

    @Handler 
    public void onClientConnectionClosedEvent(ClientConnectionClosedEvent connectionClosedEvent)
    {
        this.statusLine = "DISCONNECTED";
        this.connectionLabel.setText(" DISCONNECTED ");
        this.connectionLabel.setBackgroundColor(ANSI.BLACK);
        this.connectionLabel.setForegroundColor(ANSI.RED_BRIGHT);
    }

    @Handler 
    public void onClientConnectionFailedEvent(ClientConnectionFailedEvent clientConnectionFailedEvent)
    {
        this.statusLine = "ERROR";
        this.connectionLabel.setText("  * ERROR *   ");
        this.connectionLabel.setBackgroundColor(ANSI.BLACK);
        this.connectionLabel.setForegroundColor(ANSI.YELLOW_BRIGHT);
    }
    
    @Handler
    public void onChannelInvite(ChannelInviteEvent event)
    {
        try
        {
            Channel channel = event.getChannel();
            if (botOptions.optBoolean("acceptInvites", false))
            {
                channel.join();
                addChannelToSettings(channel.getName());
            }
        } catch (Exception e) {

        }
    }

    private boolean botIsOp(Channel channel)
    {
        SortedSet<ChannelUserMode> ss = channel.getUserModes(botNickname).get();
        for(ChannelUserMode um : ss)
        {
            if (um.getChar() == 'o')
            {
                return true;
            }
        }
        return false;
    }

    private void giveOp(Channel channel, User user)
    {
        ChannelUserMode operMode = new DefaultChannelUserMode(client, 'o', '@');
        ChannelModeCommand giveOp = channel.commands().mode().add(Action.ADD, operMode, user);
        giveOp.execute();
    }

    @Handler
    public void onChannelJoin(ChannelJoinEvent event)
    {
        try
        {
            Channel channel = event.getChannel();
            User joiner = event.getActor();
            if (botOptions.has("botOps"))
            {
                JSONArray botOps = botOptions.getJSONArray("botOps");
                if (botIsOp(channel))
                {
                    String joinerNick = joiner.getNick();
                    if (IRCGPTBotMain.inJSONArray(botOps, joinerNick))
                    {
                        giveOp(channel, joiner);
                    }
                }
            }
            if (!joiner.getNick().equals(botNickname))
            {
                ChatLog cl = this.getChatLog(channel.getName());
                if (this.botOptions.optBoolean("greet", false) && this.botOptions.optBoolean("gptEnabled", true))
                {
                    JSONArray messages = new JSONArray();
                    JSONObject greetCommand = new JSONObject();
                    if (this.botOptions.has("systemPreamble"))
                    {
                        JSONObject msgS = new JSONObject();
                        msgS.put("role", "system");
                        msgS.put("content", this.botOptions.optString("systemPreamble"));
                        messages.put(msgS);
                    }
                    greetCommand.put("role", "user");
                    greetCommand.put("content", "Here is a conversation\n" + cl.getConversationalContext() + "\nProvide a Greeting for " + joiner.getNick() + ", who just joined the conversation and summarize what has been said so far, do not use more then 512 characters.");
                    messages.put(greetCommand);
                    Future<ChatMessage> gptResponse = IRCGPTBotMain.chatGPT.callChatGPT(this.botOptions, messages);
                    ChatMessage outMsg = gptResponse.get();
                    String outText = outMsg.getBody();
                    cl.add(outMsg);
                    if (this.botOptions.optBoolean("greetPublic", false))
                    {
                        logAppend(channel.getName() + ".log", outMsg.toSimpleLine());
                        channel.sendMultiLineMessage(outText);
                    } else {
                        logAppend(joiner.getNick() + ".log", outMsg.toSimpleLine());
                        joiner.sendMultiLineNotice(outText);
                    }
                }
            }
        } catch (Exception e) {

        }
    }

    @Handler
    public void onChannelMessage(ChannelMessageEvent event)
    {
        this.messagesSeen++;
        User sender = event.getActor();
        String senderNick = sender.getNick();
        String body = event.getMessage();
        Channel channel = event.getChannel();
        List<String> nicknames = channel.getNicknames()
                                        .stream()
                                        .filter((nickname) -> !IRCGPTBotMain.inJSONArray(botOptions.optJSONArray("ignore"), nickname))
                                        .collect(Collectors.toList());
        int userCount = nicknames.size();
        String target = channel.getName();
                            
        if (!senderNick.equals(botNickname) && nicknames.contains(senderNick))
        {
            ChatLog cl = this.getChatLog(target);
            ChatMessage msg = new ChatMessage(senderNick, channel.getName(), body, new Date(System.currentTimeMillis()));
            ChatMessage previosMsg = cl.getLastMessage();
            cl.add(msg);
            
            boolean botAskedQuestion = false;
            boolean containsBotName = body.toLowerCase().contains(this.botNickname.toLowerCase());
            boolean lastMessageWasBot = false;
            boolean userCountIsTwo = (userCount == 2);
            if (previosMsg != null)
            {
                lastMessageWasBot = previosMsg.getSender().equals(this.botNickname);
                if (lastMessageWasBot)
                {
                    botAskedQuestion = previosMsg.getBody().contains("?");
                }
            }
            String rReason = "U=" + String.valueOf(userCount);
            if (userCountIsTwo)
                rReason += " U2";
            if (botAskedQuestion)
                rReason += " B?";
            if (containsBotName)
                rReason += " @B";
            logAppend(target + ".log", "("+ rReason +") "+ msg.toSimpleLine());
            if (botAskedQuestion || containsBotName || userCountIsTwo)
            {
                if (this.botOptions.optBoolean("gptEnabled", true))
                {
                    try
                    {
                        Future<ChatMessage> gptResponse = IRCGPTBotMain.chatGPT.callChatGPT(this.botOptions, cl);
                        ChatMessage outMsg = gptResponse.get();
                        outMsg.setRecipient(target);
                        logAppend(target + ".log", outMsg.toSimpleLine());
                        String outText = outMsg.getBody().replace("\n", " ").replace("\r", "");
                        cl.add(outMsg);
                        channel.sendMultiLineMessage(outText);
                        this.messagesHandled++;
                    } catch (Exception e) {
                        log(e);
                    }
                }
            }
        } else {
            ChatMessage msg = new ChatMessage(senderNick, channel.getName(), body, new Date(System.currentTimeMillis()));
            logAppend(target + ".log", "(IGNORED) " + msg.toSimpleLine());
        }
    }

    @Handler
    public void onPrivateMessage(PrivateMessageEvent event)
    {
        this.messagesSeen++;
        User sender = event.getActor();
        String senderNick = sender.getNick();
        String body = event.getMessage();
        if (!IRCGPTBotMain.inJSONArray(this.botOptions.optJSONArray("ignore"), senderNick))
        {
            try
            {
                String target = sender.getNick();
                if (!this.privateChats.contains(target))
                {
                    this.privateChats.add(target);
                }
                ChatLog cl = this.getChatLog(target);
                ChatMessage msg = new ChatMessage(sender.getNick(), this.botNickname, body, new Date(System.currentTimeMillis()));
                cl.add(msg);
                logAppend(target + ".log", msg.toSimpleLine());
                if (this.botOptions.optBoolean("privateMessages", false))
                {
                    if (this.botOptions.optBoolean("gptEnabled", true))
                    {
                        Future<ChatMessage> gptResponse = IRCGPTBotMain.chatGPT.callChatGPT(this.botOptions, cl);
                        ChatMessage outMsg = gptResponse.get();
                        String outText = outMsg.getBody();
                        outMsg.setRecipient(target);
                        cl.add(outMsg);
                        logAppend(target + ".log", outMsg.toSimpleLine());
                        sender.sendMultiLineMessage(outText);
                        this.messagesHandled++;
                    }
                } else {
                    ChatMessage outMsg = new ChatMessage(sender.getNick(), this.botNickname, "I'm sorry, my private message features have been disabled.", new Date(System.currentTimeMillis()));
                    cl.add(outMsg);
                    logAppend(target + ".log", outMsg.toSimpleLine());
                    sender.sendMultiLineMessage(outMsg.getBody());
                    this.messagesHandled++;
                }
            } catch (Exception e) {
                log(e);
            }
        }
    }

    @Override
    public void run() 
    {
        int joinedChannels = 0;
        String pattern = "HH:mm:ss yyyy-MM-dd";
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat(pattern);
        while(this.keepRunning)
        {
            try
            {
                if (this.client != null)
                {
                    joinedChannels = client.getChannels().size();
                }
            } catch (Exception ce) {
                //log(ce);
            }
            try
            {
                String date = simpleDateFormat.format(new Date());
                IRCGPTBot.this.timeLabel.setText(date);
                IRCGPTBot.this.joinedChannelsLabel.setText("Joined Channels: " + String.valueOf(joinedChannels));
                IRCGPTBot.this.messagesHandledLabel.setText("Messages Handled: " + String.valueOf(IRCGPTBot.this.messagesHandled) + " / " + String.valueOf(IRCGPTBot.this.messagesSeen));
                IRCGPTBot.this.errorCountLabel.setText("Errors: " + String.valueOf(IRCGPTBot.this.errorCount));
                IRCGPTBotMain.instance.gui.updateScreen();
                IRCGPTBotMain.instance.gui.processInput();
                Thread.sleep(200);
            } catch (Exception e) {
                //log(e);
            }
        }
        this.mainThread = null;
    }

    @Override
    public void accept(Exception t)
    {
        if (!(t instanceof KittehNagException))
            this.errorCount++;
        log(t);
    }
}

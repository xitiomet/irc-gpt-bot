package org.openstatic;

import org.kitteh.irc.client.library.Client;
import org.kitteh.irc.client.library.Client.Builder;
import org.kitteh.irc.client.library.Client.Builder.Server;
import org.kitteh.irc.client.library.Client.Builder.Server.SecurityType;
import org.kitteh.irc.client.library.element.Actor;
import org.kitteh.irc.client.library.element.Channel;
import org.kitteh.irc.client.library.element.User;
import org.kitteh.irc.client.library.event.channel.ChannelInviteEvent;
import org.kitteh.irc.client.library.event.channel.ChannelJoinEvent;
import org.kitteh.irc.client.library.event.channel.ChannelMessageEvent;
import org.kitteh.irc.client.library.event.connection.ClientConnectionEstablishedEvent;
import org.kitteh.irc.client.library.event.helper.ConnectionEvent;
import org.kitteh.irc.client.library.event.user.PrivateMessageEvent;
import org.kitteh.irc.client.library.exception.KittehNagException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.apache.commons.cli.*;
import org.json.*;

import com.googlecode.lanterna.Symbols;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.TextColor.ANSI;
import com.googlecode.lanterna.gui2.BasePane;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Border;
import com.googlecode.lanterna.gui2.BorderLayout;
import com.googlecode.lanterna.gui2.Borders;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.DefaultWindowManager;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.EmptySpace;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.gui2.WindowListener;
import com.googlecode.lanterna.gui2.WindowManager;
import com.googlecode.lanterna.gui2.dialogs.ListSelectDialog;
import com.googlecode.lanterna.gui2.dialogs.ListSelectDialogBuilder;
import com.googlecode.lanterna.gui2.CheckBox;
import com.googlecode.lanterna.gui2.CheckBoxList;
import com.googlecode.lanterna.gui2.dialogs.MessageDialog;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogBuilder;
import com.googlecode.lanterna.gui2.dialogs.TextInputDialog;
import com.googlecode.lanterna.gui2.dialogs.TextInputDialogBuilder;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;

import net.engio.mbassy.listener.Handler;

public class IRCGPTBotMain extends BasicWindow implements Runnable, Consumer<Exception>
{
    public ChatGPT chatGPT;
    public JSONObject settings;
	private Screen screen;
    private Terminal terminal;
    private MultiWindowTextGUI gui;
    private Client client;
    private Thread mainThread;
    private boolean keepRunning;
    private String botNickname;
    private HashMap<String, ChatLog> logs;
    private ArrayList<String> privateChats;
    private Panel mainPanel;
    private Label topLabel;
    private Label bottomLabel;
    private Label joinedChannelsLabel;
    private Label messagesHandledLabel;
    private Label errorCountLabel;
    private Label timeLabel;
    private Button channelsButton;
    private Button optionsButton;
    private Button exitButton;
    private long errorCount;
    private long messagesSeen;
    private long messagesHandled;
    private File settingsFile;

    public IRCGPTBotMain(JSONObject settings, File settingsFile)
    {
        super();
        this.messagesHandled = 0;
        this.messagesSeen = 0;
        this.setHints(Arrays.asList(Window.Hint.CENTERED));
        this.keepRunning = true;
        this.settingsFile = settingsFile;
        this.settings = settings;
        this.logs = new HashMap<String, ChatLog>();
        this.privateChats = new ArrayList<String>();
        try
        {
            this.terminal = new DefaultTerminalFactory().createTerminal();
            this.screen = new TerminalScreen(this.terminal);
            WindowManager wm = new DefaultWindowManager();
            this.gui = new MultiWindowTextGUI(screen, wm, new EmptySpace(TextColor.ANSI.BLACK));
            Panel backgroundPanel = new Panel(new BorderLayout());
            backgroundPanel.setFillColorOverride(ANSI.BLACK);

            Panel topLabelPanel = new Panel();
            topLabelPanel.setFillColorOverride(ANSI.RED);
            this.topLabel = new Label("IRC GPT BOT - " + settings.optString("nickname") + " / " + settings.optString("server"));
            this.topLabel.setBackgroundColor(ANSI.RED);
            this.topLabel.setForegroundColor(ANSI.BLACK);
            topLabelPanel.addComponent(this.topLabel);            
            backgroundPanel.addComponent(topLabelPanel, BorderLayout.Location.TOP);

            Panel bottomLabelPanel = new Panel();
            bottomLabelPanel.setFillColorOverride(ANSI.RED);
            this.bottomLabel = new Label("https://github.com/xitiomet/irc-gpt-bot");
            this.bottomLabel.setBackgroundColor(ANSI.RED);
            this.bottomLabel.setForegroundColor(ANSI.BLACK);
            bottomLabelPanel.addComponent(this.bottomLabel);            
            backgroundPanel.addComponent(bottomLabelPanel, BorderLayout.Location.BOTTOM);

            this.gui.getBackgroundPane().setComponent(backgroundPanel);

            this.mainPanel = new Panel();
            this.timeLabel = new Label("");
            this.joinedChannelsLabel = new Label("");
            this.messagesHandledLabel = new Label("");
            this.errorCountLabel = new Label("");
            this.mainPanel.addComponent(this.timeLabel);
            this.mainPanel.addComponent(this.joinedChannelsLabel);
            this.mainPanel.addComponent(this.messagesHandledLabel);
            this.mainPanel.addComponent(this.errorCountLabel);
            this.channelsButton = new Button("Channels", new Runnable() {
                @Override
                public void run() {
                    Thread z = new Thread(() -> {
                        try
                        {
                            Set<Channel> channels = IRCGPTBotMain.this.client.getChannels();
                            Set<String> channelNames = channels.stream().map((c) -> c.getName()).collect(Collectors.toSet());
                            ListSelectDialogBuilder<String> lsdb = new ListSelectDialogBuilder<String>();
                            lsdb.addListItem("(Join A Channel)");
                            for (String channelName : channelNames) {
                                lsdb.addListItem(channelName);
                            }
                            lsdb.setListBoxSize(new TerminalSize(50, 10));
                            lsdb.setTitle("Channels");
                            ListSelectDialog<String> lsd = (ListSelectDialog<String>) lsdb.build();
                            String channelSelected = lsd.showDialog(IRCGPTBotMain.this.gui);
                            if ("(Join A Channel)".equals(channelSelected))
                            {
                                TextInputDialogBuilder mdb = new TextInputDialogBuilder();
                                mdb.setTitle("Enter Channel Name");
                                mdb.setInitialContent("");
                                mdb.setTextBoxSize(new TerminalSize(30, 1));
                                TextInputDialog md = mdb.build();
                                String channelName = md.showDialog(IRCGPTBotMain.this.gui);
                                if (channelName != null && !"".equals(channelName))
                                {
                                    IRCGPTBotMain.this.client.addChannel(channelName);
                                    IRCGPTBotMain.this.addChannelToSettings(channelName);
                                }
                            } else {
                                Optional<Channel> channelOptional = client.getChannel(channelSelected);
                                if (channelOptional.isPresent())
                                {
                                    Channel channel = channelOptional.get();
                                    ListSelectDialogBuilder<String> lsdb2 = new ListSelectDialogBuilder<String>();
                                    lsdb2.addListItem("View Messages");
                                    lsdb2.addListItem("Show Members");
                                    lsdb2.addListItem("Leave Channel");
                                    lsdb2.setDescription("Select an action");
                                    lsdb2.setListBoxSize(new TerminalSize(30, 7));
                                    lsdb2.setTitle(channelSelected);
                                    ListSelectDialog<String> lsd2 = (ListSelectDialog<String>) lsdb2.build();
                                    String optionSelected = lsd2.showDialog(IRCGPTBotMain.this.gui);
                                    if ("Leave Channel".equals(optionSelected))
                                    {
                                        channel.part();
                                    } else if ("View Messages".equals(optionSelected)) {
                                        int messageCount = 0;
                                        String pattern = "yyyy-MM-dd HH:mm:ss";
                                        SimpleDateFormat simpleDateFormat = new SimpleDateFormat(pattern);
                                        ChatLog optThread = IRCGPTBotMain.this.getLog(channelSelected);
                                        Collection<ChatMessage> messages = optThread.getMessages();
                                        messageCount = messages.size();
                                        String convoText = optThread.getMessages().stream().map((m) -> ( m.getSender() + " (" + simpleDateFormat.format(m.getTimestamp()) + ")\n" + m.getBody() + "\n")).collect(Collectors.joining("\n"));
                                        TextInputDialogBuilder mdb = new TextInputDialogBuilder();
                                        mdb.setTitle(channel.getName() + " - " + String.valueOf(messageCount) + " Messages");
                                        mdb.setInitialContent(convoText);
                                        mdb.setTextBoxSize(new TerminalSize(60, 15));
                                        TextInputDialog md = mdb.build();
                                        md.showDialog(IRCGPTBotMain.this.gui);
                                    } else if ("Show Members".equals(optionSelected)) {
                                        ListSelectDialogBuilder<String> lsdb3 = new ListSelectDialogBuilder<String>();
                                        channel.getUsers().forEach((u) -> {
                                            lsdb3.addListItem(createSizedString(u.getNick(), 15) + " " + createSizedString(u.getUserString(), 15));
                                        });
                                        lsdb3.setListBoxSize(new TerminalSize(45, 7));
                                        lsdb3.setTitle(channelSelected);
                                        ListSelectDialog<String> lsd3 = (ListSelectDialog<String>) lsdb3.build();
                                        lsd3.showDialog(IRCGPTBotMain.this.gui);
                                    }
                                }
                            }
                        } catch (Exception ex) {

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
                        greetingOption.setChecked(IRCGPTBotMain.this.settings.optBoolean("greet"));

                        CheckBox acceptInvitesOption = new CheckBox("Accept all Channel Invites");
                        acceptInvitesOption.setChecked(IRCGPTBotMain.this.settings.optBoolean("acceptInvites"));

                        optionsPanel.addComponent(greetingOption);
                        optionsPanel.addComponent(acceptInvitesOption);

                        Button saveButton = new Button("save", new Runnable() {
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
                                        mdb.setInitialContent(IRCGPTBotMain.this.botNickname);
                                        mdb.setTextBoxSize(new TerminalSize(30, 1));
                                        TextInputDialog md = mdb.build();
                                        String newNickname = md.showDialog(IRCGPTBotMain.this.gui);
                                        if (newNickname != null && !"".equals(newNickname))
                                            IRCGPTBotMain.this.changeNickname(newNickname);
                                    } catch (Exception e2) {

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
                                        mdb.setInitialContent(IRCGPTBotMain.this.settings.optString("systemPreamble",""));
                                        mdb.setTextBoxSize(new TerminalSize(60, 15));
                                        TextInputDialog md = mdb.build();
                                        String preamble = md.showDialog(IRCGPTBotMain.this.gui);
                                        IRCGPTBotMain.this.settings.put("systemPreamble", preamble);
                                    } catch (Exception e2) {

                                    }
                                });
                                z.start();
                            }
                        });
                        optionsPanel.addComponent(changeNickButton);
                        optionsPanel.addComponent(editPreamble);
                        optionsPanel.addComponent(saveButton);
                        optionsWindow.setComponent(optionsPanel);
                        IRCGPTBotMain.this.gui.addWindowAndWait(optionsWindow);
                        IRCGPTBotMain.this.settings.put("greet", greetingOption.isChecked());
                        IRCGPTBotMain.this.settings.put("acceptInvites", acceptInvitesOption.isChecked());
                        IRCGPTBotMain.this.saveSettings();
                    });
                    t.start();
                }
            });
            this.exitButton = new Button("Shutdown", new Runnable() {
                @Override
                public void run() {
                    IRCGPTBotMain.this.shutdown();
                }
            });
            this.mainPanel.addComponent(channelsButton);
            this.mainPanel.addComponent(optionsButton);
            this.mainPanel.addComponent(exitButton);
            this.channelsButton.takeFocus();
            mainPanel.setLayoutManager(new LinearLayout(Direction.VERTICAL));
            this.setComponent(this.mainPanel.withBorder(Borders.singleLine("Bot Stats")));

            IRCGPTBotMain.this.screen.startScreen();
            IRCGPTBotMain.this.gui.addWindow(IRCGPTBotMain.this);
        } catch (Exception wex) {
            wex.printStackTrace(System.err);
        }
        this.botNickname = settings.optString("nickname");
        this.chatGPT = new ChatGPT(this.settings);
        try
        {
            Builder builder = Client.builder()
                                .nick(botNickname)
                                .realName(settings.optString("realName", botNickname))
                                .user(settings.optString("user", botNickname));
            builder.listeners().exception(this);
            SecurityType sType = SecurityType.INSECURE;
            if (settings.optBoolean("secure"))
                sType = SecurityType.SECURE;
            Server server =  builder.server()
                            .port(settings.optInt("port", 6667), sType)
                            .host(settings.optString("server"));
            if (settings.has("password"))
            {
                server = server.password(settings.optString("password"));
            }
            this.client = server.then().build();
            this.client.setExceptionListener(this);
            this.client.connect();
            this.client.getEventManager().registerEventListener(this);
            JSONArray channels = settings.getJSONArray("channels");
            channels.forEach((channel) -> {
                String channelName = (String) channel;
                this.client.addChannel(channelName);
            });
        } catch (Throwable ne) {
            ne.printStackTrace(System.err);
        }
    }

    private void changeNickname(String newNick)
    {
        this.client.setNick(newNick);
        this.botNickname = newNick;
        this.settings.put("nickname", newNick);
        this.topLabel.setText("OPENSTATIC.ORG IRC chatGPT BOT - " + settings.optString("nickname") + " / " + settings.optString("server"));
    }

    private void addChannelToSettings(String channelName)
    {
        if (channelName != null)
        {
            if (!"".equals(channelName))
            {
                JSONArray channelsSettings = IRCGPTBotMain.this.settings.getJSONArray("channels");
                List<Object> channelNames = channelsSettings.toList();
                if (!channelNames.contains(channelName))
                    channelsSettings.put(channelName);
                saveSettings();
            }
        }
    }

    public void saveSettings()
    {
        saveJSONObject(IRCGPTBotMain.this.settingsFile, IRCGPTBotMain.this.settings);
    }

    public void shutdown()
    {
        this.keepRunning = false;
        this.client.shutdown();
        Thread t = new Thread(() -> {
            try
            {
                Thread.sleep(2000);
                System.exit(0);
            } catch (Exception e) {

            }
        });
        t.start();
    }

    public void start()
    {
        this.keepRunning = true;
        if (this.mainThread == null)
        {
            this.mainThread = new Thread(this);
        }
        this.mainThread.start();
    }

    public ChatLog getLog(String target)
    {
        if (this.logs.containsKey(target))
        {
            return this.logs.get(target);
        } else {
            ChatLog cl = new ChatLog(this.settings);
            this.logs.put(target,cl);
            return cl;
        }
    }
    
    @Handler
    public void onChannelInvite(ChannelInviteEvent event)
    {
        try
        {
            Channel channel = event.getChannel();
            if (settings.optBoolean("acceptInvites", false))
            {
                channel.join();
                addChannelToSettings(channel.getName());
            }
        } catch (Exception e) {

        }
    }

    @Handler
    public void onClientConnect(ClientConnectionEstablishedEvent event)
    {
        
    }

    @Handler
    public void onChannelJoin(ChannelJoinEvent event)
    {
        try
        {
            Channel channel = event.getChannel();
            User joiner = event.getActor();
            if (!joiner.getNick().equals(botNickname))
            {
                ChatLog cl = this.getLog(channel.getName());
                if (this.settings.optBoolean("greet", false))
                {
                    JSONArray messages = new JSONArray();
                    JSONObject greetCommand = new JSONObject();
                    greetCommand.put("role", "user");
                    greetCommand.put("content", cl.getConversationalContext() + " Greet " + joiner.getNick() + ", who just joined the conversation. Also provide them with a quick summary of what has been said so far");
                    messages.put(greetCommand);
                    Future<ChatMessage> gptResponse = chatGPT.callChatGPT(messages);
                    ChatMessage outMsg = gptResponse.get();
                    String outText = outMsg.getBody();
                    cl.add(outMsg);
                    channel.sendMultiLineMessage(outText);
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
        if (!sender.getNick().equals(botNickname))
        {
            String body = event.getMessage();
            Channel channel = event.getChannel();
            String target = channel.getName();
            ChatLog cl = this.getLog(target);
            ChatMessage msg = new ChatMessage(sender.getNick(), channel.getName(), body, new Date(System.currentTimeMillis()));
            ChatMessage previosMsg = cl.getLastMessage();
            cl.add(msg);
            boolean botAskedQuestion = false;
            boolean containsBotName = body.toLowerCase().contains(this.botNickname.toLowerCase());
            boolean lastMessageWasBot = false;
            if (previosMsg != null)
            {
                lastMessageWasBot = previosMsg.getSender().equals(this.botNickname);
                if (lastMessageWasBot)
                {
                    botAskedQuestion = previosMsg.getBody().contains("?");
                }
            }
            if (botAskedQuestion || containsBotName)
            {
                try
                {
                    Future<ChatMessage> gptResponse = chatGPT.callChatGPT(cl);
                    ChatMessage outMsg = gptResponse.get();
                    outMsg.setRecipient(target);
                    String outText = outMsg.getBody().replace("\n", " ").replace("\r", "");
                    cl.add(outMsg);
                    channel.sendMultiLineMessage(outText);
                    this.messagesHandled++;
                } catch (Exception e) {
                    //e.printStackTrace(System.err);
                }
            }
        }
    }

    @Handler
    public void onPrivateMessage(PrivateMessageEvent event)
    {
        this.messagesSeen++;
        User sender = event.getActor();
        String body = event.getMessage();
        try
        {
            String target = sender.getNick();
            if (!this.privateChats.contains(target))
            {
                this.privateChats.add(target);
            }
            ChatLog cl = this.getLog(target);
            ChatMessage msg = new ChatMessage(sender.getNick(), this.botNickname, body, new Date(System.currentTimeMillis()));
            cl.add(msg);
            Future<ChatMessage> gptResponse = chatGPT.callChatGPT(cl);
            ChatMessage outMsg = gptResponse.get();
            String outText = outMsg.getBody();
            outMsg.setRecipient(target);
            cl.add(outMsg);
            sender.sendMultiLineMessage(outText);
            this.messagesHandled++;
        } catch (Exception e) {
            //e.printStackTrace(System.err);
        }
    }

    public static void main(String[] args)
    {
        CommandLine cmd = null;
        File settingsFile = new File("config.json");
        JSONObject settings = loadJSONObject(settingsFile);
        Options options = new Options();
        CommandLineParser parser = new DefaultParser();
        options.addOption(new Option("?", "help", false, "Shows help"));
        options.addOption(new Option("f", "config", true, "Specify a config file (.json) to use"));
        options.addOption(new Option("s", "server", true, "Connect to server"));
        options.addOption(new Option("e", "secure", false, "Use Secure connection"));
        options.addOption(new Option("k", "key", false, "Set the openAI key for this bot"));
        options.addOption(new Option("p", "port", true, "Specify connection port"));
        options.addOption(new Option("n", "nickname", true, "Set Bot Nickname"));
        options.addOption(new Option("c", "channels", true, "List of channels to join (separated by comma)"));
        options.addOption(new Option("x", "context-depth", true, "How many messages to provide chatGPT for context"));
        options.addOption(new Option("a", "system-preamble", true, "Provide a set of instructions for the bot to follow"));
        try
        {
            cmd = parser.parse(options, args);

            if (cmd.hasOption("?"))
            {
                showHelp(options);
            }
            if (cmd.hasOption("f"))
            {
                settingsFile = new File(cmd.getOptionValue("f"));
                settings = loadJSONObject(settingsFile);
            }
            if (cmd.hasOption("s"))
            {
                settings.put("server", cmd.getOptionValue("s"));
            }
            if (cmd.hasOption("c"))
            {
                JSONArray channels = new JSONArray();
                String channelsParam = cmd.getOptionValue("c");
                StringTokenizer st = new StringTokenizer(channelsParam, ",", false);
                while(st.hasMoreTokens())
                {
                    channels.put(st.nextToken());
                }
                settings.put("channels", channels);
            }
            if (cmd.hasOption("p"))
            {
                settings.put("port", Integer.valueOf(cmd.getOptionValue("p")).intValue());
            }
            if (cmd.hasOption("x"))
            {
                settings.put("contextDepth", Integer.valueOf(cmd.getOptionValue("x")).intValue());
            }
            if (cmd.hasOption("n"))
            {
                settings.put("nickname", cmd.getOptionValue("n"));
            }
            if (cmd.hasOption("k"))
            {
                settings.put("openAiKey", cmd.getOptionValue("k"));
            }
            if (cmd.hasOption("a"))
            {
                settings.put("systemPreamble", cmd.getOptionValue("a"));
            }
            if (cmd.hasOption("e"))
            {
                settings.put("secure", true);
            }
            if (!settings.has("channels"))
                settings.put("channels", new JSONArray());
            IRCGPTBotMain bot = new IRCGPTBotMain(settings, settingsFile);
            bot.start();
        } catch (Throwable e) {
            //e.printStackTrace(System.err);
        }
    }
    public static void showHelp(Options options)
    {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp( "irc-gpt-bot", "IRC GPT Bot: An IRC Bot for chatGPT", options, "" );
        System.exit(0);
    }

    public static JSONObject loadJSONObject(File file)
    {
        try
        {
            FileInputStream fis = new FileInputStream(file);
            StringBuilder builder = new StringBuilder();
            int ch;
            while((ch = fis.read()) != -1){
                builder.append((char)ch);
            }
            fis.close();
            JSONObject props = new JSONObject(builder.toString());
            return props;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static void saveJSONObject(File file, JSONObject obj)
    {
        try
        {
            FileOutputStream fos = new FileOutputStream(file);
            PrintStream ps = new PrintStream(fos);
            ps.print(obj.toString(2));
            ps.close();
            fos.close();
        } catch (Exception e) {
        }
    }

    @Override
    public void run() 
    {
        while(this.keepRunning)
        {
            try
            {
                String pattern = "HH:mm:ss yyyy-MM-dd";
                SimpleDateFormat simpleDateFormat = new SimpleDateFormat(pattern);
                String date = simpleDateFormat.format(new Date());
                IRCGPTBotMain.this.timeLabel.setText(date);
                IRCGPTBotMain.this.joinedChannelsLabel.setText("Joined Channels: " + String.valueOf(client.getChannels().size()));
                IRCGPTBotMain.this.messagesHandledLabel.setText("Messages Handled: " + String.valueOf(IRCGPTBotMain.this.messagesHandled) + " / " + String.valueOf(IRCGPTBotMain.this.messagesSeen));
                IRCGPTBotMain.this.errorCountLabel.setText("Errors: " + String.valueOf(IRCGPTBotMain.this.errorCount));
                IRCGPTBotMain.this.gui.updateScreen();
                IRCGPTBotMain.this.gui.processInput();
                Thread.sleep(200);
            } catch (Exception e) {
                //e.printStackTrace(System.err);
            }
        }
        this.mainThread = null;
    }

    @Override
    public void accept(Exception t)
    {
        if (!(t instanceof KittehNagException))
            this.errorCount++;
    }

    public static String getPaddingSpace(int value)
    {
        StringBuffer x = new StringBuffer("");
        for (int n = 0; n < value; n++)
        {
            x.append(" ");
        }
        return x.toString();
    }

    public static String createSizedString(String value, int size)
    {
        if (value == null)
        {
            return getPaddingSpace(size);
        } else if (value.length() == size) {
            return value;
        } else if (value.length() > size) {
            return value.substring(0, size);
        } else if (value.length() < size) {
            return value + getPaddingSpace(size - value.length());
        } else {
            return null;
        }
    }
}

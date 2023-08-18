package org.openstatic;

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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

import org.apache.commons.cli.*;
import org.json.*;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.TextColor.ANSI;
import com.googlecode.lanterna.gui2.ActionListBox;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.BorderLayout;
import com.googlecode.lanterna.gui2.Borders;
import com.googlecode.lanterna.gui2.DefaultWindowManager;
import com.googlecode.lanterna.gui2.EmptySpace;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.gui2.WindowManager;
import com.googlecode.lanterna.gui2.AbstractListBox.ListItemRenderer;

import com.googlecode.lanterna.gui2.dialogs.TextInputDialog;
import com.googlecode.lanterna.gui2.dialogs.TextInputDialogBuilder;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;
import com.googlecode.lanterna.terminal.swing.SwingTerminalFrame;

public class IRCGPTBotMain extends BasicWindow implements Runnable
{
    public static ChatGPT chatGPT;
    public static JSONObject settings;
    public static File settingsFile;
    public static IRCGPTBotMain instance;
    public static File logsFolder;

    private APIWebServer apiServer;
    public HashSet<IRCGPTBotListener> listeners;
    private HashMap<String, IRCGPTBot> bots;
	private Screen screen;
    private Terminal terminal;
    public MultiWindowTextGUI gui;
    public WindowManager wm;
    private Thread mainThread;
    private boolean keepRunning;
    private ActionListBox mainPanel;
    private Label topLabel;
    private Label bottomLabel;

    public IRCGPTBotMain()
    {
        super();
        this.setHints(Arrays.asList(Window.Hint.CENTERED));
        this.bots = new HashMap<String, IRCGPTBot>();
        this.keepRunning = true;
        this.mainPanel = new ActionListBox();
        this.listeners = new HashSet<IRCGPTBotListener>();
        mainPanel.setListItemRenderer(this.lir);
        IRCGPTBotMain.instance = this;
        String defaultLogPath = new File(settingsFile.getParentFile(), "irc-gpt-bot-logs").toString();
        IRCGPTBotMain.logsFolder = new File(IRCGPTBotMain.settings.optString("logPath", defaultLogPath));
        if (!IRCGPTBotMain.logsFolder.exists())
        {
            IRCGPTBotMain.logsFolder.mkdirs();
        }
        try
        {
            if (settings.optBoolean("guiMode", false))
            {
                final SwingTerminalFrame swingTerminal = new DefaultTerminalFactory().setInitialTerminalSize(new TerminalSize(80, 24)).createSwingTerminal();
                swingTerminal.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
                SwingUtilities.invokeAndWait(() -> {
                    swingTerminal.setTitle("IRC GPT BOT");
                    try
                    {
                        swingTerminal.setIconImage(ImageIO.read(IRCGPTBotMain.class.getResourceAsStream("/irc-gpt-bot/icon-32.png")));
                    } catch (Exception e) {}
                    swingTerminal.setLocation(40, 40);
                    swingTerminal.setVisible(true);
                });
                this.terminal = swingTerminal;
            } else {
                this.terminal = new DefaultTerminalFactory().setInitialTerminalSize(new TerminalSize(80, 24)).createHeadlessTerminal();
            }

            if (!settings.optBoolean("headless", false))
            {
                this.screen = new TerminalScreen(this.terminal);
                this.wm = new DefaultWindowManager();
                this.gui = new MultiWindowTextGUI(screen, this.wm, new EmptySpace(TextColor.ANSI.BLACK));
                Panel backgroundPanel = new Panel(new BorderLayout());
                backgroundPanel.setFillColorOverride(ANSI.BLACK);

                Panel topLabelPanel = new Panel();
                topLabelPanel.setFillColorOverride(ANSI.RED);
                this.topLabel = new Label("IRC GPT BOT - https://openstatic.org/projects/ircgptbot/");
                this.topLabel.setBackgroundColor(ANSI.RED);
                this.topLabel.setForegroundColor(ANSI.BLACK);
                topLabelPanel.addComponent(this.topLabel);            
                backgroundPanel.addComponent(topLabelPanel, BorderLayout.Location.TOP);

                Panel bottomLabelPanel = new Panel();
                bottomLabelPanel.setFillColorOverride(ANSI.RED);
                this.bottomLabel = new Label("F2: Launch Bot  |  F4: Destroy Selected Bot  |  F10: Set OpenAI Key  |  F12: Shutdown");
                this.bottomLabel.setBackgroundColor(ANSI.RED);
                this.bottomLabel.setForegroundColor(ANSI.BLACK);
                bottomLabelPanel.addComponent(this.bottomLabel);            
                backgroundPanel.addComponent(bottomLabelPanel, BorderLayout.Location.BOTTOM);

                this.gui.getBackgroundPane().setComponent(backgroundPanel);

                this.setComponent(this.mainPanel.withBorder(Borders.singleLine("Loaded Bots")));
                IRCGPTBotMain.this.gui.addWindow(IRCGPTBotMain.this);

                IRCGPTBotMain.this.screen.startScreen();
            }
        } catch (Exception wex) {
            log(wex);
        }
        this.apiServer = new APIWebServer();
        this.apiServer.setState(IRCGPTBotMain.settings.optBoolean("apiServer", false));
        this.addIRCGPTBotListener(this.apiServer);
    }

    public synchronized IRCGPTBot launchBot(String botId, JSONObject botOptions)
    {
        final IRCGPTBot bot = new IRCGPTBot(botId, botOptions);
        bot.start();
        this.bots.put(botId, bot);
        this.mainPanel.addItem(bot.getBotId(), new Runnable() {
            public void run()
            {
                if (IRCGPTBotMain.this.gui != null)
                   IRCGPTBotMain.this.gui.addWindow(bot);
            }
            
            public String toString()
            {
                return bot.getBotId();
            }
        });
        Thread x = new Thread(() -> {
            IRCGPTBotMain.instance.listeners.forEach((l) -> l.onBotAdded(bot));
        });
        x.start();
        return bot;
    }

    private ListItemRenderer<Runnable,ActionListBox> lir = new ListItemRenderer<Runnable,ActionListBox>()
    {
        @Override
        public String getLabel(ActionListBox listBox, int index, Runnable item) 
        {
            return IRCGPTBotMain.createSizedString(item.toString(), 15)  + " [" + IRCGPTBotMain.this.getBotAtIndex(index).getStatusLine() + "]";
        }
    };

    public void addIRCGPTBotListener(IRCGPTBotListener l)
    {
        this.listeners.add(l);
    }

    public void removeIRCGPTBotListener(IRCGPTBotListener l)
    {
        if (this.listeners.contains(l))
            this.listeners.remove(l);
    }

    private IRCGPTBot getBotAtIndex(int idx)
    {
        Runnable r = this.mainPanel.getItemAt(idx);
        String botId = r.toString();
        int spaceIndex = botId.indexOf(' ');
        if (spaceIndex >= 0)
            botId = botId.substring(0, spaceIndex);
        return this.bots.get(botId);
    }

    public IRCGPTBot getBotbyIdentifier(String identifier)
    {
        return this.bots.get(identifier);
    }

    private IRCGPTBot getSelectedBot()
    {
        Runnable r = this.mainPanel.getSelectedItem();
        String botId = r.toString();
        int spaceIndex = botId.indexOf(' ');
        if (spaceIndex >= 0)
            botId = botId.substring(0, spaceIndex);
        return this.bots.get(botId);
    }

    public Collection<IRCGPTBot> getBots()
    {
        return this.bots.values();
    }

    private int getMainListIndex(IRCGPTBot bot)
    {
        for (int i = 0; i<this.mainPanel.getItemCount(); i++)
        {
            Runnable r = this.mainPanel.getItemAt(i);
            if (r != null)
            {
                String botId = r.toString();
                int spaceIndex = botId.indexOf(' ');
                if (spaceIndex >= 0)
                    botId = botId.substring(0, spaceIndex);
                if (botId.equals(bot.getBotId()))
                {
                    return i;
                }
            }
        }
        return -1;
    }

    public synchronized void removeBot(IRCGPTBot bot)
    {
        bot.shutdown();
        if (this.bots.containsKey(bot.getBotId()))
        {
            this.bots.remove(bot.getBotId());
            try
            {
                int idx = getMainListIndex(bot);
                if (idx >= 0)
                {
                    this.mainPanel.removeItem(idx);
                }
            } catch (Exception e) {}
            //IRCGPTBotMain.this.gui.removeWindow(bot);
            //this.listeners.forEach((l) -> l.onBotShutdown(bot));
            Thread x = new Thread(() -> {
                IRCGPTBotMain.instance.listeners.forEach((l) -> l.onBotRemoved(bot));
            });
            x.start();
        }
    }

    public void start()
    {
        if (this.mainThread == null)
        {
            if (settings.has("bots"))
            {
                JSONObject bots = settings.getJSONObject("bots");
                Set<String> botIds = bots.keySet();
                for (String botId : botIds)
                {
                    JSONObject botSettings = bots.getJSONObject(botId);
                    try
                    {
                        this.launchBot(botId, botSettings);
                    } catch (Exception ex) {
                        
                    }
                }
            }
            if (!settings.has("openAiKey"))
            {
                editOpenAiKey();
            }
            this.mainThread = new Thread(this);
            this.mainThread.start();
        }
    }

    public static void log(Exception e)
    {

        String msg = e.getMessage();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos, true, Charset.forName("UTF-8"));
        e.printStackTrace(ps);
        ps.flush();
        logAppend("exceptions.log", msg + "\n" + baos.toString());
    }

    public static synchronized void logAppend(String filename, String text)
    {
        try
        {
            String pattern = "HH:mm:ss yyyy-MM-dd";
            SimpleDateFormat simpleDateFormat = new SimpleDateFormat(pattern);
            File logFile = new File(IRCGPTBotMain.logsFolder, filename);
            File logFileParent = logFile.getParentFile();
            if (!logFileParent.exists())
                logFileParent.mkdirs();
            FileOutputStream logOutputStream = new FileOutputStream(logFile, true);;
            PrintWriter logWriter = new PrintWriter(logOutputStream, true, Charset.forName("UTF-8"));
            logWriter.println("[" + simpleDateFormat.format(new Date(System.currentTimeMillis())) + "] " + text);
            logWriter.flush();
            logWriter.close();
            logOutputStream.close();
        } catch (Exception e) {

        }
    }

    public void saveSettings()
    {
        JSONObject botsJSON = new JSONObject();
        for(IRCGPTBot bot : this.bots.values())
        {
            botsJSON.put(bot.getBotId(), bot.getBotOptions());
        }
        IRCGPTBotMain.settings.put("bots", botsJSON);
        if (IRCGPTBotMain.settings.has("headless"))
            IRCGPTBotMain.settings.remove("headless");
        saveJSONObject(IRCGPTBotMain.settingsFile, IRCGPTBotMain.settings);
    }

    @Override
    public void run() 
    {
        KeyStroke F2 = new KeyStroke(KeyType.F2);
        KeyStroke F4 = new KeyStroke(KeyType.F4);
        KeyStroke F10 = new KeyStroke(KeyType.F10);

        KeyStroke F12 = new KeyStroke(KeyType.F12);
        KeyStroke ESC = new KeyStroke(KeyType.Escape);
        //System.err.println("Main Thread Begin");
        while(IRCGPTBotMain.this.keepRunning)
        {
            try
            {
                if (IRCGPTBotMain.this.gui != null)
                {
                    IRCGPTBotMain.this.gui.updateScreen();
                    KeyStroke keyStroke = IRCGPTBotMain.this.terminal.pollInput();
                    if (keyStroke != null)
                    {
                        if (keyStroke.equals(F12))
                        {
                            shutdown();
                        } else if (keyStroke.equals(F2)) {
                            Thread z = new Thread(() -> {
                                TextInputDialogBuilder mdb = new TextInputDialogBuilder();
                                mdb.setTitle("Bot Identifier (a name to identify the bot by, no spaces)");
                                mdb.setInitialContent("");
                                mdb.setTextBoxSize(new TerminalSize(60, 1));
                                TextInputDialog md = mdb.build();
                                String botId = md.showDialog(IRCGPTBotMain.this.gui);
                                botId = botId.replaceAll(Pattern.quote(" "), "");
                                if (!"".equals(botId) && botId != null)
                                {
                                    IRCGPTBotMain.this.launchBot(botId, new JSONObject());
                                }
                            });
                            z.start();
                        } else if (keyStroke.equals(F10)) {
                            editOpenAiKey();
                        } else if (keyStroke.equals(F4)) {
                            final IRCGPTBot bot = this.getSelectedBot();
                            Thread z = new Thread(() -> {
                                TextInputDialogBuilder mdb = new TextInputDialogBuilder();
                                mdb.setTitle("Enter Bot Identifier to Delete: " + bot.getBotId());
                                mdb.setInitialContent("");
                                mdb.setTextBoxSize(new TerminalSize(60, 1));
                                TextInputDialog md = mdb.build();
                                String botId = md.showDialog(IRCGPTBotMain.this.gui);
                                if (!"".equals(botId) && botId != null)
                                {
                                    if (botId.equals(bot.getBotId()))
                                        this.removeBot(bot);
                                }
                            });
                            z.start();
                            
                        } else if (keyStroke.equals(ESC)) {
                            Window aw = IRCGPTBotMain.this.gui.getActiveWindow();
                            if (aw instanceof IRCGPTBot)
                            {
                                IRCGPTBotMain.this.gui.removeWindow(aw);
                            }
                        } else {
                            IRCGPTBotMain.this.gui.handleInput(keyStroke);
                        }
                    }
                }
                Thread.sleep(100);
            } catch (Exception e) {
                e.printStackTrace(System.err);
                System.exit(0);
            }
        }
        System.exit(0);
    }

    public void editOpenAiKey()
    {
        if (IRCGPTBotMain.this.gui != null)
        {
            Thread z = new Thread(() -> {
                TextInputDialogBuilder mdb = new TextInputDialogBuilder();
                mdb.setTitle("Edit OpenAI Key");
                mdb.setInitialContent(IRCGPTBotMain.settings.optString("openAiKey", ""));
                mdb.setTextBoxSize(new TerminalSize(60, 1));
                TextInputDialog md = mdb.build();
                String openAiKey = md.showDialog(IRCGPTBotMain.this.gui);
                IRCGPTBotMain.settings.put("openAiKey", openAiKey);
                saveSettings();
            });
            z.start();
        } else {
            System.err.println("No OpenAI Key in config file!");
            System.exit(0);
        }
    }

    public void shutdown()
    {
        saveSettings();
        //this.apiServer.setState(false);
        ArrayList<IRCGPTBot> botCol = new ArrayList<IRCGPTBot>(this.bots.values());
        botCol.forEach((bot) -> {
            bot.shutdown();
        });
        System.exit(0);
    }

    public static boolean inJSONArray(JSONArray jsonArray, String str)
    {
        if (str != null && jsonArray != null)
        {
            for(Object s : jsonArray)
            {
                if (str.equals(s))
                    return true;
            }
        }
        return false;
    }

    public static void main(String[] args)
    {
        System.setProperty("org.eclipse.jetty.util.log.class", "org.eclipse.jetty.util.log.StdErrLog");
        System.setProperty("org.eclipse.jetty.LEVEL", "OFF");
        CommandLine cmd = null;
        IRCGPTBotMain.settingsFile = new File(System.getProperty("user.home"),".irc-gpt-bot.json");
        IRCGPTBotMain.settings = migrateConfigIfNeeded(loadJSONObject(settingsFile));
        Options options = new Options();
        CommandLineParser parser = new DefaultParser();
        options.addOption(new Option("?", "help", false, "Shows help"));
        options.addOption(new Option("f", "config", true, "Specify a config file (.json) to use"));
        options.addOption(new Option("g", "gui", false, "Turn on GUI mode"));
        options.addOption(new Option("h", "headless", false, "Turn on Headless mode"));
        options.addOption(new Option("l", "log-output", true, "Specify log output path"));
        try
        {
            cmd = parser.parse(options, args);

            if (cmd.hasOption("?"))
            {
                showHelp(options);
            }
            if (cmd.hasOption("f"))
            {
                IRCGPTBotMain.settingsFile = new File(cmd.getOptionValue("f"));
                IRCGPTBotMain.settings = migrateConfigIfNeeded(loadJSONObject(settingsFile));
            }
            if (cmd.hasOption("l"))
            {
                IRCGPTBotMain.settings.put("logPath", cmd.getOptionValue("l"));
            }
            if (cmd.hasOption("g"))
            {
                IRCGPTBotMain.settings.put("guiMode", true);
            }
            if (cmd.hasOption("h"))
            {
                IRCGPTBotMain.settings.put("headless", true);
            }
            IRCGPTBotMain.chatGPT = new ChatGPT();
            IRCGPTBotMain botMain = new IRCGPTBotMain();
            botMain.start();
        } catch (Throwable e) {
            if (settings.optBoolean("debug", false))
                e.printStackTrace(System.err);
        }
    }
    public static void showHelp(Options options)
    {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp( "irc-gpt-bot", "IRC GPT Bot: An IRC Bot for chatGPT", options, "" );
        System.exit(0);
    }

    public static JSONObject migrateConfigIfNeeded(JSONObject config)
    {
        if (!config.has("bots") && config.has("nickname"))
        {
            JSONObject bots = new JSONObject();
            JSONObject bot = new JSONObject(config.toString());
            if (bot.has("openAiKey"))
                bot.remove("openAiKey");
            if (bot.has("logPath"))
                bot.remove("logPath");
            if (bot.has("channels"))
            {
                JSONObject chanKeys = new JSONObject();
                if (bot.has("channelKeys"))
                {
                    chanKeys = bot.getJSONObject("channelKeys");
                    bot.remove("channelKeys");
                }
                final JSONObject fChanKeys = chanKeys;
                JSONArray chanArray = bot.getJSONArray("channels");
                JSONObject newChanArray = new JSONObject();
                chanArray.forEach((chan) -> {
                    String chanName = (String) chan;
                    JSONObject chObj = new JSONObject();
                    chObj.put("join", true);
                    if (fChanKeys.has(chanName))
                    {
                        chObj.put("key", fChanKeys.optString(chanName));
                    }
                    newChanArray.put(chanName, chObj);
                });
                bot.put("channels", newChanArray);
            }
            bots.put(bot.optString("nickname"), bot);
            Set<String> configKeys = new HashSet<>(config.keySet());
            for (String key : configKeys)
            {
                if (!key.equals("openAiKey") && !key.equals("logPath"))
                {
                    config.remove(key);
                }
            }
            config.put("bots", bots);
        }
        return config;
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

    public static String trimPeriods(String s) 
    {
        // Trim periods from beginning of string
        while (s.startsWith(".") || s.startsWith("?") || s.startsWith("!") || s.startsWith(",") || s.startsWith(":") || s.startsWith(";")) {
            s = s.substring(1);
        }
        
        // Trim periods from end of string
        while (s.endsWith(".") || s.endsWith("?") || s.endsWith("!") || s.endsWith(",") || s.endsWith(":") || s.endsWith(";")) {
            s = s.substring(0, s.length() - 1);
        }
        
        // Return trimmed string
        return s;
    }

    public static String[] getUncommonWords(String sentence) {
        // Remove punctuation and convert to lowercase
        String cleanedSentence = sentence.toLowerCase();
        
        // Split the sentence into individual words
        String[] words = cleanedSentence.split("\\s+");

        for(int i = 0; i < words.length; i++)
        {
            words[i] = trimPeriods(words[i]);
        }
        
        // Load the list of common words to exclude
        List<String> commonWords = Arrays.asList("the", "be", "to", "of", "and", "a", "in", "that", "have", "i", "it", "for", "not", "on", "with", "he", "as", "you", "do", "at", "this", "but", "his", "by", "from", "they", "we", "say", "her", "she", "or", "an", "will", "my", "one", "all", "would", "there", "their", "what", "so", "up", "out", "if", "about", "who", "get", "which", "go", "me", "when", "make", "can", "like", "time", "no", "just", "him", "know", "take", "person", "into", "year", "your", "good", "some", "could", "them", "see", "other", "than", "then", "now", "look", "only", "come", "its", "over", "think", "also", "back", "after", "use", "two", "how", "our", "work", "first", "well", "way", "even", "new", "want", "because", "any", "these", "give", "day", "most", "us", "yes", "no", "maybe");
        
        // Create a list to hold the words we want to keep
        List<String> result = new ArrayList<>();
        
        // Loop through each word in the array and add it to the result list if it's not a common word and not already present
        for (String word : words) {
            if (!commonWords.contains(word) && !result.contains(word)) {
                result.add(word);
            }
        }
        
        // Convert the list to an array and return it
        return result.toArray(new String[0]);
    }
}

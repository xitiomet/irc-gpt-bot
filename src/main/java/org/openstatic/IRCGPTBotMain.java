package org.openstatic;

import org.kitteh.irc.client.library.Client;
import org.kitteh.irc.client.library.Client.Builder;
import org.kitteh.irc.client.library.Client.Builder.Server.SecurityType;
import org.kitteh.irc.client.library.element.Channel;
import org.kitteh.irc.client.library.element.User;
import org.kitteh.irc.client.library.event.channel.ChannelJoinEvent;
import org.kitteh.irc.client.library.event.channel.ChannelMessageEvent;
import org.kitteh.irc.client.library.event.user.PrivateMessageEvent;
import org.kitteh.irc.client.library.exception.KittehNagException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.Date;
import java.util.HashMap;
import java.util.concurrent.Future;
import java.util.function.Consumer;

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
import com.googlecode.lanterna.gui2.WindowManager;
import com.googlecode.lanterna.gui2.dialogs.ListSelectDialog;
import com.googlecode.lanterna.gui2.dialogs.ListSelectDialogBuilder;
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

public class IRCGPTBotMain implements Runnable, Consumer<Exception>
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

    public IRCGPTBotMain(JSONObject settings)
    {
        System.err.println("Starting Bot..");
        this.keepRunning = true;
        this.settings = settings;
        this.logs = new HashMap<String, ChatLog>();
        this.botNickname = settings.optString("nickname");
        this.chatGPT = new ChatGPT(settings.optString("openAiKey"));
        try
        {
            Builder builder = Client.builder();
            builder.listeners().exception(this);
            this.client = builder
                            .nick(botNickname)
                            .server()
                            .port(settings.optInt("port", 6667), SecurityType.INSECURE)
                            .host(settings.optString("server"))
                            .then()
                            .build();
            this.client.setExceptionListener(this);
            this.client.connect();
            this.client.getEventManager().registerEventListener(this);
            this.client.addChannel(settings.optString("channel"));
        } catch (Throwable ne) {
            
        }
        this.mainThread = new Thread(this);
        this.mainThread.start();
    }

    public ChatLog getLog(String target)
    {
        if (this.logs.containsKey(target))
        {
            return this.logs.get(target);
        } else {
            ChatLog cl = new ChatLog();
            this.logs.put(target,cl);
            return cl;
        }
    }

    @Handler
    public void onChannelMessage(ChannelMessageEvent event)
    {
        User sender = event.getActor();
        String body = event.getMessage();
        if (body.contains(this.botNickname))
        {
            Channel channel = event.getChannel();
            String target = channel.getName();
            ChatLog cl = this.getLog(target);
            ChatMessage msg = new ChatMessage(sender.getNick(), channel.getName(), body, new Date(System.currentTimeMillis()));
            cl.add(msg);
            try
            {
                Future<String> gptResponse = chatGPT.callChatGPT(this.botNickname, cl);
                String outText = gptResponse.get().replace("\n", " ").replace("\r", "");
                channel.sendMultiLineMessage(outText);
            } catch (Exception e) {
                e.printStackTrace(System.err);
            }
        }
    }

    @Handler
    public void onPrivateMessage(PrivateMessageEvent event)
    {
        User sender = event.getActor();
        String body = event.getMessage();
        try
        {
            String target = sender.getNick();
            ChatLog cl = this.getLog(target);
            ChatMessage msg = new ChatMessage(sender.getNick(), this.botNickname, body, new Date(System.currentTimeMillis()));
            Future<String> gptResponse = chatGPT.callChatGPT(botNickname, cl);
            String outText = gptResponse.get();
            sender.sendMultiLineMessage(outText);
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
    }

    public static void main(String[] args)
    {
        CommandLine cmd = null;
        JSONObject settings = loadJSONObject(new File("config.json"));
        Options options = new Options();
        CommandLineParser parser = new DefaultParser();
        options.addOption(new Option("?", "help", false, "Shows help"));
        options.addOption(new Option("s", "server", true, "Connect to server"));
        options.addOption(new Option("n", "nickname", true, "Set Bot Nickname"));
        options.addOption(new Option("c", "channel", true, "Connect to server"));
        
        try
        {
            cmd = parser.parse(options, args);

            if (cmd.hasOption("?"))
            {
                showHelp(options);
            }
            if (cmd.hasOption("s"))
            {
                settings.put("server", cmd.getOptionValue("s"));
            }
            if (cmd.hasOption("c"))
            {
                settings.put("channel", cmd.getOptionValue("c"));
            }
            if (cmd.hasOption("n"))
            {
                settings.put("nickname", cmd.getOptionValue("n"));
            }

            IRCGPTBotMain bot = new IRCGPTBotMain(settings);
            
        } catch (Throwable e) {
            
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
                Thread.sleep(1000);
            } catch (Exception e) {
                e.printStackTrace(System.err);
            }
        }
    }

    @Override
    public void accept(Exception t)
    {

    }
}

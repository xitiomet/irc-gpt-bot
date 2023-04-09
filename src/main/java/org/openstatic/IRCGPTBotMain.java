package org.openstatic;

import org.kitteh.irc.client.library.Client;
import org.kitteh.irc.client.library.element.Channel;
import org.kitteh.irc.client.library.element.User;
import org.kitteh.irc.client.library.event.channel.ChannelJoinEvent;
import org.kitteh.irc.client.library.event.channel.ChannelMessageEvent;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.concurrent.Future;

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

public class IRCGPTBotMain
{
    public ChatGPT chatGPT;
    public JSONObject settings;
	private Screen screen;
    private Terminal terminal;
    private MultiWindowTextGUI gui;
    private Client client;

    public IRCGPTBotMain(JSONObject settings)
    {
        this.settings = settings;
        this.chatGPT = new ChatGPT(settings.optString("openAiKey"));
        Client client = Client.builder().nick(settings.optString("nickname")).server().host(settings.optString("server")).then().buildAndConnect();
        client.getEventManager().registerEventListener(this);
        client.addChannel(settings.optString("channel"));
    }

    @Handler
    public void onChannelMessage(ChannelMessageEvent event)
    {
       User sender = event.getActor();
       String body = event.getMessage();
       Channel channel = event.getChannel();
       try
       {
         Future<String> gptResponse = chatGPT.callChatGPT(sender.getNick(), body, null);
         channel.sendMessage(gptResponse.get());
       } catch (Exception e) {
        e.printStackTrace(System.err);
       }
    }

    public static void main(String[] args)
    {
        CommandLine cmd = null;
        Options options = new Options();
        CommandLineParser parser = new DefaultParser();
        options.addOption(new Option("?", "help", false, "Shows help"));
        try
        {
            cmd = parser.parse(options, args);

            if (cmd.hasOption("?"))
            {
                showHelp(options);
            }
        } catch (Exception e) {
            e.printStackTrace(System.err);
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
}

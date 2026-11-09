package org.openstatic;

import java.io.IOException;
import java.util.Arrays;

import org.json.JSONObject;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.CheckBox;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.gui2.dialogs.MessageDialog;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton;

final class TerminalMasterConfig
{
    static void show(IRCGPTBotMain main)
    {
        new Thread(() -> {
            BasicWindow window = new BasicWindow("Master Configuration");
            window.setHints(Arrays.asList(Window.Hint.CENTERED));
            Panel panel = new Panel();
            Label status = new Label("Pricing updates require a preview and confirmation.");
            panel.addComponent(status);
            panel.addComponent(new Button("Replace OpenAI Key", main::editOpenAiKey));
            panel.addComponent(new Button("Web Interface Settings", () -> offInputThread(() -> editApiSettings(main))));
            panel.addComponent(new Button("Open Web Interface", () -> offInputThread(() -> openWebInterface(main))));
            panel.addComponent(new Button("Pricing Status", () -> offInputThread(() -> {
                try
                {
                    MessageDialog.showMessageDialog(main.gui, "Pricing Status",
                        IRCGPTBotMain.usageTracker.pricingStatus().toString(2), MessageDialogButton.OK);
                } catch (IOException | RuntimeException e) {
                    main.log(e);
                    MessageDialog.showMessageDialog(main.gui, "Pricing Status", e.getMessage(), MessageDialogButton.OK);
                }
            })));
            panel.addComponent(new Button("Fetch Pricing Update", () -> offInputThread(() -> {
                status.setText("Fetching official pricing; please wait...");
                try
                {
                    JSONObject preview = IRCGPTBotMain.usageTracker.previewPrices(OpenAIPriceUpdater.fetch());
                    BasicWindow previewWindow = new BasicWindow("Pricing Preview (USD per million tokens)");
                    previewWindow.setHints(Arrays.asList(Window.Hint.CENTERED));
                    Panel previewPanel = new Panel();
                    previewPanel.addComponent(new Label("Review before applying. Custom endpoint rates are preserved."));
                    previewPanel.addComponent(new TextBox(new TerminalSize(65, 14),
                        preview.toString(2), TextBox.Style.MULTI_LINE).setReadOnly(true));
                    previewPanel.addComponent(new Button("Apply", () -> offInputThread(() -> {
                        try
                        {
                            JSONObject result = IRCGPTBotMain.usageTracker.applyPrices(preview.getString("token"));
                            status.setText("Updated " + result.getInt("updated") + " model prices.");
                            previewWindow.close();
                        } catch (IOException | RuntimeException e) {
                            main.log(e);
                            MessageDialog.showMessageDialog(main.gui, "Pricing Update", e.getMessage(), MessageDialogButton.OK);
                        }
                    })));
                    previewPanel.addComponent(new Button("Cancel", previewWindow::close));
                    previewWindow.setComponent(previewPanel);
                    main.gui.addWindowAndWait(previewWindow);
                    if (status.getText().startsWith("Fetching")) status.setText("Pricing preview closed without applying.");
                } catch (IOException | RuntimeException e) {
                    main.log(e);
                    status.setText("Pricing update failed.");
                    MessageDialog.showMessageDialog(main.gui, "Pricing Update", e.getMessage(), MessageDialogButton.OK);
                }
            })));
            panel.addComponent(new Button("Usage Totals", () -> offInputThread(() -> MessageDialog.showMessageDialog(main.gui,
                "Historical Usage", IRCGPTBotMain.usageTracker.totals().toString(2), MessageDialogButton.OK))));
            panel.addComponent(new Button("Close", window::close));
            window.setComponent(panel);
            main.gui.addWindowAndWait(window);
        }, "Terminal master configuration").start();
    }

    private static void editApiSettings(IRCGPTBotMain main)
    {
        BasicWindow window = new BasicWindow("Web Interface Settings");
        window.setHints(Arrays.asList(Window.Hint.CENTERED));
        Panel panel = new Panel();
        CheckBox enabled = new CheckBox("Enable API server and web interface")
            .setChecked(IRCGPTBotMain.settings.optBoolean("apiServer", false));
        TextBox port = new TextBox(new TerminalSize(8, 1), String.valueOf(IRCGPTBotMain.settings.optInt("apiPort", 6553)))
            .setValidationPattern(java.util.regex.Pattern.compile("[0-9]{0,5}"));
        TextBox password = new TextBox(new TerminalSize(38, 1)).setMask('*');
        panel.addComponent(enabled);
        panel.addComponent(new Label("API port"));
        panel.addComponent(port);
        panel.addComponent(new Label("API password (leave blank to keep the saved password)"));
        panel.addComponent(password);
        panel.addComponent(new Button("Save", () -> offInputThread(() -> {
            try
            {
                if (port.getText().isEmpty())
                    throw new IllegalArgumentException("Enter an API port.");
                main.updateApiSettings(enabled.isChecked(), Integer.parseInt(port.getText()), password.getText());
                window.close();
            } catch (IOException | RuntimeException e) {
                main.log(e);
                MessageDialog.showMessageDialog(main.gui, "Web Interface Settings", e.getMessage(), MessageDialogButton.OK);
            }
        })));
        panel.addComponent(new Button("Cancel", window::close));
        window.setComponent(panel);
        main.gui.addWindowAndWait(window);
    }

    private static void openWebInterface(IRCGPTBotMain main)
    {
        if (!main.isApiServerRunning())
        {
            MessageDialog.showMessageDialog(main.gui, "Open Web Interface",
                "The API server is not running. Enable it in Web Interface Settings.", MessageDialogButton.OK);
            return;
        }
        try
        {
            BrowserLauncher.open(main.webInterfaceAddress());
        } catch (IOException | RuntimeException e) {
            main.log(e);
            MessageDialog.showMessageDialog(main.gui, "Open Web Interface", "Unable to open browser at "
                + main.webInterfaceAddress() + ": " + e.getMessage(), MessageDialogButton.OK);
        }
    }

    // Button actions run on the main loop's input thread; blocking dialogs there stop all input processing.
    private static void offInputThread(Runnable action)
    {
        new Thread(action, "Terminal master configuration action").start();
    }
}

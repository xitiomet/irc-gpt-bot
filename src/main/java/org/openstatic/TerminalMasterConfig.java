package org.openstatic;

import java.io.IOException;
import java.util.Arrays;

import org.json.JSONObject;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Button;
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
            panel.addComponent(new Button("Pricing Status", () -> {
                try
                {
                    MessageDialog.showMessageDialog(main.gui, "Pricing Status",
                        IRCGPTBotMain.usageTracker.pricingStatus().toString(2), MessageDialogButton.OK);
                } catch (IOException | RuntimeException e) {
                    main.log(e);
                    MessageDialog.showMessageDialog(main.gui, "Pricing Status", e.getMessage(), MessageDialogButton.OK);
                }
            }));
            panel.addComponent(new Button("Fetch Pricing Update", () -> {
                status.setText("Fetching official pricing; please wait...");
                try
                {
                    main.gui.updateScreen();
                    JSONObject preview = IRCGPTBotMain.usageTracker.previewPrices(OpenAIPriceUpdater.fetch());
                    BasicWindow previewWindow = new BasicWindow("Pricing Preview (USD per million tokens)");
                    previewWindow.setHints(Arrays.asList(Window.Hint.CENTERED));
                    Panel previewPanel = new Panel();
                    previewPanel.addComponent(new Label("Review before applying. Custom endpoint rates are preserved."));
                    previewPanel.addComponent(new TextBox(new TerminalSize(65, 14),
                        preview.toString(2), TextBox.Style.MULTI_LINE).setReadOnly(true));
                    previewPanel.addComponent(new Button("Apply", () -> {
                        try
                        {
                            JSONObject result = IRCGPTBotMain.usageTracker.applyPrices(preview.getString("token"));
                            status.setText("Updated " + result.getInt("updated") + " model prices.");
                            previewWindow.close();
                        } catch (IOException | RuntimeException e) {
                            main.log(e);
                            MessageDialog.showMessageDialog(main.gui, "Pricing Update", e.getMessage(), MessageDialogButton.OK);
                        }
                    }));
                    previewPanel.addComponent(new Button("Cancel", previewWindow::close));
                    previewWindow.setComponent(previewPanel);
                    main.gui.addWindowAndWait(previewWindow);
                    if (status.getText().startsWith("Fetching")) status.setText("Pricing preview closed without applying.");
                } catch (IOException | RuntimeException e) {
                    main.log(e);
                    status.setText("Pricing update failed.");
                    MessageDialog.showMessageDialog(main.gui, "Pricing Update", e.getMessage(), MessageDialogButton.OK);
                }
            }));
            panel.addComponent(new Button("Usage Totals", () -> MessageDialog.showMessageDialog(main.gui, "Historical Usage",
                IRCGPTBotMain.usageTracker.totals().toString(2), MessageDialogButton.OK)));
            panel.addComponent(new Button("Close", window::close));
            window.setComponent(panel);
            main.gui.addWindowAndWait(window);
        }, "Terminal master configuration").start();
    }
}

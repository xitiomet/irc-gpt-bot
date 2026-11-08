package org.openstatic;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import org.json.JSONArray;
import org.json.JSONObject;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.CheckBox;
import com.googlecode.lanterna.gui2.Component;
import com.googlecode.lanterna.gui2.ComboBox;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.GridLayout;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.gui2.dialogs.MessageDialog;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton;
import com.googlecode.lanterna.gui2.dialogs.TextInputDialogBuilder;

final class TerminalBotEditor
{
    final Map<String, TextBox> text = new LinkedHashMap<>();
    final Map<String, CheckBox> checks = new LinkedHashMap<>();
    final Map<String, CheckBox> tools = new LinkedHashMap<>();
    private final JSONObject options;
    private final boolean launching;
    private final String initialState;

    TerminalBotEditor(JSONObject options, String model, String preamble, boolean launching)
    {
        this.options = options;
        this.launching = launching;
        for (String field : BotEditorOptions.TEXT_FIELDS)
            text.put(field, new TextBox(new TerminalSize(38, 1), options.getString(field)));
        for (String field : BotEditorOptions.NUMBER_FIELDS)
            text.put(field, new TextBox(new TerminalSize(12, 1), options.get(field).toString()));
        for (String field : BotEditorOptions.BOOLEAN_FIELDS)
            checks.put(field, new CheckBox(field).setChecked(options.getBoolean(field)));
        for (String field : BotEditorOptions.SECRET_FIELDS)
        {
            text.put(field, new TextBox(new TerminalSize(38, 1)).setMask('*'));
            String clear = "clear" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
            checks.put(clear, new CheckBox("Clear saved " + field));
        }
        text.put("model", new TextBox(new TerminalSize(38, 1), model));
        text.put("preamble", new TextBox(new TerminalSize(60, 12), preamble, TextBox.Style.MULTI_LINE));
        for (String field : new String[] {"ignore", "aliases", "botOps"})
            text.put(field, new TextBox(new TerminalSize(48, 8),
                String.join("\n", options.getJSONArray(field).toList().stream().map(Object::toString).toList()), TextBox.Style.MULTI_LINE));
        for (String name : GPTTools.ALL_TOOLS)
            tools.put(name, new CheckBox(name).setChecked(IRCGPTBotMain.inJSONArray(options.getJSONArray("gptTools"), name)));
        checks.put("reconnect", new CheckBox("Reconnect after saving"));
        initialState = state().toString();
    }

    JSONObject state()
    {
        JSONObject state = new JSONObject();
        text.forEach((field, control) -> {
            if (Arrays.asList(BotEditorOptions.NUMBER_FIELDS).contains(field))
            {
                try { state.put(field, Long.parseLong(control.getText().trim())); }
                catch (NumberFormatException e) { state.put(field, control.getText()); }
            } else if (Arrays.asList("ignore", "aliases", "botOps").contains(field)) {
                state.put(field, new JSONArray(Arrays.stream(control.getText().split("aliases".equals(field) ? "[\\r\\n,]+" : "[\\s,]+"))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty()).distinct().toList()));
            } else {
                state.put(field, control.getText());
            }
        });
        checks.forEach((field, control) -> state.put(field, control.isChecked()));
        JSONArray enabled = new JSONArray();
        tools.forEach((name, control) -> { if (control.isChecked()) enabled.put(name); });
        return state.put("gptTools", enabled);
    }

    static void edit(IRCGPTBot bot)
    {
        new Thread(() -> new TerminalBotEditor(bot.getEditorOptions(), bot.getModel(), bot.getPreamble(), false)
            .show(IRCGPTBotMain.instance.gui, bot.getBotId(), state -> {
                try { bot.updateEditorOptions(state); }
                catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
            }), "Terminal bot editor").start();
    }

    static void launch(IRCGPTBotMain main)
    {
        new Thread(() -> {
            String id = new TextInputDialogBuilder().setTitle("Launch Bot")
                .setDescription("Bot identifier (also used for its configuration filename)")
                .setTextBoxSize(new TerminalSize(48, 1)).build().showDialog(main.gui);
            if (id == null) return;
            try
            {
                ConfigDirectory.validateBotId(id);
                new TerminalBotEditor(BotEditorOptions.defaults(id, true), "", "", true)
                    .show(main.gui, id, state -> {
                        JSONObject configured = IRCGPTBot.validateEditorUpdate(state);
                        for (String field : new java.util.ArrayList<>(configured.keySet()))
                            if (configured.isNull(field)) configured.remove(field);
                        main.launchBot(id, configured);
                    });
            } catch (RuntimeException e) {
                main.log(e);
                MessageDialog.showMessageDialog(main.gui, "Launch Bot", e.getMessage(), MessageDialogButton.OK);
            }
        }, "Terminal launch editor").start();
    }

    void show(MultiWindowTextGUI gui, String id, Consumer<JSONObject> save)
    {
        BasicWindow window = new BasicWindow((launching ? "Launch " : "Edit ") + id);
        window.setHints(Arrays.asList(Window.Hint.CENTERED));
        Panel menu = new Panel(new LinearLayout(Direction.VERTICAL));
        menu.addComponent(new Label("Edit sections, then Save. Cancel discards all changes."));
        menu.addComponent(new Button("Model / Prompt", () -> {
            Panel panel = new Panel(new LinearLayout(Direction.VERTICAL));
            panel.addComponent(new Label("Model ID (select below or enter explicitly)"));
            panel.addComponent(text.get("model"));
            Label modelStatus = new Label("");
            Button loadModels = new Button("Load available models");
            loadModels.addListener(button -> {
                JSONObject modelOptions = new JSONObject();
                for (String field : new String[] {"modelsApiUrl", "responsesApiUrl"})
                    if (!text.get(field).getText().isBlank()) modelOptions.put(field, text.get(field).getText().trim());
                String current = text.get("model").getText();
                loadModels.setEnabled(false);
                modelStatus.setText("Loading models... You can keep editing or close this section.");
                Thread loader = new Thread(() -> {
                    try
                    {
                        JSONArray available = IRCGPTBotMain.chatGPT.fetchModels(modelOptions);
                        synchronized (gui)
                        {
                            if (panel.getTextGUI() == null) return;
                            if (available.isEmpty())
                            {
                                modelStatus.setText("No models returned. Check the key / endpoint or enter a model ID.");
                                return;
                            }
                            if (!current.isBlank() && !IRCGPTBotMain.inJSONArray(available, current)) available.put(current);
                            BasicWindow picker = new BasicWindow("Select Model");
                            picker.setHints(Arrays.asList(Window.Hint.CENTERED));
                            Panel choices = new Panel(new LinearLayout(Direction.VERTICAL));
                            ComboBox<String> models = new ComboBox<>();
                            for (Object model : available) models.addItem(model.toString());
                            if (!current.isBlank()) models.setSelectedItem(current);
                            choices.addComponent(models);
                            choices.addComponent(new Button("Use selected model", () -> {
                                text.get("model").setText(models.getSelectedItem());
                                picker.close();
                            }));
                            choices.addComponent(new Button("Cancel", picker::close));
                            picker.setComponent(choices);
                            modelStatus.setText("Models loaded.");
                            gui.addWindow(picker);
                        }
                    } catch (java.io.IOException | RuntimeException e) {
                        IRCGPTBotMain.log(e);
                        if (panel.getTextGUI() != null) modelStatus.setText("Unable to load models: " + e.getMessage());
                    } finally {
                        loadModels.setEnabled(true);
                    }
                }, "Terminal model loader");
                loader.setDaemon(true);
                loader.start();
            });
            panel.addComponent(loadModels);
            panel.addComponent(modelStatus);
            panel.addComponent(new Label("System prompt"));
            panel.addComponent(text.get("preamble"));
            section(gui, "Model / Prompt", panel);
        }));
        menu.addComponent(new Button("IRC Connection", () -> {
            Panel panel = fields("server", "port", "nickname", "user", "realName", "operatorName");
            panel.addComponent(new Label("TLS")); panel.addComponent(checks.get("secure"));
            for (String field : BotEditorOptions.SECRET_FIELDS)
            {
                String has = "has" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
                panel.addComponent(new Label(field + (options.optBoolean(has) ? " (saved)" : " (none)")));
                panel.addComponent(text.get(field));
                panel.addComponent(new Label("Blank keeps saved password"));
                panel.addComponent(checks.get("clear" + Character.toUpperCase(field.charAt(0)) + field.substring(1)));
            }
            panel.addComponent(new Label("Blank USER / real name uses nickname"));
            panel.addComponent(new Label("Connection changes need a reconnect."));
            section(gui, "IRC Connection", panel);
        }));
        menu.addComponent(new Button("Behavior / Enable", () -> {
            Panel panel = new Panel(new LinearLayout(Direction.VERTICAL));
            for (String field : BotEditorOptions.BOOLEAN_FIELDS)
                if (!"secure".equals(field)) panel.addComponent(checks.get(field));
            section(gui, "Behavior", panel);
        }));
        menu.addComponent(new Button("Built-in GPT Tools", () -> {
            Panel panel = new Panel(new LinearLayout(Direction.VERTICAL));
            Panel buttons = new Panel(new LinearLayout(Direction.HORIZONTAL));
            buttons.addComponent(new Button("Enable all", () -> tools.values().forEach(control -> control.setChecked(true))));
            buttons.addComponent(new Button("Disable all", () -> tools.values().forEach(control -> control.setChecked(false))));
            panel.addComponent(buttons);
            Panel toolGrid = new Panel(new GridLayout(2));
            tools.values().forEach(toolGrid::addComponent);
            panel.addComponent(toolGrid);
            section(gui, "Built-in GPT Tools", panel);
        }));
        for (String field : new String[] {"ignore", "aliases", "botOps"})
            menu.addComponent(new Button("Edit " + field, () -> {
                Panel panel = new Panel(new LinearLayout(Direction.VERTICAL));
                panel.addComponent(new Label("aliases".equals(field) ? "Separate with new lines / commas. Spaces stay in the alias."
                    : "One nickname per line, or separate with spaces / commas."));
                panel.addComponent(text.get(field));
                section(gui, field, panel);
            }));
        menu.addComponent(new Button("Timing / Context", () -> section(gui, "Timing / Context", fields(BotEditorOptions.NUMBER_FIELDS))));
        menu.addComponent(new Button("AI Endpoints", () -> {
            Panel panel = fields("definitionServer", "modelsApiUrl", "responsesApiUrl");
            panel.addComponent(new Label("Blank values inherit the master configuration."));
            panel.addComponent(new Label(""));
            section(gui, "AI Endpoints", panel);
        }));
        if (!launching) menu.addComponent(checks.get("reconnect"));
        Panel buttons = new Panel(new LinearLayout(Direction.HORIZONTAL));
        buttons.addComponent(new Button(launching ? "Launch" : "Save", () -> {
            try
            {
                JSONObject state = state();
                IRCGPTBot.validateEditorUpdate(state);
                save.accept(state);
                window.close();
            } catch (RuntimeException e) {
                IRCGPTBotMain.instance.log(e);
                MessageDialog.showMessageDialog(gui, "Bot settings", "Unable to save: " + e.getMessage(), MessageDialogButton.OK);
            }
        }));
        buttons.addComponent(new Button("Cancel", () -> {
            if (new JSONObject(initialState).similar(state()) || MessageDialog.showMessageDialog(gui, "Discard changes",
                "Discard unsaved bot settings?", MessageDialogButton.Yes, MessageDialogButton.No) == MessageDialogButton.Yes)
                window.close();
        }));
        menu.addComponent(buttons);
        window.setComponent(menu);
        gui.addWindowAndWait(window);
    }

    private Panel fields(String... names)
    {
        Panel panel = new Panel(new GridLayout(2));
        for (String name : names)
        {
            panel.addComponent(new Label(name));
            panel.addComponent(text.get(name));
        }
        return panel;
    }

    private static void section(MultiWindowTextGUI gui, String title, Panel panel)
    {
        BasicWindow window = new BasicWindow(title) {
            @Override
            public void close()
            {
                super.close();
                detach(panel);
            }
        };
        panel.addComponent(new Button("Done", window::close));
        window.setHints(Arrays.asList(Window.Hint.CENTERED));
        window.setComponent(panel);
        gui.addWindow(window);
    }

    private static void detach(Panel panel)
    {
        for (Component child : panel.getChildren())
            if (child instanceof Panel) detach((Panel) child);
        panel.removeAllComponents();
    }
}

package org.openstatic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import org.json.JSONObject;

final class ConfigDirectory
{
    final Path directory;
    private final Set<String> savedBots = new HashSet<>();

    ConfigDirectory(Path directory)
    {
        this.directory = directory.toAbsolutePath();
    }

    static ConfigDirectory open(Path location) throws IOException
    {
        if (!location.toString().endsWith(".json") && !Files.isRegularFile(location))
            return new ConfigDirectory(location);
        Path absolute = location.toAbsolutePath();
        String filename = absolute.getFileName().toString();
        return new ConfigDirectory(absolute.resolveSibling(filename.replaceFirst("\\.json$", "") + ".d"));
    }

    static void validateBotId(String id)
    {
        if (id == null || !id.matches("[A-Za-z0-9_\\-\\[\\]{}^`.]+") || id.startsWith(".")
            || Set.of("irc-gpt-bot", "pricing", "usage").contains(id.toLowerCase(Locale.ROOT))
            || id.toLowerCase(Locale.ROOT).matches("(con|prn|aux|nul)(\\..*)?")
            || id.toLowerCase(Locale.ROOT).matches("(com|lpt)[1-9](\\..*)?") || id.endsWith("."))
            throw new IllegalArgumentException("Invalid or reserved bot identifier: " + id);
    }

    synchronized JSONObject load(Path legacy) throws IOException
    {
        Path root = directory.resolve("irc-gpt-bot.json");
        JSONObject settings;
        if (Files.exists(root))
            settings = IRCGPTBotMain.migrateConfigIfNeeded(JsonFiles.read(root));
        else if (legacy != null && Files.exists(legacy))
        {
            settings = IRCGPTBotMain.migrateConfigIfNeeded(JsonFiles.read(legacy));
            if (!settings.has("logPath"))
                settings.put("logPath", legacy.toAbsolutePath().getParent().resolve("irc-gpt-bot-logs").toString());
        }
        else
            settings = new JSONObject();
        JSONObject bots = settings.has("bots") ? settings.getJSONObject("bots") : new JSONObject();
        Set<String> names = new HashSet<>();
        for (String id : bots.keySet())
        {
            validateBotId(id);
            if (!names.add(id.toLowerCase(Locale.ROOT)))
                throw new IOException("Bot identifiers collide on case-insensitive filesystems: " + id);
        }
        if (Files.isDirectory(directory))
        {
            try (Stream<Path> paths = Files.list(directory))
            {
                for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList())
                {
                    String filename = path.getFileName().toString();
                    if ("irc-gpt-bot.json".equals(filename) || "pricing.json".equals(filename))
                        continue;
                    String id = filename.substring(0, filename.length() - 5);
                    validateBotId(id);
                    JSONObject bot = JsonFiles.read(path);
                    if (bots.has(id))
                    {
                        if (!bots.getJSONObject(id).similar(bot))
                            throw new IOException("Conflicting inline and file configuration for bot " + id);
                    } else {
                        if (!names.add(id.toLowerCase(Locale.ROOT)))
                            throw new IOException("Bot identifiers collide on case-insensitive filesystems: " + id);
                        bots.put(id, bot);
                    }
                    savedBots.add(id);
                }
            }
        }
        settings.put("bots", bots);
        if (Files.exists(root))
        {
            JSONObject original = JsonFiles.read(root);
            if ((original.has("bots") || original.has("nickname")) && !Files.exists(root.resolveSibling("irc-gpt-bot.json.bak")))
                Files.copy(root, root.resolveSibling("irc-gpt-bot.json.bak"));
        }
        save(settings);
        return settings;
    }

    synchronized void save(JSONObject settings) throws IOException
    {
        JSONObject bots = settings.getJSONObject("bots");
        Set<String> names = new HashSet<>();
        for (String id : bots.keySet())
        {
            validateBotId(id);
            if (!names.add(id.toLowerCase(Locale.ROOT)))
                throw new IOException("Bot identifiers collide on case-insensitive filesystems: " + id);
            Path file = directory.resolve(id + ".json");
            if (!savedBots.contains(id) && Files.exists(file) && !JsonFiles.read(file).similar(bots.getJSONObject(id)))
                throw new IOException("Refusing to overwrite existing bot configuration: " + file);
        }
        for (String id : bots.keySet())
        {
            JsonFiles.write(directory.resolve(id + ".json"), bots.getJSONObject(id));
            savedBots.add(id);
        }
        for (String id : new HashSet<>(savedBots))
        {
            if (!bots.has(id))
            {
                Files.deleteIfExists(directory.resolve(id + ".json"));
                savedBots.remove(id);
            }
        }
        JSONObject root = new JSONObject(settings.toString());
        root.remove("bots");
        root.remove("headless");
        JsonFiles.write(directory.resolve("irc-gpt-bot.json"), root);
    }
}

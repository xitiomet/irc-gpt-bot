package org.openstatic;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

final class ChannelHistory
{
    static final String MARKER = "(CHANNEL HISTORY) ";
    static final int MAX_MESSAGES = 10000;
    private static final int MAX_LOG_BYTES = 16 * 1024 * 1024;
    private static final Pattern ENTRY = Pattern.compile("^\\[(\\d{2}:\\d{2}:\\d{2} \\d{4}-\\d{2}-\\d{2})\\] (.*)$");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss yyyy-MM-dd");
    private final Map<String, Day> days = new HashMap<>();
    private final LocalDateTime started = LocalDateTime.now().withNano(0);
    private long sequence;

    private static final class Day
    {
        LocalDate date;
        JSONArray messages = new JSONArray();
        boolean truncated;
        boolean writable;
        String error;
        Day(LocalDate date) { this.date = date; }
    }

    private Day day(IRCGPTBot bot, String channel)
    {
        String key = channel.toLowerCase(java.util.Locale.ROOT);
        LocalDate today = LocalDate.now();
        Day day = days.get(key);
        if (day != null && day.date.equals(today)) return day;
        day = new Day(today);
        days.put(key, day);
        try
        {
            Path base = bot.getLogFolder().toPath().toRealPath();
            Path file = base.resolve(channel + ".log").normalize();
            if (!file.getParent().equals(base) || Files.isSymbolicLink(file))
                throw new IOException("Unsafe channel log path");
            boolean exists = Files.exists(file, LinkOption.NOFOLLOW_LINKS);
            if (exists && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Channel log is not a regular file");
            day.writable = true;
            if (!exists) return day;
            try (FileChannel input = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
            {
                long offset = Math.max(0, input.size() - MAX_LOG_BYTES);
                day.truncated = offset > 0;
                input.position(offset);
                ByteBuffer bytes = ByteBuffer.allocate((int) (input.size() - offset));
                while (bytes.hasRemaining() && input.read(bytes) != -1) {}
                bytes.flip();
                String contents = StandardCharsets.UTF_8.decode(bytes).toString();
                if (offset > 0)
                {
                    int newline = contents.indexOf('\n');
                    contents = newline < 0 ? "" : contents.substring(newline + 1);
                }
                restore(day, contents);
            }
        } catch (IOException | RuntimeException e) {
            day.error = "Unable to restore channel history: " + e.getMessage();
            bot.log(e);
        }
        return day;
    }

    private void restore(Day day, String contents)
    {
        boolean structured = false;
        JSONObject previous = null;
        for (String line : contents.split("\\r?\\n"))
        {
            Matcher entry = ENTRY.matcher(line);
            if (!entry.matches())
            {
                if (!structured && previous != null)
                    previous.put("body", previous.getString("body") + "\n" + line);
                continue;
            }
            previous = null;
            LocalDateTime time = LocalDateTime.parse(entry.group(1), TIME);
            if (!time.toLocalDate().equals(day.date)) continue;
            String text = entry.group(2);
            if (text.startsWith(MARKER))
            {
                structured = true;
                JSONObject message = new JSONObject(text.substring(MARKER.length()));
                previous = append(day, message.getString("sender"), message.getString("body"), message.getLong("timestamp"));
                continue;
            }
            if (structured || !time.isBefore(started) || text.startsWith("(OQ") || text.startsWith("(BOT)")) continue;
            if (text.startsWith("("))
            {
                int end = text.indexOf(") ");
                if (end < 0) continue;
                text = text.substring(end + 2);
            }
            int colon = text.indexOf(": ");
            if (colon <= 0 || text.substring(0, colon).chars().anyMatch(Character::isWhitespace)) continue;
            previous = append(day, text.substring(0, colon), text.substring(colon + 2),
                time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        }
    }

    private JSONObject append(Day day, String sender, String body, long timestamp)
    {
        JSONObject message = new JSONObject().put("id", ++sequence).put("sender", sender).put("body", body).put("timestamp", timestamp);
        day.messages.put(message);
        if (day.messages.length() > MAX_MESSAGES)
        {
            day.messages.remove(0);
            day.truncated = true;
        }
        return message;
    }

    synchronized void record(IRCGPTBot bot, String channel, String sender, String body)
    {
        Day day = day(bot, channel);
        JSONObject message = append(day, sender, body, System.currentTimeMillis());
        if (day.writable) bot.logAppend(channel + ".log", MARKER + message);
    }

    synchronized JSONObject snapshot(IRCGPTBot bot, String channel)
    {
        Day day = day(bot, channel);
        JSONObject result = new JSONObject().put("messages", new JSONArray(day.messages.toString()))
            .put("historyDate", day.date.toString()).put("historyTruncated", day.truncated);
        if (day.error != null) result.put("historyError", day.error);
        return result;
    }
}

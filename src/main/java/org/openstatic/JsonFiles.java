package org.openstatic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.json.JSONException;
import org.json.JSONObject;

final class JsonFiles
{
    static JSONObject read(Path path) throws IOException
    {
        try
        {
            return new JSONObject(Files.readString(path, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException("Invalid JSON in " + path, e);
        }
    }

    static void write(Path path, JSONObject data) throws IOException
    {
        path = path.toAbsolutePath();
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "." + path.getFileName(), ".tmp");
        try
        {
            Files.writeString(temporary, data.toString(2) + "\n", StandardCharsets.UTF_8);
            try
            {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}

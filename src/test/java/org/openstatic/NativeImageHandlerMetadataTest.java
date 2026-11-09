package org.openstatic;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import net.engio.mbassy.listener.Handler;

/** IRC event handlers are invoked reflectively, so native builds fail at runtime unless each one is registered. */
class NativeImageHandlerMetadataTest
{
    @Test
    void everyIrcEventHandlerIsRegisteredForReflection() throws Exception
    {
        Path metadata = Path.of("src", "main", "resources", "META-INF", "native-image", "reachability-metadata.json");
        JSONArray reflection = new JSONObject(Files.readString(metadata, StandardCharsets.UTF_8)).getJSONArray("reflection");
        for (Class<?> type : new Class<?>[] { IRCGPTBot.class, GPTTools.ListCollector.class })
        {
            JSONObject entry = null;
            for (Object value : reflection)
            {
                if (type.getName().equals(((JSONObject) value).optString("type")))
                    entry = (JSONObject) value;
            }
            assertNotNull(entry, type.getName() + " missing from reachability-metadata.json");
            for (Method method : type.getDeclaredMethods())
            {
                if (!method.isAnnotationPresent(Handler.class))
                    continue;
                boolean registered = false;
                for (Object value : entry.optJSONArray("methods", new JSONArray()))
                {
                    JSONObject registeredMethod = (JSONObject) value;
                    JSONArray parameters = registeredMethod.getJSONArray("parameterTypes");
                    registered |= method.getName().equals(registeredMethod.getString("name"))
                        && parameters.length() == method.getParameterCount()
                        && (parameters.isEmpty() || method.getParameterTypes()[0].getName().equals(parameters.getString(0)));
                }
                assertTrue(registered, type.getSimpleName() + "." + method.getName()
                    + " is a @Handler but is not registered in reachability-metadata.json");
            }
        }
    }
}

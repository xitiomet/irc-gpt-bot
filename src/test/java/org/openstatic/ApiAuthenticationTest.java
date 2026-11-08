package org.openstatic;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;

import jakarta.servlet.http.HttpServletRequest;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApiAuthenticationTest
{
    private JSONObject previousSettings;
    private final APIWebServer.ApiServlet servlet = new APIWebServer.ApiServlet();

    @BeforeEach
    void setup()
    {
        previousSettings = IRCGPTBotMain.settings;
        IRCGPTBotMain.settings = new JSONObject().put("apiPassword", "test-password");
    }

    @AfterEach
    void cleanup()
    {
        IRCGPTBotMain.settings = previousSettings;
    }

    private static HttpServletRequest request(String header, String query)
    {
        return (HttpServletRequest) Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),
            new Class<?>[] {HttpServletRequest.class}, (proxy, method, arguments) -> {
                if ("getHeader".equals(method.getName()) && "X-API-Password".equals(arguments[0]))
                    return header;
                if ("getParameter".equals(method.getName()) && "apiPassword".equals(arguments[0]))
                    return query;
                throw new UnsupportedOperationException(method.getName());
            });
    }

    @Test
    void authenticatesGetAndPostWithHeaderWithoutUrlOrBodyCredentials()
    {
        assertTrue(servlet.isAuthenticated(request("test-password", null), null));
        assertTrue(servlet.isAuthenticated(request("test-password", null), new JSONObject()));
    }

    @Test
    void preservesLegacyQueryAndJsonBodyAuthentication()
    {
        assertTrue(servlet.isAuthenticated(request(null, "test-password"), null));
        assertTrue(servlet.isAuthenticated(request(null, null), new JSONObject().put("apiPassword", "test-password")));
    }

    @Test
    void suppliedHeaderTakesPrecedenceOverLegacyCredentials()
    {
        assertFalse(servlet.isAuthenticated(request("wrong", "test-password"), null));
        assertFalse(servlet.isAuthenticated(request("wrong", null), new JSONObject().put("apiPassword", "test-password")));
        assertTrue(servlet.isAuthenticated(request("test-password", "wrong"), null));
        assertTrue(servlet.isAuthenticated(request("test-password", null), new JSONObject().put("apiPassword", "wrong")));
    }

    @Test
    void rejectsMissingAndIncorrectCredentials()
    {
        assertFalse(servlet.isAuthenticated(request(null, null), null));
        assertFalse(servlet.isAuthenticated(request(null, null), new JSONObject()));
        assertFalse(servlet.isAuthenticated(request("", null), new JSONObject()));
        assertFalse(servlet.isAuthenticated(request("wrong", null), null));
    }
}

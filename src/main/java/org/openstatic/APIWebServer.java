package org.openstatic;

import org.json.*;

import java.io.IOException;
import java.io.BufferedReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.Future;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.server.Server;

import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.servlet.ServletContextHandler;

public class APIWebServer implements IRCGPTBotListener
{
    private Server httpServer;
    protected ArrayList<WebSocketSession> wsSessions;
    protected HashMap<WebSocketSession, JSONObject> sessionProps;

    protected static APIWebServer instance;
    private String staticRoot;

    public APIWebServer()
    {
        APIWebServer.instance = this;
        this.wsSessions = new ArrayList<WebSocketSession>();
        this.sessionProps = new HashMap<WebSocketSession, JSONObject>();
        httpServer = new Server(6553);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        context.setContextPath("/");
        context.addServlet(ApiServlet.class, "/ircgptbot/api/*");
        context.addServlet(EventsWebSocketServlet.class, "/ircgptbot/*");
        try {
            /*
            DefaultServlet assetServlet = new DefaultServlet();
            ServletHolder holderAssets = new ServletHolder("assets", assetServlet);
            String assetsPath = MidiTools.getAssetFolder().toPath().toUri().toASCIIString();
            System.err.println("Assets Path: " + assetsPath);
            holderAssets.setInitParameter("resourceBase", assetsPath);
            holderAssets.setInitParameter("dirAllowed","true");
            holderAssets.setInitParameter("pathInfoOnly","true");
            context.addServlet(holderAssets, "/assets/*");
            */

            URL url = APIWebServer.class.getResource("/irc-gpt-bot/index.html");
            this.staticRoot = url.toString().replaceAll("index.html", "");
            DefaultServlet defaultServlet = new DefaultServlet();
            ServletHolder holderPwd = new ServletHolder("default", defaultServlet);
            holderPwd.setInitParameter("resourceBase", this.staticRoot);
            context.addServlet(holderPwd, "/*");
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
        httpServer.setHandler(context);
    }

    public void shareBots(WebSocketSession session)
    {
        IRCGPTBotMain.instance.getBots().forEach((bot) -> {
            JSONObject msg = new JSONObject();
            msg.put("type", "bot");
            msg.put("action", "added");
            msg.put("id", bot.getBotId());
            msg.put("stats", bot.toJSONObject());
            msg.put("preamble", bot.getPreamble());
            session.getRemote().sendStringByFuture(msg.toString());
        });
    }

    public void handleWebSocketEvent(JSONObject j, WebSocketSession session) 
    {
        JSONObject sessionProperties = this.sessionProps.get(session);
        if (j.has("command"))
        {
            String command = j.optString("command", "NOOP");
            if (sessionProperties.optBoolean("auth", false))
            {
                if (command.equals("shutdown"))
                {
                    String objectId = j.optString("id", null);
                    if (objectId != null)
                    {
                        IRCGPTBot bot = IRCGPTBotMain.instance.getBotbyIdentifier(objectId);
                        bot.shutdown();
                    }
                }
                if (command.equals("remove"))
                {
                    String objectId = j.optString("id", null);
                    if (objectId != null)
                    {
                        IRCGPTBot bot = IRCGPTBotMain.instance.getBotbyIdentifier(objectId);
                        IRCGPTBotMain.instance.removeBot(bot);
                    }
                }
                if (command.equals("reconnect"))
                {
                    String objectId = j.optString("id", null);
                    if (objectId != null)
                    {
                        IRCGPTBot bot = IRCGPTBotMain.instance.getBotbyIdentifier(objectId);
                        bot.connect();
                    }
                }
                if (command.equals("preamble"))
                {
                    String objectId = j.optString("id", null);
                    if (objectId != null && j.has("preamble"))
                    {
                        IRCGPTBotMain.instance.getBotbyIdentifier(objectId).setPreamble(j.optString("preamble", ""));
                    }
                }
                if (command.equals("launch") && j.has("botOptions") && j.has("id"))
                {
                    JSONObject botOptions = j.getJSONObject("botOptions");
                    String botId = j.optString("id");
                    try
                    {
                        IRCGPTBotMain.instance.launchBot(botId, botOptions);
                    } catch (Exception e) {
                        IRCGPTBotMain.log(e);
                    }
                }
            } else {
                if (command.equals("auth") && j.has("password"))
                {
                    try
                    {
                        String password = j.optString("password");
                        if (!password.equals(IRCGPTBotMain.settings.optString("apiPassword")))
                        {
                            JSONObject authObj = new JSONObject();
                            authObj.put("type", "user");
                            authObj.put("action", "authfail");
                            authObj.put("error", "Invalid Password!");
                            session.getRemote().sendStringByFuture(authObj.toString());
                        } else {
                            sessionProperties.put("auth", true);
                            JSONObject authObj = new JSONObject();
                            authObj.put("type", "user");
                            authObj.put("action", "authok");
                            session.getRemote().sendStringByFuture(authObj.toString());
                            shareBots(session);
                        }
                    } catch (Exception e) {
                        JSONObject authObj = new JSONObject();
                        authObj.put("type", "user");
                        authObj.put("action", "authfail");
                        authObj.put("error", e.getLocalizedMessage());
                        session.getRemote().sendStringByFuture(authObj.toString());
                    }
                }
            }
        }
        this.sessionProps.put(session, sessionProperties);
    }

    public void setState(boolean b) {
        if (b) {
            try {
                httpServer.start();
            } catch (Exception e) {
                e.printStackTrace(System.err);
            }
        } else {
            try {
                httpServer.stop();
            } catch (Exception e) {
                e.printStackTrace(System.err);
            }
        }
    }

    public void broadcastJSONObject(JSONObject jo) 
    {
        String message = jo.toString();
        for (Session s : this.wsSessions) {
            try {
                JSONObject sessionProps = this.sessionProps.get(s);
                if (sessionProps.optBoolean("auth", false))
                {
                    s.getRemote().sendStringByFuture(message);
                }
            } catch (Exception e) {

            }
        }
    }

    public static class EventsWebSocketServlet extends WebSocketServlet {
        @Override
        public void configure(WebSocketServletFactory factory) {
            // factory.getPolicy().setIdleTimeout(10000);
            factory.register(EventsWebSocket.class);
        }
    }

    @WebSocket
    public static class EventsWebSocket {

        @OnWebSocketMessage
        public void onText(Session session, String message) throws IOException {
            try {
                JSONObject jo = new JSONObject(message);
                if (session instanceof WebSocketSession) {
                    WebSocketSession wssession = (WebSocketSession) session;
                    APIWebServer.instance.handleWebSocketEvent(jo, wssession);
                } else {
                    //System.err.println("not instance of WebSocketSession");
                }
            } catch (Exception e) {
                //e.printStackTrace(System.err);
            }
        }

        @OnWebSocketConnect
        public void onConnect(Session session) throws IOException {
            //System.err.println("@OnWebSocketConnect");
            if (session instanceof WebSocketSession) {
                WebSocketSession wssession = (WebSocketSession) session;
                //System.out.println(wssession.getRemoteAddress().getHostString() + " connected!");
                APIWebServer.instance.wsSessions.add(wssession);
                APIWebServer.instance.sessionProps.put(wssession, new JSONObject());
            } else {
                //System.err.println("Not an instance of WebSocketSession");
            }
        }

        @OnWebSocketClose
        public void onClose(Session session, int status, String reason) {
            if (session instanceof WebSocketSession) {
                WebSocketSession wssession = (WebSocketSession) session;
                APIWebServer.instance.wsSessions.remove(wssession);
                APIWebServer.instance.sessionProps.remove(wssession);
            }
        }

    }

    public static class ApiServlet extends HttpServlet {
        public JSONObject readJSONObjectPOST(HttpServletRequest request) {
            StringBuffer jb = new StringBuffer();
            String line = null;
            try {
                BufferedReader reader = request.getReader();
                while ((line = reader.readLine()) != null) {
                    jb.append(line);
                }
            } catch (Exception e) {
                //e.printStackTrace(System.err);
            }

            try {
                JSONObject jsonObject = new JSONObject(jb.toString());
                return jsonObject;
            } catch (JSONException e) {
                e.printStackTrace(System.err);
                return new JSONObject();
            }
        }

        public boolean isNumber(String v) {
            try {
                Integer.parseInt(v);
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        @Override
        protected void doPost(HttpServletRequest request, HttpServletResponse httpServletResponse)
                throws ServletException, IOException {
                    httpServletResponse.setContentType("text/javascript");
                    httpServletResponse.setStatus(HttpServletResponse.SC_OK);
                    httpServletResponse.setCharacterEncoding("iso-8859-1");
                    String target = request.getPathInfo();
                    //System.err.println("Path: " + target);
                    JSONObject response = new JSONObject();
                    try {
                        if ("/bots/add/".equals(target)) {
                            JSONObject requestPost = readJSONObjectPOST(request);
                            if (requestPost.has("id") && requestPost.has("botOptions") && requestPost.has("apiPassword"))
                            {
                                if (requestPost.optString("apiPassword", "").equals(IRCGPTBotMain.settings.optString("apiPassword")))
                                {
                                    IRCGPTBot bot = IRCGPTBotMain.instance.launchBot(requestPost.optString("id"), requestPost.optJSONObject("botOptions"));
                                    response.put("bot", bot.toJSONObject());
                                } else {
                                    response.put("error","Invalid apiPassword");
                                }
                            }
                        }
                    } catch (Exception x) {
                        x.printStackTrace(System.err);
                    }
                    httpServletResponse.getWriter().println(response.toString());
        }

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse httpServletResponse)
                throws ServletException, IOException {
            httpServletResponse.setContentType("text/javascript");
            httpServletResponse.setStatus(HttpServletResponse.SC_OK);
            httpServletResponse.setCharacterEncoding("iso-8859-1");
            String target = request.getPathInfo();
            //System.err.println("Path: " + target);
            Set<String> parameterNames = request.getParameterMap().keySet();
            JSONObject response = new JSONObject();
            if (IRCGPTBotMain.settings.optString("apiPassword","").equals(request.getParameter("apiPassword")))
            {
                try {
                    if ("/bots/".equals(target)) {
                        JSONObject bots = new JSONObject();
                        IRCGPTBotMain.instance.getBots().forEach((bot) -> {
                            bots.put(bot.getBotId(), bot.toJSONObject());
                        });
                        response.put("bots", bots);
                    } else if (target.startsWith("/bot/")) {
                        target = target.substring(5);
                        StringTokenizer st = new StringTokenizer(target, "/", false);
                        if (st.hasMoreTokens())
                        {
                            String objectId = st.nextToken();
                            IRCGPTBot bot = IRCGPTBotMain.instance.getBotbyIdentifier(objectId);
                            if (bot != null)
                            {
                                response.put("bot", bot.toJSONObject());
                                if (st.hasMoreTokens())
                                {
                                    String command = st.nextToken();
                                    if ("privmsg".equals(command) && parameterNames.contains("message") && parameterNames.contains("to"))
                                    {
                                        String message = request.getParameter("message");
                                        String to = request.getParameter("to");
                                        bot.sendPrivateMessage(to, message);
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception x) {
                    x.printStackTrace(System.err);
                }
            } else {
                response.put("error", "Invalid apiPassword!");
            }
            httpServletResponse.getWriter().println(response.toString());
            // request.setHandled(true);
        }
    }

    @Override
    public void onBotRemoved(IRCGPTBot bot) {
        JSONObject msg = new JSONObject();
        msg.put("type", "bot");
        msg.put("id", bot.getBotId());
        msg.put("stats", bot.toJSONObject());
        msg.put("action", "removed");
        this.broadcastJSONObject(msg);
    }

    @Override
    public void onBotAdded(IRCGPTBot bot) {
        JSONObject msg = new JSONObject();
        msg.put("type", "bot");
        msg.put("id", bot.getBotId());
        msg.put("stats", bot.toJSONObject());
        msg.put("action", "added");
        msg.put("preamble", bot.getPreamble());
        this.broadcastJSONObject(msg);
    }

    @Override
    public void onBotStats(IRCGPTBot bot) {
        JSONObject msg = new JSONObject();
        msg.put("type", "bot");
        msg.put("id", bot.getBotId());
        msg.put("stats", bot.toJSONObject());
        msg.put("action", "stats");
        this.broadcastJSONObject(msg);
    }

    @Override
    public void onPreambleChange(IRCGPTBot bot) {
        JSONObject msg = new JSONObject();
        msg.put("type", "bot");
        msg.put("id", bot.getBotId());
        msg.put("stats", bot.toJSONObject());
        msg.put("action", "preamble");
        msg.put("preamble", bot.getPreamble());
        this.broadcastJSONObject(msg);
    }

}

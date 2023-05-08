package org.openstatic;

import java.net.URL;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.MimeTypes;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class InterfaceServlet extends HttpServlet
{

    public InterfaceServlet()
    {

    }
   
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse httpServletResponse) throws ServletException, IOException
    {
        String target = request.getPathInfo();
        if (target == null)
            target = "/";
        if ("/".equals(target))
            target = "/index.html";
        //System.err.println("Interface Path: " + target);
        target = "/irc-gpt-bot" + target;
        //IRCGPTBotMain.logAppend("interface.log", "GET " + target);
        URL data = getClass().getResource(target);
        if (data != null)
        {
            String contentType = getContentTypeFor(target);            
            httpServletResponse.setContentType(contentType);
            httpServletResponse.setStatus(HttpServletResponse.SC_OK);
            httpServletResponse.setCharacterEncoding("UTF-8");
            InputStream inputStream = getClass().getResourceAsStream(target);
            OutputStream output = httpServletResponse.getOutputStream();
            inputStream.transferTo(output);
            output.flush();
        } else {
            //IRCGPTBotMain.logAppend("interface.log", "GET " + target + " 404");
            httpServletResponse.setStatus(HttpServletResponse.SC_NOT_FOUND);

        }
        //request.setHandled(true);
    }

    /** Determine the content type of a local file */
    public static String getContentTypeFor(String filename)
    {
        String lc_file = filename.toLowerCase();
        if (lc_file.endsWith(".html") || lc_file.endsWith(".htm"))
        {
            return "text/html";
        } else if (lc_file.endsWith(".txt")) {
            return "text/plain";
        } else if (lc_file.endsWith(".css")) {
            return "text/css";
        } else if (lc_file.endsWith(".js")) {
            return "text/javascript";
        } else if (lc_file.endsWith(".jpg") || lc_file.endsWith(".jpe") || lc_file.endsWith(".jpeg")) {
            return "image/jpeg";
        } else if (lc_file.endsWith(".gif")) {
            return "image/gif";
        } else if (lc_file.endsWith(".png")) {
            return "image/png";
        } else if (lc_file.endsWith(".svg")) {
            return "image/svg+xml";
        } else if (lc_file.endsWith(".bmp")) {
            return "image/x-ms-bmp";
        } else if (lc_file.endsWith(".mp3")) {
            return "audio/mpeg3";
        } else if (lc_file.endsWith(".zip")) {
            return "application/zip";
        } else if (lc_file.endsWith(".pdf")) {
            return "application/pdf";
        } else if (lc_file.endsWith(".xml")) {
            return "text/xml";
        } else if (lc_file.endsWith(".mid") || lc_file.endsWith(".midi")) {
            return "audio/midi";
        } else if (lc_file.endsWith(".tar")) {
            return "application/x-tar";
        } else if (lc_file.endsWith(".ico")) {
            return "image/x-icon";
        } else if (lc_file.endsWith(".avi")) {
            return "video/x-msvideo";
        } else if (lc_file.endsWith(".mp4")) {
            return "video/mp4";
        } else if (lc_file.endsWith(".mkv")) {
            return "video/x-matroska";
        } else if (lc_file.endsWith(".mov")) {
            return "video/quicktime";
        } else if (lc_file.endsWith(".wmv")) {
            return "video/x-ms-wmv";
        } else if (lc_file.endsWith(".3gp")) {
            return "video/3gpp";
        } else {
            String result = MimeTypes.getDefaultMimeByExtension(filename);
            if ("".equals(result) || result == null)
                result = "application/octet-stream";
            return result;
        }
    }
}


package com.cgbystrom.sockjs.pages;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Formatter;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.QueryStringDecoder;
import org.jboss.netty.util.CharsetUtil;

public class IframePage extends SimpleChannelHandler {

    private final String content;
    private final String etag;

    public IframePage(String url) {
        if(url == null || url.isEmpty()) {
            throw new IllegalArgumentException("invalid url");
        }

        content = "<!DOCTYPE html>\n"
                + "<html>\n"
                + "<head>\n"
                + "  <meta http-equiv=\"X-UA-Compatible\" contentBuffer=\"IE=edge\" />\n"
                + "  <meta http-equiv=\"Content-Type\" contentBuffer=\"text/html; charset=UTF-8\" />\n"
                + "  <script>\n"
                + "    document.domain = document.domain;\n"
                + "    _sockjs_onload = function(){SockJS.bootstrap_iframe();};\n"
                + "  </script>\n"
                + "  <script src=\"" + url + "\"></script>\n"
                + "</head>\n"
                + "<body>\n"
                + "  <h2>Don't panic!</h2>\n"
                + "  <p>This is a SockJS hidden iframe. It's used for cross domain magic.</p>\n"
                + "</body>\n"
                + "</html>";

        etag = "\"" + generateMd5(content) + "\"";
    }

    @Override
    public void messageReceived(ChannelHandlerContext context, MessageEvent event) throws Exception {
        HttpRequest request = (HttpRequest) event.getMessage();

        QueryStringDecoder requestQueryDecoder = new QueryStringDecoder(request.getUri());
        String path = requestQueryDecoder.getPath();

        if (!path.matches(".*/iframe[0-9-.a-z_]*.html")) {
            throw new IllegalStateException("invalid iframe uri");
        }

        ChannelBuffer contentBuffer;
        contentBuffer = ChannelBuffers.copiedBuffer(content, CharsetUtil.UTF_8);

        HttpResponse response;
        response = new DefaultHttpResponse(request.getProtocolVersion(), HttpResponseStatus.OK);
        response.setHeader(HttpHeaders.Names.SET_COOKIE, "JSESSIONID=dummy; path=/");
        response.setHeader(HttpHeaders.Names.ETAG, etag);

        if (request.containsHeader(HttpHeaders.Names.IF_NONE_MATCH)) {
            response.setStatus(HttpResponseStatus.NOT_MODIFIED);
            response.removeHeader(HttpHeaders.Names.CONTENT_TYPE);
        } else {
            response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "text/html; charset=UTF-8");
            response.setHeader(HttpHeaders.Names.CACHE_CONTROL, "max-age=31536000, public");
            response.setHeader(HttpHeaders.Names.EXPIRES, "FIXME"); // FIXME:
            // Fix this
            response.removeHeader(HttpHeaders.Names.SET_COOKIE);
            response.setHeader(HttpHeaders.Names.CONTENT_LENGTH, contentBuffer.readableBytes());
            response.setContent(contentBuffer);
        }

        context.getChannel().write(response);

    }

    private static String generateMd5(String value) {
        String encryptedString = null;
        byte[] bytesToBeEncrypted;
        Formatter formatter = new Formatter();
        try {
            // convert string to bytes using a encoding scheme
            bytesToBeEncrypted = value.getBytes("UTF-8");
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] theDigest = md.digest(bytesToBeEncrypted);
            // convert each byte to a hexadecimal digit
            for (byte b : theDigest) {
                formatter.format("%02x", b);
            }
            encryptedString = formatter.toString().toLowerCase();
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("unable to generate MD5", e);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("unable to generate MD5", e);
        } finally {
            formatter.close();
        }
        return encryptedString;
    }
}

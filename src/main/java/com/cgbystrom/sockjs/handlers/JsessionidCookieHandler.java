/**
 * 
 */
package com.cgbystrom.sockjs.handlers;

import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.COOKIE;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.SET_COOKIE;

import java.util.Set;

import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.handler.codec.http.Cookie;
import org.jboss.netty.handler.codec.http.CookieDecoder;
import org.jboss.netty.handler.codec.http.CookieEncoder;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;

public class JsessionidCookieHandler extends SimpleChannelHandler {

    private static final CookieDecoder COOKIE_DECODER = new CookieDecoder();
    private static final String JSESSIONID = "JSESSIONID";
    private static final String DEFAULT_COOKIE = "JSESSIONID=dummy; path=/";

    protected String cookie = DEFAULT_COOKIE;

    /** Save a reference to the initating HTTP request */
    protected HttpRequest request;

    @Override
    public void messageReceived(ChannelHandlerContext context, MessageEvent event) throws Exception {
        if(event.getMessage() instanceof HttpRequest) {
            request = (HttpRequest) event.getMessage();

            // FIXME: Check if cookies are enabled in the server
            cookie = DEFAULT_COOKIE;
            String cookieHeader = request.getHeader(COOKIE);
            if (cookieHeader != null) {
                Set<Cookie> cookies = COOKIE_DECODER.decode(cookieHeader);
                for (Cookie c : cookies) {
                    if (c.getName().equals(JSESSIONID)) {
                        c.setPath("/");
                        CookieEncoder cookieEncoder = new CookieEncoder(true);
                        cookieEncoder.addCookie(c);
                        cookie = cookieEncoder.encode();
                    }
                }
            }
        }

        super.messageReceived(context, event);
    }

    @Override
    public void writeRequested(ChannelHandlerContext context, MessageEvent event) throws Exception {
        if(event.getMessage() instanceof HttpResponse) {
            HttpResponse response = (HttpResponse) event.getMessage();
            response.setHeader(SET_COOKIE, cookie);
        }

        super.writeRequested(context, event);
    }


}

package com.cgbystrom.sockjs.transports;

import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CACHE_CONTROL;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CONTENT_LENGTH;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.COOKIE;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.SET_COOKIE;

import java.util.Set;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelState;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.channel.UpstreamChannelStateEvent;
import org.jboss.netty.handler.codec.http.Cookie;
import org.jboss.netty.handler.codec.http.CookieDecoder;
import org.jboss.netty.handler.codec.http.CookieEncoder;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.jboss.netty.logging.InternalLogger;
import org.jboss.netty.logging.InternalLoggerFactory;
import org.jboss.netty.util.CharsetUtil;

import com.cgbystrom.sockjs.SessionHandler;

public class BaseTransport extends SimpleChannelHandler {

    @SuppressWarnings("unused")
    private static final InternalLogger LOGGER = InternalLoggerFactory.getInstance(BaseTransport.class);

    public static final String CONTENT_TYPE_JAVASCRIPT = "application/javascript; charset=UTF-8";
    public static final String CONTENT_TYPE_FORM = "application/x-www-form-urlencoded";
    public static final String CONTENT_TYPE_PLAIN = "text/plain; charset=UTF-8";
    public static final String CONTENT_TYPE_HTML = "text/html; charset=UTF-8";

    private static final CookieDecoder COOKIE_DECODER = new CookieDecoder();
    private static final String JSESSIONID = "JSESSIONID";
    private static final String DEFAULT_COOKIE = "JSESSIONID=dummy; path=/";

    protected String cookie = DEFAULT_COOKIE;

    /** Save a reference to the initating HTTP request */
    protected HttpRequest request;

    @Override
    public final void channelOpen(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        // Overridden method to prevent propagation of channel state event upstream.
    }

    @Override
    public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        // Overridden method to prevent propagation of channel state event upstream.
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        request = (HttpRequest) e.getMessage();
        handleCookie(request);

        // Since we have silenced the usual channel state events for open and connected for the socket,
        // we must notify handlers downstream to now consider this connection connected.
        // We are responsible for manually dispatching this event upstream
        ctx.sendUpstream(new UpstreamChannelStateEvent(e.getChannel(), ChannelState.CONNECTED, Boolean.TRUE));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
        if (e.getCause() instanceof SessionHandler.NotFoundException) {
            respondAndClose(e.getChannel(), HttpResponseStatus.NOT_FOUND, "Session not found.");
        } else {
            super.exceptionCaught(ctx, e);
        }
    }

    protected static void respondAndClose(Channel channel, HttpResponseStatus status, String message) throws Exception {
        // TODO: Why aren't response data defined in SockJS for error messages?
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_0, status);
        response.setHeader(CONTENT_TYPE, "text/plain; charset=UTF-8");

        final ChannelBuffer buffer = ChannelBuffers.copiedBuffer(message, CharsetUtil.UTF_8);
        response.setContent(buffer);
        response.setHeader(CONTENT_LENGTH, buffer.readableBytes());
        response.setHeader(SET_COOKIE, "JSESSIONID=dummy; path=/"); // FIXME: Don't sprinkle cookies in every request
        response.setHeader(CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0");
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Allow-Credentials", "true");

        if (channel.isWritable())
            channel.write(response).addListener(ChannelFutureListener.CLOSE);
    }

    protected HttpResponse createResponse(String contentType) {
        final HttpVersion version = request.getProtocolVersion();
        HttpResponse response = new DefaultHttpResponse(version, HttpResponseStatus.OK);
        response.setHeader(CONTENT_TYPE, contentType);
        response.setHeader(CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0");
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Allow-Credentials", "true");
        response.setHeader(SET_COOKIE, cookie); // FIXME: Check if cookies are enabled
        return response;
    }

    protected void handleCookie(HttpRequest request) {
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
}

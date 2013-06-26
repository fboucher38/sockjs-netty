/**
 * 
 */
package com.cgbystrom.sockjs.handlers;

import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;

import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.util.CharsetUtil;

import com.cgbystrom.sockjs.Service;
import com.cgbystrom.sockjs.pages.IframePage;
import com.cgbystrom.sockjs.pages.InfoPage;
import com.cgbystrom.sockjs.transports.AbstractTransport;
import com.cgbystrom.sockjs.transports.EventSourceTransport;
import com.cgbystrom.sockjs.transports.HtmlFileTransport;
import com.cgbystrom.sockjs.transports.JsonpPollingTransport;
import com.cgbystrom.sockjs.transports.JsonpSendTransport;
import com.cgbystrom.sockjs.transports.RawWebSocketTransport;
import com.cgbystrom.sockjs.transports.WebSocketTransport;
import com.cgbystrom.sockjs.transports.XhrPollingTransport;
import com.cgbystrom.sockjs.transports.XhrSendTransport;
import com.cgbystrom.sockjs.transports.XhrStreamingTransport;

/**
 * @author fbou
 *
 */
public class TransportRouterHandler extends SimpleChannelHandler {

    private enum SessionCreation { CREATE_OR_REUSE, FORCE_REUSE, FORCE_CREATE }

    private static final Pattern SERVER_SESSION = Pattern.compile("^/([^/.]+)/([^/.]+)/([^?.]+)");
    private static final Random RANDOM = new Random();

    private final Service service;
    private final String sockjsUrl;

    public TransportRouterHandler(Service service, String sockjsUrl) {
        this.service = service;
        this.sockjsUrl = sockjsUrl;
    }

    @Override
    public void messageReceived(ChannelHandlerContext context, MessageEvent event) throws Exception {
        HttpRequest request = (HttpRequest) event.getMessage();
        String requestUriSuffix = request.getUri().replaceFirst(service.getUrl(), "");

        if (requestUriSuffix.equals("") || requestUriSuffix.equals("/")) {
            HttpResponse response;
            response = new DefaultHttpResponse(request.getProtocolVersion(), HttpResponseStatus.OK);
            response.setHeader(CONTENT_TYPE, AbstractTransport.CONTENT_TYPE_PLAIN);
            response.setContent(ChannelBuffers.copiedBuffer("Welcome to SockJS!\n", CharsetUtil.UTF_8));
            event.getChannel().write(response);
        } else if (requestUriSuffix.startsWith("/iframe")) {
            context.getPipeline().addLast("sockjs-iframe", new IframePage(sockjsUrl));
        } else if (requestUriSuffix.startsWith("/info")) {
            context.getPipeline().addLast("sockjs-nocache", new NoCacheHandler());
            context.getPipeline().addLast("sockjs-info", new InfoPage(service));
        } else if (requestUriSuffix.startsWith("/websocket")) {
            context.getPipeline().addLast("sockjs-websocket", new RawWebSocketTransport());
            context.getPipeline().addLast("sockjs-handler", service.forceCreateSession("rawwebsocket-" + RANDOM.nextLong()));
        } else {
            if (!handleSession(context, event, requestUriSuffix)) {
                HttpResponse response;
                response = new DefaultHttpResponse(request.getProtocolVersion(), HttpResponseStatus.NOT_FOUND);
                response.setContent(ChannelBuffers.copiedBuffer("Not found", CharsetUtil.UTF_8));
                event.getChannel().write(response);
                return;
            }
        }

        super.messageReceived(context, event);
    }

    private boolean handleSession(ChannelHandlerContext ctx, MessageEvent e, String path)
            throws Exception {
        Matcher m = SERVER_SESSION.matcher(path);

        if (!m.find()) {
            return false;
        }

        String sessionId = m.group(2);
        String transport = m.group(3);

        ChannelPipeline pipeline;
        pipeline = ctx.getPipeline();

        SessionCreation sessionCreation;
        sessionCreation = SessionCreation.CREATE_OR_REUSE;

        if (transport.equals("xhr_send")) {
            pipeline.addLast("sockjs-cookie", new CookieHandler());
            pipeline.addLast("sockjs-nocache", new NoCacheHandler());
            pipeline.addLast("sockjs-xhr-send", new XhrSendTransport());
            sessionCreation = SessionCreation.FORCE_REUSE; // Expect an existing session
        } else if (transport.equals("jsonp_send")) {
            pipeline.addLast("sockjs-cookie", new CookieHandler());
            pipeline.addLast("sockjs-nocache", new NoCacheHandler());
            pipeline.addLast("sockjs-jsonp-send", new JsonpSendTransport());
            sessionCreation = SessionCreation.FORCE_REUSE; // Expect an existing session
        } else if (transport.equals("xhr_streaming")) {
            pipeline.addLast("sockjs-cookie", new CookieHandler());
            pipeline.addLast("sockjs-nocache", new NoCacheHandler());
            pipeline.addLast("sockjs-xhr-streaming", new XhrStreamingTransport());
        } else if (transport.equals("xhr")) {
            pipeline.addLast("sockjs-cookie", new CookieHandler());
            pipeline.addLast("sockjs-nocache", new NoCacheHandler());
            pipeline.addLast("sockjs-xhr-polling", new XhrPollingTransport());
        } else if (transport.equals("jsonp")) {
            pipeline.addLast("sockjs-cookie", new CookieHandler());
            pipeline.addLast("sockjs-nocache", new NoCacheHandler());
            pipeline.addLast("sockjs-jsonp-polling", new JsonpPollingTransport());
        } else if (transport.equals("htmlfile")) {
            pipeline.addLast("sockjs-cookie", new CookieHandler());
            pipeline.addLast("sockjs-nocache", new NoCacheHandler());
            pipeline.addLast("sockjs-htmlfile-polling", new HtmlFileTransport());
        } else if (transport.equals("eventsource")) {
            pipeline.addLast("sockjs-cookie", new CookieHandler());
            pipeline.addLast("sockjs-nocache", new NoCacheHandler());
            pipeline.addLast("sockjs-eventsource", new EventSourceTransport());
        } else if (transport.equals("websocket")) {
            pipeline.addLast("sockjs-websocket", new WebSocketTransport());
            sessionCreation = SessionCreation.FORCE_CREATE; // Websockets should re-create a session every time
        } else {
            return false;
        }

        SessionHandler sessionHandler = null;
        switch (sessionCreation) {
            case CREATE_OR_REUSE:
                sessionHandler = service.getOrCreateSession(sessionId);;
                break;
            case FORCE_REUSE:
                sessionHandler = service.getSession(sessionId);
                break;
            case FORCE_CREATE:
                sessionHandler = service.forceCreateSession(sessionId);
                break;
            default:
                throw new Exception("Unknown sessionCreation value: " + sessionCreation);
        }

        pipeline.addLast("sockjs-handler", sessionHandler);

        return true;
    }

}

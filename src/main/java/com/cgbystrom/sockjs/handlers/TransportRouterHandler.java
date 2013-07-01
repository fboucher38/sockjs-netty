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
import com.cgbystrom.sockjs.transports.WebSocketTransport;
import com.cgbystrom.sockjs.transports.XhrPollingTransport;
import com.cgbystrom.sockjs.transports.XhrSendTransport;
import com.cgbystrom.sockjs.transports.XhrStreamingTransport;

public class TransportRouterHandler extends SimpleChannelHandler {

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
            SessionHandler newSession;
            newSession = service.forceCreateSession("rawwebsocket-" + RANDOM.nextLong());
            /*context.getPipeline().addLast("sockjs-websocket",
                    new RawWebSocketTransport(newSession, new SimpleReceiver(event.getChannel())));*/

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

    private boolean handleSession(ChannelHandlerContext context, MessageEvent event, String path)
            throws Exception {
        Matcher m = SERVER_SESSION.matcher(path);

        if (!m.find()) {
            return false;
        }

        String sessionId = m.group(2);
        String transport = m.group(3);

        ChannelPipeline pipeline;
        pipeline = context.getPipeline();

        if (transport.equals("xhr_send")) {
            pipeline.addLast("sockjs-cookie", new CookieHandler());
            pipeline.addLast("sockjs-nocache", new NoCacheHandler());
            pipeline.addLast("sockjs-xhr-send",
                    new XhrSendTransport(service.getSession(sessionId))); // Expect an existing session

        } else if (transport.equals("jsonp_send")) {
            pipeline.addLast("sockjs-cookie", new CookieHandler());
            pipeline.addLast("sockjs-nocache", new NoCacheHandler());
            pipeline.addLast("sockjs-jsonp-send",
                    new JsonpSendTransport(service.getSession(sessionId))); // Expect an existing session

        } else if (transport.equals("xhr_streaming")) {
            pipeline.addLast("sockjs-cookie", new CookieHandler());
            pipeline.addLast("sockjs-nocache", new NoCacheHandler());
            pipeline.addLast("sockjs-xhr-streaming",
                    new XhrStreamingTransport(
                            service.getOrCreateSession(sessionId), service.getResponseSizeLimit()));

        } else if (transport.equals("xhr")) {
            pipeline.addLast("sockjs-cookie", new CookieHandler());
            pipeline.addLast("sockjs-nocache", new NoCacheHandler());
            pipeline.addLast("sockjs-xhr-polling",
                    new XhrPollingTransport(
                            service.getOrCreateSession(sessionId)));

        } else if (transport.equals("jsonp")) {
            pipeline.addLast("sockjs-cookie", new CookieHandler());
            pipeline.addLast("sockjs-nocache", new NoCacheHandler());
            pipeline.addLast("sockjs-jsonp-polling",
                    new JsonpPollingTransport(
                            service.getOrCreateSession(sessionId)));

        } else if (transport.equals("htmlfile")) {
            pipeline.addLast("sockjs-cookie", new CookieHandler());
            pipeline.addLast("sockjs-nocache", new NoCacheHandler());
            pipeline.addLast("sockjs-htmlfile-polling",
                    new HtmlFileTransport(
                            service.getOrCreateSession(sessionId), service.getResponseSizeLimit()));

        } else if (transport.equals("eventsource")) {
            pipeline.addLast("sockjs-cookie", new CookieHandler());
            pipeline.addLast("sockjs-nocache", new NoCacheHandler());
            pipeline.addLast("sockjs-eventsource",
                    new EventSourceTransport(
                            service.getOrCreateSession(sessionId), service.getResponseSizeLimit()));

        } else if (transport.equals("websocket")) {
            pipeline.addLast("sockjs-websocket",
                    new WebSocketTransport(service.forceCreateSession(sessionId)));

        } else {
            return false;
        }

        return true;
    }

}

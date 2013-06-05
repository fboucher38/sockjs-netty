package com.cgbystrom.sockjs;

import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CACHE_CONTROL;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CONNECTION;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CONTENT_LENGTH;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Values.KEEP_ALIVE;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.QueryStringDecoder;
import org.jboss.netty.logging.InternalLogger;
import org.jboss.netty.logging.InternalLoggerFactory;
import org.jboss.netty.util.CharsetUtil;

import com.cgbystrom.sockjs.transports.BaseTransport;
import com.cgbystrom.sockjs.transports.EventSourceTransport;
import com.cgbystrom.sockjs.transports.HtmlFileTransport;
import com.cgbystrom.sockjs.transports.JsonpPollingTransport;
import com.cgbystrom.sockjs.transports.RawWebSocketTransport;
import com.cgbystrom.sockjs.transports.WebSocketTransport;
import com.cgbystrom.sockjs.transports.XhrPollingTransport;
import com.cgbystrom.sockjs.transports.XhrSendTransport;
import com.cgbystrom.sockjs.transports.XhrStreamingTransport;

public class ServiceRouter extends SimpleChannelHandler {
    private static final InternalLogger LOGGER         = InternalLoggerFactory.getInstance(ServiceRouter.class);
    private static final Pattern        SERVER_SESSION = Pattern.compile("^/([^/.]+)/([^/.]+)/");
    private static final Random         RANDOM         = new Random();

    private enum SessionCreation {
        CREATE_OR_REUSE, FORCE_REUSE, FORCE_CREATE
    }

    private final List<Service> services;
    private final IframePage    iframe;

    /**
     * @param clientUrl
     *            URL to SockJS JavaScript client. Needed by the iframe to
     *            properly load. (Hint: SockJS has a CDN,
     *            http://cdn.sockjs.org/)
     */
    public ServiceRouter(String clientUrl, List<Service> services) {
        this.iframe = new IframePage(clientUrl);
        this.services = Collections.unmodifiableList(new ArrayList<Service>(services));
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        HttpRequest request = (HttpRequest) e.getMessage();
        if (LOGGER.isDebugEnabled())
            LOGGER.debug("URI " + request.getUri());

        for (Service service : services) {
            // Check if there's a service registered with this URL
            if (request.getUri().startsWith(service.getUrl())) {
                handleService(ctx, e, service);
                super.messageReceived(ctx, e);
                return;
            }
        }

        // No match for service found, return 404
        HttpResponse response = new DefaultHttpResponse(request.getProtocolVersion(), HttpResponseStatus.NOT_FOUND);
        response.setContent(ChannelBuffers.copiedBuffer("Not found", CharsetUtil.UTF_8));
        writeResponse(e.getChannel(), request, response);
    }

    private void handleService(ChannelHandlerContext ctx, MessageEvent e, Service service) throws Exception {
        HttpRequest request = (HttpRequest) e.getMessage();
        request.setUri(request.getUri().replaceFirst(service.getUrl(), ""));
        QueryStringDecoder qsd = new QueryStringDecoder(request.getUri());
        String path = qsd.getPath();

        HttpResponse response = new DefaultHttpResponse(request.getProtocolVersion(), HttpResponseStatus.OK);
        if (path.equals("") || path.equals("/")) {
            response.setHeader(CONTENT_TYPE, BaseTransport.CONTENT_TYPE_PLAIN);
            response.setContent(ChannelBuffers.copiedBuffer("Welcome to SockJS!\n", CharsetUtil.UTF_8));
            writeResponse(e.getChannel(), request, response);
        } else if (path.startsWith("/iframe")) {
            iframe.handle(request, response);
            writeResponse(e.getChannel(), request, response);
        } else if (path.startsWith("/info")) {
            response.setHeader(CONTENT_TYPE, "application/json; charset=UTF-8");
            response.setHeader(CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0");
            response.setContent(formatInfoForService(service));
            writeResponse(e.getChannel(), request, response);
        } else if (path.startsWith("/websocket")) {
            // Raw web socket
            SessionHandler sessionHandler;
            sessionHandler = service.forceCreateSession("rawwebsocket-" + RANDOM.nextLong(), false);
            ctx.getPipeline().addLast("sockjs-websocket", new RawWebSocketTransport(path));
            ctx.getPipeline().addLast("sockjs-session-handler", sessionHandler);
        } else {
            if (!handleSession(ctx, e, path, service)) {
                response.setStatus(HttpResponseStatus.NOT_FOUND);
                response.setContent(ChannelBuffers.copiedBuffer("Not found", CharsetUtil.UTF_8));
                writeResponse(e.getChannel(), request, response);
            }
        }
    }

    private boolean handleSession(ChannelHandlerContext ctx, MessageEvent e, String path, Service service)
            throws Exception {
        Matcher m = SERVER_SESSION.matcher(path);

        if (!m.find()) {
            return false;
        }

        String server = m.group(1);
        String sessionId = m.group(2);
        String transport = path.replaceFirst("/" + server + "/" + sessionId, "");

        ChannelPipeline pipeline = ctx.getPipeline();
        SessionCreation sessionCreation = SessionCreation.CREATE_OR_REUSE;
        boolean heartbeatRequired = true;
        if (transport.equals("/xhr_send")) {
            pipeline.addLast("sockjs-xhr-send", new XhrSendTransport(false));
            sessionCreation = SessionCreation.FORCE_REUSE; // Expect an existing
                                                           // session
        } else if (transport.equals("/jsonp_send")) {
            pipeline.addLast("sockjs-jsonp-send", new XhrSendTransport(true));
            sessionCreation = SessionCreation.FORCE_REUSE; // Expect an existing
                                                           // session
        } else if (transport.equals("/xhr_streaming")) {
            pipeline.addLast("sockjs-xhr-streaming", new XhrStreamingTransport(service.getMaxResponseSize()));
        } else if (transport.equals("/xhr")) {
            pipeline.addLast("sockjs-xhr-polling", new XhrPollingTransport());
        } else if (transport.equals("/jsonp")) {
            pipeline.addLast("sockjs-jsonp-polling", new JsonpPollingTransport());
        } else if (transport.equals("/htmlfile")) {
            pipeline.addLast("sockjs-htmlfile-polling", new HtmlFileTransport(service.getMaxResponseSize()));
        } else if (transport.equals("/eventsource")) {
            pipeline.addLast("sockjs-eventsource", new EventSourceTransport(service.getMaxResponseSize()));
        } else if (transport.equals("/websocket")) {
            pipeline.addLast("sockjs-websocket", new WebSocketTransport(service.getUrl() + path, service));
            // Websockets should re-create a session every time
            sessionCreation = SessionCreation.FORCE_CREATE;
            heartbeatRequired = false;
        } else {
            return false;
        }

        SessionHandler sessionHandler = null;
        switch (sessionCreation) {
            case CREATE_OR_REUSE:
                sessionHandler = service.getOrCreateSession(sessionId, heartbeatRequired);
                break;
            case FORCE_REUSE:
                sessionHandler = service.getSession(sessionId);
                break;
            case FORCE_CREATE:
                sessionHandler = service.forceCreateSession(sessionId, heartbeatRequired);
                break;
            default:
                throw new Exception("Unknown sessionCreation value: " + sessionCreation);
        }

        pipeline.addLast("sockjs-session-handler", sessionHandler);

        return true;
    }

    /** Handle conditional connection close depending on keep-alive */
    private void writeResponse(Channel channel, HttpRequest request, HttpResponse response) {
        response.setHeader(CONTENT_LENGTH, response.getContent().readableBytes());

        boolean hasKeepAliveHeader = KEEP_ALIVE.equalsIgnoreCase(request.getHeader(CONNECTION));
        if (!request.getProtocolVersion().isKeepAliveDefault() && hasKeepAliveHeader) {
            response.setHeader(CONNECTION, KEEP_ALIVE);
        }

        ChannelFuture wf = channel.write(response);
        if (!HttpHeaders.isKeepAlive(request)) {
            wf.addListener(ChannelFutureListener.CLOSE);
        }
    }

    private ChannelBuffer formatInfoForService(Service service) {
        StringBuilder sb = new StringBuilder(100);
        sb.append("{");
        sb.append("\"websocket\": ");
        sb.append(service.isWebSocketEnabled());
        sb.append(", ");
        sb.append("\"origins\": [\"*:*\"], ");
        sb.append("\"cookie_needed\": ");
        sb.append(service.isJsessionid());
        sb.append(", ");
        sb.append("\"entropy\": ");
        sb.append(RANDOM.nextInt(Integer.MAX_VALUE) + 1);
        sb.append("}");
        return ChannelBuffers.copiedBuffer(sb.toString(), CharsetUtil.UTF_8);
    }

}

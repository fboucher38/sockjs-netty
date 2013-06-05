package com.cgbystrom.sockjs.transports;

import static org.jboss.netty.handler.codec.http.HttpHeaders.isKeepAlive;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.ALLOW;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CONNECTION;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.SEC_WEBSOCKET_VERSION;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.UPGRADE;
import static org.jboss.netty.handler.codec.http.HttpMethod.GET;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.METHOD_NOT_ALLOWED;
import static org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBufferInputStream;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelState;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.DownstreamMessageEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.channel.UpstreamChannelStateEvent;
import org.jboss.netty.channel.UpstreamMessageEvent;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpHeaders.Values;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.WebSocketHandshakeException;
import org.jboss.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import org.jboss.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import org.jboss.netty.handler.ssl.SslHandler;
import org.jboss.netty.logging.InternalLogger;
import org.jboss.netty.logging.InternalLoggerFactory;

import com.cgbystrom.sockjs.Frame;
import com.cgbystrom.sockjs.PreflightHandler;
import com.cgbystrom.sockjs.Service;
import com.cgbystrom.sockjs.ServiceRouter;
import com.cgbystrom.sockjs.SessionHandler;
import com.cgbystrom.sockjs.SockJsMessage;

// FIMXE: Mark as sharable?
public class WebSocketTransport extends SimpleChannelHandler {
    private static final InternalLogger logger       = InternalLoggerFactory.getInstance(WebSocketTransport.class);
    private static final ObjectMapper   mapper       = new ObjectMapper();

    /**
     * Max size of response content sent before closing the connection. Since
     * browsers buffer chunked/streamed content in-memory the connection must be
     * closed at regular intervals. Call it "garbage collection" if you will.
     */
    private final int                   maxResponseSize;

    /** Track size of content chunks sent to the browser. */
    private final AtomicInteger         numBytesSent = new AtomicInteger(0);
    // FIXME: Do we really need to be atomic? Are not each pipeline handler
    // assigned to an I/O thread, such as this class?

    private WebSocketServerHandshaker   handshaker;
    private final String                path;

    public WebSocketTransport(String path, Service service) {
        this.path = path;
        this.maxResponseSize = service.getMaxResponseSize();
    }

    @Override
    public void channelOpen(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        // Overridden method to prevent propagation of channel state event
        // upstream.
    }

    @Override
    public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        // Overridden method to prevent propagation of channel state event
        // upstream.
    }

    @Override
    public void channelDisconnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        super.channelDisconnected(ctx, e);
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        Object msg = e.getMessage();
        if (msg instanceof HttpRequest) {
            handleHttpRequest(ctx, e.getChannel(), (HttpRequest) msg);
        } else if (msg instanceof WebSocketFrame) {
            handleWebSocketFrame(ctx, e.getChannel(), (WebSocketFrame) msg);
        } else {
            throw new IOException("Unknown frame type: " + msg.getClass().getSimpleName());
        }
    }

    @Override
    public void writeRequested(ChannelHandlerContext ctx, final MessageEvent e) throws Exception {
        if (e.getMessage() instanceof Frame) {
            Frame f = (Frame) e.getMessage();
            logger.debug("Write requested for " + f.getClass().getSimpleName());
            // FIXME: special case for closeFrame is it really necessary ?
            if (f instanceof Frame.CloseFrame) {
                e.getFuture().addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception {
                        // FIXME: Should really send close frame here?
                        // handshaker.close(e.getChannel(), new
                        // CloseWebSocketFrame()); ?
                        e.getChannel().close();
                    }
                });
            }
            TextWebSocketFrame message = new TextWebSocketFrame(Frame.encode((Frame) e.getMessage(), false));
            super.writeRequested(ctx,
                    new DownstreamMessageEvent(e.getChannel(), e.getFuture(), message, e.getRemoteAddress()));
        } else {
            super.writeRequested(ctx, e);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
        // FIXME: Move to BaseTransport
        if (e.getCause() instanceof SessionHandler.NotFoundException) {
            BaseTransport.respond(e.getChannel(), HttpResponseStatus.NOT_FOUND, "Session not found.");
        } else if (e.getCause() instanceof SessionHandler.LockException) {
            if (e.getChannel().isWritable()) {
                e.getChannel().write(Frame.closeFrame(2010, "Another connection still open"));
            }
        } else if (e.getCause() instanceof JsonParseException || e.getCause() instanceof JsonMappingException) {
            // NotFoundHandler.respond(e.getChannel(),
            // HttpResponseStatus.INTERNAL_SERVER_ERROR,
            // "Broken JSON encoding.");
            e.getChannel().close();
        } else if (e.getCause() instanceof WebSocketHandshakeException) {
            if (e.getCause().getMessage().contains("missing upgrade")) {
                BaseTransport.respond(e.getChannel(), HttpResponseStatus.BAD_REQUEST,
                        "Can \"Upgrade\" only to \"WebSocket\".");
            }
            // NotFoundHandler.respond(e.getChannel(),
            // HttpResponseStatus.INTERNAL_SERVER_ERROR,
            // "Broken JSON encoding.");
            // e.getChannel().close();

        } else {
            super.exceptionCaught(ctx, e);
        }

    }

    private void handleHttpRequest(final ChannelHandlerContext ctx, final Channel channel, HttpRequest req)
            throws Exception {
        // Allow only GET methods.
        if (req.getMethod() != GET) {
            DefaultHttpResponse response = new DefaultHttpResponse(HTTP_1_1, METHOD_NOT_ALLOWED);
            response.addHeader(ALLOW, GET.toString());
            sendHttpResponse(ctx, req, response);
            return;
        }

        // Compatibility hack for Firefox 6.x
        String connectionHeader = req.getHeader(CONNECTION);
        if (connectionHeader != null && connectionHeader.equals("keep-alive, Upgrade")) {
            req.setHeader(CONNECTION, UPGRADE);
        }

        // If we get WS version 7, treat it as 8 as they are almost identical.
        // (Really true?)
        String wsVersionHeader = req.getHeader(SEC_WEBSOCKET_VERSION);
        if (wsVersionHeader != null && wsVersionHeader.equals("7")) {
            req.setHeader(SEC_WEBSOCKET_VERSION, "8");
        }

        // Handshake
        String wsLocation = getWebSocketLocation(channel.getPipeline(), req);
        WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(wsLocation, null, false);

        handshaker = wsFactory.newHandshaker(req);
        if (handshaker == null) {
            wsFactory.sendUnsupportedWebSocketVersionResponse(ctx.getChannel());
        } else {
            handshaker.handshake(ctx.getChannel(), req).addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    if (future.isSuccess()) {
                        ctx.getPipeline().remove(ServiceRouter.class);
                        ctx.getPipeline().remove(PreflightHandler.class);
                        ctx.sendUpstream(new UpstreamChannelStateEvent(channel, ChannelState.CONNECTED, Boolean.TRUE));
                    }
                }
            });
        }
    }

    private void handleWebSocketFrame(ChannelHandlerContext ctx, Channel channel, WebSocketFrame frame)
            throws IOException {
        // Check for closing frame
        if (frame instanceof CloseWebSocketFrame) {
            handshaker.close(ctx.getChannel(), (CloseWebSocketFrame) frame);
            return;
        } else if (frame instanceof PingWebSocketFrame) {
            ctx.getChannel().write(new PongWebSocketFrame(frame.getBinaryData()));
            return;
        } else if (!(frame instanceof TextWebSocketFrame)) {
            throw new UnsupportedOperationException(String.format("%s frame types not supported", frame.getClass()
                    .getName()));
        }

        // Send the uppercase string back.
        String request = ((TextWebSocketFrame) frame).getText();
        logger.debug(String.format("Channel %s received '%s'", ctx.getChannel().getId(), request));
        ChannelBuffer payload = frame.getBinaryData();

        if (frame.getBinaryData().readableBytes() == 0) {
            return;
        }

        ChannelBufferInputStream cbis = new ChannelBufferInputStream(payload);
        String[] messages;
        if (payload.getByte(0) == '[') {
            // decode array
            messages = mapper.readValue(cbis, String[].class);
        } else if (payload.getByte(0) == '"') {
            // decode string
            messages = new String[1];
            messages[0] = mapper.readValue(cbis, String.class);
        } else {
            throw new IOException("Expected message as string or string[]");
        }

        for (String message : messages) {
            SockJsMessage jsMessage = new SockJsMessage(message);
            ctx.sendUpstream(new UpstreamMessageEvent(channel, jsMessage, channel.getRemoteAddress()));
        }
    }

    private void sendHttpResponse(ChannelHandlerContext ctx, HttpRequest req, HttpResponse res) {
        // Send the response and close the connection if necessary.
        if (!isKeepAlive(req) || res.getStatus().getCode() != 200) {
            res.setHeader(CONNECTION, Values.CLOSE);
            ctx.getChannel().write(res).addListener(ChannelFutureListener.CLOSE);
        } else {
            ctx.getChannel().write(res);
        }
    }

    private String getWebSocketLocation(ChannelPipeline pipeline, HttpRequest req) {
        boolean isSsl = pipeline.get(SslHandler.class) != null;
        if (isSsl) {
            return "wss://" + req.getHeader(HttpHeaders.Names.HOST) + path;
        } else {
            return "ws://" + req.getHeader(HttpHeaders.Names.HOST) + path;
        }
    }
}

package com.cgbystrom.sockjs.transports;

import static org.jboss.netty.handler.codec.http.HttpHeaders.isKeepAlive;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.ALLOW;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CONNECTION;
import static org.jboss.netty.handler.codec.http.HttpMethod.GET;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.METHOD_NOT_ALLOWED;
import static org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelState;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.channel.UpstreamChannelStateEvent;
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

import com.cgbystrom.sockjs.PreflightHandler;
import com.cgbystrom.sockjs.ServiceRouter;
import com.cgbystrom.sockjs.SessionHandler;
import com.cgbystrom.sockjs.frames.Frame;

public abstract class BaseWebSocketTransport extends SimpleChannelHandler {

    @SuppressWarnings("unused")
    private static final InternalLogger LOGGER = InternalLoggerFactory.getInstance(BaseWebSocketTransport.class);

    private WebSocketServerHandshaker   handshaker;
    private final String                path;

    public BaseWebSocketTransport(String path) {
        this.path = path;
    }

    protected abstract void handleReceivedTextWebSocketFrame(ChannelHandlerContext context, MessageEvent event, TextWebSocketFrame textWebSocketFrame) throws Exception;

    protected abstract void handleWroteSockJsFrame(ChannelHandlerContext context, MessageEvent event, Frame frame) throws Exception;

    @Override
    public final void channelOpen(ChannelHandlerContext context, ChannelStateEvent event) throws Exception {
        // Overridden method to prevent propagation of channel state event upstream.
    }

    @Override
    public final void channelConnected(ChannelHandlerContext context, ChannelStateEvent event) throws Exception {
        // Overridden method to prevent propagation of channel state event upstream.
    }

    @Override
    public void messageReceived(ChannelHandlerContext context, MessageEvent event) throws Exception {
        Object msg = event.getMessage();
        if (msg instanceof HttpRequest) {
            handleHttpRequest(context, event, (HttpRequest) msg);
        } else if (msg instanceof WebSocketFrame) {
            handleWebSocketFrame(context, event, (WebSocketFrame) msg);
        } else {
            throw new IllegalArgumentException("Unknown frame type: " + event.getMessage());
        }
    }

    @Override
    public void writeRequested(ChannelHandlerContext context, MessageEvent event) throws Exception {
        if (event.getMessage() instanceof Frame) {
            handleWroteSockJsFrame(context, event, (Frame) event.getMessage());
        } else {
            super.writeRequested(context, event);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, ExceptionEvent event) throws Exception {
        // FIXME: Move to BaseTransport
        if (event.getCause() instanceof SessionHandler.NotFoundException) {
            BaseTransport.respondAndClose(event.getChannel(), HttpResponseStatus.NOT_FOUND, "Session not found.");
        } else if (event.getCause() instanceof WebSocketHandshakeException) {
            if (event.getCause().getMessage().contains("missing upgrade")) {
                BaseTransport.respondAndClose(event.getChannel(), HttpResponseStatus.BAD_REQUEST,
                        "Can \"Upgrade\" only to \"WebSocket\".");
            }
        } else {
            super.exceptionCaught(context, event);
        }
    }

    private void handleHttpRequest(final ChannelHandlerContext context, MessageEvent event, HttpRequest req)
            throws Exception {
        final Channel channel = event.getChannel();

        // Allow only GET methods.
        if (req.getMethod() != GET) {
            DefaultHttpResponse response = new DefaultHttpResponse(HTTP_1_1, METHOD_NOT_ALLOWED);
            response.addHeader(ALLOW, GET.toString());
            sendHttpResponse(context, req, response);
            return;
        }

        // Compatibility hack for Firefox 6.x
        String connectionHeader = req.getHeader(HttpHeaders.Names.CONNECTION);
        if (connectionHeader != null && connectionHeader.equals("keep-alive, Upgrade")) {
            req.setHeader(HttpHeaders.Names.CONNECTION, HttpHeaders.Names.UPGRADE);
        }

        // If we get WS version 7, treat it as 8 as they are almost identical.
        // (Really true?)
        String wsVersionHeader = req.getHeader(HttpHeaders.Names.SEC_WEBSOCKET_VERSION);
        if (wsVersionHeader != null && wsVersionHeader.equals("7")) {
            req.setHeader(HttpHeaders.Names.SEC_WEBSOCKET_VERSION, "8");
        }

        // Handshake
        String wsLocation = getWebSocketLocation(channel.getPipeline(), req);
        WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(wsLocation, null, false);

        handshaker = wsFactory.newHandshaker(req);
        if (handshaker == null) {
            wsFactory.sendUnsupportedWebSocketVersionResponse(context.getChannel());
        } else {
            handshaker.handshake(context.getChannel(), req).addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    if (future.isSuccess()) {
                        context.getPipeline().remove(ServiceRouter.class);
                        context.getPipeline().remove(PreflightHandler.class);
                        context.sendUpstream(new UpstreamChannelStateEvent(channel, ChannelState.CONNECTED, Boolean.TRUE));
                    }
                }
            });
        }
    }

    private void handleWebSocketFrame(ChannelHandlerContext context, MessageEvent event, WebSocketFrame frame) throws Exception {
        // Check for closing frame
        if (frame instanceof CloseWebSocketFrame) {
            handshaker.close(context.getChannel(), (CloseWebSocketFrame) frame);
            return;
        } else if (frame instanceof PingWebSocketFrame) {
            context.getChannel().write(new PongWebSocketFrame(frame.getBinaryData()));
            return;
        } else if (!(frame instanceof TextWebSocketFrame)) {
            throw new UnsupportedOperationException(String.format("%s frame types not supported", frame.getClass()
                    .getName()));
        }

        TextWebSocketFrame textWebSocketFrame;
        textWebSocketFrame = (TextWebSocketFrame) frame;
        handleReceivedTextWebSocketFrame(context, event, textWebSocketFrame);
    }

    private void sendHttpResponse(ChannelHandlerContext context, HttpRequest req, HttpResponse res) {
        // Send the response and close the connection if necessary.
        if (!isKeepAlive(req) || res.getStatus().getCode() != 200) {
            res.setHeader(CONNECTION, Values.CLOSE);
            context.getChannel().write(res).addListener(ChannelFutureListener.CLOSE);
        } else {
            context.getChannel().write(res);
        }
    }

    private String getWebSocketLocation(ChannelPipeline pipeline, HttpRequest req) {
        // FIXME: Handle non-standard HTTP port?
        boolean isSsl = pipeline.get(SslHandler.class) != null;
        if (isSsl) {
            return "wss://" + req.getHeader(HttpHeaders.Names.HOST) + path;
        } else {
            return "ws://" + req.getHeader(HttpHeaders.Names.HOST) + path;
        }
    }
}

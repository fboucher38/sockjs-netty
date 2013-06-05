package com.cgbystrom.sockjs.transports;

import static org.jboss.netty.handler.codec.http.HttpHeaders.isKeepAlive;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.ALLOW;
import static org.jboss.netty.handler.codec.http.HttpMethod.GET;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.METHOD_NOT_ALLOWED;
import static org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import java.io.IOException;

import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.map.JsonMappingException;
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
import com.cgbystrom.sockjs.ServiceRouter;
import com.cgbystrom.sockjs.SessionHandler;
import com.cgbystrom.sockjs.SockJsMessage;

public class RawWebSocketTransport extends SimpleChannelHandler {
    private static final InternalLogger LOGGER = InternalLoggerFactory.getInstance(RawWebSocketTransport.class);

    private WebSocketServerHandshaker   handshaker;
    private final String                path;

    public RawWebSocketTransport(String path) {
        this.path = path;
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
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        Object msg = e.getMessage();
        if (msg instanceof HttpRequest) {
            handleHttpRequest(ctx, e.getChannel(), (HttpRequest) msg);
        } else if (msg instanceof WebSocketFrame) {
            handleWebSocketFrame(ctx, e.getChannel(), (WebSocketFrame) msg);
        } else {
            LOGGER.error("Unknown frame type: " + e.getMessage());
        }
    }

    @Override
    public void writeRequested(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        if (e.getMessage() instanceof Frame) {
            if (e.getMessage() instanceof Frame.MessageFrame) {
                Frame.MessageFrame f = (Frame.MessageFrame) e.getMessage();
                if (LOGGER.isDebugEnabled())
                    LOGGER.debug("Write requested for " + f.getClass().getSimpleName());
                for (SockJsMessage m : f.getMessages()) {
                    TextWebSocketFrame message = new TextWebSocketFrame(m.getMessage());
                    super.writeRequested(ctx,
                            new DownstreamMessageEvent(e.getChannel(), e.getFuture(), message, e.getRemoteAddress()));
                }
            } else if (e.getMessage() instanceof Frame.CloseFrame) {
                CloseWebSocketFrame message = new CloseWebSocketFrame();
                super.writeRequested(ctx,
                        new DownstreamMessageEvent(e.getChannel(), e.getFuture(), message, e.getRemoteAddress()));
            } else if (e.getMessage() instanceof Frame.OpenFrame) {
                LOGGER.debug("Open frame silenced");
            } else if (e.getMessage() instanceof Frame.HeartbeatFrame) {
                LOGGER.debug("Heartbeat frame silenced");
            } else {
                throw new RuntimeException("Unknown frame: " + e.getMessage());
            }
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

        // Handshake
        WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(getWebSocketLocation(
                channel.getPipeline(), req), "chat, superchat", false);

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

        String request = ((TextWebSocketFrame) frame).getText();
        LOGGER.debug(String.format("Channel %s received '%s'", ctx.getChannel().getId(), request));

        SockJsMessage jsMessage = new SockJsMessage(request);
        ctx.sendUpstream(new UpstreamMessageEvent(channel, jsMessage, channel.getRemoteAddress()));
    }

    private void sendHttpResponse(ChannelHandlerContext ctx, HttpRequest req, HttpResponse res) {
        // Generate an error page if response status code is not OK (200).
        if (res.getStatus().getCode() != 200) {
            // res.setContent(ChannelBuffers.copiedBuffer(res.getStatus().toString(),
            // CharsetUtil.UTF_8));
            // setContentLength(res, res.getContent().readableBytes());
        }

        // Send the response and close the connection if necessary.
        ChannelFuture f = ctx.getChannel().write(res);
        if (!isKeepAlive(req) || res.getStatus().getCode() != 200) {
            f.addListener(ChannelFutureListener.CLOSE);
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

package com.cgbystrom.sockjs.transports;

import static org.jboss.netty.handler.codec.http.HttpMethod.GET;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.METHOD_NOT_ALLOWED;
import static org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpHeaders.Values;
import org.jboss.netty.handler.codec.http.HttpRequest;
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

import com.cgbystrom.sockjs.Service;
import com.cgbystrom.sockjs.frames.Frame;
import com.cgbystrom.sockjs.handlers.HttpRequestHandler;
import com.cgbystrom.sockjs.handlers.PreflightHandler;
import com.cgbystrom.sockjs.handlers.ServiceRouterHandler;
import com.cgbystrom.sockjs.handlers.TransportRouterHandler;

public abstract class AbstractWebSocketTransport extends AbstractTransport {

    private WebSocketServerHandshaker handshaker;

    protected abstract void handleReceivedTextWebSocketFrame(ChannelHandlerContext context, MessageEvent event, TextWebSocketFrame textWebSocketFrame) throws Exception;

    protected abstract void handleWroteSockJsFrame(ChannelHandlerContext context, MessageEvent event, Frame frame) throws Exception;

    @Override
    public void messageReceived(ChannelHandlerContext context, MessageEvent event) throws Exception {
        Object message = event.getMessage();
        if (message instanceof HttpRequest) {
            handleHttpRequest(context, event, (HttpRequest) message);
        } else if (message instanceof WebSocketFrame) {
            handleWebSocketFrame(context, event, (WebSocketFrame) message);
        } else {
            throw new IllegalArgumentException("Unknown frame type: " + message);
        }
    }

    @Override
    public void writeRequested(ChannelHandlerContext context, MessageEvent event) throws Exception {
        if (event.getMessage() instanceof Frame) {
            handleWroteSockJsFrame(context, event, (Frame)event.getMessage());
        } else {
            super.writeRequested(context, event);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, ExceptionEvent event) throws Exception {
        if (event.getCause() instanceof WebSocketHandshakeException) {
            if (event.getCause().getMessage().contains("missing upgrade")) {
                respondAndClose(event.getChannel(), HttpResponseStatus.BAD_REQUEST,
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
            response.addHeader(HttpHeaders.Names.ALLOW, GET.toString());
            response.setHeader(HttpHeaders.Names.CONNECTION, Values.CLOSE);
            context.getChannel().write(response).addListener(ChannelFutureListener.CLOSE);
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
        String wsLocation = getWebSocketLocation(channel);
        WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(wsLocation, null, false);

        handshaker = wsFactory.newHandshaker(req);
        if (handshaker == null) {
            wsFactory.sendUnsupportedWebSocketVersionResponse(context.getChannel());
        } else {
            handshaker.handshake(context.getChannel(), req).addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    if (future.isSuccess()) {
                        context.getPipeline().remove(HttpRequestHandler.class);
                        context.getPipeline().remove(ServiceRouterHandler.class);
                        context.getPipeline().remove(TransportRouterHandler.class);
                        context.getPipeline().remove(PreflightHandler.class);
                        Channels.fireChannelOpen(context);
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

    private String getWebSocketLocation(Channel channel) {
        boolean isSsl = channel.getPipeline().get(SslHandler.class) != null;
        HttpRequest request = HttpRequestHandler.getRequestForChannel(channel);
        Service service = ServiceRouterHandler.getServiceForChannel(channel);
        if (isSsl) {
            return "wss://" + request.getHeader(HttpHeaders.Names.HOST) + service.getUrl();
        } else {
            return "ws://" + request.getHeader(HttpHeaders.Names.HOST) + service.getUrl();
        }
    }
}
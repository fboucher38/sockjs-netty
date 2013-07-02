package com.cgbystrom.sockjs.transports;

import static org.jboss.netty.handler.codec.http.HttpMethod.GET;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.METHOD_NOT_ALLOWED;
import static org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
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

import com.cgbystrom.sockjs.handlers.PreflightHandler;
import com.cgbystrom.sockjs.handlers.ServiceRouterHandler;
import com.cgbystrom.sockjs.handlers.SessionHandler;
import com.cgbystrom.sockjs.handlers.TransportRouterHandler;

public abstract class AbstractWebSocketTransport extends AbstractReceiverTransport {

    private WebSocketServerHandshaker handshaker;

    protected abstract void webSocketReady(Channel channel);

    protected abstract void textWebSocketFrameReceived(ChannelHandlerContext context, MessageEvent event, TextWebSocketFrame textWebSocketFrame) throws Exception;

    public AbstractWebSocketTransport(SessionHandler sessionHandler) {
        super(sessionHandler);
    }

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

    private void handleHttpRequest(final ChannelHandlerContext context, MessageEvent event, HttpRequest request) throws Exception {
        // Allow only GET methods.
        if (request.getMethod() != GET) {
            DefaultHttpResponse response = new DefaultHttpResponse(HTTP_1_1, METHOD_NOT_ALLOWED);
            response.addHeader(HttpHeaders.Names.ALLOW, GET.toString());
            response.setHeader(HttpHeaders.Names.CONNECTION, Values.CLOSE);
            context.getChannel().write(response).addListener(ChannelFutureListener.CLOSE);
            return;
        }

        // Compatibility hack for Firefox 6.x
        String connectionHeader = request.getHeader(HttpHeaders.Names.CONNECTION);
        if (connectionHeader != null && connectionHeader.equals("keep-alive, Upgrade")) {
            request.setHeader(HttpHeaders.Names.CONNECTION, HttpHeaders.Names.UPGRADE);
        }

        // If we get WS version 7, treat it as 8 as they are almost identical.
        // (Really true?)
        String wsVersionHeader = request.getHeader(HttpHeaders.Names.SEC_WEBSOCKET_VERSION);
        if (wsVersionHeader != null && wsVersionHeader.equals("7")) {
            request.setHeader(HttpHeaders.Names.SEC_WEBSOCKET_VERSION, "8");
        }

        // Handshake
        String websocketUri = formatWebSocketLocation(event.getChannel(), request);
        WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(websocketUri, null, false);

        handshaker = wsFactory.newHandshaker(request);
        if (handshaker == null) {
            wsFactory.sendUnsupportedWebSocketVersionResponse(context.getChannel());
        } else {
            handshaker.handshake(context.getChannel(), request).addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    if (future.isSuccess()) {
                        context.getPipeline().remove(ServiceRouterHandler.class);
                        context.getPipeline().remove(TransportRouterHandler.class);
                        context.getPipeline().remove(PreflightHandler.class);

                        webSocketReady(future.getChannel());
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
        textWebSocketFrameReceived(context, event, textWebSocketFrame);
    }

    private String formatWebSocketLocation(Channel channel, HttpRequest request) {
        boolean isSslEnabled = channel.getPipeline().get(SslHandler.class) != null;
        return isSslEnabled ? "wss://" : "ws://" + request.getHeader(HttpHeaders.Names.HOST) + request.getUri();
    }

}

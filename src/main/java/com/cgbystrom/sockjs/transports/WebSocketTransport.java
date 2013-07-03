package com.cgbystrom.sockjs.transports;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import com.cgbystrom.sockjs.handlers.SessionHandler;

public class WebSocketTransport extends AbstractWebSocketTransport {

    public WebSocketTransport(SessionHandler sessionHandler) {
        super(sessionHandler);
    }

    @Override
    protected void webSocketReady(Channel channel) {
        registerReceiver(new WebSocketReceiver(channel));
    }

    @Override
    protected void textWebSocketFrameReceived(ChannelHandlerContext context, MessageEvent event,
                                              TextWebSocketFrame textWebSocketFrame) throws Exception {
        String content = textWebSocketFrame.getText();
        for(String message : TransportUtils.decodeMessage(content)) {
            getSessionHandler().messageReceived(message);
        }
    }

    private final class WebSocketReceiver extends ResponseReceiver {

        private final ChannelFuture lastWriteFuture;

        public WebSocketReceiver(Channel channel) {
            super(channel);
            lastWriteFuture = Channels.succeededFuture(channel);
        }

        @Override
        protected boolean doSend(String frame) {
            boolean closed = isClosed();
            if(!closed) {
                getChannel().write(new TextWebSocketFrame(frame));
            }
            return !closed;
        }

        @Override
        public boolean doClose(int status, String reason) {
            boolean didClose = super.doClose(status, reason);
            if(didClose) {
                lastWriteFuture.addListener(ChannelFutureListener.CLOSE);
            }
            return didClose;
        }

    }

}
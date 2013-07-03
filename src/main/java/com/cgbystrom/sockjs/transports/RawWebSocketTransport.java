package com.cgbystrom.sockjs.transports;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import com.cgbystrom.sockjs.handlers.SessionHandler;

public class RawWebSocketTransport extends AbstractWebSocketTransport {

    public RawWebSocketTransport(SessionHandler sessionHandler) {
        super(sessionHandler);
    }

    @Override
    protected void webSocketReady(Channel channel) {
        registerReceiver(new RawWebSocketReceiver(channel));
    }

    @Override
    protected void textWebSocketFrameReceived(ChannelHandlerContext context, MessageEvent event,
                                              TextWebSocketFrame textWebSocketFrame) {
        String message = textWebSocketFrame.getText();
        getSessionHandler().messageReceived(message);
    }

    private final class RawWebSocketReceiver extends GenericReceiver {

        private ChannelFuture lastWriteFuture;

        public RawWebSocketReceiver(Channel channel) {
            super(channel);
            lastWriteFuture = Channels.succeededFuture(channel);
        }

        @Override
        public boolean doOpen() {
            return false;
        }

        @Override
        public boolean doWrite(String[] messages) {
            for(String message : messages) {
                TextWebSocketFrame textWebSocketFrame;
                textWebSocketFrame = new TextWebSocketFrame(message);
                lastWriteFuture = getChannel().write(textWebSocketFrame);
            }
            return !isClosed();
        }

        @Override
        public boolean doHeartbeat() {
            return !isClosed();
        }

        @Override
        public boolean doClose(int aStatus, String aReason) {
            lastWriteFuture.addListener(ChannelFutureListener.CLOSE);
            return !isClosed();
        }

    }

}

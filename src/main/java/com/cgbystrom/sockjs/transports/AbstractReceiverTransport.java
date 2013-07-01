package com.cgbystrom.sockjs.transports;

import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;

import com.cgbystrom.sockjs.handlers.SessionHandler;
import com.cgbystrom.sockjs.handlers.SessionHandler.Receiver;
import com.fasterxml.jackson.core.io.JsonStringEncoder;


public abstract class AbstractReceiverTransport extends AbstractTransport {

    private final AtomicReference<Receiver> receiver;

    public AbstractReceiverTransport(SessionHandler sessionHandler) {
        super(sessionHandler);
        this.receiver = new AtomicReference<Receiver>();
    }

    @Override
    public void channelClosed(ChannelHandlerContext context, ChannelStateEvent event) throws Exception {
        Receiver previousReceiver;
        previousReceiver = receiver.getAndSet(null);
        if(previousReceiver != null) {
            getSessionHandler().unregisterReceiver(previousReceiver);
        }
    }

    protected void registerReceiver(Receiver newReceiver) throws IllegalStateException {
        if(receiver.compareAndSet(null, newReceiver)) {
            getSessionHandler().registerReceiver(newReceiver);
        } else {
            throw new IllegalStateException("already registered");
        }
    }

    protected void unregisterReceiver() throws IllegalStateException {
        Receiver previousReceiver;
        previousReceiver = receiver.getAndSet(null);
        if(previousReceiver != null) {
            getSessionHandler().unregisterReceiver(previousReceiver);
        } else {
            throw new IllegalStateException("not registered");
        }
    }

    public abstract class GenericReceiver implements Receiver {

        private final Channel channel;

        private boolean closed = false;

        public GenericReceiver(Channel channel) {
            if(channel == null) {
                throw new NullPointerException("channel");
            }
            this.channel = channel;
        }

        protected synchronized boolean setClosed() {
            if(!closed) {
                closed = true;
                unregisterReceiver();
                return true;
            }
            return false;
        }

        protected Channel getChannel() {
            return channel;
        }

        @Override
        public synchronized boolean isClosed() {
            return closed;
        }

        @Override
        public SocketAddress getLocalAddress() {
            return channel.getLocalAddress();
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return channel.getRemoteAddress();
        }

        @Override
        public String toString() {
            return "Receiver " + channel;
        }

    }

    public abstract class ResponseReceiver extends GenericReceiver {

        public ResponseReceiver(Channel channel) {
            super(channel);
        }

        @Override
        public boolean doOpen() {
            return doSend("o");
        }

        @Override
        public boolean doWrite(String[] messages) {
            StringBuilder frameBuilder;
            frameBuilder = new StringBuilder();
            frameBuilder.append('a');
            frameBuilder.append('[');
            for(String message : messages) {
                if(messages[0] != message) {
                    frameBuilder.append(',');
                }
                frameBuilder.append('"');
                frameBuilder.append(new JsonStringEncoder().quoteAsString(message));
                frameBuilder.append('"');
            }
            frameBuilder.append(']');

            return doSend(frameBuilder.toString());
        }

        @Override
        public boolean doHeartbeat() {
            return doSend("h");
        }

        @Override
        public boolean doClose(int status, String reason) {
            StringBuilder frameBuilder;
            frameBuilder = new StringBuilder();
            frameBuilder.append('c');
            frameBuilder.append('[');
            frameBuilder.append(status);
            frameBuilder.append(',');
            frameBuilder.append('"');
            frameBuilder.append(new JsonStringEncoder().quoteAsString(reason));
            frameBuilder.append('"');
            frameBuilder.append(']');

            return doSend(frameBuilder.toString()) && setClosed();

        }

        protected abstract boolean doSend(String frame);

    }

}

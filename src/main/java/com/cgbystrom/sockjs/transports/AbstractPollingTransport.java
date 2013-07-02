package com.cgbystrom.sockjs.transports;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpResponse;

import com.cgbystrom.sockjs.handlers.SessionHandler;


public abstract class AbstractPollingTransport extends AbstractReceiverTransport {

    public AbstractPollingTransport(SessionHandler sessionHandler) {
        super(sessionHandler);
    }

    public abstract class SingleResponseReceiver extends ResponseReceiver {

        private final HttpResponse httpResponse;

        public SingleResponseReceiver(Channel aChannel, HttpResponse httpResponse) {
            super(aChannel);
            if(httpResponse == null) {
                throw new NullPointerException("httpResponse");
            }
            this.httpResponse = httpResponse;
        }

        @Override
        protected synchronized boolean doSend(String frame) {
            boolean closed = isClosed();

            if(!closed) {
                ChannelBuffer contentBuffer;
                contentBuffer = formatFrame(frame);

                httpResponse.setHeader(HttpHeaders.Names.CONTENT_LENGTH, contentBuffer.readableBytes());
                httpResponse.setContent(contentBuffer);

                ChannelFuture writeFuture;
                writeFuture = getChannel().write(httpResponse);

                if(!isKeepAliveEnabled()) {
                    writeFuture.addListener(ChannelFutureListener.CLOSE);
                }

                setClosed();
            }

            return !closed;
        }

        protected abstract ChannelBuffer formatFrame(String frame);

    }

}

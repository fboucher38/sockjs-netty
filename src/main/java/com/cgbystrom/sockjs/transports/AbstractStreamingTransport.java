package com.cgbystrom.sockjs.transports;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.handler.codec.http.DefaultHttpChunk;
import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpResponse;

import com.cgbystrom.sockjs.handlers.SessionHandler;

/**
 * Base class for streaming transports
 *
 * Handles HTTP chunking and response size limiting for browser "garbage collection".
 */
public abstract class AbstractStreamingTransport extends AbstractReceiverTransport {

    public AbstractStreamingTransport(SessionHandler sessionHandler) {
        super(sessionHandler);
    }

    @Override
    protected HttpResponse createResponse(Channel channel, String contentType) {
        HttpResponse response = super.createResponse(channel, contentType);
        response.setHeader(HttpHeaders.Names.TRANSFER_ENCODING, HttpHeaders.Values.CHUNKED);
        return response;
    }

    public abstract class AbstractStreamingReceiver extends ResponseReceiver {

        private final long responseSizeLimit;

        private long responseSize;

        public AbstractStreamingReceiver(Channel channel, long responseSizeLimit) {
            super(channel);
            this.responseSizeLimit = responseSizeLimit;
        }

        @Override
        protected synchronized boolean doSend(String frame) {
            boolean closed = isClosed();

            if(!closed) {
                ChannelBuffer frameBuffer = formatFrame(frame);
                HttpChunk frameChunk = new DefaultHttpChunk(frameBuffer);
                getChannel().write(frameChunk);

                if((responseSize = responseSize + frame.length()) > responseSizeLimit) {
                    setClosed();
                }
            }

            return !closed;
        }

        @Override
        protected boolean setClosed() {
            boolean closing = super.setClosed();
            if(closing) {
                ChannelFuture writeFuture;
                writeFuture = getChannel().write(HttpChunk.LAST_CHUNK);

                if(!isKeepAliveEnabled()) {
                    writeFuture.addListener(ChannelFutureListener.CLOSE);
                }
            }
            return closing;
        }

        protected abstract ChannelBuffer formatFrame(String frame);

    }

}

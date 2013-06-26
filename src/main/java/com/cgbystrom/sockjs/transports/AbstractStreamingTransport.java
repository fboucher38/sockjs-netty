package com.cgbystrom.sockjs.transports;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.WriteCompletionEvent;
import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpVersion;

import com.cgbystrom.sockjs.handlers.HttpRequestHandler;

/**
 * Base class for streaming transports
 *
 * Handles HTTP chunking and response size limiting for browser "garbage collection".
 */
public abstract class AbstractStreamingTransport extends AbstractTransport {

    /**
     *  Max size of response content sent before closing the connection.
     *  Since browsers buffer chunked/streamed content in-memory the connection must be closed
     *  at regular intervals. Call it "garbage collection" if you will.
     */
    protected final long responseLimit;

    /** Track size of content chunks sent to the browser. */
    protected AtomicLong numBytesSent = new AtomicLong(0);

    /** For streaming/chunked transports we need to send HTTP header only once (naturally) */
    protected AtomicBoolean headerSent = new AtomicBoolean(false);

    /** Keep track if ending HTTP chunk has been sent */
    private final AtomicBoolean lastChunkSent = new AtomicBoolean(false);

    public AbstractStreamingTransport() {
        this(128 * 1024); // 128 KiB
    }

    public AbstractStreamingTransport(int responseLimit) {
        this.responseLimit = responseLimit;
    }

    @Override
    public void closeRequested(ChannelHandlerContext context, ChannelStateEvent event) throws Exception {
        HttpRequest request;
        request = HttpRequestHandler.getRequestForChannel(context.getChannel());

        // request can be null since close can be requested prior to receiving a message.
        if (request != null && request.getProtocolVersion() == HttpVersion.HTTP_1_1 && lastChunkSent.compareAndSet(false, true)) {
            event.getChannel().write(HttpChunk.LAST_CHUNK).addListener(ChannelFutureListener.CLOSE);
        } else {
            super.closeRequested(context, event);
        }
    }

    @Override
    public void writeComplete(ChannelHandlerContext context, WriteCompletionEvent event) throws Exception {
        if (numBytesSent.addAndGet(event.getWrittenAmount()) >= responseLimit) {
            // Close the connection to allow the browser to flush in-memory buffered content from this XHR stream.
            event.getFuture().addListener(ChannelFutureListener.CLOSE);
        }
    }

    @Override
    protected HttpResponse createResponse(Channel channel, String contentType) {
        HttpResponse response = super.createResponse(channel, contentType);
        if (response.getProtocolVersion().equals(HttpVersion.HTTP_1_1)) {
            response.setHeader(HttpHeaders.Names.TRANSFER_ENCODING, HttpHeaders.Values.CHUNKED);
        }
        return response;
    }

}

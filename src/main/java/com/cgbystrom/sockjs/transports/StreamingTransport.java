package com.cgbystrom.sockjs.transports;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.WriteCompletionEvent;
import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpVersion;

/**
 * Base class for streaming transports
 *
 * Handles HTTP chunking and response size limiting for browser "garbage collection".
 */
public abstract class StreamingTransport extends BaseTransport {
    /**
     *  Max size of response content sent before closing the connection.
     *  Since browsers buffer chunked/streamed content in-memory the connection must be closed
     *  at regular intervals. Call it "garbage collection" if you will.
     */
    protected final long maxResponseSize;

    /** Track size of content chunks sent to the browser. */
    protected AtomicLong numBytesSent = new AtomicLong(0);

    /** For streaming/chunked transports we need to send HTTP header only once (naturally) */
    protected AtomicBoolean headerSent = new AtomicBoolean(false);

    /** Keep track if ending HTTP chunk has been sent */
    private final AtomicBoolean lastChunkSent = new AtomicBoolean(false);

    public StreamingTransport() {
        this.maxResponseSize = 128 * 1024; // 128 KiB
    }

    public StreamingTransport(int maxResponseSize) {
        this.maxResponseSize = maxResponseSize;
    }

    @Override
    public void closeRequested(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        // request can be null since close can be requested prior to receiving a message.
        if (request != null && request.getProtocolVersion() == HttpVersion.HTTP_1_1 && lastChunkSent.compareAndSet(false, true)) {
            e.getChannel().write(HttpChunk.LAST_CHUNK).addListener(ChannelFutureListener.CLOSE);
        } else {
            super.closeRequested(ctx, e);
        }
    }

    @Override
    public void writeComplete(ChannelHandlerContext context, WriteCompletionEvent event) throws Exception {
        if (numBytesSent.addAndGet(event.getWrittenAmount()) >= maxResponseSize) {
            // Close the connection to allow the browser to flush in-memory buffered content from this XHR stream.
            event.getFuture().addListener(ChannelFutureListener.CLOSE);
        }
        super.writeComplete(context, event);
    }

    @Override
    protected HttpResponse createResponse(String contentType) {
        HttpResponse response = super.createResponse(contentType);
        if (request.getProtocolVersion().equals(HttpVersion.HTTP_1_1)) {
            response.setHeader(HttpHeaders.Names.TRANSFER_ENCODING, HttpHeaders.Values.CHUNKED);
        }
        return response;
    }
}

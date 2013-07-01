package com.cgbystrom.sockjs.transports;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.handler.codec.http.DefaultHttpChunk;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.util.CharsetUtil;

import com.cgbystrom.sockjs.handlers.SessionHandler;

public class EventSourceTransport extends AbstractStreamingTransport {

    private static final String CONTENT_TYPE_EVENT_STREAM = "text/event-stream; charset=UTF-8";
    private final static byte[] PRE_EVENT_FRAME = "data: ".getBytes(CharsetUtil.UTF_8);
    private final static byte[] POST_EVENT_FRAME = "\r\n\r\n".getBytes(CharsetUtil.UTF_8);

    private final int responseSizeLimit;

    public EventSourceTransport(SessionHandler sessionHandler, int responseSizeLimit) {
        super(sessionHandler);
        this.responseSizeLimit = responseSizeLimit;
    }

    @Override
    public void messageReceived(ChannelHandlerContext context, MessageEvent event) throws Exception {
        HttpResponse response = createResponse(context.getChannel(), CONTENT_TYPE_EVENT_STREAM);
        Channels.write(context, Channels.succeededFuture(event.getChannel()), response);
        Channels.write(context, Channels.succeededFuture(event.getChannel()), new DefaultHttpChunk(ChannelBuffers.copiedBuffer("\r\n", CharsetUtil.UTF_8)));

        registerReceiver(new EventSourceResponseReceiver(context.getChannel(), responseSizeLimit));
    }

    private class EventSourceResponseReceiver extends AbstractStreamingReceiver {

        public EventSourceResponseReceiver(Channel channel, int responseSizeLimit) {
            super(channel, responseSizeLimit);
        }

        @Override
        protected ChannelBuffer formatFrame(String frame) {
            return ChannelBuffers.wrappedBuffer(
                    PRE_EVENT_FRAME,
                    frame.getBytes(CharsetUtil.UTF_8),
                    POST_EVENT_FRAME);
        }

    }
}
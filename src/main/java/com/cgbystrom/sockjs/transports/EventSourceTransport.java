package com.cgbystrom.sockjs.transports;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.handler.codec.http.DefaultHttpChunk;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.util.CharsetUtil;

import com.cgbystrom.sockjs.frames.Frame;
import com.cgbystrom.sockjs.frames.FrameEncoder;

public class EventSourceTransport extends AbstractStreamingTransport {

    private static final ChannelBuffer NEW_LINE = ChannelBuffers.copiedBuffer("\r\n", CharsetUtil.UTF_8);
    private static final ChannelBuffer FRAME_BEGIN = ChannelBuffers.copiedBuffer("data: ", CharsetUtil.UTF_8);
    private static final ChannelBuffer FRAME_END = ChannelBuffers.copiedBuffer("\r\n\r\n", CharsetUtil.UTF_8);
    private static final String CONTENT_TYPE_EVENT_STREAM = "text/event-stream; charset=UTF-8";

    @Override
    public void writeRequested(ChannelHandlerContext context, MessageEvent event) throws Exception {
        if (event.getMessage() instanceof Frame) {
            Frame frame = (Frame) event.getMessage();

            if (headerSent.compareAndSet(false, true)) {
                HttpResponse response = createResponse(context.getChannel(), CONTENT_TYPE_EVENT_STREAM);
                Channels.write(context, event.getFuture(), response);
                Channels.write(context, event.getFuture(), new DefaultHttpChunk(NEW_LINE));
            }

            ChannelBuffer wrappedContent;
            wrappedContent = ChannelBuffers.wrappedBuffer(FRAME_BEGIN, FrameEncoder.encode(frame, false), FRAME_END);
            Channels.write(context, event.getFuture(), new DefaultHttpChunk(wrappedContent));

        } else {
            super.writeRequested(context, event);
        }
    }
}

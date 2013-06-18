package com.cgbystrom.sockjs.transports;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.handler.codec.http.DefaultHttpChunk;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.logging.InternalLogger;
import org.jboss.netty.logging.InternalLoggerFactory;
import org.jboss.netty.util.CharsetUtil;

import com.cgbystrom.sockjs.frames.Frame;
import com.cgbystrom.sockjs.frames.FrameEncoder;

public class EventSourceTransport extends StreamingTransport {

    @SuppressWarnings("unused")
    private static final InternalLogger LOGGER = InternalLoggerFactory.getInstance(EventSourceTransport.class);

    private static final ChannelBuffer NEW_LINE = ChannelBuffers.copiedBuffer("\r\n", CharsetUtil.UTF_8);
    private static final ChannelBuffer FRAME_BEGIN = ChannelBuffers.copiedBuffer("data: ", CharsetUtil.UTF_8);
    private static final ChannelBuffer FRAME_END = ChannelBuffers.copiedBuffer("\r\n\r\n", CharsetUtil.UTF_8);
    private static final String CONTENT_TYPE_EVENT_STREAM = "text/event-stream; charset=UTF-8";

    public EventSourceTransport(int maxResponseSize) {
        super(maxResponseSize);
    }

    @Override
    public void writeRequested(ChannelHandlerContext context, MessageEvent event) throws Exception {
        if (event.getMessage() instanceof Frame[]) {
            Frame[] frames = (Frame[]) event.getMessage();

            if (headerSent.compareAndSet(false, true)) {
                HttpResponse response = createResponse(CONTENT_TYPE_EVENT_STREAM);
                Channels.write(context, event.getFuture(), response);
                Channels.write(context, event.getFuture(), new DefaultHttpChunk(NEW_LINE));
            }

            for(Frame frame : frames) {
                ChannelBuffer buffer;
                buffer = ChannelBuffers.wrappedBuffer(FRAME_BEGIN, FrameEncoder.encode(frame, false), FRAME_END);
                Channels.write(context, event.getFuture(), buffer);
            }

        } else {
            super.writeRequested(context, event);
        }
    }
}

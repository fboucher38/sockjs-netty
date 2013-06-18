package com.cgbystrom.sockjs.transports;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.DownstreamMessageEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.handler.codec.http.DefaultHttpChunk;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.logging.InternalLogger;
import org.jboss.netty.logging.InternalLoggerFactory;

import com.cgbystrom.sockjs.frames.Frame;
import com.cgbystrom.sockjs.frames.FrameEncoder;

public class XhrStreamingTransport extends StreamingTransport {

    @SuppressWarnings("unused")
    private static final InternalLogger LOGGER = InternalLoggerFactory.getInstance(XhrStreamingTransport.class);

    public XhrStreamingTransport(int maxResponseSize) {
        super(maxResponseSize);
    }

    @Override
    public void writeRequested(ChannelHandlerContext context, MessageEvent event) throws Exception {
        if (event.getMessage() instanceof Frame[]) {
            Frame[] frames = (Frame[]) event.getMessage();

            if (headerSent.compareAndSet(false, true)) {
                HttpResponse response = createResponse(CONTENT_TYPE_JAVASCRIPT);
                context.sendDownstream(new DownstreamMessageEvent(event.getChannel(), Channels.future(event.getChannel()), response, event.getRemoteAddress()));

                // IE requires 2KB prefix:
                // http://blogs.msdn.com/b/ieinternals/archive/2010/04/06/comet-streaming-in-internet-explorer-with-xmlhttprequest-and-xdomainrequest.aspx
                DefaultHttpChunk message = new DefaultHttpChunk(FrameEncoder.encode(Frame.preludeFrame(), true));
                context.sendDownstream(new DownstreamMessageEvent(event.getChannel(), Channels.future(event.getChannel()), message, event.getRemoteAddress()));
            }

            for(Frame frame : frames) {
                ChannelBuffer content = FrameEncoder.encode(frame, true);
                Channels.write(context, event.getFuture(), new DefaultHttpChunk(content));
            }

        } else {
            super.writeRequested(context, event);
        }
    }

}
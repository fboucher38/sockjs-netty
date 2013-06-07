package com.cgbystrom.sockjs.transports;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.ChannelFutureListener;
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
    public void writeRequested(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        if (e.getMessage() instanceof Frame) {
            if (headerSent.compareAndSet(false, true)) {
                HttpResponse response = createResponse(CONTENT_TYPE_JAVASCRIPT);
                ctx.sendDownstream(new DownstreamMessageEvent(e.getChannel(), Channels.future(e.getChannel()), response, e.getRemoteAddress()));

                // IE requires 2KB prefix:
                // http://blogs.msdn.com/b/ieinternals/archive/2010/04/06/comet-streaming-in-internet-explorer-with-xmlhttprequest-and-xdomainrequest.aspx
                DefaultHttpChunk message = new DefaultHttpChunk(FrameEncoder.encode(Frame.preludeFrame(), true));
                ctx.sendDownstream(new DownstreamMessageEvent(e.getChannel(), Channels.future(e.getChannel()), message, e.getRemoteAddress()));
            }
            final Frame frame = (Frame) e.getMessage();
            ChannelBuffer content = FrameEncoder.encode(frame, true);

            if (frame instanceof Frame.CloseFrame) {
                e.getFuture().addListener(ChannelFutureListener.CLOSE);
            }

            ctx.sendDownstream(new DownstreamMessageEvent(e.getChannel(), e.getFuture(), new DefaultHttpChunk(content), e.getRemoteAddress()));
            logResponseSize(e.getChannel(), content);
        } else {
            super.writeRequested(ctx, e);
        }
    }
}
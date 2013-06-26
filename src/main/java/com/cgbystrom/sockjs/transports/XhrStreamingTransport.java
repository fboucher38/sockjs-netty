package com.cgbystrom.sockjs.transports;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.handler.codec.http.DefaultHttpChunk;
import org.jboss.netty.handler.codec.http.HttpResponse;

import com.cgbystrom.sockjs.frames.Frame;
import com.cgbystrom.sockjs.frames.FrameEncoder;

public class XhrStreamingTransport extends AbstractStreamingTransport {

    @Override
    public void writeRequested(ChannelHandlerContext context, MessageEvent event) throws Exception {
        if (event.getMessage() instanceof Frame) {
            Frame frame = (Frame) event.getMessage();

            if (headerSent.compareAndSet(false, true)) {
                HttpResponse response = createResponse(context.getChannel(), CONTENT_TYPE_JAVASCRIPT);
                Channels.write(event.getChannel(), response);

                // IE requires 2KB prefix:
                // http://blogs.msdn.com/b/ieinternals/archive/2010/04/06/comet-streaming-in-internet-explorer-with-xmlhttprequest-and-xdomainrequest.aspx
                DefaultHttpChunk message = new DefaultHttpChunk(FrameEncoder.encode(Frame.preludeFrame(), true));
                Channels.write(event.getChannel(), message);
            }

            ChannelBuffer content = FrameEncoder.encode(frame, true);
            Channels.write(context, event.getFuture(), new DefaultHttpChunk(content));

        } else {
            super.writeRequested(context, event);
        }
    }

}
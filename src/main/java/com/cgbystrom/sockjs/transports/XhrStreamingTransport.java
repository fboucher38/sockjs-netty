package com.cgbystrom.sockjs.transports;

import java.util.Arrays;

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

public class XhrStreamingTransport extends AbstractStreamingTransport {

    private final static byte[] PRELUDE_FRAME;
    static {
        char[] preludeChars = new char[2048];
        Arrays.fill(preludeChars, 'h');
        PRELUDE_FRAME = (String.valueOf(preludeChars) + "\n").getBytes(CharsetUtil.UTF_8);
    }

    private final int responseSizeLimit;

    public XhrStreamingTransport(SessionHandler sessionHandler, int responseSizeLimit) {
        super(sessionHandler);
        this.responseSizeLimit = responseSizeLimit;
    }

    @Override
    public void messageReceived(ChannelHandlerContext context, MessageEvent event) throws Exception {
        HttpResponse response = createResponse(context.getChannel(), CONTENT_TYPE_JAVASCRIPT);
        Channels.write(context, Channels.succeededFuture(event.getChannel()), response);

        // IE requires 2KB prefix:
        // http://blogs.msdn.com/b/ieinternals/archive/2010/04/06/comet-streaming-in-internet-explorer-with-xmlhttprequest-and-xdomainrequest.aspx
        DefaultHttpChunk message = new DefaultHttpChunk(ChannelBuffers.copiedBuffer(PRELUDE_FRAME));
        Channels.write(context, Channels.succeededFuture(event.getChannel()), message);

        registerReceiver(new XhrStreamingResponseReceiver(context.getChannel(), responseSizeLimit));
    }

    private class XhrStreamingResponseReceiver extends AbstractStreamingReceiver {

        public XhrStreamingResponseReceiver(Channel channel, int responseSizeLimit) {
            super(channel, responseSizeLimit);
        }

        @Override
        protected ChannelBuffer formatFrame(String frame) {
            return ChannelBuffers.wrappedBuffer(
                    ChannelBuffers.copiedBuffer(frame, CharsetUtil.UTF_8),
                    ChannelBuffers.copiedBuffer("\n", CharsetUtil.UTF_8));
        }

    }

}
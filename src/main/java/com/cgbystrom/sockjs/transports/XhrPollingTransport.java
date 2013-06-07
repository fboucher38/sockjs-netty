package com.cgbystrom.sockjs.transports;

import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CONNECTION;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CONTENT_LENGTH;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Values.CLOSE;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.DownstreamMessageEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.logging.InternalLogger;
import org.jboss.netty.logging.InternalLoggerFactory;

import com.cgbystrom.sockjs.frames.Frame;
import com.cgbystrom.sockjs.frames.FrameEncoder;

public class XhrPollingTransport extends BaseTransport {

    @SuppressWarnings("unused")
    private static final InternalLogger LOGGER = InternalLoggerFactory.getInstance(XhrPollingTransport.class);

    @Override
    public void writeRequested(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        if (e.getMessage() instanceof Frame) {
            Frame frame = (Frame) e.getMessage();
            ChannelBuffer content = FrameEncoder.encode(frame, true);
            HttpResponse response = createResponse(CONTENT_TYPE_JAVASCRIPT);
            response.setHeader(CONTENT_LENGTH, content.readableBytes());
            response.setHeader(CONNECTION, CLOSE);
            response.setContent(content);
            e.getFuture().addListener(ChannelFutureListener.CLOSE);
            ctx.sendDownstream(new DownstreamMessageEvent(e.getChannel(), e.getFuture(), response, e.getRemoteAddress()));
        } else {
            super.writeRequested(ctx, e);
        }
    }
}
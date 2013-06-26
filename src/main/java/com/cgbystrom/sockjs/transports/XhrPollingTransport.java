package com.cgbystrom.sockjs.transports;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpResponse;

import com.cgbystrom.sockjs.frames.Frame;
import com.cgbystrom.sockjs.frames.FrameEncoder;

public class XhrPollingTransport extends AbstractPollingTransport {

    @Override
    public void writeRequested(ChannelHandlerContext context, MessageEvent event) throws Exception {
        if (event.getMessage() instanceof Frame) {
            Frame frame = (Frame) event.getMessage();

            ChannelBuffer frameBuffer;
            frameBuffer = FrameEncoder.encode(frame, true);

            HttpResponse response = createResponse(context.getChannel(), CONTENT_TYPE_JAVASCRIPT);
            response.setHeader(HttpHeaders.Names.CONTENT_LENGTH, frameBuffer.readableBytes());
            response.setHeader(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE);
            response.setContent(frameBuffer);

            event.getFuture().addListener(ChannelFutureListener.CLOSE);
            Channels.write(context, event.getFuture(), response);

        } else {
            super.writeRequested(context, event);
        }
    }

}
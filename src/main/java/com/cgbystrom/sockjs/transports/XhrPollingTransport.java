package com.cgbystrom.sockjs.transports;

import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CONNECTION;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CONTENT_LENGTH;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Values.CLOSE;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.Channels;
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
    public void writeRequested(ChannelHandlerContext context, MessageEvent event) throws Exception {
        if (event.getMessage() instanceof Frame[]) {
            Frame[] frames = (Frame[]) event.getMessage();

            ChannelBuffer outputBuffer = ChannelBuffers.dynamicBuffer();
            for(Frame frame : frames) {
                outputBuffer.writeBytes(FrameEncoder.encode(frame, true));
            }

            HttpResponse response = createResponse(CONTENT_TYPE_JAVASCRIPT);
            response.setHeader(CONTENT_LENGTH, outputBuffer.readableBytes());
            response.setHeader(CONNECTION, CLOSE);
            response.setContent(outputBuffer);

            event.getFuture().addListener(ChannelFutureListener.CLOSE);
            Channels.write(context, event.getFuture(), response);

        } else {
            super.writeRequested(context, event);
        }
    }
}
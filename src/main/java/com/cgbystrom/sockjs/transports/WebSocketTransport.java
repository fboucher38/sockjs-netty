package com.cgbystrom.sockjs.transports;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import com.cgbystrom.sockjs.frames.Frame;
import com.cgbystrom.sockjs.frames.FrameDecoder;
import com.cgbystrom.sockjs.frames.FrameEncoder;

public class WebSocketTransport extends AbstractWebSocketTransport {

    @Override
    protected void handleReceivedTextWebSocketFrame(ChannelHandlerContext context, MessageEvent event,
                                                    TextWebSocketFrame aTextWebSocketFrame) throws Exception {
        String content = aTextWebSocketFrame.getText();
        for(String message : FrameDecoder.decodeMessage(content)) {
            Channels.fireMessageReceived(context, message);
        }
    }

    @Override
    protected void handleWroteSockJsFrame(ChannelHandlerContext context, MessageEvent event, Frame frame) {
        if (frame instanceof Frame.CloseFrame) {
            event.getFuture().addListener(ChannelFutureListener.CLOSE);
        }
        ChannelBuffer encodedFrame = FrameEncoder.encode((Frame) event.getMessage(), false);
        TextWebSocketFrame message = new TextWebSocketFrame(encodedFrame);
        Channels.write(context, event.getFuture(), message);
    }

}
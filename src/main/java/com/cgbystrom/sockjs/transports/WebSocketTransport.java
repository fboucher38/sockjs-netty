package com.cgbystrom.sockjs.transports;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.DownstreamMessageEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import com.cgbystrom.sockjs.frames.Frame;
import com.cgbystrom.sockjs.frames.Frame.MessageFrame;
import com.cgbystrom.sockjs.frames.FrameDecoder;
import com.cgbystrom.sockjs.frames.FrameEncoder;

public class WebSocketTransport extends BaseWebSocketTransport {

    public WebSocketTransport(String aPath) {
        super(aPath);
    }

    @Override
    protected void handleReceivedTextWebSocketFrame(ChannelHandlerContext context, MessageEvent event,
                                                    TextWebSocketFrame aTextWebSocketFrame) throws Exception {

        String content = aTextWebSocketFrame.getText();
        MessageFrame messageFrame = FrameDecoder.decodeMessage(content);
        context.sendUpstream(new DownstreamMessageEvent(event.getChannel(), event.getFuture(), messageFrame, event.getRemoteAddress()));
    }

    @Override
    protected void handleWroteSockJsFrame(ChannelHandlerContext context, MessageEvent event, Frame frame) {
        if (frame instanceof Frame.CloseFrame) {
            event.getFuture().addListener(ChannelFutureListener.CLOSE);
        }
        ChannelBuffer encodedFrame = FrameEncoder.encode((Frame) event.getMessage(), false);
        TextWebSocketFrame message = new TextWebSocketFrame(encodedFrame);
        context.sendDownstream(new DownstreamMessageEvent(event.getChannel(), event.getFuture(), message, event.getRemoteAddress()));
    }

}
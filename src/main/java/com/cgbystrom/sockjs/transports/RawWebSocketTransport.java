package com.cgbystrom.sockjs.transports;

import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.DownstreamMessageEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.UpstreamMessageEvent;
import org.jboss.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import com.cgbystrom.sockjs.frames.Frame;
import com.cgbystrom.sockjs.frames.Frame.CloseFrame;
import com.cgbystrom.sockjs.frames.Frame.MessageFrame;

public class RawWebSocketTransport extends BaseWebSocketTransport {

    public RawWebSocketTransport(String aPath) {
        super(aPath);
    }

    @Override
    protected void handleReceivedTextWebSocketFrame(ChannelHandlerContext context, MessageEvent event,
                                                    TextWebSocketFrame textWebSocketFrame) {
        String content = textWebSocketFrame.getText();
        context.sendDownstream(new DownstreamMessageEvent(event.getChannel(), event.getFuture(), content, event.getRemoteAddress()));
    }

    @Override
    protected void handleWroteSockJsFrame(ChannelHandlerContext context, MessageEvent event, Frame frame) {
        if(frame instanceof MessageFrame) {
            for(String message : ((MessageFrame)frame).getMessages()) {
                TextWebSocketFrame textWebSocketFrame;
                textWebSocketFrame = new TextWebSocketFrame(message);
                context.sendUpstream(new UpstreamMessageEvent(event.getChannel(), textWebSocketFrame, event.getRemoteAddress()));
            }
        } else if(frame instanceof CloseFrame) {
            event.getFuture().addListener(ChannelFutureListener.CLOSE);
        }
    }

}

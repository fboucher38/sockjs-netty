package com.cgbystrom.sockjs.transports;

import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import com.cgbystrom.sockjs.frames.Frame;
import com.cgbystrom.sockjs.frames.Frame.CloseFrame;
import com.cgbystrom.sockjs.frames.Frame.MessageFrame;

public class RawWebSocketTransport extends AbstractWebSocketTransport {

    @Override
    protected void handleReceivedTextWebSocketFrame(ChannelHandlerContext context, MessageEvent event,
                                                    TextWebSocketFrame textWebSocketFrame) {
        String message = textWebSocketFrame.getText();
        Channels.fireMessageReceived(context, message);
    }

    @Override
    protected void handleWroteSockJsFrame(ChannelHandlerContext context, MessageEvent event, Frame frame) {
        if(frame instanceof MessageFrame) {
            for(String message : ((MessageFrame)frame).getMessages()) {
                TextWebSocketFrame textWebSocketFrame;
                textWebSocketFrame = new TextWebSocketFrame(message);
                Channels.write(context, event.getFuture(), textWebSocketFrame);
            }
        } else if(frame instanceof CloseFrame) {
            event.getFuture().addListener(ChannelFutureListener.CLOSE);
        }
    }

}

package com.cgbystrom.sockjs.transports;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.util.CharsetUtil;

import com.cgbystrom.sockjs.handlers.SessionHandler;

public class XhrPollingTransport extends AbstractPollingTransport {

    public XhrPollingTransport(SessionHandler sessionHandler) {
        super(sessionHandler);
    }

    @Override
    public void messageReceived(ChannelHandlerContext context, MessageEvent event) throws Exception {
        super.messageReceived(context, event);

        HttpResponse response = createResponse(context.getChannel(), CONTENT_TYPE_JAVASCRIPT);
        registerReceiver(new XhrResponseReceiver(context.getChannel(), response));
    }

    private class XhrResponseReceiver extends SingleResponseReceiver {

        public XhrResponseReceiver(Channel aChannel, HttpResponse httpResponse) {
            super(aChannel, httpResponse);
        }

        @Override
        protected ChannelBuffer formatFrame(String frame) {
            return ChannelBuffers.wrappedBuffer(
                    ChannelBuffers.copiedBuffer(frame, CharsetUtil.UTF_8),
                    ChannelBuffers.copiedBuffer("\n", CharsetUtil.UTF_8));
        }

    }

}
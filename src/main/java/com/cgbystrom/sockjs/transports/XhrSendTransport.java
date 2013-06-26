package com.cgbystrom.sockjs.transports;

import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;

public class XhrSendTransport extends AbstractSendTransport {

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        super.messageReceived(ctx, e);
        respondAndClose(e.getChannel(), HttpResponseStatus.NO_CONTENT, "ok");
    }

}

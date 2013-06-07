package com.cgbystrom.sockjs.transports;

import static org.jboss.netty.handler.codec.http.HttpResponseStatus.OK;

import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.MessageEvent;

public class JsonpSendTransport extends SendTransport {

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        super.messageReceived(ctx, e);
        BaseTransport.respondAndClose(e.getChannel(), OK, "ok");
    }

}

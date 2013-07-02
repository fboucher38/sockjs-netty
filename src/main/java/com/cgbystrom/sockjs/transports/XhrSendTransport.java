package com.cgbystrom.sockjs.transports;

import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;

import com.cgbystrom.sockjs.handlers.SessionHandler;

public class XhrSendTransport extends AbstractSendTransport {

    public XhrSendTransport(SessionHandler sessionHandler) {
        super(sessionHandler);
    }

    @Override
    public void messageReceived(ChannelHandlerContext context, MessageEvent event) throws Exception {
        super.messageReceived(context, event);

        respond(event.getChannel(), HttpResponseStatus.NO_CONTENT);
    }

}

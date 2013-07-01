package com.cgbystrom.sockjs.transports;

import static org.jboss.netty.handler.codec.http.HttpResponseStatus.OK;

import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.MessageEvent;

import com.cgbystrom.sockjs.handlers.SessionHandler;

public class JsonpSendTransport extends AbstractSendTransport {

    public JsonpSendTransport(SessionHandler sessionHandler) {
        super(sessionHandler);
    }

    @Override
    public void messageReceived(ChannelHandlerContext context, MessageEvent event) throws Exception {
        super.messageReceived(context, event);
        respond(event.getChannel(), OK, "ok");
    }

}

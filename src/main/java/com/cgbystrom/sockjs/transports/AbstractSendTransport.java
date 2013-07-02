package com.cgbystrom.sockjs.transports;

import static org.jboss.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;

import java.util.List;

import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.QueryStringDecoder;
import org.jboss.netty.util.CharsetUtil;

import com.cgbystrom.sockjs.handlers.SessionHandler;

public abstract class AbstractSendTransport extends AbstractTransport {

    public AbstractSendTransport(SessionHandler sessionHandler) {
        super(sessionHandler);
    }

    @Override
    public void messageReceived(ChannelHandlerContext context, MessageEvent event) throws Exception {
        super.messageReceived(context, event);

        HttpRequest request = (HttpRequest) event.getMessage();

        if (request.getContent().readableBytes() == 0) {
            respondAndClose(event.getChannel(), INTERNAL_SERVER_ERROR, "Payload expected.");
            return;
        }

        String contentTypeHeader = request.getHeader(HttpHeaders.Names.CONTENT_TYPE);
        if (contentTypeHeader == null) {
            contentTypeHeader = AbstractTransport.CONTENT_TYPE_PLAIN;
        }

        String decodedContent;
        if (AbstractTransport.CONTENT_TYPE_FORM.equals(contentTypeHeader)) {
            QueryStringDecoder decoder = new QueryStringDecoder("?" + request.getContent().toString(CharsetUtil.UTF_8));
            List<String> d = decoder.getParameters().get("d");
            if (d == null) {
                respondAndClose(event.getChannel(), HttpResponseStatus.INTERNAL_SERVER_ERROR, "Payload expected.");
                return;
            }
            decodedContent = d.get(0);
        } else {
            decodedContent = request.getContent().toString(CharsetUtil.UTF_8);
        }

        if (decodedContent.length() == 0) {
            respondAndClose(event.getChannel(), HttpResponseStatus.INTERNAL_SERVER_ERROR, "Payload expected.");
            return;
        }

        String[] decodedMessageArray;
        decodedMessageArray = TransportUtils.decodeMessage(decodedContent);
        for(String message : decodedMessageArray) {
            getSessionHandler().messageReceived(message);
        }

        if(!isKeepAliveEnabled()) {
            event.getFuture().addListener(ChannelFutureListener.CLOSE);
        }
    }

}

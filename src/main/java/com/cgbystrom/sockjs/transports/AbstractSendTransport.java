package com.cgbystrom.sockjs.transports;

import static org.jboss.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;

import java.util.List;

import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.QueryStringDecoder;
import org.jboss.netty.util.CharsetUtil;

import com.cgbystrom.sockjs.frames.FrameDecoder;

public abstract class AbstractSendTransport extends AbstractTransport {

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        HttpRequest request = (HttpRequest) e.getMessage();

        if (request.getContent().readableBytes() == 0) {
            respondAndClose(e.getChannel(), INTERNAL_SERVER_ERROR, "Payload expected.");
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
                respondAndClose(e.getChannel(), HttpResponseStatus.INTERNAL_SERVER_ERROR, "Payload expected.");
                return;
            }
            decodedContent = d.get(0);
        } else {
            decodedContent = request.getContent().toString(CharsetUtil.UTF_8);
        }

        if (decodedContent.length() == 0) {
            respondAndClose(e.getChannel(), HttpResponseStatus.INTERNAL_SERVER_ERROR, "Payload expected.");
            return;
        }

        String[] decodedMessageArray;
        decodedMessageArray = FrameDecoder.decodeMessage(decodedContent);
        for(String message : decodedMessageArray) {
            Channels.fireMessageReceived(ctx, message);
        }
    }

    @Override
    public void channelClosed(ChannelHandlerContext context, ChannelStateEvent event) throws Exception {
        // do not forward
    }

}

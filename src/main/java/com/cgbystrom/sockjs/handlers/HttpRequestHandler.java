/**
 * 
 */
package com.cgbystrom.sockjs.handlers;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.handler.codec.http.HttpRequest;

/**
 * @author fbou
 *
 */
public class HttpRequestHandler extends SimpleChannelHandler {

    private final static String HTTP_REQUEST_ATTRIBUTE_NAME = "httpRequest";

    @Override
    public void messageReceived(ChannelHandlerContext context, MessageEvent event) throws Exception {
        if(event.getMessage() instanceof HttpRequest) {
            AttachmentHelper.setAttribute(context.getChannel(), HTTP_REQUEST_ATTRIBUTE_NAME, event.getMessage());
        }
        super.messageReceived(context, event);
    }

    public static HttpRequest getRequestForChannel(Channel channel) {
        HttpRequest request;
        request = (HttpRequest) AttachmentHelper.getAttribute(channel, HTTP_REQUEST_ATTRIBUTE_NAME);

        if(request == null) {
            throw new IllegalStateException("request not initialized");
        }

        return request;
    }



}

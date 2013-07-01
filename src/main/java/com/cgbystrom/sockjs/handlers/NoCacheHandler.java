/**
 * 
 */
package com.cgbystrom.sockjs.handlers;

import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelDownstreamHandler;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpResponse;

public class NoCacheHandler extends SimpleChannelDownstreamHandler {

    @Override
    public void writeRequested(ChannelHandlerContext context, MessageEvent event) throws Exception {
        if(event.getMessage() instanceof HttpResponse) {
            HttpResponse response = (HttpResponse)event.getMessage();
            response.setHeader(HttpHeaders.Names.CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0");
        }
        super.writeRequested(context, event);
    }

}

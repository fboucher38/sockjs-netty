package com.cgbystrom.sockjs.pages;

import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CACHE_CONTROL;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;

import java.util.Random;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.util.CharsetUtil;

import com.cgbystrom.sockjs.Service;

public class InfoPage extends SimpleChannelHandler {

    private static final Random RANDOM = new Random();

    private final Service service;

    public InfoPage(Service service) {
        this.service = service;
    }

    @Override
    public void messageReceived(ChannelHandlerContext context, MessageEvent event) throws Exception {
        HttpRequest request;
        request = (HttpRequest) event.getMessage();

        HttpResponse response;
        response = new DefaultHttpResponse(request.getProtocolVersion(), HttpResponseStatus.OK);
        response.setHeader(CONTENT_TYPE, "application/json; charset=UTF-8");
        response.setHeader(CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0");
        response.setContent(formatInfoForService(service));

        context.getChannel().write(response).addListener(ChannelFutureListener.CLOSE);

    }

    private ChannelBuffer formatInfoForService(Service service) {
        StringBuilder sb = new StringBuilder(100);
        sb.append("{");
        sb.append("\"websocket\": ");
        sb.append(service.isWebSocketEnabled());
        sb.append(", ");
        sb.append("\"origins\": [\"*:*\"], ");
        sb.append("\"cookie_needed\": ");
        sb.append(service.isJsessionid());
        sb.append(", ");
        sb.append("\"entropy\": ");
        sb.append(RANDOM.nextInt(Integer.MAX_VALUE) + 1);
        sb.append("}");
        return ChannelBuffers.copiedBuffer(sb.toString(), CharsetUtil.UTF_8);
    }

}

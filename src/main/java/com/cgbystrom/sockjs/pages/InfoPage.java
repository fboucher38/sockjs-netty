package com.cgbystrom.sockjs.pages;

import java.util.Random;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpHeaders;
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

        ChannelBuffer infoBuffer;
        infoBuffer = formatInfoForService(service);

        HttpResponse response;
        response = new DefaultHttpResponse(request.getProtocolVersion(), HttpResponseStatus.OK);
        response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/json; charset=UTF-8");
        response.setHeader(HttpHeaders.Names.CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0");
        response.setHeader(HttpHeaders.Names.CONTENT_LENGTH, infoBuffer.readableBytes());
        response.setContent(infoBuffer);

        context.getChannel().write(response);
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

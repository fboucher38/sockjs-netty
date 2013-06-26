package com.cgbystrom.sockjs.transports;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.jboss.netty.util.CharsetUtil;

import com.cgbystrom.sockjs.handlers.HttpRequestHandler;

public abstract class AbstractTransport extends SimpleChannelHandler {

    public static final String CONTENT_TYPE_JAVASCRIPT = "application/javascript; charset=UTF-8";
    public static final String CONTENT_TYPE_FORM = "application/x-www-form-urlencoded";
    public static final String CONTENT_TYPE_PLAIN = "text/plain; charset=UTF-8";
    public static final String CONTENT_TYPE_HTML = "text/html; charset=UTF-8";

    @Override
    public void channelOpen(ChannelHandlerContext context, ChannelStateEvent event) throws Exception {
        // do not forward
    }

    @Override
    public void channelConnected(ChannelHandlerContext aCtx, ChannelStateEvent aE) throws Exception {
        // do not forward
    }

    @Override
    public void messageReceived(ChannelHandlerContext context, MessageEvent event) throws Exception {
        if(!(event.getMessage() instanceof HttpRequest)) {
            throw new IllegalArgumentException("HttpRequest expected");
        }

        // Since we have silenced the usual channel state events for open and connected for the socket,
        // we must notify handlers downstream to now consider this connection connected.
        // We are responsible for manually dispatching this event upstream
        Channels.fireChannelOpen(context);
    }

    protected HttpResponse createResponse(Channel channel, String contentType) {
        HttpRequest request;
        request = HttpRequestHandler.getRequestForChannel(channel);

        if(request == null) {
            throw new IllegalStateException("request not initialized");
        }

        HttpVersion version;
        version = request.getProtocolVersion();

        HttpResponse response;
        response = new DefaultHttpResponse(version, HttpResponseStatus.OK);
        response.setHeader(HttpHeaders.Names.CONTENT_TYPE, contentType);

        return response;
    }

    protected void respondAndClose(Channel channel, HttpResponseStatus status, String message) throws Exception {
        ChannelBuffer buffer;
        buffer = ChannelBuffers.copiedBuffer(message, CharsetUtil.UTF_8);

        HttpResponse response;
        response = createResponse(channel, AbstractTransport.CONTENT_TYPE_PLAIN);
        response.setContent(buffer);
        response.setHeader(HttpHeaders.Names.CONTENT_LENGTH, buffer.readableBytes());

        channel.write(response).addListener(ChannelFutureListener.CLOSE);

    }

}

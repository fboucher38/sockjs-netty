package com.cgbystrom.sockjs.transports;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.jboss.netty.util.CharsetUtil;

import com.cgbystrom.sockjs.handlers.ServiceRouterHandler;
import com.cgbystrom.sockjs.handlers.SessionHandler;

public abstract class AbstractTransport extends SimpleChannelHandler {

    public static final String CONTENT_TYPE_JAVASCRIPT = "application/javascript; charset=UTF-8";
    public static final String CONTENT_TYPE_FORM = "application/x-www-form-urlencoded";
    public static final String CONTENT_TYPE_PLAIN = "text/plain; charset=UTF-8";
    public static final String CONTENT_TYPE_HTML = "text/html; charset=UTF-8";

    public static final ChannelFutureListener CLOSE_IF_NOT_KEEP_ALIVE = new ChannelFutureListener() {
        @Override
        public void operationComplete(ChannelFuture future) throws Exception {
            if(future.isSuccess() && future.getChannel().isOpen() && !isRequestKeepAlive(future.getChannel())) {
                future.getChannel().close();
            }
        }
    };

    private final SessionHandler sessionHandler;

    public AbstractTransport(SessionHandler sessionHandler) {
        if(sessionHandler == null) {
            throw new NullPointerException("sessionHandler");
        }
        this.sessionHandler = sessionHandler;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, ExceptionEvent event) throws Exception {
        getSessionHandler().exceptionCaught(event.getCause());
        if(event.getChannel().isOpen()) {
            event.getChannel().close();
        }
    }

    protected SessionHandler getSessionHandler() {
        return sessionHandler;
    }

    protected HttpResponse createResponse(Channel channel, String contentType, HttpResponseStatus status) {
        HttpResponse response;
        response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, status);
        response.setHeader(HttpHeaders.Names.CONTENT_TYPE, contentType);
        response.setHeader(HttpHeaders.Names.CONNECTION,  isRequestKeepAlive(channel) ? HttpHeaders.Values.KEEP_ALIVE : HttpHeaders.Values.CLOSE);

        return response;
    }

    protected HttpResponse createResponse(Channel channel, String contentType) {
        return createResponse(channel, contentType, HttpResponseStatus.OK);
    }

    protected void respond(Channel channel, HttpResponseStatus status, String message) throws Exception {
        ChannelBuffer buffer;
        buffer = ChannelBuffers.copiedBuffer(message, CharsetUtil.UTF_8);

        HttpResponse response;
        response = createResponse(channel, AbstractTransport.CONTENT_TYPE_PLAIN, status);
        response.setContent(buffer);
        response.setHeader(HttpHeaders.Names.CONTENT_LENGTH, buffer.readableBytes());

        channel.write(response);
    }

    protected void respondAndClose(Channel channel, HttpResponseStatus status, String message) throws Exception {
        ChannelBuffer buffer;
        buffer = ChannelBuffers.copiedBuffer(message, CharsetUtil.UTF_8);

        HttpResponse response;
        response = createResponse(channel, AbstractTransport.CONTENT_TYPE_PLAIN, status);
        response.setContent(buffer);
        response.setHeader(HttpHeaders.Names.CONTENT_LENGTH, buffer.readableBytes());
        response.setHeader(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE);

        channel.write(response).addListener(ChannelFutureListener.CLOSE);
    }

    private static boolean isRequestKeepAlive(Channel channel) {
        HttpRequest request;
        request = ServiceRouterHandler.getRequestForChannel(channel);

        boolean isKeepAlive;
        isKeepAlive = request.getHeaders(HttpHeaders.Names.CONNECTION).isEmpty() || !request.getHeaders(HttpHeaders.Names.CONNECTION).contains(HttpHeaders.Values.CLOSE);

        return isKeepAlive;
    }

}
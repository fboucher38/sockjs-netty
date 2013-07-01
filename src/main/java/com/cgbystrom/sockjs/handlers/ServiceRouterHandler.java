/**
 * 
 */
package com.cgbystrom.sockjs.handlers;

import java.util.List;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.jboss.netty.handler.timeout.IdleState;
import org.jboss.netty.handler.timeout.IdleStateAwareChannelHandler;
import org.jboss.netty.handler.timeout.IdleStateEvent;
import org.jboss.netty.logging.InternalLogger;
import org.jboss.netty.logging.InternalLoggerFactory;
import org.jboss.netty.util.CharsetUtil;

import com.cgbystrom.sockjs.Service;
import com.cgbystrom.sockjs.Service.SessionNotFound;

public class ServiceRouterHandler extends IdleStateAwareChannelHandler {

    private static final InternalLogger LOGGER = InternalLoggerFactory.getInstance(ServiceRouterHandler.class);

    private final static String SERVICE_ATTRIBUTE_NAME = "service";
    private final static String HTTP_REQUEST_ATTRIBUTE_NAME = "httpRequest";

    private final List<Service> services;
    private final String javascriptLibraryUrl;

    public ServiceRouterHandler(List<Service> services, String javascriptLibraryUrl) {
        this.services = services;
        this.javascriptLibraryUrl = javascriptLibraryUrl;
    }

    @Override
    public void messageReceived(ChannelHandlerContext context, MessageEvent event) throws Exception {
        HttpRequest request = (HttpRequest) event.getMessage();

        ChannelPipeline pipeline;
        pipeline = context.getPipeline();

        String requestUri;
        requestUri = request.getUri();

        if (LOGGER.isDebugEnabled())
            LOGGER.debug("Lookup service for " + requestUri + " for channel " + context.getChannel());

        if(pipeline.getLast() != this) {
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("Reset pipeline for channel " + context.getChannel());

            while(pipeline.getLast() != this) {
                pipeline.removeLast();
            }
        }

        for (Service service : services) {
            // Check if there's a service registered with this URL
            if (requestUri.startsWith(service.getUrl())) {
                if (LOGGER.isDebugEnabled())
                    LOGGER.debug("Found matching service for " + requestUri + " to " + service);

                AttachmentHelper.setAttribute(context.getChannel(), SERVICE_ATTRIBUTE_NAME, service);
                AttachmentHelper.setAttribute(context.getChannel(), HTTP_REQUEST_ATTRIBUTE_NAME, request);

                pipeline.addLast("sockjs-service-router", new TransportRouterHandler(service, javascriptLibraryUrl));

                Channels.fireMessageReceived(context, request);
                return;
            }
        }

        writeNotFoundResponse(context.getChannel(), "No service found");

    }

    @Override
    public void channelIdle(ChannelHandlerContext context, IdleStateEvent event) throws Exception {
        // disconnect HTTP keep-alive connection when idle
        if(event.getState() == IdleState.ALL_IDLE && context.getPipeline().getLast() == ServiceRouterHandler.this) {
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("Closing idle connection for" + context.getChannel() + ".");

            context.getChannel().close();

        } else {
            super.channelIdle(context, event);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, ExceptionEvent event) throws Exception {
        if(event.getCause() instanceof SessionNotFound) {
            writeNotFoundResponse(event.getChannel(), "Session not found");

        } else if(context.getPipeline().getLast() == this) {
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("Exception caught for " + context.getChannel() + ":", event.getCause());

            context.getChannel().close();

        } else {
            super.exceptionCaught(context, event);
        }
    }

    private ChannelFuture writeNotFoundResponse(Channel channel, String message) {
        ChannelBuffer buffer;
        buffer = ChannelBuffers.copiedBuffer(message, CharsetUtil.UTF_8);

        HttpResponse response;
        response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);
        response.setHeader(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE);
        response.setHeader(HttpHeaders.Names.CONTENT_LENGTH, buffer.readableBytes());
        response.setContent(buffer);

        return channel.write(response);
    }

    public static Service getServiceForChannel(Channel channel) {
        Service service;
        service = (Service) AttachmentHelper.getAttribute(channel, SERVICE_ATTRIBUTE_NAME);

        if(service == null) {
            throw new IllegalStateException("service not initialized");
        }

        return service;
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

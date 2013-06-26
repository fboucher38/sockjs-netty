/**
 * 
 */
package com.cgbystrom.sockjs.handlers;

import java.util.List;

import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.logging.InternalLogger;
import org.jboss.netty.logging.InternalLoggerFactory;
import org.jboss.netty.util.CharsetUtil;

import com.cgbystrom.sockjs.Service;

/**
 *
 */
public class ServiceRouterHandler extends SimpleChannelHandler {

    private static final InternalLogger LOGGER = InternalLoggerFactory.getInstance(ServiceRouterHandler.class);

    private final static String SERVICE_ATTRIBUTE_NAME = "service";

    private final List<Service> services;
    private final String javascriptLibraryUrl;

    public ServiceRouterHandler(List<Service> services, String javascriptLibraryUrl) {
        this.services = services;
        this.javascriptLibraryUrl = javascriptLibraryUrl;
    }

    @Override
    public void messageReceived(ChannelHandlerContext context, MessageEvent event) throws Exception {
        HttpRequest request = (HttpRequest) event.getMessage();
        String requestUri = request.getUri();

        if (LOGGER.isDebugEnabled())
            LOGGER.debug("Lookup service for " + requestUri);

        ChannelPipeline pipeline;
        pipeline = context.getPipeline();

        for (Service service : services) {
            // Check if there's a service registered with this URL
            if (requestUri.startsWith(service.getUrl())) {
                if (LOGGER.isDebugEnabled())
                    LOGGER.debug("Found matching service for " + requestUri + " to " + service);

                AttachmentHelper.setAttribute(context.getChannel(), SERVICE_ATTRIBUTE_NAME, service);

                pipeline.addLast("sockjs-request-handler", new HttpRequestHandler());
                pipeline.addLast("sockjs-service-router", new TransportRouterHandler(service, javascriptLibraryUrl));

                super.messageReceived(context, event);
                return;
            }
        }

        // No match for service found, return 404
        HttpResponse response;
        response = new DefaultHttpResponse(request.getProtocolVersion(), HttpResponseStatus.NOT_FOUND);
        response.setContent(ChannelBuffers.copiedBuffer("Not found", CharsetUtil.UTF_8));
        event.getChannel().write(response).addListener(ChannelFutureListener.CLOSE);

    }

    public static Service getServiceForChannel(Channel channel) {
        Service service;
        service = (Service) AttachmentHelper.getAttribute(channel, SERVICE_ATTRIBUTE_NAME);

        if(service == null) {
            throw new IllegalStateException("service not initialized");
        }

        return service;
    }

}

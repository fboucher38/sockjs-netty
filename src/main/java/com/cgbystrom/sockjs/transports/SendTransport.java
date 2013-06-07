package com.cgbystrom.sockjs.transports;

import static org.jboss.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;

import java.util.List;

import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.UpstreamMessageEvent;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.QueryStringDecoder;
import org.jboss.netty.logging.InternalLogger;
import org.jboss.netty.logging.InternalLoggerFactory;
import org.jboss.netty.util.CharsetUtil;

import com.cgbystrom.sockjs.SessionHandler;
import com.cgbystrom.sockjs.frames.Frame.MessageFrame;
import com.cgbystrom.sockjs.frames.FrameDecoder;

public abstract class SendTransport extends SimpleChannelUpstreamHandler {

    @SuppressWarnings("unused")
    private static final InternalLogger LOGGER = InternalLoggerFactory.getInstance(SendTransport.class);

    @Override
    public final void channelOpen(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        // Overridden method to prevent propagation of channel state event
        // upstream.
    }

    @Override
    public final void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        // Overridden method to prevent propagation of channel state event
        // upstream.
    }

    @Override
    public final void channelDisconnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        // Overridden method to prevent propagation of channel state event
        // upstream.
    }

    @Override
    public final void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        // Overridden method to prevent propagation of channel state event
        // upstream.
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        HttpRequest request = (HttpRequest) e.getMessage();

        if (request.getContent().readableBytes() == 0) {
            BaseTransport.respondAndClose(e.getChannel(), INTERNAL_SERVER_ERROR, "Payload expected.");
            return;
        }

        String contentTypeHeader = request.getHeader(HttpHeaders.Names.CONTENT_TYPE);
        if (contentTypeHeader == null) {
            contentTypeHeader = BaseTransport.CONTENT_TYPE_PLAIN;
        }

        String decodedContent;
        if (BaseTransport.CONTENT_TYPE_FORM.equals(contentTypeHeader)) {
            QueryStringDecoder decoder = new QueryStringDecoder("?" + request.getContent().toString(CharsetUtil.UTF_8));
            List<String> d = decoder.getParameters().get("d");
            if (d == null) {
                BaseTransport.respondAndClose(e.getChannel(), HttpResponseStatus.INTERNAL_SERVER_ERROR, "Payload expected.");
                return;
            }
            decodedContent = d.get(0);
        } else {
            decodedContent = request.getContent().toString(CharsetUtil.UTF_8);
        }

        if (decodedContent.length() == 0) {
            BaseTransport.respondAndClose(e.getChannel(), HttpResponseStatus.INTERNAL_SERVER_ERROR, "Payload expected.");
            return;
        }

        MessageFrame messageFrame;
        messageFrame = FrameDecoder.decodeMessage(decodedContent);

        ctx.sendUpstream(new UpstreamMessageEvent(e.getChannel(), messageFrame, e.getRemoteAddress()));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
        if (e.getCause() instanceof SessionHandler.NotFoundException) {
            BaseTransport.respondAndClose(e.getChannel(), HttpResponseStatus.NOT_FOUND,
                    "Session not found. Cannot send data to non-existing session.");
        } else {
            super.exceptionCaught(ctx, e);
        }
    }
}

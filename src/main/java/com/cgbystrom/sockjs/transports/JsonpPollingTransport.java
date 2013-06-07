package com.cgbystrom.sockjs.transports;

import java.util.List;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.DownstreamMessageEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.QueryStringDecoder;
import org.jboss.netty.logging.InternalLogger;
import org.jboss.netty.logging.InternalLoggerFactory;
import org.jboss.netty.util.CharsetUtil;

import com.cgbystrom.sockjs.frames.Frame;
import com.cgbystrom.sockjs.frames.FrameEncoder;

public class JsonpPollingTransport extends BaseTransport {

    @SuppressWarnings("unused")
    private static final InternalLogger LOGGER = InternalLoggerFactory.getInstance(JsonpPollingTransport.class);

    private String                      jsonpCallback;

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        HttpRequest request = (HttpRequest) e.getMessage();

        QueryStringDecoder qsd = new QueryStringDecoder(request.getUri());
        final List<String> c = qsd.getParameters().get("c");
        if (c == null) {
            respondAndClose(e.getChannel(), HttpResponseStatus.INTERNAL_SERVER_ERROR, "\"callback\" parameter required.");
            return;
        }

        jsonpCallback = c.get(0);

        super.messageReceived(ctx, e);
    }

    @Override
    public void writeRequested(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        if (e.getMessage() instanceof Frame) {
            final Frame frame = (Frame) e.getMessage();
            HttpResponse response = createResponse(CONTENT_TYPE_JAVASCRIPT);
            response.setHeader(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE);
            response.setHeader(HttpHeaders.Names.CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0");

            ChannelBuffer escapedContent = ChannelBuffers.dynamicBuffer();
            ChannelBuffer rawContent = FrameEncoder.encode(frame, false);
            FrameEncoder.escapeJson(rawContent, escapedContent);
            String message = jsonpCallback + "(\"" + escapedContent.toString(CharsetUtil.UTF_8) + "\");\r\n";

            e.getFuture().addListener(ChannelFutureListener.CLOSE);

            final ChannelBuffer content = ChannelBuffers.copiedBuffer(message, CharsetUtil.UTF_8);
            response.setContent(content);
            response.setHeader(HttpHeaders.Names.CONTENT_LENGTH, content.readableBytes());

            ctx.sendDownstream(new DownstreamMessageEvent(e.getChannel(), e.getFuture(), response, e.getRemoteAddress()));
        } else {
            super.writeRequested(ctx, e);
        }
    }
}

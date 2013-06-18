package com.cgbystrom.sockjs.transports;

import java.util.List;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.Channels;
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
    public void messageReceived(ChannelHandlerContext context, MessageEvent event) throws Exception {
        HttpRequest request = (HttpRequest) event.getMessage();

        QueryStringDecoder queryStringDecoder = new QueryStringDecoder(request.getUri());
        final List<String> c = queryStringDecoder.getParameters().get("c");
        if (c == null || c.isEmpty()) {
            respondAndClose(event.getChannel(), HttpResponseStatus.INTERNAL_SERVER_ERROR, "\"callback\" parameter required.");
            return;
        }

        jsonpCallback = c.get(0);

        super.messageReceived(context, event);
    }

    @Override
    public void writeRequested(ChannelHandlerContext context, MessageEvent event) throws Exception {
        if (event.getMessage() instanceof Frame[]) {
            Frame[] frames = (Frame[]) event.getMessage();

            HttpResponse response = createResponse(CONTENT_TYPE_JAVASCRIPT);
            response.setHeader(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE);
            response.setHeader(HttpHeaders.Names.CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0");

            ChannelBuffer content = ChannelBuffers.dynamicBuffer();
            content.writeBytes(new String(jsonpCallback + "(\"").getBytes(CharsetUtil.UTF_8));
            for(Frame frame : frames) {
                ChannelBuffer escapedContent = ChannelBuffers.dynamicBuffer();
                ChannelBuffer rawContent = FrameEncoder.encode(frame, false);
                FrameEncoder.escapeJson(rawContent, escapedContent);
                content.writeBytes(escapedContent.toString(CharsetUtil.UTF_8).getBytes(CharsetUtil.UTF_8));
            }
            content.writeBytes(new String("\");\r\n").getBytes(CharsetUtil.UTF_8));

            response.setContent(content);
            response.setHeader(HttpHeaders.Names.CONTENT_LENGTH, content.readableBytes());

            event.getFuture().addListener(ChannelFutureListener.CLOSE);

            Channels.write(context, event.getFuture(), response);

        } else {
            super.writeRequested(context, event);
        }
    }
}

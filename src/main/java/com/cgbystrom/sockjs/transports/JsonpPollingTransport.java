/**
 * 
 */
package com.cgbystrom.sockjs.transports;

import java.util.List;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.jboss.netty.handler.codec.http.QueryStringDecoder;
import org.jboss.netty.util.CharsetUtil;

import com.cgbystrom.sockjs.frames.Frame;
import com.cgbystrom.sockjs.frames.FrameEncoder;

/**
 *
 */
public class JsonpPollingTransport extends AbstractPollingTransport {

    private String jsonpCallback;

    @Override
    public void messageReceived(ChannelHandlerContext context, MessageEvent event) throws Exception {
        HttpRequest request = (HttpRequest) event.getMessage();

        QueryStringDecoder queryStringDecoder = new QueryStringDecoder(request.getUri());
        List<String> c = queryStringDecoder.getParameters().get("c");
        if (c == null || c.isEmpty()) {
            respondAndClose(event.getChannel(), HttpResponseStatus.INTERNAL_SERVER_ERROR, "\"callback\" parameter required.");
            return;
        }

        jsonpCallback = c.get(0);

        Channels.fireChannelOpen(context);

    }

    @Override
    public void writeRequested(ChannelHandlerContext context, MessageEvent event) throws Exception {
        Frame frame = (Frame) event.getMessage();

        ChannelBuffer escapedContent = ChannelBuffers.dynamicBuffer();
        ChannelBuffer rawContent = FrameEncoder.encode(frame, false);
        FrameEncoder.escapeJson(rawContent, escapedContent);
        ChannelBuffer content = ChannelBuffers.wrappedBuffer(
                ChannelBuffers.copiedBuffer(jsonpCallback, CharsetUtil.UTF_8),
                ChannelBuffers.copiedBuffer("(\"", CharsetUtil.UTF_8),
                escapedContent,
                ChannelBuffers.copiedBuffer("\");\r\n", CharsetUtil.UTF_8));

        HttpResponse response;
        response = new DefaultHttpResponse(HttpVersion.HTTP_1_0, HttpResponseStatus.OK);
        response.setHeader(HttpHeaders.Names.CONTENT_TYPE, CONTENT_TYPE_JAVASCRIPT);
        response.setHeader(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE);
        response.setContent(content);
        response.setHeader(HttpHeaders.Names.CONTENT_LENGTH, content.readableBytes());

        event.getFuture().addListener(ChannelFutureListener.CLOSE);

        Channels.write(context, event.getFuture(), response);

    }

}

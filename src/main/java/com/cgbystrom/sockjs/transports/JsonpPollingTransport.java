/**
 * 
 */
package com.cgbystrom.sockjs.transports;

import java.util.List;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.QueryStringDecoder;
import org.jboss.netty.util.CharsetUtil;

import com.cgbystrom.sockjs.handlers.SessionHandler;
import com.fasterxml.jackson.core.io.JsonStringEncoder;

public class JsonpPollingTransport extends AbstractPollingTransport {

    private final static byte[] PRE_CALLBACK = "(\"".getBytes(CharsetUtil.UTF_8);
    private final static byte[] POST_CALLBACK = "\");\r\n".getBytes(CharsetUtil.UTF_8);

    public JsonpPollingTransport(SessionHandler sessionHandler) {
        super(sessionHandler);
    }

    @Override
    public void messageReceived(ChannelHandlerContext context, MessageEvent event) throws Exception {
        super.messageReceived(context, event);

        HttpRequest request = (HttpRequest) event.getMessage();

        QueryStringDecoder queryStringDecoder = new QueryStringDecoder(request.getUri());
        List<String> c = queryStringDecoder.getParameters().get("c");
        if (c == null || c.isEmpty()) {
            respondAndClose(event.getChannel(), HttpResponseStatus.INTERNAL_SERVER_ERROR, "\"callback\" parameter required.");
            return;
        }

        String jsonpCallback;
        jsonpCallback = c.get(0);

        HttpResponse response;
        response = createResponse(context.getChannel(), CONTENT_TYPE_JAVASCRIPT);

        registerReceiver(new JsonpResponseReceiver(context.getChannel(), response, jsonpCallback));
    }

    private class JsonpResponseReceiver extends SingleResponseReceiver {

        private final String jsonpCallback;

        public JsonpResponseReceiver(Channel channel, HttpResponse httpResponse, String jsonpCallback) {
            super(channel, httpResponse);
            if(jsonpCallback == null) {
                throw new NullPointerException("jsonpCallback");
            }
            this.jsonpCallback = jsonpCallback;
        }

        @Override
        protected ChannelBuffer formatFrame(String frame) {
            return ChannelBuffers.wrappedBuffer(
                    jsonpCallback.getBytes(CharsetUtil.UTF_8),
                    PRE_CALLBACK,
                    new JsonStringEncoder().quoteAsUTF8(frame),
                    POST_CALLBACK);
        }

    }

}

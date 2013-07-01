package com.cgbystrom.sockjs.transports;

import java.util.List;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.handler.codec.http.DefaultHttpChunk;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.QueryStringDecoder;
import org.jboss.netty.util.CharsetUtil;

import com.cgbystrom.sockjs.handlers.SessionHandler;
import com.fasterxml.jackson.core.io.JsonStringEncoder;

public class HtmlFileTransport extends AbstractStreamingTransport {

    private static final byte[] HEADER_PART1 = ("<!doctype html>\n" +
            "<html><head>\n" +
            "  <meta http-equiv=\"X-UA-Compatible\" content=\"IE=edge\" />\n" +
            "  <meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\" />\n" +
            "</head><body><h2>Don't panic!</h2>\n" +
            "  <script>\n" +
            "    document.domain = document.domain;\n" +
            "    var c = parent.").getBytes(CharsetUtil.UTF_8);
    private static final byte[] HEADER_PART2 = (";\n" +
            "    c.start();\n" +
            "    function p(d) {c.message(d);};\n" +
            "    window.onload = function() {c.stop();};\n" +
            "  </script>").getBytes(CharsetUtil.UTF_8);
    private static final byte[] PREFIX = "<script>\np(\"".getBytes(CharsetUtil.UTF_8);
    private static final byte[] POSTFIX = "\");\n</script>\r\n".getBytes(CharsetUtil.UTF_8);

    private final int responseSizeLimit;

    public HtmlFileTransport(SessionHandler sessionHandler, int responseSizeLimit) {
        super(sessionHandler);
        this.responseSizeLimit = responseSizeLimit;
    }

    @Override
    public void messageReceived(ChannelHandlerContext context, MessageEvent event) throws Exception {
        HttpRequest request = (HttpRequest) event.getMessage();
        QueryStringDecoder requestUriDecoder = new QueryStringDecoder(request.getUri());

        List<String> c = requestUriDecoder.getParameters().get("c");
        if (c == null) {
            respondAndClose(event.getChannel(), HttpResponseStatus.INTERNAL_SERVER_ERROR, "\"callback\" parameter required.");
            return;
        }

        String callback = c.get(0);

        ChannelBuffer header = ChannelBuffers.wrappedBuffer(
                HEADER_PART1,
                callback.getBytes(CharsetUtil.UTF_8),
                HEADER_PART2);

        // Safari needs at least 1024 bytes to parse the website. Relevant:
        //   http://code.google.com/p/browsersec/wiki/Part2#Survey_of_content_sniffing_behaviors
        int spaces = 1024 - header.readableBytes();
        ChannelBuffer paddedHeader = ChannelBuffers.buffer(1024 + 50);

        paddedHeader.writeBytes(header);
        for (int i = 0; i < spaces + 20; i++) {
            paddedHeader.writeByte(' ');
        }
        paddedHeader.writeByte('\r');
        paddedHeader.writeByte('\n');
        // Opera needs one more new line at the start.
        paddedHeader.writeByte('\r');
        paddedHeader.writeByte('\n');

        HttpResponse response;
        response = createResponse(context.getChannel(), CONTENT_TYPE_HTML);

        Channels.write(context, Channels.succeededFuture(event.getChannel()), response);
        Channels.write(context, Channels.succeededFuture(event.getChannel()), new DefaultHttpChunk(paddedHeader));

        registerReceiver(new HtmlFileResponseReceiver(context.getChannel(), responseSizeLimit));
    }

    private class HtmlFileResponseReceiver extends AbstractStreamingReceiver {

        public HtmlFileResponseReceiver(Channel channel, int responseSizeLimit) {
            super(channel, responseSizeLimit);
        }

        @Override
        protected ChannelBuffer formatFrame(String frame) {
            return ChannelBuffers.wrappedBuffer(
                    PREFIX,
                    new JsonStringEncoder().quoteAsUTF8(frame),
                    POSTFIX);
        }

    }
}
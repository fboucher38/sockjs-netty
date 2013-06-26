package com.cgbystrom.sockjs.transports;

import java.util.List;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.handler.codec.http.DefaultHttpChunk;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.QueryStringDecoder;
import org.jboss.netty.util.CharsetUtil;

import com.cgbystrom.sockjs.frames.Frame;
import com.cgbystrom.sockjs.frames.FrameEncoder;

public class HtmlFileTransport extends AbstractStreamingTransport {

    private static final ChannelBuffer HEADER_PART1 = ChannelBuffers.copiedBuffer("<!doctype html>\n" +
            "<html><head>\n" +
            "  <meta http-equiv=\"X-UA-Compatible\" content=\"IE=edge\" />\n" +
            "  <meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\" />\n" +
            "</head><body><h2>Don't panic!</h2>\n" +
            "  <script>\n" +
            "    document.domain = document.domain;\n" +
            "    var c = parent.", CharsetUtil.UTF_8);
    private static final ChannelBuffer HEADER_PART2 = ChannelBuffers.copiedBuffer(";\n" +
            "    c.start();\n" +
            "    function p(d) {c.message(d);};\n" +
            "    window.onload = function() {c.stop();};\n" +
            "  </script>", CharsetUtil.UTF_8);
    private static final ChannelBuffer PREFIX = ChannelBuffers.copiedBuffer("<script>\np(\"", CharsetUtil.UTF_8);
    private static final ChannelBuffer POSTFIX = ChannelBuffers.copiedBuffer("\");\n</script>\r\n", CharsetUtil.UTF_8);


    private ChannelBuffer header;

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
        header = ChannelBuffers.wrappedBuffer(HEADER_PART1, ChannelBuffers.copiedBuffer(callback, CharsetUtil.UTF_8), HEADER_PART2);

        super.messageReceived(context, event);
    }

    @Override
    public void writeRequested(ChannelHandlerContext context, MessageEvent event) throws Exception {
        if (event.getMessage() instanceof Frame) {
            Frame frame = (Frame) event.getMessage();

            if (headerSent.compareAndSet(false, true)) {
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

                Channels.write(event.getChannel(), response);
                Channels.write(event.getChannel(), new DefaultHttpChunk(paddedHeader));
            }

            ChannelBuffer frameContent = FrameEncoder.encode(frame, false);
            ChannelBuffer content = ChannelBuffers.dynamicBuffer(frameContent.readableBytes() + 10);
            FrameEncoder.escapeJson(frameContent, content);
            ChannelBuffer wrappedContent = ChannelBuffers.wrappedBuffer(PREFIX, content, POSTFIX);
            Channels.write(context, event.getFuture(), new DefaultHttpChunk(wrappedContent));

        } else {
            super.writeRequested(context, event);
        }
    }
}

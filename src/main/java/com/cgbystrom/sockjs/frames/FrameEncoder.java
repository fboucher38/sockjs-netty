/**
 * 
 */
package com.cgbystrom.sockjs.frames;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.util.CharsetUtil;

import com.cgbystrom.sockjs.frames.Frame.CloseFrame;
import com.cgbystrom.sockjs.frames.Frame.HeartbeatFrame;
import com.cgbystrom.sockjs.frames.Frame.MessageFrame;
import com.cgbystrom.sockjs.frames.Frame.OpenFrame;
import com.cgbystrom.sockjs.frames.Frame.PreludeFrame;
import com.fasterxml.jackson.core.io.JsonStringEncoder;

/**
 * @author fbou
 *
 */
public class FrameEncoder {

    public static ChannelBuffer encode(Frame frame, boolean appendNewline) {
        if (frame instanceof OpenFrame) {
            return appendNewline ? ChannelBuffers.copiedBuffer("o\n", CharsetUtil.UTF_8) : ChannelBuffers.copiedBuffer("o", CharsetUtil.UTF_8);
        } else if (frame instanceof HeartbeatFrame) {
            return appendNewline ? ChannelBuffers.copiedBuffer("h\n", CharsetUtil.UTF_8) : ChannelBuffers.copiedBuffer("h", CharsetUtil.UTF_8);
        } else if (frame instanceof PreludeFrame) {
            return appendNewline ? generatePreludeFrame('h', 2048, true) : generatePreludeFrame('h', 2048, false);
        } else if (frame instanceof MessageFrame) {
            ChannelBuffer encodedMessageFrame;
            encodedMessageFrame = encoderMessageFrame((MessageFrame)frame);
            return appendNewline ? ChannelBuffers.wrappedBuffer(encodedMessageFrame, ChannelBuffers.copiedBuffer("\n", CharsetUtil.UTF_8)) : encodedMessageFrame;
        }  else if (frame instanceof CloseFrame) {
            ChannelBuffer encodedCloseFrame;
            encodedCloseFrame = encoderCloseFrame((CloseFrame)frame);
            return appendNewline ? ChannelBuffers.wrappedBuffer(encodedCloseFrame, ChannelBuffers.copiedBuffer("\n", CharsetUtil.UTF_8)) : encodedCloseFrame;
        } else {
            throw new IllegalArgumentException("Unknown frame type passed: " + frame.getClass().getSimpleName());
        }
    }

    private static ChannelBuffer encoderCloseFrame(CloseFrame frame) {
        return ChannelBuffers.copiedBuffer("c[" + frame.getStatus() + ",\"" + frame.getReason() + "\"]", CharsetUtil.UTF_8);
    }

    private static ChannelBuffer encoderMessageFrame(MessageFrame frame) {
        String[] messages;
        messages = frame.getMessages();
        ChannelBuffer data;
        data = ChannelBuffers.dynamicBuffer();
        data.writeByte('a');
        data.writeByte('[');
        for (int i = 0; i < messages.length; i++) {
            String message = messages[i];
            data.writeByte('"');
            char[] escaped = new JsonStringEncoder().quoteAsString(message);
            data.writeBytes(ChannelBuffers.copiedBuffer(escapeCharacters(escaped), CharsetUtil.UTF_8));
            data.writeByte('"');
            if (i < messages.length - 1) {
                data.writeByte(',');
            }
        }
        data.writeByte(']');

        return data;
    }

    private static ChannelBuffer generatePreludeFrame(char c, int num, boolean appendNewline) {
        ChannelBuffer cb = ChannelBuffers.buffer(num + 1);
        for (int i = 0; i < num; i++) {
            cb.writeByte(c);
        }
        if (appendNewline)
            cb.writeByte('\n');
        return cb;
    }

    private static String escapeCharacters(char[] value) {
        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < value.length; i++) {
            char ch = value[i];
            if ((ch >= '\u0000' && ch <= '\u001F') ||
                    (ch >= '\uD800' && ch <= '\uDFFF') ||
                    (ch >= '\u200C' && ch <= '\u200F') ||
                    (ch >= '\u2028' && ch <= '\u202F') ||
                    (ch >= '\u2060' && ch <= '\u206F') ||
                    (ch >= '\uFFF0' && ch <= '\uFFFF')) {
                String ss = Integer.toHexString(ch);
                buffer.append('\\');
                buffer.append('u');
                for (int k = 0; k < 4 - ss.length(); k++) {
                    buffer.append('0');
                }
                buffer.append(ss.toLowerCase());
            } else {
                buffer.append(ch);
            }
        }
        return buffer.toString();
    }

    public static void escapeJson(ChannelBuffer input, ChannelBuffer buffer) {
        for (int i = 0; i < input.readableBytes(); i++) {
            byte ch = input.getByte(i);
            switch(ch) {
                case '"': buffer.writeByte('\\'); buffer.writeByte('\"'); break;
                case '/': buffer.writeByte('\\'); buffer.writeByte('/'); break;
                case '\\': buffer.writeByte('\\'); buffer.writeByte('\\'); break;
                case '\b': buffer.writeByte('\\'); buffer.writeByte('b'); break;
                case '\f': buffer.writeByte('\\'); buffer.writeByte('f'); break;
                case '\n': buffer.writeByte('\\'); buffer.writeByte('n'); break;
                case '\r': buffer.writeByte('\\'); buffer.writeByte('r'); break;
                case '\t': buffer.writeByte('\\'); buffer.writeByte('t'); break;

                default:
                    // Reference: http://www.unicode.org/versions/Unicode5.1.0/
                    if ((ch >= '\u0000' && ch <= '\u001F') ||
                            (ch >= '\uD800' && ch <= '\uDFFF') ||
                            (ch >= '\u200C' && ch <= '\u200F') ||
                            (ch >= '\u2028' && ch <= '\u202F') ||
                            (ch >= '\u2060' && ch <= '\u206F') ||
                            (ch >= '\uFFF0' && ch <= '\uFFFF')) {
                        String ss = Integer.toHexString(ch);
                        buffer.writeByte('\\');
                        buffer.writeByte('u');
                        for (int k = 0; k < 4 - ss.length(); k++) {
                            buffer.writeByte('0');
                        }
                        buffer.writeBytes(ss.toLowerCase().getBytes());
                    } else {
                        buffer.writeByte(ch);
                    }
            }
        }
    }

}

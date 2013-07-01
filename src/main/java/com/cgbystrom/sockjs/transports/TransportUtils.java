/**
 * 
 */
package com.cgbystrom.sockjs.transports;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class TransportUtils {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static String[] decodeMessage(String content) throws JsonProcessingException, IOException {
        JsonNode jsonContent = OBJECT_MAPPER.readTree(content);
        String[] messagesArray;
        if(jsonContent.isArray()) {
            List<String> messages = new ArrayList<String>();
            for(JsonNode messageNode : jsonContent) {
                messages.add(messageNode.asText());
            }
            messagesArray = messages.toArray(new String[messages.size()]);
        } else if(jsonContent.isTextual()) {
            messagesArray = new String[] {jsonContent.asText()};
        } else {
            throw new IllegalArgumentException("Invalid content");
        }
        return messagesArray;
    }

    public static ChannelBuffer generatePrelude(char c, int num, boolean appendNewline) {
        ChannelBuffer cb = ChannelBuffers.buffer(num + 1);
        for (int i = 0; i < num; i++) {
            cb.writeByte(c);
        }
        if (appendNewline)
            cb.writeByte('\n');
        return cb;
    }

    public static String escapeCharacters(char[] value) {
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

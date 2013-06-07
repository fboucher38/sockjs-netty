/**
 * 
 */
package com.cgbystrom.sockjs.frames;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.cgbystrom.sockjs.frames.Frame.MessageFrame;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * @author fbou
 *
 */
public class FrameDecoder {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static MessageFrame decodeMessage(String content) throws JsonProcessingException, IOException {
        JsonNode jsonContent = OBJECT_MAPPER.readTree(content);
        MessageFrame messageFrame;
        if(jsonContent.isArray()) {
            List<String> messages = new ArrayList<String>();
            for(JsonNode messageNode : jsonContent) {
                messages.add(messageNode.asText());
            }
            messageFrame = Frame.messageFrame(messages.toArray(new String[messages.size()]));
        } else if(jsonContent.isTextual()) {
            messageFrame = Frame.messageFrame(jsonContent.asText());
        } else {
            throw new IllegalArgumentException("Invalid content");
        }
        return messageFrame;
    }

}

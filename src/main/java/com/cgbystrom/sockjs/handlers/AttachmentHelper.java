package com.cgbystrom.sockjs.handlers;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.jboss.netty.channel.Channel;

public class AttachmentHelper {

    public static Object setAttribute(Channel channel, String key, Object value) {
        return getOrCreateMap(channel).put(key, value);
    }

    public static Object getAttribute(Channel channel, String key) {
        return getOrCreateMap(channel).get(key);
    }

    public static Object removeAttribute(Channel channel, String key) {
        return getOrCreateMap(channel).remove(key);
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentMap<String, Object> getOrCreateMap(Channel channel) {
        Object currentAttachment = channel.getAttachment();
        if(currentAttachment == null) {
            currentAttachment = new ConcurrentHashMap<String, Object>();
            channel.setAttachment(currentAttachment);
        }
        if(!(currentAttachment instanceof ConcurrentMap)) {
            throw new IllegalArgumentException("wrong attachment");
        }
        return (ConcurrentMap<String, Object>) currentAttachment;
    }

}

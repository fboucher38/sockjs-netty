package com.cgbystrom.sockjs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DisabledWebSocketEchoSession implements SessionCallback {
    private static final Logger logger = LoggerFactory.getLogger(DisabledWebSocketEchoSession.class);
    private Session             session;

    @Override
    public void onOpen(Session session) {
        logger.debug("Connected!");
        this.session = session;
    }

    @Override
    public void onClose(Session session) {
        logger.debug("Disconnected!");
    }

    @Override
    public void onMessage(Session session, String message) {
        logger.debug("Echoing back message");
        session.send(message);
    }

    @Override
    public boolean onError(Session session, Throwable exception) {
        logger.error("Error", exception);
        return true;
    }
}

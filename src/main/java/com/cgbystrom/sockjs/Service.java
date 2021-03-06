/**
 * 
 */
package com.cgbystrom.sockjs;

import com.cgbystrom.sockjs.handlers.SessionHandler;
import com.cgbystrom.sockjs.handlers.SimpleSessionHandler;

public interface Service {

    public String getUrl();

    public SessionCallbackFactory getFactory();

    public boolean isWebSocketEnabled();

    public int getResponseSizeLimit();

    public boolean isJsessionidEnabled();

    public String getJavascriptLibraryUrl();

    public SessionHandler getOrCreateSession(String sessionId) throws SessionNotFound;

    public SessionHandler getSession(String sessionId) throws SessionNotFound;

    public SessionHandler forceCreateSession(String sessionId) throws SessionNotFound;

    public SessionHandler removeSession(SimpleSessionHandler aSessionHandler);

    public class SessionNotFound extends Exception {

        private static final long serialVersionUID = 3716968509374998804L;

        private final String sessionId;

        public SessionNotFound(String sessionId, String message) {
            super(message);
            this.sessionId = sessionId;
        }

        public String getSessionId() {
            return sessionId;
        }

    }

}
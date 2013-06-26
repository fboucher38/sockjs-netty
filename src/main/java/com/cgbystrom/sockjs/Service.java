/**
 * 
 */
package com.cgbystrom.sockjs;

import com.cgbystrom.sockjs.handlers.SessionHandler;

public interface Service {

    public String getUrl();

    public SessionCallbackFactory getFactory();

    public boolean isWebSocketEnabled();

    public int getMaxResponseSize();

    public boolean isJsessionid();

    public SessionHandler getOrCreateSession(String sessionId) throws Exception;

    public SessionHandler getSession(String sessionId) throws Exception;

    public SessionHandler forceCreateSession(String sessionId) throws Exception;

    public SessionHandler removeSession(SessionHandler aSessionHandler);

}
/**
 * 
 */
package com.cgbystrom.sockjs;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * @author fbou
 * 
 */
public class Service {

    private final String                                url;
    private final SessionCallbackFactory                factory;
    private final ConcurrentMap<String, SessionHandler> sessions;
    private final boolean                               isWebSocketEnabled;
    private final int                                   maxResponseSize;
    private final boolean                               jsessionidEnabled;
    private final ScheduledExecutorService              scheduledExecutor;
    private final Integer                               timeoutDelay;
    private final Integer                               hreatbeatDelay;

    /**
     * Service using custom configuration
     * 
     * @param url
     * @param factory
     * @param isWebSocketEnabled
     * @param maxResponseSize
     * @param jsessionidEnabled
     * @param scheduledExecutor
     * @param timeoutDelay
     * @param hreatbeatDelay
     */
    public Service(String url, SessionCallbackFactory factory, boolean isWebSocketEnabled, int maxResponseSize,
            boolean jsessionid, ScheduledExecutorService scheduledExecutor, Integer timeoutDelay, Integer hreatbeatDelay) {
        this.url = url;
        this.factory = factory;
        this.isWebSocketEnabled = isWebSocketEnabled;
        this.maxResponseSize = maxResponseSize;
        this.jsessionidEnabled = jsessionid;
        this.scheduledExecutor = scheduledExecutor;
        this.timeoutDelay = timeoutDelay;
        this.hreatbeatDelay = hreatbeatDelay;
        this.sessions = new ConcurrentHashMap<String, SessionHandler>();
    }

    /**
     * Service using default configuration
     */
    public Service(String url, SessionCallbackFactory factory) {
        this(url, factory, true, 128 * 1024, false, Executors.newSingleThreadScheduledExecutor(), 10000, 30000);
    }

    public String getUrl() {
        return url;
    }

    public SessionCallbackFactory getFactory() {
        return factory;
    }

    public boolean isWebSocketEnabled() {
        return isWebSocketEnabled;
    }

    public int getMaxResponseSize() {
        return maxResponseSize;
    }

    public boolean isJsessionid() {
        return jsessionidEnabled;
    }

    public SessionHandler getOrCreateSession(String sessionId) throws Exception {
        SessionHandler session;

        session = sessions.get(sessionId);
        if (session == null) {
            SessionHandler newSession;

            newSession = new SessionHandler(this, sessionId, factory.createSessionCallback(sessionId), scheduledExecutor,
                    timeoutDelay, hreatbeatDelay);
            session = sessions.putIfAbsent(sessionId, newSession);
            if (session == null) {
                session = newSession;
            }
        }

        return session;
    }

    public SessionHandler getSession(String sessionId) throws SessionHandler.NotFoundException {
        SessionHandler session;

        session = sessions.get(sessionId);
        if (session == null) {
            throw new SessionHandler.NotFoundException(url, sessionId);
        }

        return session;
    }

    /**
     * @param aSessionId
     * @return the created session
     * @throws Exception
     */
    public SessionHandler forceCreateSession(String sessionId) throws Exception {
        SessionHandler newSession;
        newSession = newSession(sessionId);

        SessionHandler oldSession;
        oldSession = sessions.replace(sessionId, newSession);

        if (oldSession != null) {
            oldSession.close();
        }

        return newSession;
    }

    /**
     * @param aSessionHandler
     * @return the removed session or null if not found
     */
    public SessionHandler removeSession(SessionHandler aSessionHandler) {
        return sessions.remove(aSessionHandler);
    }

    /**
     * @param sessionId
     * @return
     * @throws Exception
     */
    private SessionHandler newSession(String sessionId) throws Exception {
        return new SessionHandler(this, sessionId, factory.createSessionCallback(sessionId), scheduledExecutor, timeoutDelay,
                hreatbeatDelay);
    }

}
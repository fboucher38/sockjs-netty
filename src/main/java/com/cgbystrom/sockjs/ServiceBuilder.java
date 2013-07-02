/**
 * 
 */
package com.cgbystrom.sockjs;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import com.cgbystrom.sockjs.handlers.SimpleSessionHandler;

public final class ServiceBuilder {

    private final static ScheduledExecutorService DEFAULT_SCHEDULED_EXECUTOR = Executors.newSingleThreadScheduledExecutor();

    private String                   url;
    private SessionCallbackFactory   factory;
    private boolean                  isWebSocketEnabled = true;
    private int                      maxResponseSize = 128 * 1024;
    private boolean                  jsessionidEnabled = false;
    private String                   javascriptLibraryUrl = "http://cdn.sockjs.org/sockjs-0.3.4.min.js";
    private ScheduledExecutorService scheduledExecutor = DEFAULT_SCHEDULED_EXECUTOR;
    private Integer                  timeoutDelay = 5000;
    private Integer                  hreatbeatDelay = 25000;

    public void setUrl(String url) {
        this.url = url;
    }

    public void setFactory(SessionCallbackFactory factory) {
        this.factory = factory;
    }

    public void setWebSocketEnabled(boolean isWebSocketEnabled) {
        this.isWebSocketEnabled = isWebSocketEnabled;
    }

    public void setResponseSizeLimit(int responseSizeLimit) {
        this.maxResponseSize = responseSizeLimit;
    }

    public void setJsessionidEnabled(boolean jsessionidEnabled) {
        this.jsessionidEnabled = jsessionidEnabled;
    }

    public void setScheduledExecutor(ScheduledExecutorService scheduledExecutor) {
        this.scheduledExecutor = scheduledExecutor;
    }

    public void setTimeoutDelay(int timeoutDelay) {
        this.timeoutDelay = timeoutDelay;
    }

    public void setHreatbeatDelay(int hreatbeatDelay) {
        this.hreatbeatDelay = hreatbeatDelay;
    }

    public void setJavascriptLibraryUrl(String aJavascriptLibraryUrl) {
        javascriptLibraryUrl = aJavascriptLibraryUrl;
    }

    public Service build() {
        if(url == null) {
            throw new NullPointerException("url");
        }
        if(factory == null) {
            throw new NullPointerException("factory");
        }
        if(scheduledExecutor == null) {
            throw new NullPointerException("scheduledExecutor");
        }
        if(javascriptLibraryUrl == null) {
            throw new NullPointerException("javascriptLibraryUrl");
        }

        return new ServiceImpl(url, factory, javascriptLibraryUrl, isWebSocketEnabled, maxResponseSize, jsessionidEnabled, scheduledExecutor, timeoutDelay, hreatbeatDelay);
    }

    private static class ServiceImpl implements Service {

        private final String                                      url;
        private final String                                      javascriptLibraryUrl;
        private final ConcurrentMap<String, SimpleSessionHandler> sessions;
        private final SessionCallbackFactory                      factory;
        private final boolean                                     isWebSocketEnabled;
        private final int                                         responseSizeLimit;
        private final boolean                                     jsessionidEnabled;
        private final ScheduledExecutorService                    scheduledExecutor;
        private final Integer                                     timeoutDelay;
        private final Integer                                      hreatbeatDelay;

        public ServiceImpl(String url, SessionCallbackFactory factory, String javascriptLibraryUrl, boolean isWebSocketEnabled, int responseSizeLimit,
                           boolean jsessionid, ScheduledExecutorService scheduledExecutor, Integer timeoutDelay, Integer hreatbeatDelay) {
            this.url = url;
            this.factory = factory;
            this.javascriptLibraryUrl = javascriptLibraryUrl;
            this.isWebSocketEnabled = isWebSocketEnabled;
            this.responseSizeLimit = responseSizeLimit;
            this.jsessionidEnabled = jsessionid;
            this.scheduledExecutor = scheduledExecutor;
            this.timeoutDelay = timeoutDelay;
            this.hreatbeatDelay = hreatbeatDelay;
            this.sessions = new ConcurrentHashMap<String, SimpleSessionHandler>();
        }

        @Override
        public String getUrl() {
            return url;
        }

        @Override
        public SessionCallbackFactory getFactory() {
            return factory;
        }

        @Override
        public String getJavascriptLibraryUrl() {
            return javascriptLibraryUrl;
        }

        @Override
        public boolean isWebSocketEnabled() {
            return isWebSocketEnabled;
        }

        @Override
        public int getResponseSizeLimit() {
            return responseSizeLimit;
        }

        @Override
        public boolean isJsessionidEnabled() {
            return jsessionidEnabled;
        }

        @Override
        public SimpleSessionHandler getOrCreateSession(String sessionId) {
            SimpleSessionHandler session;

            session = sessions.get(sessionId);
            if (session == null) {
                SimpleSessionHandler newSession;

                newSession = newSession(sessionId);
                session = sessions.putIfAbsent(sessionId, newSession);
                if (session == null) {
                    session = newSession;
                }
            }

            return session;
        }

        @Override
        public SimpleSessionHandler getSession(String sessionId) throws SessionNotFound {
            SimpleSessionHandler session;

            session = sessions.get(sessionId);
            if (session == null) {
                throw new SessionNotFound(sessionId, "not found");
            }

            return session;
        }

        /**
         * @param aSessionId
         * @return the created session
         * @throws Exception
         */
        @Override
        public SimpleSessionHandler forceCreateSession(String sessionId) {
            SimpleSessionHandler newSession;
            newSession = newSession(sessionId);

            SimpleSessionHandler oldSession;
            oldSession = sessions.replace(sessionId, newSession);

            if (oldSession != null) {
                throw new IllegalStateException("this is a bug");
            }

            return newSession;
        }

        /**
         * @param aSessionHandler
         * @return the removed session or null if not found
         */
        @Override
        public SimpleSessionHandler removeSession(SimpleSessionHandler aSessionHandler) {
            return sessions.remove(aSessionHandler);
        }

        /**
         * @param sessionId
         * @return
         * @throws Exception
         */
        private SimpleSessionHandler newSession(final String sessionId) {
            Runnable disposer;
            disposer = new Runnable() {
                @Override
                public void run() {
                    sessions.remove(sessionId);
                }
            };
            return new SimpleSessionHandler(sessionId, factory.createSessionCallback(sessionId), scheduledExecutor,
                    timeoutDelay, hreatbeatDelay, disposer);
        }

        @Override
        public String toString() {
            return "Service [url=" + url + ", isWebSocketEnabled=" + isWebSocketEnabled + ", responseSizeLimit="
                    + responseSizeLimit + ", jsessionidEnabled=" + jsessionidEnabled + ", timeoutDelay=" + timeoutDelay
                    + ", hreatbeatDelay=" + hreatbeatDelay + "]";
        }

    }

}

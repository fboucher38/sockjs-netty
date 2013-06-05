package com.cgbystrom.sockjs;

public interface SessionCallback {

    /**
     * @param session
     */
    public void onOpen(Session session) throws Exception;

    /**
     * 
     * @param session
     */
    public void onClose(Session session) throws Exception;

    /**
     * 
     * @param session
     * @param message
     */
    public void onMessage(Session session, String message) throws Exception;

    /**
     * If return false, then silence the exception
     * 
     * @param session
     * @param exception
     * @return
     */
    public boolean onError(Session session, Throwable exception);

}

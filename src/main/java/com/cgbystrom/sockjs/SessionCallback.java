package com.cgbystrom.sockjs;

public interface SessionCallback {

    /**
     * @param session
     */
    public void onOpen(Session session);

    /**
     * 
     * @param session
     */
    public void onClose(Session session);

    /**
     * 
     * @param session
     * @param message
     */
    public void onMessage(Session session, String message);

    /**
     * If return false, then silence the exception
     * 
     * @param session
     * @param exception
     * @return
     */
    public boolean onError(Session session, Throwable exception);

}

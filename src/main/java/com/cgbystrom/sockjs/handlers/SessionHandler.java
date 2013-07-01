/**
 * 
 */
package com.cgbystrom.sockjs.handlers;

import java.net.SocketAddress;

public interface SessionHandler {

    void registerReceiver(Receiver receiver);

    void unregisterReceiver(Receiver receiver);

    void messageReceived(String message);

    void exceptionCaught(Throwable throwable);

    public interface Receiver {

        boolean doOpen();

        boolean doWrite(String[] messages);

        boolean doHeartbeat();

        boolean doClose(int aStatus, String aReason);

        boolean isClosed();

        SocketAddress getLocalAddress();

        SocketAddress getRemoteAddress();

    }

}

package com.cgbystrom.sockjs;

import java.net.SocketAddress;

public interface Session {

    public void send(String message);

    public void close();

    public void close(int code, String message);

    public SocketAddress getLocalAddress();

    public SocketAddress getRemoteAddress();

}
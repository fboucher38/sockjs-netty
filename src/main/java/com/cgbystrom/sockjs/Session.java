package com.cgbystrom.sockjs;

public interface Session {

    public void send(String message);

    public void close();

    public void close(int code, String message);

}
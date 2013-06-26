package com.cgbystrom.sockjs;

public interface SessionCallbackFactory {

    SessionCallback createSessionCallback(String id) throws Exception;

}

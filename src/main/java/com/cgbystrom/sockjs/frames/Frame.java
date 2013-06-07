package com.cgbystrom.sockjs.frames;



public abstract class Frame {

    private static final OpenFrame OPEN_FRAME_OBJ = new OpenFrame();
    private static final HeartbeatFrame HEARTBEAT_FRAME_OBJ = new HeartbeatFrame();
    private static final PreludeFrame PRELUDE_FRAME_OBJ = new PreludeFrame();

    public static OpenFrame openFrame() {
        return OPEN_FRAME_OBJ;
    }

    public static CloseFrame closeFrame(int status, String reason) {
        return new CloseFrame(status, reason);
    }

    public static HeartbeatFrame heartbeatFrame() {
        return HEARTBEAT_FRAME_OBJ;
    }

    /** Used by XHR streaming */
    public static PreludeFrame preludeFrame() {
        return PRELUDE_FRAME_OBJ;
    }

    public static MessageFrame messageFrame(String... messages) {
        return new MessageFrame(messages);
    }

    public static class OpenFrame extends Frame {

    }

    public static class CloseFrame extends Frame {
        private final int status;
        private final String reason;

        private CloseFrame(int status, String reason) {
            this.status = status;
            this.reason = reason;
        }

        public int getStatus() {
            return status;
        }

        public String getReason() {
            return reason;
        }
    }

    public static class MessageFrame extends Frame {
        private final String[] messages;

        private MessageFrame(String... messages) {
            this.messages = messages;
        }

        public String[] getMessages() {
            return messages;
        }
    }

    public static class HeartbeatFrame extends Frame {

    }

    public static class PreludeFrame extends Frame {

    }
}

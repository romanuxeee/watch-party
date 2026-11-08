package org.watchparty.model;

public class RoomException extends RuntimeException {

    private final int status;

    public RoomException(String message) {
        this(message, 400);
    }

    public RoomException(String message, int status) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}

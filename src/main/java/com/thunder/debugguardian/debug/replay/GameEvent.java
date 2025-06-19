package com.thunder.debugguardian.debug.replay;

import java.time.Instant;

public class GameEvent {
    public enum EventType { TICK, ENTITY_SPAWN, PACKET_IN, PACKET_OUT, COMMAND }

    private final Instant timestamp;
    private final EventType type;
    private final Object data;

    public GameEvent(EventType type, Object data) {
        this.timestamp = Instant.now();
        this.type = type;
        this.data = data;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public EventType getType() {
        return type;
    }

    public Object getData() {
        return data;
    }
}

package com.thunder.debugguardian.debug.replay;

import java.time.Instant;
import java.util.UUID;

public class GameEvent {
    public enum EventType { TICK, ENTITY_SPAWN, PACKET_IN, PACKET_OUT, COMMAND }

    private final Instant timestamp;
    private final EventType type;
    private final GameEventPayload data;

    public GameEvent(EventType type, GameEventPayload data) {
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

    public GameEventPayload getData() {
        return data;
    }

    public sealed interface GameEventPayload permits TickEventPayload, EntitySpawnPayload, CommandPayload, PacketPayload {
    }

    public record TickEventPayload(String side, String phase, Long gameTime, String dimension) implements GameEventPayload {
    }

    public record EntitySpawnPayload(String entityType, String displayName, UUID uuid, double x, double y, double z,
                                     String dimension, boolean clientSide) implements GameEventPayload {
    }

    public record CommandPayload(String sourceName, String command, double[] position, String dimension,
                                 boolean executesOnServer) implements GameEventPayload {
    }

    public record PacketPayload(String direction, String channel, int payloadSize, String dimension) implements GameEventPayload {
    }
}

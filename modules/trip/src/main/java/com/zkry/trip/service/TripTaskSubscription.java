package com.zkry.trip.service;

public record TripTaskSubscription(
    String taskId,
    Runnable unsubscribe
) implements AutoCloseable {

    @Override
    public void close() {
        unsubscribe.run();
    }
}

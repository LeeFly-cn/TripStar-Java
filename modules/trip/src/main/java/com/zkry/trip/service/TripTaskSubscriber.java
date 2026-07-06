package com.zkry.trip.service;

import com.zkry.trip.dto.TripTaskEvent;

@FunctionalInterface
public interface TripTaskSubscriber {

    void onEvent(TripTaskEvent event);
}

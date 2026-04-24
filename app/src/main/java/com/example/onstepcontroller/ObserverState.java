package com.example.onstepcontroller;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

final class ObserverState {
    static final double BOSTON_LATITUDE = 42.3601;
    static final double BOSTON_LONGITUDE = -71.0589;
    static final ZoneId BOSTON_ZONE = ZoneId.of("America/New_York");

    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

    final double latitudeDegrees;
    final double longitudeDegrees;
    final ZoneId zoneId;
    final String locationName;

    ObserverState(double latitudeDegrees, double longitudeDegrees, ZoneId zoneId, String locationName) {
        this.latitudeDegrees = latitudeDegrees;
        this.longitudeDegrees = longitudeDegrees;
        this.zoneId = zoneId;
        this.locationName = locationName;
    }

    static ObserverState boston() {
        return new ObserverState(BOSTON_LATITUDE, BOSTON_LONGITUDE, BOSTON_ZONE, "Boston");
    }

    ObserverState withLocation(double latitudeDegrees, double longitudeDegrees, String locationName) {
        return new ObserverState(latitudeDegrees, longitudeDegrees, zoneId, locationName);
    }

    ObserverState withDeviceZone() {
        return new ObserverState(latitudeDegrees, longitudeDegrees, ZoneId.systemDefault(), locationName);
    }

    String formatCoordinates() {
        return String.format(Locale.US, "%.5f°, %.5f°", latitudeDegrees, longitudeDegrees);
    }

    String formatTime(Instant instant) {
        ZonedDateTime localTime = instant.atZone(zoneId);
        return TIME_FORMATTER.format(localTime) + " (" + localTime.getOffset() + ")";
    }
}

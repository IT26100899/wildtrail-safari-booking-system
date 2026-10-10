package com.wildtrail.model;

import com.wildtrail.exception.ApiException;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

public final class Booking {
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "PENDING", Set.of("CONFIRMED", "CANCELLED"),
            "CONFIRMED", Set.of("COMPLETED", "CANCELLED"),
            "COMPLETED", Set.of(),
            "CANCELLED", Set.of());

    private String status;

    public Booking(String status) {
        this.status = status;
    }

    public String getStatus() { return status; }

    public void transitionTo(String next) {
        if (next.equals(status)) return;
        if (!TRANSITIONS.getOrDefault(status, Set.of()).contains(next)) {
            throw ApiException.badRequest("Cannot change booking from " + status + " to " + next);
        }
        status = next;
    }

    public static Map<String, Double> quote(SafariPackage safariPackage, Vehicle vehicle, int guests) {
        double packageCost = safariPackage.calculatePrice(guests);
        double vehicleCost = vehicle.calculateCost(safariPackage.getDurationHours());
        return Map.of("packageCost", packageCost, "vehicleCost", vehicleCost,
                "total", SafariPackage.round(packageCost + vehicleCost));
    }

    public static String validateDate(String rawDate) {
        try {
            LocalDate date = LocalDate.parse(rawDate);
            if (date.isBefore(LocalDate.now())) throw ApiException.badRequest("Safari date cannot be in the past");
            return date.toString();
        } catch (java.time.format.DateTimeParseException exception) {
            throw ApiException.badRequest("Invalid safari date");
        }
    }
}

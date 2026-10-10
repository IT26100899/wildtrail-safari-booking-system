package com.wildtrail.controller;

import com.wildtrail.exception.ApiException;
import com.wildtrail.security.SessionUser;
import com.wildtrail.service.BookingService;
import com.wildtrail.util.RequestValues;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class BookingController {
    private final BookingService bookings;

    public BookingController(BookingService bookings) {
        this.bookings = bookings;
    }

    @GetMapping("/bookings")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> all() {
        return bookings.all();
    }

    @PostMapping("/bookings")
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body,
                                                       Authentication authentication) {
        SessionUser user = authentication != null && authentication.getPrincipal() instanceof SessionUser principal
                ? principal : null;
        return ResponseEntity.status(201).body(bookings.create(body, user));
    }

    @GetMapping("/bookings/mine")
    @PreAuthorize("hasRole('CUSTOMER')")
    public List<Map<String, Object>> mine(Authentication authentication) {
        return bookings.mine(((SessionUser) authentication.getPrincipal()).uid());
    }

    @PostMapping("/bookings/mine")
    @PreAuthorize("hasRole('CUSTOMER')")
    public Map<String, Object> cancelMine(@RequestBody Map<String, Object> body, Authentication authentication) {
        Object id = body.get("id");
        try {
            int bookingId = Integer.parseInt(id.toString());
            if (bookingId < 1) throw new NumberFormatException();
            return bookings.cancelMine(bookingId, ((SessionUser) authentication.getPrincipal()).uid());
        } catch (RuntimeException exception) {
            if (exception instanceof ApiException apiException) throw apiException;
            throw ApiException.badRequest("Id must be a whole number");
        }
    }

    @GetMapping("/bookings/quote")
    public Map<String, ?> quote(@RequestParam(required = false) String packageId,
                                    @RequestParam(required = false) String vehicleId,
                                    @RequestParam(required = false) String guests,
                                    @RequestParam(required = false) String availability,
                                    @RequestParam(required = false) String date,
                                    @RequestParam(required = false) String slot) {
        if ("1".equals(availability)) {
            if (date == null || slot == null) throw ApiException.badRequest("date and slot are required");
            try {
                LocalDate.parse(date);
            } catch (DateTimeParseException exception) {
                throw ApiException.badRequest("Invalid safari date");
            }
            return Map.of("availableVehicleIds", bookings.availableVehicleIds(date, slot));
        }
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("packageId", packageId);
        query.put("vehicleId", vehicleId);
        query.put("guests", guests);
        int pkg = RequestValues.integer(query, "packageId", 1, Integer.MAX_VALUE);
        int vehicle = RequestValues.integer(query, "vehicleId", 1, Integer.MAX_VALUE);
        int count = RequestValues.integer(query, "guests", 1, 20);
        return bookings.quote(pkg, vehicle, count);
    }

    @PatchMapping("/bookings/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> updateStatus(@PathVariable int id, @RequestBody Map<String, Object> body) {
        Object status = body.get("status");
        if (status == null) throw ApiException.badRequest("Status is required");
        return bookings.updateStatus(id, status.toString());
    }

    @DeleteMapping("/bookings/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Boolean> delete(@PathVariable int id) {
        bookings.delete(id);
        return Map.of("ok", true);
    }

    @GetMapping("/payments")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> payments() {
        return bookings.payments();
    }

    @PatchMapping("/payments/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Boolean> updatePaymentStatus(@PathVariable int id, @RequestBody Map<String, Object> body) {
        Object status = body.get("status");
        if (status == null) throw ApiException.badRequest("Status is required");
        bookings.updatePaymentStatus(id, status.toString());
        return Map.of("ok", true);
    }
}

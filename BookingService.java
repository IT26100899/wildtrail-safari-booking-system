package com.wildtrail.service;

import com.wildtrail.exception.ApiException;
import com.wildtrail.model.*;
import com.wildtrail.repository.JdbcBookingRepository;
import com.wildtrail.repository.JdbcPeopleRepository;
import com.wildtrail.security.SessionUser;
import com.wildtrail.util.RequestValues;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class BookingService {
    private static final List<String> PAYMENT_STATUSES = List.of("PENDING", "PAID", "REFUNDED", "FAILED");
    private final JdbcBookingRepository bookings;
    private final JdbcPeopleRepository people;
    private final CatalogService catalog;

    public BookingService(JdbcBookingRepository bookings, JdbcPeopleRepository people, CatalogService catalog) {
        this.bookings = bookings;
        this.people = people;
        this.catalog = catalog;
    }

    public Map<String, Double> quote(int packageId, int vehicleId, int guests) {
        SafariPackage safariPackage = catalog.requirePackage(packageId);
        Vehicle vehicle = catalog.requireVehicle(vehicleId);
        safariPackage.validateGuests(guests);
        return Booking.quote(safariPackage, vehicle, guests);
    }

    public List<Integer> availableVehicleIds(String dateText, String slot) {
        LocalDate date;
        try {
            date = LocalDate.parse(dateText);
        } catch (java.time.format.DateTimeParseException exception) {
            throw ApiException.badRequest("Invalid safari date");
        }
        if (slot == null || slot.isBlank()) throw ApiException.badRequest("date and slot are required");
        return bookings.availableVehicleIds(date, slot);
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> body, SessionUser user) {
        int packageId = RequestValues.integer(body, "packageId", 1, Integer.MAX_VALUE);
        int vehicleId = RequestValues.integer(body, "vehicleId", 1, Integer.MAX_VALUE);
        int guests = RequestValues.integer(body, "guests", 1, 20);
        String safariDateText = RequestValues.text(body, "safariDate", 10);
        String safariDate = Booking.validateDate(safariDateText);
        SafariPackage safariPackage = catalog.requirePackage(packageId);
        Vehicle vehicle = catalog.requireVehicle(vehicleId);
        String slot = RequestValues.optionalText(body, "timeSlot");
        if (slot.isBlank()) slot = safariPackage.getStartTime();
        if (slot.length() > 10) throw ApiException.badRequest("Time slot is invalid");
        if (!safariPackage.isBookable()) throw ApiException.badRequest("This package is not currently bookable");
        safariPackage.validateGuests(guests);
        vehicle.ensureBookableFor(guests);
        if (bookings.countVehicleBookings(vehicleId, LocalDate.parse(safariDate), slot) > 0) {
            throw ApiException.conflict(vehicle.getName() + " is already booked for " + safariDate + " at " + slot +
                    ". Please choose another vehicle.");
        }

        Map<String, Double> quote = Booking.quote(safariPackage, vehicle, guests);
        String method = RequestValues.text(body, "paymentMethod", 10);
        Payment payment = PaymentFactory.create(method, quote.get("total"), body);
        PaymentResult result = payment.process();

        String name = RequestValues.text(body, "fullName", 120);
        String email = RequestValues.email(body);
        String phone = RequestValues.text(body, "phone", 40);
        String country = RequestValues.optionalText(body, "country");
        Map<String, Object> customer = user == null ? null : people.customerByUser(user.uid());
        if (customer == null) customer = people.customerByEmail(email);
        int customerId;
        if (customer == null) {
            customerId = people.createGuestCustomer(name, email, phone, country, user == null ? null : user.uid());
        } else {
            customerId = ((Number) customer.get("id")).intValue();
            people.updateCustomerContact(customerId, phone, country);
        }

        String status = "PAID".equals(result.status()) ? "CONFIRMED" : "PENDING";
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("reference", newReference());
        values.put("customerId", customerId);
        values.put("packageId", packageId);
        values.put("vehicleId", vehicleId);
        values.put("safariDate", LocalDate.parse(safariDate));
        values.put("timeSlot", slot);
        values.put("guests", guests);
        values.put("specialRequests", RequestValues.optionalText(body, "specialRequests"));
        values.put("totalAmount", quote.get("total"));
        values.put("status", status);
        int bookingId = bookings.create(values);
        bookings.createPayment(bookingId, result.method(), result.amount(), result.status(),
                result.transactionRef(), result.cardLast4(), result.paidAt());
        return detailedByReference((String) values.get("reference"));
    }

    public List<Map<String, Object>> all() {
        return bookings.allDetailed();
    }

    public List<Map<String, Object>> mine(int userId) {
        Map<String, Object> customer = people.customerByUser(userId);
        if (customer == null) return List.of();
        return bookings.forCustomer(((Number) customer.get("id")).intValue());
    }

    @Transactional
    public Map<String, Object> cancelMine(int id, int userId) {
        Map<String, Object> row = bookings.basic(id);
        if (row == null) throw ApiException.notFound("Booking with id " + id + " was not found");
        Map<String, Object> customer = people.customerByUser(userId);
        if (customer == null || ((Number) row.get("customerId")).intValue() !=
                ((Number) customer.get("id")).intValue()) {
            throw ApiException.forbidden("This booking does not belong to you");
        }
        Object rawDate = row.get("safariDate");
        LocalDate safariDate = rawDate instanceof LocalDate date ? date : LocalDate.parse(rawDate.toString());
        long millisUntilSafari = safariDate.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                - System.currentTimeMillis();
        if (millisUntilSafari < 24L * 60 * 60 * 1000) {
            throw ApiException.badRequest("Bookings can only be cancelled at least 24 hours before the safari");
        }
        return updateStatus(id, "CANCELLED");
    }

    @Transactional
    public Map<String, Object> updateStatus(int id, String next) {
        Map<String, Object> row = bookings.basic(id);
        if (row == null) throw ApiException.notFound("Booking with id " + id + " was not found");
        if (!List.of("PENDING", "CONFIRMED", "COMPLETED", "CANCELLED").contains(next)) {
            throw ApiException.badRequest("Invalid status");
        }
        Booking booking = new Booking((String) row.get("status"));
        booking.transitionTo(next);
        bookings.updateBookingStatus(id, booking.getStatus());
        if ("CANCELLED".equals(booking.getStatus())) {
            Map<String, Object> payment = bookings.paymentForBooking(id);
            if (payment != null) {
                String newStatus = "PAID".equals(payment.get("status")) ? "REFUNDED" : "FAILED";
                bookings.updatePaymentStatus(((Number) payment.get("id")).intValue(), newStatus);
            }
        }
        return detailedByReference((String) row.get("reference"));
    }

    public void delete(int id) {
        if (!bookings.delete(id)) throw ApiException.notFound("Booking with id " + id + " was not found");
    }

    public List<Map<String, Object>> payments() {
        return bookings.payments();
    }

    @Transactional
    public void updatePaymentStatus(int id, String status) {
        if (!PAYMENT_STATUSES.contains(status)) throw ApiException.badRequest("Invalid payment status");
        if (bookings.payment(id) == null) throw ApiException.notFound("Payment with id " + id + " was not found");
        bookings.updatePaymentStatus(id, status);
    }

    private Map<String, Object> detailedByReference(String reference) {
        return bookings.byReference(reference).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Booking " + reference + " was not found"));
    }

    private String newReference() {
        String timePart = Long.toString(System.currentTimeMillis(), 36).toUpperCase();
        return "WT-" + timePart.substring(Math.max(0, timePart.length() - 5)) +
                ThreadLocalRandom.current().nextInt(10, 100);
    }
}

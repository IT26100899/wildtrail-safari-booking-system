package com.wildtrail.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Repository
public class JdbcBookingRepository {
    private static final String DETAIL_SELECT = """
            SELECT b.id, b.reference, b.safari_date AS "safariDate", b.time_slot AS "timeSlot",
                   b.guests, b.total_amount AS "totalAmount", b.status, b.special_requests AS "specialRequests",
                   b.created_at AS "createdAt", b.customer_id AS "customerId", c.full_name AS "customerName",
                   c.email AS "customerEmail", c.phone AS "customerPhone", b.package_id AS "packageId",
                   p.name AS "packageName", d.name AS "destinationName", b.vehicle_id AS "vehicleId",
                   v.name AS "vehicleName", pay.method AS "paymentMethod", pay.status AS "paymentStatus",
                   pay.transaction_ref AS "transactionRef"
            FROM bookings b
            JOIN customers c ON c.id=b.customer_id
            JOIN safari_packages p ON p.id=b.package_id
            JOIN destinations d ON d.id=p.destination_id
            JOIN vehicles v ON v.id=b.vehicle_id
            LEFT JOIN payments pay ON pay.booking_id=b.id
            """;

    private final JdbcTemplate jdbc;

    public JdbcBookingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> allDetailed() {
        return jdbc.queryForList(DETAIL_SELECT + " ORDER BY b.created_at DESC");
    }

    public List<Map<String, Object>> forCustomer(int customerId) {
        return jdbc.queryForList(DETAIL_SELECT + " WHERE b.customer_id=? ORDER BY b.created_at DESC", customerId);
    }

    public List<Map<String, Object>> byReference(String reference) {
        return jdbc.queryForList(DETAIL_SELECT + " WHERE b.reference=? ORDER BY b.created_at DESC", reference);
    }

    public Map<String, Object> basic(int id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, reference, customer_id AS "customerId", package_id AS "packageId",
                       vehicle_id AS "vehicleId", safari_date AS "safariDate", time_slot AS "timeSlot",
                       guests, special_requests AS "specialRequests", total_amount AS "totalAmount", status
                FROM bookings WHERE id=?
                """, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int countVehicleBookings(int vehicleId, LocalDate date, String slot) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM bookings
                WHERE vehicle_id=? AND safari_date=? AND time_slot=? AND status <> 'CANCELLED'
                """, Integer.class, vehicleId, date, slot);
        return count == null ? 0 : count;
    }

    public List<Integer> availableVehicleIds(LocalDate date, String slot) {
        return jdbc.queryForList("""
                SELECT v.id FROM vehicles v
                WHERE v.available=TRUE AND NOT EXISTS (
                    SELECT 1 FROM bookings b WHERE b.vehicle_id=v.id AND b.safari_date=? AND b.time_slot=?
                        AND b.status <> 'CANCELLED'
                )
                ORDER BY v.id
                """, Integer.class, date, slot);
    }

    public int create(Map<String, Object> values) {
        return jdbc.queryForObject("""
                INSERT INTO bookings(reference, customer_id, package_id, vehicle_id, safari_date, time_slot,
                                     guests, special_requests, total_amount, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, NULLIF(?, ''), ?, ?)
                RETURNING id
                """, Integer.class, values.get("reference"), values.get("customerId"), values.get("packageId"),
                values.get("vehicleId"), values.get("safariDate"), values.get("timeSlot"), values.get("guests"),
                values.get("specialRequests"), values.get("totalAmount"), values.get("status"));
    }

    public void createPayment(int bookingId, String method, double amount, String status,
                              String transactionRef, String cardLast4, java.time.Instant paidAt) {
        jdbc.update("""
                INSERT INTO payments(booking_id, method, amount, status, transaction_ref, card_last4, paid_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, bookingId, method, amount, status, transactionRef, cardLast4,
                paidAt == null ? null : java.sql.Timestamp.from(paidAt));
    }

    public Map<String, Object> paymentForBooking(int bookingId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, booking_id AS "bookingId", method, amount, status,
                       transaction_ref AS "transactionRef", card_last4 AS "cardLast4", paid_at AS "paidAt"
                FROM payments WHERE booking_id=?
                """, bookingId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void updateBookingStatus(int id, String status) {
        jdbc.update("UPDATE bookings SET status=?, updated_at=NOW() WHERE id=?", status, id);
    }

    public void updatePaymentStatus(int id, String status) {
        jdbc.update("UPDATE payments SET status=?, paid_at=CASE WHEN ?='PAID' THEN NOW() ELSE paid_at END WHERE id=?",
                status, status, id);
    }

    public List<Map<String, Object>> payments() {
        return jdbc.queryForList("""
                SELECT p.id, p.booking_id AS "bookingId", p.method, p.amount, p.status,
                       p.transaction_ref AS "transactionRef", p.card_last4 AS "cardLast4",
                       p.paid_at AS "paidAt", p.created_at AS "createdAt",
                       b.reference AS "bookingReference", c.full_name AS "customerName"
                FROM payments p JOIN bookings b ON b.id=p.booking_id
                JOIN customers c ON c.id=b.customer_id
                ORDER BY p.created_at DESC
                """);
    }

    public Map<String, Object> payment(int id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, booking_id AS "bookingId", method, amount, status,
                       transaction_ref AS "transactionRef", card_last4 AS "cardLast4", paid_at AS "paidAt"
                FROM payments WHERE id=?
                """, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean delete(int id) {
        return jdbc.update("DELETE FROM bookings WHERE id=?", id) > 0;
    }
}

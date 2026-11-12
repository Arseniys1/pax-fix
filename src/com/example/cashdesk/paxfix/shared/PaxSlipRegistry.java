package com.example.cashdesk.paxfix.shared;

import com.bifit.cashdesk.desktop.common.documents.entity.PrintDocument;
import com.bifit.cashdesk.desktop.core.utils.PrintDocumentUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Transfers slip identity from the INPAS provider to the KKM provider without
 * changing the slip text or persisting customer/payment data.
 */
public final class PaxSlipRegistry {

    private static final Logger log = LoggerFactory.getLogger(PaxSlipRegistry.class);
    private static final long ENTRY_TTL_MILLIS = 5 * 60 * 1000L;
    private static final int MAX_ENTRIES = 512;
    private static final ConcurrentHashMap<String, Entry> ENTRIES = new ConcurrentHashMap<>();

    private PaxSlipRegistry() {
    }

    public static void registerSlip(String slip) {
        if (slip == null || slip.isBlank()) {
            return;
        }
        try {
            registerDocument(PrintDocumentUtils.convertFromString(slip));
        } catch (RuntimeException error) {
            // The payment has already completed in the stock INPAS driver.
            // Registry failure must never alter its result.
            log.error("Unable to register a PAX slip; stock printing will be used", error);
        }
    }

    public static boolean consume(PrintDocument document) {
        String key = fingerprint(document);
        if (key == null) {
            return false;
        }

        long now = System.currentTimeMillis();
        AtomicBoolean consumed = new AtomicBoolean(false);
        ENTRIES.computeIfPresent(key, (ignored, current) -> {
            if (current.expiresAt < now) {
                return null;
            }
            consumed.set(true);
            return current.remainingUses > 1
                    ? new Entry(current.remainingUses - 1, current.expiresAt)
                    : null;
        });
        return consumed.get();
    }

    private static void registerDocument(PrintDocument document) {
        String key = fingerprint(document);
        if (key == null) {
            return;
        }

        long now = System.currentTimeMillis();
        long expiresAt = now + ENTRY_TTL_MILLIS;
        ENTRIES.compute(key, (ignored, current) -> {
            if (current == null || current.expiresAt < now) {
                return new Entry(1, expiresAt);
            }
            return new Entry(current.remainingUses + 1, expiresAt);
        });

        if (ENTRIES.size() > MAX_ENTRIES) {
            ENTRIES.entrySet().removeIf(entry -> entry.getValue().expiresAt < now);
            if (ENTRIES.size() > MAX_ENTRIES) {
                // Failing closed only disables the fix for pending slips; it
                // never affects payment results or ordinary printing.
                ENTRIES.clear();
            }
        }
    }

    private static String fingerprint(PrintDocument document) {
        if (document == null || document.isEmpty()) {
            return null;
        }
        try {
            String canonical = document.toString(48);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static final class Entry {
        private final int remainingUses;
        private final long expiresAt;

        private Entry(int remainingUses, long expiresAt) {
            this.remainingUses = remainingUses;
            this.expiresAt = expiresAt;
        }
    }
}

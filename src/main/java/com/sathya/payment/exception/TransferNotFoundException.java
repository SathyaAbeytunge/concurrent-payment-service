package com.sathya.payment.exception;

import java.util.UUID;

public class TransferNotFoundException extends RuntimeException {
    public TransferNotFoundException(UUID transactionId) {
        super("Transfer not found: " + transactionId);
    }
}

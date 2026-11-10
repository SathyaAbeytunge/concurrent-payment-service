package com.sathya.payment.service;

import com.sathya.payment.dto.TransferResponse;
import com.sathya.payment.exception.TransferNotFoundException;
import com.sathya.payment.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransferQueryService {
    private final TransactionRepository transactionRepository;

    @Transactional(readOnly = true)
    public TransferResponse getTransfer(UUID transactionId) {
        var transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransferNotFoundException(transactionId));
        return new TransferResponse(transaction.getId(),
                transaction.getSourceAccount().getId(), transaction.getTargetAccount().getId(),
                transaction.getAmount(), transaction.getCurrency(), transaction.getStatus(),
                transaction.getCreatedAt());
    }
}

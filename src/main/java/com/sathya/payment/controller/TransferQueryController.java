package com.sathya.payment.controller;

import com.sathya.payment.dto.TransferResponse;
import com.sathya.payment.service.TransferQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/transfers")
@RequiredArgsConstructor
public class TransferQueryController {
    private final TransferQueryService transferQueryService;

    @GetMapping("/{transactionId}")
    public TransferResponse getTransfer(@PathVariable UUID transactionId) {
        return transferQueryService.getTransfer(transactionId);
    }
}

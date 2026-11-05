
package com.sathya.payment.controller;

import com.sathya.payment.dto.AccountResponse;
import com.sathya.payment.dto.CreateAccountRequest;
import com.sathya.payment.service.AccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import com.sathya.payment.dto.DepositRequest;

@RestController
@RequestMapping("/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse createAccount(
            @Valid @RequestBody CreateAccountRequest request) {

        return accountService.createAccount(request);
    }

    @GetMapping("/{accountId}")
    public AccountResponse getAccount(
            @PathVariable UUID accountId) {

        return accountService.getAccount(accountId);
    }

    
    /**
     * Adds demo funds for examples and tests. Creates no transaction or ledger
     * entries; repeated requests each increase the balance again.
     */
    @PostMapping("/{accountId}/deposit")
    public AccountResponse deposit(
        @PathVariable UUID accountId,
        @Valid @RequestBody DepositRequest request) {

    return accountService.deposit(accountId, request);
    }

}

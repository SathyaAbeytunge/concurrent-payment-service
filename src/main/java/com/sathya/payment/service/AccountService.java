package com.sathya.payment.service;

import com.sathya.payment.dto.AccountResponse;
import com.sathya.payment.dto.CreateAccountRequest;
import com.sathya.payment.entity.Account;
import com.sathya.payment.exception.AccountNotFoundException;
import com.sathya.payment.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.sathya.payment.dto.DepositRequest;
import com.sathya.payment.exception.InvalidTransferException;

@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;

    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request) {

        Account account = Account.builder()
                .userId(request.userId())
                .currency(request.currency() == null ? "USD" : request.currency())
                .balance(BigDecimal.ZERO)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();

        Account savedAccount = accountRepository.save(account);

        return toResponse(savedAccount);
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccount(UUID accountId) {

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));

        return toResponse(account);
    }

    private AccountResponse toResponse(Account account) {

        return new AccountResponse(
                account.getId(),
                account.getUserId(),
                account.getCurrency(),
                account.getBalance(),
                account.getCreatedAt()
        );
    }


    @Transactional
    public AccountResponse deposit(UUID accountId, DepositRequest request) {

        Account account = accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));

        account.setBalance(
                account.getBalance().add(request.amount())
        );

        account.setUpdatedAt(OffsetDateTime.now());

        Account savedAccount = accountRepository.save(account);

        return toResponse(savedAccount);
    }

}
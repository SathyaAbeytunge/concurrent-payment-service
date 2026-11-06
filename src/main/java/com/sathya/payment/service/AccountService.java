package com.sathya.payment.service;

import com.sathya.payment.dto.AccountResponse;
import com.sathya.payment.dto.CreateAccountRequest;
import com.sathya.payment.dto.LedgerEntryResponse;
import com.sathya.payment.entity.Account;
import com.sathya.payment.exception.AccountNotFoundException;
import com.sathya.payment.repository.AccountRepository;
import com.sathya.payment.repository.LedgerEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.sathya.payment.dto.DepositRequest;
import com.sathya.payment.exception.InvalidTransferException;

@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

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

    /** Returns transfer entries only; demo funding is not recorded in the ledger. */
    @Transactional(readOnly = true)
    public List<LedgerEntryResponse> getLedgerHistory(UUID accountId) {
        if (!accountRepository.existsById(accountId)) {
            throw new AccountNotFoundException(accountId);
        }

        return ledgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(accountId).stream()
                .map(entry -> new LedgerEntryResponse(
                        entry.getId(),
                        entry.getTransaction().getId(),
                        entry.getType(),
                        entry.getAmount(),
                        entry.getBalanceAfter(),
                        entry.getCreatedAt()
                ))
                .toList();
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


    /**
     * Seeds an account with demo funds outside the transfer ledger.
     * Funding is not recorded in ledger entries, so the transfer ledger alone
     * cannot reconstruct account balances. This operation is not idempotent.
     */
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

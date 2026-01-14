package com.experiment.acidlab.transaction.domain;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 트랜잭션 엔티티
 */
public class Transaction {

    private final String id;
    private final Long fromAccountId;
    private final Long toAccountId;
    private final long amount;
    private TransactionStatus status;
    private final LocalDateTime createdAt;
    private LocalDateTime completedAt;

    private Transaction(String id, Long fromAccountId, Long toAccountId, long amount,
                        TransactionStatus status, LocalDateTime createdAt) {
        this.id = id;
        this.fromAccountId = fromAccountId;
        this.toAccountId = toAccountId;
        this.amount = amount;
        this.status = status;
        this.createdAt = createdAt;
    }

    // 팩토리 메서드
    public static Transaction create(Long fromAccountId, Long toAccountId, long amount) {
        String id = "TXN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return new Transaction(id, fromAccountId, toAccountId, amount,
                TransactionStatus.PENDING, LocalDateTime.now());
    }

    public static Transaction fromCsv(String id, Long fromAccountId, Long toAccountId,
                                      long amount, TransactionStatus status, LocalDateTime createdAt) {
        return new Transaction(id, fromAccountId, toAccountId, amount, status, createdAt);
    }

    // 상태 변경
    public void begin() {
        if (status != TransactionStatus.PENDING) {
            throw new IllegalStateException("Cannot begin transaction in state: " + status);
        }
        this.status = TransactionStatus.ACTIVE;
    }

    public void commit() {
        if (status != TransactionStatus.ACTIVE) {
            throw new IllegalStateException("Cannot commit transaction in state: " + status);
        }
        this.status = TransactionStatus.COMMITTED;
        this.completedAt = LocalDateTime.now();
    }

    public void rollback() {
        if (status != TransactionStatus.ACTIVE && status != TransactionStatus.PENDING) {
            throw new IllegalStateException("Cannot rollback transaction in state: " + status);
        }
        this.status = TransactionStatus.ROLLBACK;
        this.completedAt = LocalDateTime.now();
    }

    public void fail(String reason) {
        this.status = TransactionStatus.FAILED;
        this.completedAt = LocalDateTime.now();
    }

    // Getters
    public String getId() {
        return id;
    }

    public Long getFromAccountId() {
        return fromAccountId;
    }

    public Long getToAccountId() {
        return toAccountId;
    }

    public long getAmount() {
        return amount;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public boolean isCompleted() {
        return status == TransactionStatus.COMMITTED ||
                status == TransactionStatus.ROLLBACK ||
                status == TransactionStatus.FAILED;
    }

    public boolean isActive() {
        return status == TransactionStatus.ACTIVE;
    }

    // CSV 변환
    public String toCsvLine() {
        return String.format("%s,%d,%d,%d,%s,%s",
                id, fromAccountId, toAccountId, amount, status, createdAt);
    }

    @Override
    public String toString() {
        return String.format("Transaction{id=%s, from=%d, to=%d, amount=%d, status=%s}",
                id, fromAccountId, toAccountId, amount, status);
    }
}
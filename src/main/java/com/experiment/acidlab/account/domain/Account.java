package com.experiment.acidlab.account.domain;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 계좌 엔티티
 * - 불변 객체로 설계 (상태 변경 시 새 객체 반환)
 * - 도메인 로직 포함
 */
public class Account {

    private final Long id;
    private final String name;
    private final long balance;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    private Account(Long id, String name, long balance, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.name = name;
        this.balance = balance;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    // 팩토리 메서드 - 새 계좌 생성
    public static Account create(Long id, String name, long initialBalance) {
        if (initialBalance < 0) {
            throw new IllegalArgumentException("Initial balance cannot be negative: " + initialBalance);
        }
        LocalDateTime now = LocalDateTime.now();
        return new Account(id, name, initialBalance, now, now);
    }

    // 팩토리 메서드 - CSV에서 복원
    public static Account fromCsv(Long id, String name, long balance,
                                  LocalDateTime createdAt, LocalDateTime updatedAt) {
        return new Account(id, name, balance, createdAt, updatedAt);
    }

    // 입금 - 새 객체 반환
    public Account deposit(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Deposit amount must be positive: " + amount);
        }
        return new Account(id, name, balance + amount, createdAt, LocalDateTime.now());
    }

    // 출금 - 새 객체 반환
    public Account withdraw(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Withdraw amount must be positive: " + amount);
        }
        if (balance < amount) {
            throw new InsufficientBalanceException(
                    String.format("Insufficient balance. Current: %d, Requested: %d", balance, amount));
        }
        return new Account(id, name, balance - amount, createdAt, LocalDateTime.now());
    }

    // 잔액 변경 (내부용) - 스냅샷 복원 등에 사용
    public Account withBalance(long newBalance) {
        return new Account(id, name, newBalance, createdAt, LocalDateTime.now());
    }

    // Getters
    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public long getBalance() {
        return balance;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    // 잔액 검증
    public boolean hasEnoughBalance(long amount) {
        return balance >= amount;
    }

    // CSV 형식으로 변환
    public String toCsvLine() {
        return String.format("%d,%s,%d,%s,%s",
                id, name, balance, createdAt, updatedAt);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Account account = (Account) o;
        return Objects.equals(id, account.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return String.format("Account{id=%d, name='%s', balance=%d}", id, name, balance);
    }

    // 예외 클래스
    public static class InsufficientBalanceException extends RuntimeException {
        public InsufficientBalanceException(String message) {
            super(message);
        }
    }
}
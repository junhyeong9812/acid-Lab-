package com.experiment.acidlab.account.repository;

import com.experiment.acidlab.account.domain.Account;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 인메모리 계좌 저장소
 * - ConcurrentHashMap 사용으로 스레드 안전
 * - 스냅샷 기능으로 Atomicity 지원
 */
@Repository
public class InMemoryAccountRepository implements AccountRepository {

    private final Map<Long, Account> store = new ConcurrentHashMap<>();

    @Override
    public Account save(Account account) {
        store.put(account.getId(), account);
        return account;
    }

    @Override
    public Optional<Account> findById(Long id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<Account> findAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public boolean existsById(Long id) {
        return store.containsKey(id);
    }

    @Override
    public void deleteById(Long id) {
        store.remove(id);
    }

    @Override
    public void deleteAll() {
        store.clear();
    }

    @Override
    public long count() {
        return store.size();
    }

    @Override
    public long getTotalBalance() {
        return store.values().stream()
                .mapToLong(Account::getBalance)
                .sum();
    }

    @Override
    public AccountSnapshot createSnapshot() {
        // 깊은 복사로 스냅샷 생성
        List<Account> snapshotAccounts = store.values().stream()
                .map(account -> Account.fromCsv(
                        account.getId(),
                        account.getName(),
                        account.getBalance(),
                        account.getCreatedAt(),
                        account.getUpdatedAt()
                ))
                .collect(Collectors.toList());
        return new InMemorySnapshot(snapshotAccounts);
    }

    @Override
    public void restoreSnapshot(AccountSnapshot snapshot) {
        store.clear();
        snapshot.getAccounts().forEach(account -> store.put(account.getId(), account));
    }

    /**
     * 인메모리 스냅샷 구현
     */
    private static class InMemorySnapshot implements AccountSnapshot {
        private final List<Account> accounts;

        public InMemorySnapshot(List<Account> accounts) {
            this.accounts = Collections.unmodifiableList(accounts);
        }

        @Override
        public List<Account> getAccounts() {
            return accounts;
        }
    }
}
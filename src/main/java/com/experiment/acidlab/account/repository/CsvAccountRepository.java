package com.experiment.acidlab.account.repository;

import com.experiment.acidlab.account.domain.Account;
import com.experiment.acidlab.storage.csv.CsvStorage;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * CSV 파일 기반 계좌 저장소
 * - 인메모리 캐시 + CSV 영속화
 * - Durability 지원
 */
public class CsvAccountRepository implements AccountRepository {

    private static final String CSV_HEADER = "id,name,balance,created_at,updated_at";
    private static final String DEFAULT_FILE_PATH = "data/accounts.csv";

    private final Map<Long, Account> cache = new ConcurrentHashMap<>();
    private final CsvStorage csvStorage;
    private final String filePath;

    public CsvAccountRepository(CsvStorage csvStorage) {
        this(csvStorage, DEFAULT_FILE_PATH);
    }

    public CsvAccountRepository(CsvStorage csvStorage, String filePath) {
        this.csvStorage = csvStorage;
        this.filePath = filePath;
        loadFromCsv();
    }

    @Override
    public Account save(Account account) {
        cache.put(account.getId(), account);
        persistToCsv();
        return account;
    }

    @Override
    public Optional<Account> findById(Long id) {
        return Optional.ofNullable(cache.get(id));
    }

    @Override
    public List<Account> findAll() {
        return new ArrayList<>(cache.values());
    }

    @Override
    public boolean existsById(Long id) {
        return cache.containsKey(id);
    }

    @Override
    public void deleteById(Long id) {
        cache.remove(id);
        persistToCsv();
    }

    @Override
    public void deleteAll() {
        cache.clear();
        persistToCsv();
    }

    @Override
    public long count() {
        return cache.size();
    }

    @Override
    public long getTotalBalance() {
        return cache.values().stream()
                .mapToLong(Account::getBalance)
                .sum();
    }

    @Override
    public AccountSnapshot createSnapshot() {
        List<Account> snapshotAccounts = cache.values().stream()
                .map(account -> Account.fromCsv(
                        account.getId(),
                        account.getName(),
                        account.getBalance(),
                        account.getCreatedAt(),
                        account.getUpdatedAt()
                ))
                .collect(Collectors.toList());
        return new CsvSnapshot(snapshotAccounts);
    }

    @Override
    public void restoreSnapshot(AccountSnapshot snapshot) {
        cache.clear();
        snapshot.getAccounts().forEach(account -> cache.put(account.getId(), account));
        persistToCsv();
    }

    /**
     * CSV에서 데이터 로드
     */
    private void loadFromCsv() {
        List<String> lines = csvStorage.readLines(filePath);

        for (int i = 1; i < lines.size(); i++) { // 헤더 스킵
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;

            try {
                Account account = parseCsvLine(line);
                cache.put(account.getId(), account);
            } catch (Exception e) {
                System.err.println("Failed to parse line: " + line + ", error: " + e.getMessage());
            }
        }
    }

    /**
     * CSV로 데이터 저장
     */
    private void persistToCsv() {
        List<String> lines = new ArrayList<>();
        lines.add(CSV_HEADER);

        cache.values().stream()
                .sorted(Comparator.comparing(Account::getId))
                .forEach(account -> lines.add(account.toCsvLine()));

        csvStorage.writeLines(filePath, lines);
    }

    /**
     * CSV 라인 파싱
     */
    private Account parseCsvLine(String line) {
        String[] parts = line.split(",");
        if (parts.length < 5) {
            throw new IllegalArgumentException("Invalid CSV format: " + line);
        }

        Long id = Long.parseLong(parts[0].trim());
        String name = parts[1].trim();
        long balance = Long.parseLong(parts[2].trim());
        LocalDateTime createdAt = LocalDateTime.parse(parts[3].trim());
        LocalDateTime updatedAt = LocalDateTime.parse(parts[4].trim());

        return Account.fromCsv(id, name, balance, createdAt, updatedAt);
    }

    /**
     * 강제 동기화 (fsync)
     */
    public void sync() {
        persistToCsv();
        csvStorage.sync(filePath);
    }

    /**
     * CSV 스냅샷 구현
     */
    private static class CsvSnapshot implements AccountSnapshot {
        private final List<Account> accounts;

        public CsvSnapshot(List<Account> accounts) {
            this.accounts = Collections.unmodifiableList(accounts);
        }

        @Override
        public List<Account> getAccounts() {
            return accounts;
        }
    }
}
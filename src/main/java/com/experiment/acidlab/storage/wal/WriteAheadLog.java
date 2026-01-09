package com.experiment.acidlab.storage.wal;

import com.experiment.acidlab.storage.csv.CsvStorage;
import com.experiment.acidlab.global.logger.ConsoleLogger;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Write-Ahead Log (WAL) 관리자
 *
 * WAL의 핵심 원칙:
 * "데이터를 변경하기 전에, 반드시 로그를 먼저 디스크에 기록한다"
 *
 * 이 클래스의 책임:
 * 1. WalEntry 생성 및 파일 저장
 * 2. 트랜잭션별 로그 추적 (메모리 캐시)
 * 3. 커밋 시 fsync로 Durability 보장
 * 4. 복구 시 미완료 트랜잭션 탐지
 *
 * 구조:
 * WriteAheadLog (이 클래스)
 *     ├── CsvStorage: 실제 파일 I/O 담당
 *     ├── WalEntry: 로그 레코드 데이터 구조
 *     └── transactionLogs: 트랜잭션별 엔트리 캐시 (롤백용)
 *
 * WAL 파일 형식 (CSV):
 * lsn,transaction_id,operation,table_name,row_id,before_value,after_value,timestamp
 * 1,tx-001,BEGIN,,,,2024-01-15T10:00:00
 * 2,tx-001,UPDATE,users,123,oldData,newData,2024-01-15T10:00:01
 * 3,tx-001,COMMIT,,,,2024-01-15T10:00:02
 */
public class WriteAheadLog {

    // ==================== 상수 ====================

    /**
     * WAL CSV 파일 헤더
     * 파일 생성 시 첫 줄에 기록됩니다.
     */
    private static final String WAL_HEADER = "lsn,transaction_id,operation,table_name,row_id,before_value,after_value,timestamp";

    /**
     * 기본 WAL 파일 경로
     */
    private static final String DEFAULT_WAL_PATH = "data/wal.csv";

    // ==================== 필드 ====================

    /**
     * CSV 파일 I/O 담당
     * 실제 파일 읽기/쓰기/동기화는 이 객체에 위임합니다.
     */
    private final CsvStorage csvStorage;

    /**
     * WAL 파일 경로
     */
    private final String walPath;

    /**
     * LSN 카운터
     *
     * AtomicLong 사용 이유:
     * - 여러 트랜잭션이 동시에 로그를 기록할 수 있음
     * - incrementAndGet()으로 원자적 증가 보장
     * - 락 없이도 스레드 안전한 고유 번호 발급
     */
    private final AtomicLong lsnCounter;

    /**
     * 로거
     */
    private final ConsoleLogger logger;

    /**
     * 트랜잭션별 WAL 엔트리 캐시
     *
     * Key: 트랜잭션 ID
     * Value: 해당 트랜잭션의 데이터 변경 엔트리 목록
     *
     * 용도:
     * - 롤백 시 UNDO할 작업 목록 빠르게 조회
     * - 파일을 다시 읽지 않고 메모리에서 바로 접근
     *
     * LinkedHashMap 사용 이유:
     * - 삽입 순서 유지 (작업 순서대로 저장)
     * - 롤백 시 역순으로 처리해야 하므로 순서가 중요
     *
     * 생명주기:
     * - BEGIN 시 빈 리스트로 생성
     * - 데이터 작업 시 엔트리 추가
     * - COMMIT/ROLLBACK 시 제거 (메모리 해제)
     */
    private final Map<String, List<WalEntry>> transactionLogs = new LinkedHashMap<>();

    // ==================== 생성자 ====================

    /**
     * 기본 경로로 WAL 생성
     */
    public WriteAheadLog(CsvStorage csvStorage) {
        this(csvStorage, DEFAULT_WAL_PATH);
    }

    /**
     * 경로 지정 WAL 생성
     *
     * @param csvStorage CSV 파일 I/O 담당
     * @param walPath WAL 파일 경로
     *
     * 초기화 과정:
     * 1. 기존 WAL 파일이 있으면 마지막 LSN을 읽어서 카운터 초기화
     * 2. 없으면 LSN을 0부터 시작
     *
     * 이렇게 하면 재시작 후에도 LSN이 중복되지 않습니다.
     */
    public WriteAheadLog(CsvStorage csvStorage, String walPath) {
        this.csvStorage = csvStorage;
        this.walPath = walPath;
        this.logger = ConsoleLogger.of("WAL");
        this.lsnCounter = new AtomicLong(initializeLsn());
    }

    // ==================== 로그 기록 메서드 ====================

    /**
     * 트랜잭션 시작 로그 기록
     *
     * @param transactionId 시작하는 트랜잭션 ID
     *
     * 동작:
     * 1. BEGIN 엔트리 생성 및 파일에 기록
     * 2. 트랜잭션 캐시에 빈 리스트 생성
     *
     * 이 시점부터 해당 트랜잭션의 작업이 추적됩니다.
     */
    public void logBegin(String transactionId) {
        WalEntry entry = WalEntry.begin(nextLsn(), transactionId);
        appendToWal(entry);
        transactionLogs.put(transactionId, new ArrayList<>());
        logger.debug("BEGIN logged: " + transactionId);
    }

    /**
     * UPDATE 로그 기록
     *
     * @param transactionId 트랜잭션 ID
     * @param tableName 테이블 이름
     * @param rowId 행 ID
     * @param beforeValue 변경 전 값 (롤백용)
     * @param afterValue 변경 후 값 (REDO용)
     *
     * 호출 시점:
     * 실제 데이터 변경 "전에" 호출해야 합니다.
     * (Write-Ahead = 먼저 쓰기)
     */
    public void logUpdate(String transactionId, String tableName, Long rowId,
                          String beforeValue, String afterValue) {
        WalEntry entry = WalEntry.update(nextLsn(), transactionId, tableName, rowId, beforeValue, afterValue);
        appendToWal(entry);
        addToTransactionLog(transactionId, entry);
        logger.debug(String.format("UPDATE logged: %s.%d [%s -> %s]", tableName, rowId, beforeValue, afterValue));
    }

    /**
     * INSERT 로그 기록
     *
     * @param transactionId 트랜잭션 ID
     * @param tableName 테이블 이름
     * @param rowId 행 ID
     * @param value 삽입할 데이터
     */
    public void logInsert(String transactionId, String tableName, Long rowId, String value) {
        WalEntry entry = WalEntry.insert(nextLsn(), transactionId, tableName, rowId, value);
        appendToWal(entry);
        addToTransactionLog(transactionId, entry);
        logger.debug(String.format("INSERT logged: %s.%d [%s]", tableName, rowId, value));
    }

    /**
     * DELETE 로그 기록
     *
     * @param transactionId 트랜잭션 ID
     * @param tableName 테이블 이름
     * @param rowId 행 ID
     * @param value 삭제될 데이터 (롤백 시 복원용)
     */
    public void logDelete(String transactionId, String tableName, Long rowId, String value) {
        WalEntry entry = WalEntry.delete(nextLsn(), transactionId, tableName, rowId, value);
        appendToWal(entry);
        addToTransactionLog(transactionId, entry);
        logger.debug(String.format("DELETE logged: %s.%d [%s]", tableName, rowId, value));
    }

    /**
     * 커밋 로그 기록
     *
     * @param transactionId 커밋하는 트랜잭션 ID
     *
     * 동작:
     * 1. COMMIT 엔트리 생성 및 파일에 기록
     * 2. fsync 호출로 디스크에 강제 기록 ← 핵심!
     * 3. 트랜잭션 캐시에서 제거
     *
     * fsync가 중요한 이유:
     * - COMMIT 로그가 디스크에 있어야 복구 시 "커밋됨"으로 인식
     * - fsync 없이 크래시되면 COMMIT 로그가 사라질 수 있음
     * - 그러면 커밋된 트랜잭션이 롤백되는 문제 발생
     *
     * Durability 보장:
     * 이 메서드가 반환되면 트랜잭션은 확실히 커밋된 것입니다.
     */
    public void logCommit(String transactionId) {
        WalEntry entry = WalEntry.commit(nextLsn(), transactionId);
        appendToWal(entry);

        // 핵심: fsync로 디스크에 강제 기록
        // 이 시점 이후로 트랜잭션은 영구히 커밋됨
        csvStorage.sync(walPath);

        // 메모리 캐시 정리 (더 이상 필요 없음)
        transactionLogs.remove(transactionId);
        logger.debug("COMMIT logged and synced: " + transactionId);
    }

    /**
     * 롤백 로그 기록
     *
     * @param transactionId 롤백하는 트랜잭션 ID
     *
     * 동작:
     * 1. ROLLBACK 엔트리 기록
     * 2. 트랜잭션 캐시에서 제거
     *
     * fsync를 안 하는 이유:
     * - 롤백은 "취소"이므로 디스크에 확실히 기록할 필요 없음
     * - 크래시되어 ROLLBACK 로그가 사라져도,
     *   COMMIT이 없으므로 복구 시 어차피 롤백됨
     */
    public void logRollback(String transactionId) {
        WalEntry entry = WalEntry.rollback(nextLsn(), transactionId);
        appendToWal(entry);
        transactionLogs.remove(transactionId);
        logger.debug("ROLLBACK logged: " + transactionId);
    }

    // ==================== 복구 관련 메서드 ====================

    /**
     * 트랜잭션의 UNDO 정보 반환 (롤백용)
     *
     * @param transactionId 롤백할 트랜잭션 ID
     * @return 역순의 데이터 변경 엔트리 목록
     *
     * 역순으로 반환하는 이유:
     * - 롤백은 최신 작업부터 취소해야 함
     * - INSERT → UPDATE → DELETE 순으로 했다면
     * - DELETE → UPDATE → INSERT 역순으로 취소
     *
     * 예시:
     * 1. INSERT row (값: A)
     * 2. UPDATE row (A → B)
     * 3. UPDATE row (B → C)
     *
     * 롤백:
     * 3. UPDATE row (C → B) - beforeValue로 복원
     * 2. UPDATE row (B → A) - beforeValue로 복원
     * 1. DELETE row - INSERT 취소
     */
    public List<WalEntry> getUndoEntries(String transactionId) {
        List<WalEntry> entries = transactionLogs.getOrDefault(transactionId, new ArrayList<>());
        // 역순으로 반환 (최신 → 오래된 순)
        List<WalEntry> reversed = new ArrayList<>(entries);
        Collections.reverse(reversed);
        return reversed;
    }

    /**
     * 커밋되지 않은 트랜잭션 찾기 (복구용)
     *
     * @return 미완료 트랜잭션 ID 목록
     *
     * 시스템 재시작 시 호출하여 복구 대상을 파악합니다.
     *
     * 알고리즘:
     * 1. 모든 WAL 엔트리를 읽음
     * 2. BEGIN이 있는 트랜잭션 ID 수집
     * 3. COMMIT 또는 ROLLBACK이 있는 트랜잭션 ID 수집
     * 4. (BEGIN 집합) - (완료 집합) = 미완료 트랜잭션
     *
     * 복구 시 처리:
     * - 미완료 트랜잭션은 롤백해야 함 (Atomicity)
     * - getEntriesForTransaction()으로 해당 작업들 조회
     * - beforeValue로 데이터 복원
     */
    public List<String> findUncommittedTransactions() {
        List<WalEntry> allEntries = readAllEntries();

        Set<String> begun = new HashSet<>();   // BEGIN이 있는 트랜잭션
        Set<String> ended = new HashSet<>();   // COMMIT 또는 ROLLBACK이 있는 트랜잭션

        for (WalEntry entry : allEntries) {
            if (entry.isBegin()) {
                begun.add(entry.getTransactionId());
            } else if (entry.isCommit() || entry.isRollback()) {
                ended.add(entry.getTransactionId());
            }
        }

        // 시작했지만 끝나지 않은 트랜잭션
        begun.removeAll(ended);
        return new ArrayList<>(begun);
    }

    /**
     * 특정 트랜잭션의 모든 데이터 변경 엔트리 반환 (복구용)
     *
     * @param transactionId 조회할 트랜잭션 ID
     * @return 해당 트랜잭션의 INSERT/UPDATE/DELETE 엔트리 목록
     *
     * 용도:
     * - 미완료 트랜잭션 롤백 시 취소할 작업 목록 조회
     * - 파일에서 직접 읽어오므로 재시작 후에도 사용 가능
     *
     * transactionLogs 캐시 vs 이 메서드:
     * - 캐시: 현재 세션에서 진행 중인 트랜잭션용 (빠름)
     * - 이 메서드: 재시작 후 복구용 (파일에서 읽음)
     */
    public List<WalEntry> getEntriesForTransaction(String transactionId) {
        return readAllEntries().stream()
                .filter(e -> e.getTransactionId().equals(transactionId))
                .filter(WalEntry::isDataOperation)  // BEGIN, COMMIT, ROLLBACK 제외
                .collect(Collectors.toList());
    }

    /**
     * 모든 WAL 엔트리 읽기
     *
     * @return 전체 WAL 엔트리 목록 (LSN 순서)
     *
     * 파일에서 읽어오므로 복구나 분석 시 사용합니다.
     * 첫 줄(헤더)은 건너뜁니다.
     */
    public List<WalEntry> readAllEntries() {
        List<String> lines = csvStorage.readLines(walPath);
        List<WalEntry> entries = new ArrayList<>();

        // i = 1부터 시작하여 헤더 스킵
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;

            try {
                entries.add(WalEntry.fromCsvLine(line));
            } catch (Exception e) {
                // 파싱 실패한 라인은 경고만 남기고 건너뜀
                // 손상된 로그가 전체 복구를 막지 않도록
                logger.warn("Failed to parse WAL entry: " + line);
            }
        }

        return entries;
    }

    /**
     * WAL 초기화 (테스트용)
     *
     * 모든 로그를 삭제하고 헤더만 남깁니다.
     * 프로덕션에서는 사용하지 않습니다.
     */
    public void clear() {
        csvStorage.writeLines(walPath, List.of(WAL_HEADER));
        transactionLogs.clear();
        logger.info("WAL cleared");
    }

    // ==================== 내부 메서드 ====================

    /**
     * WAL 파일에 엔트리 추가
     *
     * @param entry 추가할 WAL 엔트리
     *
     * 파일이 없으면 헤더와 함께 새로 생성합니다.
     */
    private void appendToWal(WalEntry entry) {
        // 파일이 없으면 헤더와 함께 생성
        if (!csvStorage.exists(walPath)) {
            csvStorage.writeLines(walPath, List.of(WAL_HEADER));
        }
        csvStorage.appendLine(walPath, entry.toCsvLine());
    }

    /**
     * 트랜잭션 캐시에 엔트리 추가
     *
     * @param transactionId 트랜잭션 ID
     * @param entry 추가할 엔트리
     *
     * computeIfAbsent: 트랜잭션 ID가 없으면 새 리스트 생성
     * (BEGIN 없이 바로 데이터 작업이 오는 경우 대비)
     */
    private void addToTransactionLog(String transactionId, WalEntry entry) {
        transactionLogs.computeIfAbsent(transactionId, k -> new ArrayList<>()).add(entry);
    }

    /**
     * LSN 카운터 초기화
     *
     * @return 시작할 LSN 값
     *
     * 기존 WAL 파일이 있으면 마지막 LSN을 찾아서 그 다음부터 시작합니다.
     * 이렇게 해야 재시작 후에도 LSN이 중복되지 않습니다.
     *
     * 예시:
     * - WAL 파일에 LSN 1, 2, 3이 있으면 → 4부터 시작
     * - WAL 파일이 없으면 → 1부터 시작 (0 반환 후 incrementAndGet)
     */
    private long initializeLsn() {
        if (!csvStorage.exists(walPath)) {
            return 0;
        }

        List<WalEntry> entries = readAllEntries();
        if (entries.isEmpty()) {
            return 0;
        }

        // 모든 엔트리 중 최대 LSN 찾기
        return entries.stream()
                .mapToLong(WalEntry::getLsn)
                .max()
                .orElse(0);
    }

    /**
     * 다음 LSN 발급
     *
     * @return 새로운 고유 LSN
     *
     * AtomicLong.incrementAndGet():
     * - 원자적으로 1 증가시키고 증가된 값 반환
     * - 여러 스레드가 동시에 호출해도 각각 고유한 값 받음
     */
    private long nextLsn() {
        return lsnCounter.incrementAndGet();
    }
}
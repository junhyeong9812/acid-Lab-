package com.experiment.acidlab.storage.wal;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Write-Ahead Log 엔트리 (데이터 클래스)
 *
 * WAL에 기록되는 단일 로그 레코드를 표현하는 불변(Immutable) 데이터 클래스입니다.
 * WriteAheadLog 클래스가 이 엔트리들을 생성하고 파일로 저장/로드합니다.
 *
 * WAL(Write-Ahead Logging)이란?
 * - 데이터를 변경하기 "전에" 먼저 로그를 기록하는 기법
 * - 시스템 장애 시 로그를 보고 복구 가능 (Durability)
 * - 미완료 트랜잭션은 로그를 역순으로 읽어 롤백 가능 (Atomicity)
 *
 * 이 클래스의 책임:
 * 1. 로그 레코드 데이터 구조 정의 (DTO 역할)
 * 2. CSV 직렬화: 엔트리 → 문자열 (파일 저장용)
 * 3. CSV 역직렬화: 문자열 → 엔트리 (파일 로드용)
 * 4. 작업 타입별 팩토리 메서드 제공 (생성 편의)
 *
 * 엔트리 구조 (CSV 컬럼 순서):
 * [LSN][트랜잭션ID][작업타입][테이블][행ID][이전값][이후값][타임스탬프]
 *
 * 예시:
 * 1,tx-001,BEGIN,,,,2024-01-15T10:00:00
 * 2,tx-001,UPDATE,users,123,{"name":"old"},{"name":"new"},2024-01-15T10:00:01
 * 3,tx-001,COMMIT,,,,2024-01-15T10:00:02
 *
 * 불변 객체로 설계한 이유:
 * - 한 번 기록된 로그는 변경되면 안 됨 (감사 추적)
 * - 스레드 안전성 보장
 * - 예측 가능한 동작
 */
public class WalEntry {

    // ==================== 상수 ====================

    /**
     * 타임스탬프 포맷터
     *
     * ISO_LOCAL_DATE_TIME 형식 사용
     * 예: "2024-01-15T14:30:00"
     *
     * 이 형식을 사용하는 이유:
     * - ISO 8601 국제 표준으로 호환성 좋음
     * - 사람이 읽기 쉬움
     * - 문자열 비교만으로도 시간순 정렬 가능 (사전순 = 시간순)
     * - Java의 LocalDateTime과 직접 호환
     *
     * 주의: 타임존 정보는 포함하지 않음 (LOCAL)
     * 분산 시스템에서는 UTC 사용 고려 필요
     */
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    // ==================== 필드 ====================

    /**
     * Log Sequence Number (로그 일련 번호)
     *
     * WAL 엔트리의 고유 식별자이자 전역 순서 번호입니다.
     * WriteAheadLog 클래스의 AtomicLong 카운터에서 순차 발급합니다.
     *
     * 특징:
     * - 단조 증가 (monotonically increasing): 절대 감소하지 않음
     * - 전역 고유: 모든 트랜잭션에 걸쳐 유일한 값
     * - 순서 보장: LSN이 작으면 먼저 발생한 작업
     * - 갭 허용: 1, 2, 5, 6처럼 중간이 비어도 됨 (롤백 등으로)
     *
     * 용도:
     * - 복구 시 어디까지 처리했는지 추적 (체크포인트)
     * - 로그 재생(replay) 순서 결정
     * - 로그 엔트리 고유 식별
     * - 복제(replication) 시 동기화 지점
     *
     * 예시:
     * LSN 1: tx-001 BEGIN
     * LSN 2: tx-001 UPDATE users.123
     * LSN 3: tx-002 BEGIN
     * LSN 4: tx-001 COMMIT
     * → tx-001과 tx-002가 동시에 실행되어도 LSN으로 순서 구분 가능
     */
    private final long lsn;

    /**
     * 트랜잭션 ID
     *
     * 이 작업을 수행한 트랜잭션의 고유 식별자입니다.
     *
     * 용도:
     * - 같은 트랜잭션의 작업들을 그룹화
     * - 복구 시 트랜잭션 단위로 REDO/UNDO 결정
     * - 롤백 시 해당 트랜잭션의 모든 작업 취소
     *
     * 형식 예시: "tx-001", "TX_20240115_001", UUID 등
     * (시스템 설계에 따라 다름)
     */
    private final String transactionId;

    /**
     * 작업 타입
     *
     * 이 로그 엔트리가 어떤 종류의 작업인지 나타냅니다.
     * Operation enum의 값 중 하나입니다.
     *
     * 트랜잭션 제어 작업:
     * - BEGIN: 트랜잭션 시작
     * - COMMIT: 트랜잭션 커밋 (변경 확정)
     * - ROLLBACK: 트랜잭션 롤백 (변경 취소)
     *
     * 데이터 변경 작업:
     * - INSERT: 새 행 삽입
     * - UPDATE: 기존 행 수정
     * - DELETE: 기존 행 삭제
     *
     * 복구 시 처리:
     * - COMMIT이 있는 트랜잭션 → 변경 유지 (REDO)
     * - COMMIT이 없는 트랜잭션 → 변경 취소 (UNDO)
     */
    private final Operation operation;

    /**
     * 테이블 이름
     *
     * 데이터 작업(INSERT/UPDATE/DELETE)의 대상 테이블입니다.
     *
     * 값:
     * - 데이터 작업: 테이블 이름 (예: "users", "orders")
     * - 트랜잭션 제어 작업: null (테이블과 무관)
     *
     * rowId와 함께 변경된 행을 식별합니다.
     * tableName + rowId = 행의 고유 식별자
     */
    private final String tableName;

    /**
     * 행 ID
     *
     * 데이터 작업의 대상 행 식별자입니다.
     *
     * 값:
     * - 데이터 작업: 행의 Primary Key (예: 123L)
     * - 트랜잭션 제어 작업: null (행과 무관)
     *
     * Long 타입 사용 이유:
     * - 대부분의 DB에서 PK로 정수형 사용
     * - null 허용을 위해 primitive long 대신 wrapper Long 사용
     */
    private final Long rowId;

    /**
     * 변경 전 값 (Before Image)
     *
     * 데이터 변경 "이전"의 행 상태입니다.
     * UNDO(롤백) 복구에 사용됩니다.
     *
     * 작업별 값:
     * - INSERT: null (이전에 행이 존재하지 않았음)
     * - UPDATE: 수정 전 원본 데이터
     * - DELETE: 삭제 전 데이터 (복원 시 필요)
     * - 트랜잭션 제어: null
     *
     * UNDO 복구 예시:
     * UPDATE 롤백: beforeValue로 행을 덮어씀
     * DELETE 롤백: beforeValue로 행을 다시 삽입
     * INSERT 롤백: 해당 행 삭제 (beforeValue 불필요)
     *
     * 저장 형식:
     * 일반적으로 CSV나 JSON 문자열로 직렬화된 행 데이터
     * 예: "1,홍길동,hong@email.com,2024-01-01"
     */
    private final String beforeValue;

    /**
     * 변경 후 값 (After Image)
     *
     * 데이터 변경 "이후"의 행 상태입니다.
     * REDO(재실행) 복구에 사용됩니다.
     *
     * 작업별 값:
     * - INSERT: 삽입된 데이터
     * - UPDATE: 수정 후 데이터
     * - DELETE: null (이후에 행이 존재하지 않음)
     * - 트랜잭션 제어: null
     *
     * REDO 복구 예시:
     * 커밋된 트랜잭션의 변경이 디스크에 반영 안 됐을 때:
     * INSERT REDO: afterValue로 행 삽입
     * UPDATE REDO: afterValue로 행 덮어씀
     * DELETE REDO: 해당 행 삭제 (afterValue 불필요)
     */
    private final String afterValue;

    /**
     * 작업 발생 시각
     *
     * 이 로그 엔트리가 생성된 시점입니다.
     *
     * 용도:
     * - 디버깅: 문제 발생 시점 추적
     * - 감사(Audit): 누가 언제 무엇을 했는지 기록
     * - 분석: 트랜잭션 처리 시간 측정
     * - 로그 정리: 오래된 로그 삭제 기준
     *
     * LSN과의 차이:
     * - LSN: 순서 보장용 (논리적 순서)
     * - timestamp: 실제 시각 (물리적 시간)
     * - 동시 작업은 같은 timestamp를 가질 수 있지만 LSN은 다름
     */
    private final LocalDateTime timestamp;

    // ==================== 생성자 ====================

    /**
     * 전체 필드 생성자
     *
     * 모든 필드를 직접 지정하여 WalEntry를 생성합니다.
     *
     * 사용 권장 사항:
     * - 외부에서 직접 호출보다는 팩토리 메서드 사용 권장
     * - 팩토리 메서드가 각 작업 타입에 맞는 필드를 적절히 설정
     * - 예: insert()는 beforeValue를 null로 설정
     *
     * 이 생성자가 public인 이유:
     * - fromCsvLine()에서 파싱 후 생성 시 필요
     * - 테스트에서 임의의 엔트리 생성 시 필요
     *
     * @param lsn 로그 일련 번호 (WriteAheadLog에서 발급)
     * @param transactionId 트랜잭션 ID
     * @param operation 작업 타입
     * @param tableName 테이블 이름 (데이터 작업 시)
     * @param rowId 행 ID (데이터 작업 시)
     * @param beforeValue 변경 전 값 (UPDATE, DELETE 시)
     * @param afterValue 변경 후 값 (INSERT, UPDATE 시)
     * @param timestamp 작업 발생 시각
     */
    public WalEntry(long lsn, String transactionId, Operation operation,
                    String tableName, Long rowId, String beforeValue,
                    String afterValue, LocalDateTime timestamp) {
        this.lsn = lsn;
        this.transactionId = transactionId;
        this.operation = operation;
        this.tableName = tableName;
        this.rowId = rowId;
        this.beforeValue = beforeValue;
        this.afterValue = afterValue;
        this.timestamp = timestamp;
    }

    // ==================== 팩토리 메서드 ====================

    /**
     * UPDATE 작업 엔트리 생성
     *
     * 기존 행의 데이터를 수정하는 작업을 기록합니다.
     *
     * @param lsn 로그 일련 번호
     * @param txId 트랜잭션 ID
     * @param table 테이블 이름
     * @param rowId 수정할 행 ID
     * @param before 변경 전 값 (UNDO용 - 롤백 시 이 값으로 복원)
     * @param after 변경 후 값 (REDO용 - 재실행 시 이 값으로 적용)
     * @return UPDATE 타입의 WalEntry
     *
     * UPDATE는 before와 after 모두 필요한 이유:
     * - 롤백 시: before로 원래 상태 복원
     * - 재실행 시: after로 변경 재적용
     * - 둘 다 없으면 복구 불가능
     *
     * 사용 예시 (WriteAheadLog에서):
     * WalEntry entry = WalEntry.update(nextLsn(), "tx-001", "users", 123L,
     *     "1,홍길동,hong@old.com",   // before
     *     "1,홍길동,hong@new.com");  // after
     */
    public static WalEntry update(long lsn, String txId, String table, Long rowId,
                                  String before, String after) {
        return new WalEntry(lsn, txId, Operation.UPDATE, table, rowId, before, after, LocalDateTime.now());
    }

    /**
     * INSERT 작업 엔트리 생성
     *
     * 새로운 행을 삽입하는 작업을 기록합니다.
     *
     * @param lsn 로그 일련 번호
     * @param txId 트랜잭션 ID
     * @param table 테이블 이름
     * @param rowId 삽입할 행 ID
     * @param value 삽입할 데이터 (after 값이 됨)
     * @return INSERT 타입의 WalEntry
     *
     * INSERT는 after만 있는 이유:
     * - 삽입 전에는 해당 행이 존재하지 않음 → before = null
     * - after에 삽입된 전체 데이터 저장
     *
     * 복구 시 처리:
     * - 롤백: 해당 행 삭제 (before 불필요, rowId만 있으면 됨)
     * - 재실행: after 값으로 행 삽입
     */
    public static WalEntry insert(long lsn, String txId, String table, Long rowId, String value) {
        return new WalEntry(lsn, txId, Operation.INSERT, table, rowId, null, value, LocalDateTime.now());
    }

    /**
     * DELETE 작업 엔트리 생성
     *
     * 기존 행을 삭제하는 작업을 기록합니다.
     *
     * @param lsn 로그 일련 번호
     * @param txId 트랜잭션 ID
     * @param table 테이블 이름
     * @param rowId 삭제할 행 ID
     * @param value 삭제될 데이터 (before 값이 됨 - 복원용)
     * @return DELETE 타입의 WalEntry
     *
     * DELETE는 before만 있는 이유:
     * - 삭제 후에는 해당 행이 존재하지 않음 → after = null
     * - before에 삭제 전 데이터 저장 (롤백 시 복원용)
     *
     * 복구 시 처리:
     * - 롤백: before 값으로 행 다시 삽입
     * - 재실행: 해당 행 삭제 (after 불필요, rowId만 있으면 됨)
     */
    public static WalEntry delete(long lsn, String txId, String table, Long rowId, String value) {
        return new WalEntry(lsn, txId, Operation.DELETE, table, rowId, value, null, LocalDateTime.now());
    }

    /**
     * BEGIN 작업 엔트리 생성
     *
     * 트랜잭션 시작을 기록합니다.
     *
     * @param lsn 로그 일련 번호
     * @param txId 시작하는 트랜잭션 ID
     * @return BEGIN 타입의 WalEntry
     *
     * BEGIN 엔트리의 역할:
     * - 트랜잭션의 시작점 표시
     * - 복구 시 활성 트랜잭션 목록 파악
     * - 트랜잭션 경계 식별
     *
     * 데이터 관련 필드는 모두 null:
     * - tableName: null (특정 테이블과 무관)
     * - rowId: null (특정 행과 무관)
     * - beforeValue: null (데이터 변경 없음)
     * - afterValue: null (데이터 변경 없음)
     *
     * 복구 시 사용:
     * BEGIN은 있는데 COMMIT/ROLLBACK이 없으면 → 미완료 트랜잭션 → UNDO 필요
     */
    public static WalEntry begin(long lsn, String txId) {
        return new WalEntry(lsn, txId, Operation.BEGIN, null, null, null, null, LocalDateTime.now());
    }

    /**
     * COMMIT 작업 엔트리 생성
     *
     * 트랜잭션 커밋(성공적 완료)을 기록합니다.
     *
     * @param lsn 로그 일련 번호
     * @param txId 커밋하는 트랜잭션 ID
     * @return COMMIT 타입의 WalEntry
     *
     * COMMIT 엔트리의 핵심 역할:
     * - 트랜잭션이 성공적으로 완료되었음을 표시
     * - 이 로그가 디스크에 있으면 → 트랜잭션은 영구히 커밋됨
     * - 이 로그가 없으면 → 트랜잭션은 완료되지 않은 것
     *
     * Durability 보장의 핵심:
     * WriteAheadLog.logCommit()에서 이 엔트리 기록 후 fsync() 호출
     * → 디스크에 확실히 기록 → 시스템 장애 후에도 커밋 상태 유지
     *
     * 복구 시 판단 기준:
     * - COMMIT 있음 → 변경 유지 (필요 시 REDO)
     * - COMMIT 없음 → 변경 취소 (UNDO)
     */
    public static WalEntry commit(long lsn, String txId) {
        return new WalEntry(lsn, txId, Operation.COMMIT, null, null, null, null, LocalDateTime.now());
    }

    /**
     * ROLLBACK 작업 엔트리 생성
     *
     * 트랜잭션 롤백(취소)을 기록합니다.
     *
     * @param lsn 로그 일련 번호
     * @param txId 롤백하는 트랜잭션 ID
     * @return ROLLBACK 타입의 WalEntry
     *
     * ROLLBACK 엔트리의 역할:
     * - 트랜잭션이 명시적으로 취소되었음을 표시
     * - 복구 시 이 트랜잭션은 무시해도 됨
     *
     * COMMIT과의 차이:
     * - COMMIT: 변경을 확정 → REDO 가능
     * - ROLLBACK: 변경을 취소 → 이미 UNDO됨
     *
     * fsync 불필요한 이유:
     * - ROLLBACK 로그가 사라져도 문제 없음
     * - COMMIT이 없으면 어차피 복구 시 UNDO됨
     */
    public static WalEntry rollback(long lsn, String txId) {
        return new WalEntry(lsn, txId, Operation.ROLLBACK, null, null, null, null, LocalDateTime.now());
    }

    // ==================== CSV 직렬화/역직렬화 ====================

    /**
     * CSV 라인으로 변환 (직렬화)
     *
     * 이 엔트리를 CSV 형식의 문자열로 변환합니다.
     * WriteAheadLog가 파일에 저장할 때 호출합니다.
     *
     * @return CSV 형식의 문자열 (개행 문자 미포함)
     *
     * 출력 형식:
     * "lsn,transactionId,operation,tableName,rowId,beforeValue,afterValue,timestamp"
     *
     * 예시:
     * - BEGIN: "1,tx-001,BEGIN,,,,,2024-01-15T10:00:00"
     * - UPDATE: "2,tx-001,UPDATE,users,123,oldData,newData,2024-01-15T10:00:01"
     * - COMMIT: "3,tx-001,COMMIT,,,,,2024-01-15T10:00:02"
     *
     * null 처리:
     * - null 값은 빈 문자열("")로 변환
     * - "null" 문자열이 파일에 저장되는 것 방지
     * - 파싱 시 빈 문자열은 다시 null로 변환
     *
     * 주의사항:
     * - beforeValue나 afterValue에 쉼표(,)가 포함되면 파싱 오류 가능
     * - 실제 운영에서는 이스케이프 처리나 다른 구분자 고려 필요
     */
    public String toCsvLine() {
        return String.format("%d,%s,%s,%s,%s,%s,%s,%s",
                lsn,
                transactionId,
                operation,                                      // enum.toString() 자동 호출
                tableName != null ? tableName : "",             // null → 빈 문자열
                rowId != null ? rowId : "",                     // null → 빈 문자열
                beforeValue != null ? beforeValue : "",         // null → 빈 문자열
                afterValue != null ? afterValue : "",           // null → 빈 문자열
                timestamp.format(FORMATTER));                   // ISO 형식으로 포맷
    }

    /**
     * CSV 라인에서 파싱 (역직렬화)
     *
     * CSV 형식의 문자열을 파싱하여 WalEntry 객체를 생성합니다.
     * WriteAheadLog가 파일에서 읽을 때 호출합니다.
     *
     * @param line CSV 형식의 문자열
     * @return 파싱된 WalEntry 객체
     * @throws IllegalArgumentException 형식이 올바르지 않으면
     *
     * 입력 형식:
     * "lsn,transactionId,operation,tableName,rowId,beforeValue,afterValue,timestamp"
     *
     * split(",", -1) 설명:
     * 두 번째 인자 -1은 빈 문자열도 결과 배열에 포함하라는 의미입니다.
     *
     * 비교:
     * - "a,,c".split(",")     → ["a", "c"]      (빈 문자열 누락!)
     * - "a,,c".split(",", -1) → ["a", "", "c"]  (빈 문자열 포함)
     *
     * 이게 중요한 이유:
     * BEGIN 엔트리: "1,tx-001,BEGIN,,,,,2024-..."
     * → split(",")을 쓰면 빈 필드들이 누락되어 파싱 오류
     * → split(",", -1)을 써야 8개 필드가 모두 유지됨
     *
     * 빈 문자열 → null 변환:
     * - 빈 문자열은 toCsvLine()에서 null을 표현한 것
     * - 다시 null로 복원해야 원본과 동일
     */
    public static WalEntry fromCsvLine(String line) {
        // -1: 빈 문자열도 결과에 포함 (trailing empty strings 유지)
        String[] parts = line.split(",", -1);

        // 최소 8개 필드 필요 (lsn ~ timestamp)
        if (parts.length < 8) {
            throw new IllegalArgumentException("Invalid WAL entry format: " + line);
        }

        // 각 필드 파싱
        long lsn = Long.parseLong(parts[0].trim());
        String txId = parts[1].trim();
        Operation op = Operation.valueOf(parts[2].trim());  // 문자열 → enum 변환

        // 빈 문자열은 null로 변환 (원본 복원)
        String table = parts[3].trim().isEmpty() ? null : parts[3].trim();
        Long rowId = parts[4].trim().isEmpty() ? null : Long.parseLong(parts[4].trim());
        String before = parts[5].trim().isEmpty() ? null : parts[5].trim();
        String after = parts[6].trim().isEmpty() ? null : parts[6].trim();

        // 타임스탬프 파싱
        LocalDateTime ts = LocalDateTime.parse(parts[7].trim(), FORMATTER);

        return new WalEntry(lsn, txId, op, table, rowId, before, after, ts);
    }

    // ==================== Getter 메서드 ====================

    /**
     * LSN(로그 일련 번호) 반환
     * @return 이 엔트리의 고유 일련 번호
     */
    public long getLsn() {
        return lsn;
    }

    /**
     * 트랜잭션 ID 반환
     * @return 이 작업을 수행한 트랜잭션의 ID
     */
    public String getTransactionId() {
        return transactionId;
    }

    /**
     * 작업 타입 반환
     * @return 이 엔트리의 작업 종류 (BEGIN, INSERT, UPDATE, DELETE, COMMIT, ROLLBACK)
     */
    public Operation getOperation() {
        return operation;
    }

    /**
     * 테이블 이름 반환
     * @return 데이터 작업의 대상 테이블, 트랜잭션 제어 작업이면 null
     */
    public String getTableName() {
        return tableName;
    }

    /**
     * 행 ID 반환
     * @return 데이터 작업의 대상 행 ID, 트랜잭션 제어 작업이면 null
     */
    public Long getRowId() {
        return rowId;
    }

    /**
     * 변경 전 값(Before Image) 반환
     * @return UNDO용 이전 데이터, INSERT나 트랜잭션 제어 작업이면 null
     */
    public String getBeforeValue() {
        return beforeValue;
    }

    /**
     * 변경 후 값(After Image) 반환
     * @return REDO용 이후 데이터, DELETE나 트랜잭션 제어 작업이면 null
     */
    public String getAfterValue() {
        return afterValue;
    }

    /**
     * 작업 발생 시각 반환
     * @return 이 로그 엔트리가 생성된 시점
     */
    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    // ==================== 상태 확인 메서드 ====================

    /**
     * 트랜잭션 시작 엔트리인지 확인
     *
     * @return BEGIN 작업이면 true
     *
     * 용도:
     * - 복구 시 활성 트랜잭션 목록 구성
     * - 트랜잭션 경계 식별
     */
    public boolean isBegin() {
        return operation == Operation.BEGIN;
    }

    /**
     * 커밋 엔트리인지 확인
     *
     * @return COMMIT 작업이면 true
     *
     * 용도:
     * - 복구 시 트랜잭션 완료 여부 판단의 핵심
     * - isCommit() == true → 변경 유지
     * - isCommit() == false && !isRollback() → UNDO 필요
     */
    public boolean isCommit() {
        return operation == Operation.COMMIT;
    }

    /**
     * 롤백 엔트리인지 확인
     *
     * @return ROLLBACK 작업이면 true
     *
     * 용도:
     * - 복구 시 이미 취소된 트랜잭션 식별
     * - 해당 트랜잭션의 변경은 이미 UNDO됨
     */
    public boolean isRollback() {
        return operation == Operation.ROLLBACK;
    }

    /**
     * 데이터 변경 작업인지 확인
     *
     * @return INSERT, UPDATE, DELETE 중 하나면 true
     *
     * 용도:
     * - 롤백 시 UNDO할 작업만 필터링
     * - 복구 시 REDO할 작업만 필터링
     * - BEGIN, COMMIT, ROLLBACK은 데이터 변경이 아니므로 제외
     *
     * 사용 예시:
     * entries.stream()
     *     .filter(WalEntry::isDataOperation)  // 데이터 작업만
     *     .forEach(this::undo);               // UNDO 수행
     */
    public boolean isDataOperation() {
        return operation == Operation.INSERT ||
                operation == Operation.UPDATE ||
                operation == Operation.DELETE;
    }

    /**
     * 문자열 표현 반환
     *
     * @return 디버깅용 문자열
     *
     * 출력 형식:
     * "WalEntry{lsn=1, txId=tx-001, op=UPDATE, table=users, row=123}"
     *
     * beforeValue, afterValue는 포함하지 않음:
     * - 값이 길 수 있어 로그 가독성 저하
     * - 필요 시 getter로 직접 조회
     */
    @Override
    public String toString() {
        return String.format("WalEntry{lsn=%d, txId=%s, op=%s, table=%s, row=%s}",
                lsn, transactionId, operation, tableName, rowId);
    }

    // ==================== 내부 열거형 ====================

    /**
     * WAL 작업 타입 열거형
     *
     * WAL에 기록될 수 있는 모든 작업 종류를 정의합니다.
     *
     * 분류:
     *
     * 1. 트랜잭션 제어 작업 (Transaction Control)
     *    - BEGIN: 트랜잭션 시작점 표시
     *    - COMMIT: 트랜잭션 성공적 완료 표시
     *    - ROLLBACK: 트랜잭션 취소 표시
     *
     * 2. 데이터 변경 작업 (Data Manipulation)
     *    - INSERT: 새 행 삽입 (after만 사용)
     *    - UPDATE: 기존 행 수정 (before, after 모두 사용)
     *    - DELETE: 기존 행 삭제 (before만 사용)
     *
     * 복구 알고리즘에서의 역할:
     *
     * ARIES 스타일 복구:
     * 1. Analysis: BEGIN/COMMIT/ROLLBACK으로 활성 트랜잭션 파악
     * 2. Redo: 커밋된 트랜잭션의 데이터 작업을 after로 재적용
     * 3. Undo: 미완료 트랜잭션의 데이터 작업을 before로 롤백
     */
    public enum Operation {
        /**
         * 트랜잭션 시작
         * 새로운 트랜잭션이 시작되었음을 표시합니다.
         */
        BEGIN,

        /**
         * 행 삽입
         * 새로운 행이 테이블에 추가되었음을 기록합니다.
         * afterValue에 삽입된 데이터가 저장됩니다.
         */
        INSERT,

        /**
         * 행 수정
         * 기존 행의 데이터가 변경되었음을 기록합니다.
         * beforeValue에 원본, afterValue에 변경 후 데이터가 저장됩니다.
         */
        UPDATE,

        /**
         * 행 삭제
         * 기존 행이 테이블에서 제거되었음을 기록합니다.
         * beforeValue에 삭제된 데이터가 저장됩니다.
         */
        DELETE,

        /**
         * 트랜잭션 커밋
         * 트랜잭션이 성공적으로 완료되었음을 표시합니다.
         * 이 로그가 디스크에 있으면 트랜잭션은 영구히 커밋된 것입니다.
         */
        COMMIT,

        /**
         * 트랜잭션 롤백
         * 트랜잭션이 취소되었음을 표시합니다.
         * 해당 트랜잭션의 모든 변경사항은 이미 UNDO되었습니다.
         */
        ROLLBACK
    }
}
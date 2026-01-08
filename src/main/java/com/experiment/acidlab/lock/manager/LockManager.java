package com.experiment.acidlab.lock.manager;

import com.experiment.acidlab.lock.domain.RowLock;
import com.experiment.acidlab.global.logger.ConsoleLogger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 락 관리자 (Lock Manager)
 *
 * 데이터베이스 트랜잭션의 격리성(Isolation)을 보장하기 위한 중앙 집중식 락 관리 시스템입니다.
 *
 * 핵심 책임:
 * 1. 행 단위 락(Row-Level Lock) 생성 및 관리
 * 2. 트랜잭션별 보유 락 추적
 * 3. 락 타임아웃 처리로 데드락 방지
 * 4. 트랜잭션 종료 시 일괄 락 해제
 *
 * 동시성 제어:
 * - ConcurrentHashMap 사용으로 멀티스레드 환경에서 안전
 * - 락 획득/해제 시 경쟁 상태(race condition) 방지
 *
 * 사용 예:
 * LockManager lockManager = new LockManager();
 *
 * // 트랜잭션 시작 후 락 획득
 * if (lockManager.acquireLock("tx-001", "users", 123L)) {
 *     try {
 *         // 데이터 수정 작업
 *     } finally {
 *         lockManager.releaseAllLocks("tx-001");
 *     }
 * }
 */
public class LockManager {

    // ==================== 상수 ====================

    /**
     * 기본 락 타임아웃 (밀리초)
     *
     * 5초 동안 락을 획득하지 못하면 실패 처리합니다.
     * - 너무 짧으면: 정상적인 락 경쟁에서도 실패 발생
     * - 너무 길면: 데드락 상황에서 오래 대기
     */
    private static final long DEFAULT_LOCK_TIMEOUT_MS = 5000;

    // ==================== 필드 ====================

    /**
     * 전역 락 저장소
     *
     * Key: 락 키 (형식: "테이블명:행ID", 예: "users:123")
     * Value: 해당 행의 RowLock 객체
     *
     * ConcurrentHashMap 사용 이유:
     * - 여러 스레드가 동시에 다른 행의 락을 생성/조회할 수 있음
     * - synchronized 없이도 스레드 안전한 put/get 연산
     * - 읽기 작업은 블로킹 없이 수행 (높은 동시성)
     *
     * 주의: 락 객체 자체는 RowLock 내부의 ReentrantLock이 동기화 담당
     */
    private final Map<String, RowLock> locks = new ConcurrentHashMap<>();

    /**
     * 트랜잭션별 보유 락 추적
     *
     * Key: 트랜잭션 ID (예: "tx-001")
     * Value: 해당 트랜잭션이 보유한 락 키들의 집합
     *
     * 용도:
     * - 트랜잭션 종료 시 해당 트랜잭션의 모든 락을 일괄 해제
     * - 특정 트랜잭션이 어떤 락을 보유 중인지 조회
     * - 데드락 탐지 시 트랜잭션 간 의존 관계 분석
     *
     * ConcurrentHashMap.newKeySet() 사용:
     * - Value가 Set<String>이므로 내부 Set도 스레드 안전해야 함
     * - 여러 스레드가 같은 트랜잭션에 락을 추가/제거할 수 있음
     */
    private final Map<String, Set<String>> transactionLocks = new ConcurrentHashMap<>();

    /**
     * 로거
     * 락 획득/해제/타임아웃 등 이벤트를 기록합니다.
     */
    private final ConsoleLogger logger;

    /**
     * 락 타임아웃 설정값 (밀리초)
     * 생성 시 설정되며 이후 변경 불가 (불변성)
     */
    private final long lockTimeoutMs;

    // ==================== 생성자 ====================

    /**
     * 기본 생성자
     * 기본 타임아웃(5초)으로 LockManager를 생성합니다.
     */
    public LockManager() {
        this(DEFAULT_LOCK_TIMEOUT_MS);
    }

    /**
     * 타임아웃 지정 생성자
     *
     * @param lockTimeoutMs 락 획득 대기 최대 시간 (밀리초)
     *
     * 사용 예:
     * - 빠른 응답이 필요한 시스템: new LockManager(1000)  // 1초
     * - 긴 트랜잭션이 많은 시스템: new LockManager(30000) // 30초
     */
    public LockManager(long lockTimeoutMs) {
        this.lockTimeoutMs = lockTimeoutMs;
        this.logger = ConsoleLogger.of("LockManager");
    }

    // ==================== 락 획득/해제 메서드 ====================

    /**
     * 행 락 획득
     *
     * @param transactionId 락을 요청하는 트랜잭션 ID
     * @param tableName 락을 걸 테이블 이름
     * @param rowId 락을 걸 행의 ID
     * @return 락 획득 성공 시 true, 타임아웃 또는 인터럽트 시 false
     *
     * 동작 순서:
     * 1. 락 키 생성 ("테이블명:행ID")
     * 2. 해당 키의 RowLock이 없으면 새로 생성 (computeIfAbsent로 원자적 처리)
     * 3. 지정된 타임아웃 동안 락 획득 시도
     * 4. 성공 시: 트랜잭션 락 목록에 등록하고 true 반환
     * 5. 실패 시: 로그 남기고 false 반환
     *
     * computeIfAbsent 사용 이유:
     * - 락이 없으면 생성, 있으면 기존 것 반환
     * - 이 과정이 원자적(atomic)으로 수행됨
     * - 두 스레드가 동시에 같은 키로 요청해도 하나의 RowLock만 생성됨
     *
     * 사용 예:
     * if (lockManager.acquireLock("tx-001", "accounts", 100L)) {
     *     // 계좌 100번 행 수정 가능
     * } else {
     *     // 락 획득 실패 - 재시도 또는 에러 처리
     * }
     */
    public boolean acquireLock(String transactionId, String tableName, Long rowId) {
        // 1. 락 키 생성
        String lockKey = createLockKey(tableName, rowId);

        // 2. RowLock 가져오기 (없으면 생성)
        // computeIfAbsent: 키가 없을 때만 람다 실행하여 값 생성
        // 여러 스레드가 동시에 호출해도 RowLock은 하나만 생성됨 (원자적 연산)
        RowLock lock = locks.computeIfAbsent(lockKey, k -> new RowLock(tableName, rowId));

        try {
            // 3. 타임아웃 내에 락 획득 시도
            boolean acquired = lock.tryLock(lockTimeoutMs, TimeUnit.MILLISECONDS);

            if (acquired) {
                // 4a. 락 획득 성공
                // 트랜잭션의 락 목록에 추가 (없으면 새 Set 생성)
                // ConcurrentHashMap.newKeySet(): 스레드 안전한 Set 생성
                transactionLocks.computeIfAbsent(transactionId, k -> ConcurrentHashMap.newKeySet())
                        .add(lockKey);
                logger.debug(String.format("[%s] Lock acquired: %s", transactionId, lockKey));
                return true;
            } else {
                // 4b. 락 획득 실패 (타임아웃)
                // 누가 락을 잡고 있는지 로그에 기록 (디버깅용)
                logger.warn(String.format("[%s] Lock timeout: %s (held by %s)",
                        transactionId, lockKey, lock.getHolder()));
                return false;
            }
        } catch (InterruptedException e) {
            // 5. 대기 중 인터럽트 발생
            // 인터럽트 상태 복원: 상위 호출자가 인터럽트를 인지할 수 있도록
            Thread.currentThread().interrupt();
            logger.warn(String.format("[%s] Lock interrupted: %s", transactionId, lockKey));
            return false;
        }
    }

    /**
     * 단일 행 락 해제
     *
     * @param transactionId 락을 보유한 트랜잭션 ID
     * @param tableName 락을 해제할 테이블 이름
     * @param rowId 락을 해제할 행의 ID
     *
     * 동작 순서:
     * 1. 락 키로 RowLock 조회
     * 2. 현재 스레드가 락 소유자인지 확인
     * 3. 소유자라면 락 해제
     * 4. 트랜잭션 락 목록에서 제거
     *
     * 안전 장치:
     * - isHeldByCurrentThread() 체크로 다른 스레드가 해제하는 것 방지
     * - 락이 없거나 소유자가 아니면 아무 작업도 안 함 (안전한 무시)
     *
     * 주의:
     * - 일반적으로는 releaseAllLocks()를 사용하여 일괄 해제 권장
     * - 개별 해제는 2PL(2-Phase Locking) 위반 가능성 있음
     */
    public void releaseLock(String transactionId, String tableName, Long rowId) {
        String lockKey = createLockKey(tableName, rowId);
        RowLock lock = locks.get(lockKey);

        // 락이 존재하고 현재 스레드가 소유자인 경우에만 해제
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
            logger.debug(String.format("[%s] Lock released: %s", transactionId, lockKey));
        }

        // 트랜잭션 락 목록에서 제거
        // 락 해제와 별개로 항상 목록에서는 제거 (정리 목적)
        Set<String> txLocks = transactionLocks.get(transactionId);
        if (txLocks != null) {
            txLocks.remove(lockKey);
        }
    }

    /**
     * 트랜잭션의 모든 락 일괄 해제
     *
     * @param transactionId 락을 해제할 트랜잭션 ID
     *
     * 동작 순서:
     * 1. 트랜잭션 락 목록을 가져오면서 동시에 제거 (remove)
     * 2. 목록의 모든 락을 순회하며 해제
     *
     * 사용 시점:
     * - 트랜잭션 커밋 후
     * - 트랜잭션 롤백 후
     * - 트랜잭션 타임아웃 시
     *
     * 2PL(2-Phase Locking) 준수:
     * - Growing Phase: 락 획득만 함
     * - Shrinking Phase: 락 해제만 함 (이 메서드)
     * - 모든 락을 한 번에 해제하여 격리성 보장
     *
     * 안전 장치:
     * - isHeldByCurrentThread() 체크로 자신의 락만 해제
     * - 트랜잭션 목록에 있지만 실제로는 해제된 락도 안전하게 처리
     */
    public void releaseAllLocks(String transactionId) {
        // remove(): 가져오면서 동시에 Map에서 제거
        // 트랜잭션 종료 후에는 더 이상 락 목록이 필요 없음
        Set<String> txLocks = transactionLocks.remove(transactionId);

        if (txLocks != null) {
            for (String lockKey : txLocks) {
                RowLock lock = locks.get(lockKey);
                // 현재 스레드가 소유한 락만 해제
                if (lock != null && lock.isHeldByCurrentThread()) {
                    lock.unlock();
                    logger.debug(String.format("[%s] Lock released: %s", transactionId, lockKey));
                }
            }
        }
    }

    // ==================== 조회 메서드 ====================

    /**
     * 특정 행에 대한 락 보유 여부 확인
     *
     * @param transactionId 확인할 트랜잭션 ID
     * @param tableName 테이블 이름
     * @param rowId 행 ID
     * @return 해당 트랜잭션이 락을 보유 중이면 true
     *
     * 용도:
     * - 락 재획득 전 이미 보유 중인지 확인
     * - 디버깅 및 상태 확인
     *
     * 주의:
     * - 트랜잭션 락 목록 기준으로 확인
     * - 실제 RowLock 상태와 다를 수 있음 (동기화 문제 가능)
     */
    public boolean hasLock(String transactionId, String tableName, Long rowId) {
        String lockKey = createLockKey(tableName, rowId);
        Set<String> txLocks = transactionLocks.get(transactionId);
        return txLocks != null && txLocks.contains(lockKey);
    }

    /**
     * 트랜잭션이 보유한 모든 락 키 조회
     *
     * @param transactionId 조회할 트랜잭션 ID
     * @return 보유 중인 락 키 Set (없으면 빈 Set)
     *
     * 용도:
     * - 데드락 탐지: 트랜잭션 간 락 의존 관계 분석
     * - 디버깅: 트랜잭션이 어떤 리소스를 점유 중인지 확인
     * - 모니터링: 락 보유량 추적
     *
     * 반환값 특징:
     * - getOrDefault로 null 대신 빈 Set 반환 (NPE 방지)
     * - 원본 Set 반환하므로 외부에서 수정 시 주의 필요
     */
    public Set<String> getLocksForTransaction(String transactionId) {
        return transactionLocks.getOrDefault(transactionId, Collections.emptySet());
    }

    /**
     * 전체 락 상태 조회 (디버깅/모니터링용)
     *
     * @return 모든 락의 현재 상태 Map
     *         Key: 락 키 ("테이블:행ID")
     *         Value: 락 보유 스레드 이름 또는 "free"
     *
     * 용도:
     * - 관리자 대시보드에서 락 상태 표시
     * - 데드락 분석
     * - 시스템 모니터링
     *
     * LinkedHashMap 사용 이유:
     * - 입력 순서 유지 (출력 시 일관된 순서)
     *
     * 주의:
     * - 조회 시점의 스냅샷일 뿐, 즉시 변경될 수 있음
     * - 동기화 제어 용도가 아닌 모니터링 용도로만 사용
     */
    public Map<String, String> getLockStatus() {
        Map<String, String> status = new LinkedHashMap<>();
        locks.forEach((key, lock) -> {
            Thread holder = lock.getHolder();
            status.put(key, holder != null ? holder.getName() : "free");
        });
        return status;
    }

    // ==================== 유틸리티 메서드 ====================

    /**
     * 락 키 생성
     *
     * @param tableName 테이블 이름
     * @param rowId 행 ID
     * @return "테이블명:행ID" 형식의 고유 키
     *
     * 예: createLockKey("users", 123L) → "users:123"
     *
     * 키 형식의 중요성:
     * - 동일한 테이블/행 조합은 항상 같은 키 생성
     * - 서로 다른 조합은 반드시 다른 키 생성
     * - 간결하면서도 고유성 보장
     */
    private String createLockKey(String tableName, Long rowId) {
        return tableName + ":" + rowId;
    }

    /**
     * 모든 락 초기화 (테스트용)
     *
     * 동작:
     * 1. 현재 스레드가 보유한 모든 락 해제
     * 2. 락 저장소 비우기
     * 3. 트랜잭션 락 목록 비우기
     *
     * 용도:
     * - 단위 테스트 간 상태 초기화
     * - 통합 테스트 후 정리
     *
     * 주의:
     * - 프로덕션 환경에서는 사용 금지!
     * - 다른 스레드의 락은 해제하지 않음 (isHeldByCurrentThread 체크)
     * - 테스트 환경에서만 호출해야 함
     */
    public void clear() {
        // 현재 스레드가 보유한 락만 해제
        locks.values().forEach(lock -> {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        });
        // 모든 데이터 초기화
        locks.clear();
        transactionLocks.clear();
        logger.info("All locks cleared");
    }
}
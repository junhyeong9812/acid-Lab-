package com.experiment.acidlab.lock.domain;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 행 단위 락 (Row-Level Lock)
 *
 * 데이터베이스의 특정 테이블의 특정 행에 대한 동시 접근을 제어합니다.
 * 여러 스레드가 같은 행을 동시에 수정하는 것을 방지하여 데이터 일관성을 보장합니다.
 *
 * 주요 특징:
 * - ReentrantLock 기반: 같은 스레드가 락을 여러 번 획득 가능 (재진입 가능)
 * - 공정성(Fair) 모드: 락 요청 순서대로 획득 (기아 상태 방지)
 * - 타임아웃 지원: 무한 대기 방지 가능
 * - 락 소유자 추적: 디버깅 및 모니터링 용이
 */
public class RowLock {

    // ==================== 필드 ====================

    /**
     * 락이 적용되는 테이블 이름
     * 락의 범위를 식별하는 데 사용됩니다.
     */
    private final String tableName;

    /**
     * 락이 적용되는 행의 고유 식별자
     * tableName과 조합하여 유일한 락 키를 생성합니다.
     */
    private final Long rowId;

    /**
     * 실제 락 구현체
     *
     * ReentrantLock(true): 공정 모드로 생성
     * - 대기 중인 스레드들이 요청 순서대로 락을 획득합니다.
     * - 특정 스레드가 계속 락을 못 얻는 기아(starvation) 현상을 방지합니다.
     * - 성능은 비공정 모드보다 약간 떨어지지만 공정성이 보장됩니다.
     */
    private final ReentrantLock lock;

    /**
     * 현재 락을 보유한 스레드 참조
     *
     * volatile 키워드 사용 이유:
     * - 한 스레드가 holder를 변경하면 다른 모든 스레드가 즉시 그 변경을 볼 수 있습니다.
     * - CPU 캐시가 아닌 메인 메모리에서 직접 읽고 씁니다.
     * - 락 소유자 정보의 가시성(visibility)을 보장합니다.
     *
     * 용도:
     * - 디버깅: 어떤 스레드가 락을 잡고 있는지 확인
     * - 모니터링: 락 점유 상태 추적
     * - 로깅: 데드락 분석 시 유용
     */
    private volatile Thread holder;

    // ==================== 생성자 ====================

    /**
     * RowLock 생성자
     *
     * @param tableName 락을 적용할 테이블 이름
     * @param rowId 락을 적용할 행의 ID
     *
     * 생성 시점에는 락이 해제된 상태이며, holder는 null입니다.
     */
    public RowLock(String tableName, Long rowId) {
        this.tableName = tableName;
        this.rowId = rowId;
        this.lock = new ReentrantLock(true);  // 공정 모드 활성화
    }

    // ==================== 락 획득 메서드 ====================

    /**
     * 락 획득 (블로킹 방식, 무한 대기)
     *
     * 동작 방식:
     * 1. 락이 사용 가능하면 즉시 획득하고 반환
     * 2. 락이 다른 스레드에 의해 점유 중이면 해제될 때까지 현재 스레드가 대기
     * 3. 락 획득 후 holder에 현재 스레드 기록
     *
     * 주의사항:
     * - 데드락 발생 시 영원히 대기할 수 있음
     * - 반드시 try-finally로 unlock() 호출 보장 필요
     *
     * 사용 예:
     * rowLock.lock();
     * try {
     *     // 임계 영역 - 행 데이터 수정
     * } finally {
     *     rowLock.unlock();
     * }
     */
    public void lock() {
        lock.lock();  // 락 획득까지 블로킹
        holder = Thread.currentThread();  // 락 소유자 기록
    }

    /**
     * 락 획득 시도 (타임아웃 지원)
     *
     * @param timeout 대기할 최대 시간
     * @param unit 시간 단위 (예: TimeUnit.SECONDS, TimeUnit.MILLISECONDS)
     * @return 락 획득 성공 시 true, 타임아웃 시 false
     * @throws InterruptedException 대기 중 인터럽트 발생 시
     *
     * 동작 방식:
     * 1. 지정된 시간 동안 락 획득 시도
     * 2. 시간 내 획득 성공하면 true 반환, holder에 현재 스레드 기록
     * 3. 시간 초과 시 false 반환, holder는 변경되지 않음
     *
     * 장점:
     * - 무한 대기 방지 (데드락 회피 가능)
     * - 락 획득 실패 시 대안 로직 실행 가능
     *
     * 사용 예:
     * if (rowLock.tryLock(5, TimeUnit.SECONDS)) {
     *     try {
     *         // 락 획득 성공 - 작업 수행
     *     } finally {
     *         rowLock.unlock();
     *     }
     * } else {
     *     // 락 획득 실패 - 대안 처리 (재시도, 에러 반환 등)
     * }
     */
    public boolean tryLock(long timeout, TimeUnit unit) throws InterruptedException {
        boolean acquired = lock.tryLock(timeout, unit);  // 지정 시간만큼 대기
        if (acquired) {
            holder = Thread.currentThread();  // 성공 시에만 소유자 기록
        }
        return acquired;
    }

    /**
     * 락 획득 시도 (즉시 반환, 비블로킹)
     *
     * @return 락 획득 성공 시 true, 이미 점유 중이면 즉시 false
     *
     * 동작 방식:
     * 1. 락이 사용 가능하면 즉시 획득하고 true 반환
     * 2. 락이 점유 중이면 대기 없이 즉시 false 반환
     *
     * 특징:
     * - 절대 블로킹되지 않음
     * - 락 획득 가능 여부를 빠르게 확인할 때 유용
     * - 낙관적 락(optimistic locking) 구현에 활용 가능
     *
     * 사용 예:
     * if (rowLock.tryLock()) {
     *     try {
     *         // 즉시 획득 성공
     *     } finally {
     *         rowLock.unlock();
     *     }
     * } else {
     *     // 락이 이미 사용 중 - 즉시 다른 처리
     * }
     */
    public boolean tryLock() {
        boolean acquired = lock.tryLock();  // 즉시 시도, 대기 없음
        if (acquired) {
            holder = Thread.currentThread();  // 성공 시에만 소유자 기록
        }
        return acquired;
    }

    // ==================== 락 해제 메서드 ====================

    /**
     * 락 해제
     *
     * 동작 방식:
     * 1. 현재 스레드가 락을 보유 중인지 확인
     * 2. 보유 중이면 holder를 null로 설정 후 락 해제
     * 3. 보유 중이 아니면 아무 작업도 하지 않음 (안전한 무시)
     *
     * 안전 장치:
     * - isHeldByCurrentThread() 체크로 다른 스레드가 해제하는 것 방지
     * - 락을 보유하지 않은 스레드가 unlock() 호출해도 예외 발생 안 함
     *
     * 주의사항:
     * - 반드시 락을 획득한 스레드에서만 호출해야 함
     * - try-finally 블록에서 호출하여 예외 발생 시에도 해제 보장
     *
     * 순서가 중요한 이유 (holder = null 먼저):
     * - holder를 먼저 null로 설정해야 다른 스레드가 락 획득 직후
     *   이전 holder 값을 보는 것을 방지
     */
    public void unlock() {
        if (lock.isHeldByCurrentThread()) {  // 현재 스레드가 소유자인지 확인
            holder = null;  // 소유자 정보 먼저 제거
            lock.unlock();  // 실제 락 해제 (대기 중인 스레드 깨움)
        }
    }

    // ==================== 상태 확인 메서드 ====================

    /**
     * 현재 스레드가 락을 보유 중인지 확인
     *
     * @return 현재 스레드가 락 소유 중이면 true
     *
     * 용도:
     * - unlock() 전 소유권 확인
     * - 재진입(reentrant) 상황 감지
     * - 디버깅 및 assertion
     *
     * 사용 예:
     * assert rowLock.isHeldByCurrentThread() : "락을 보유해야 합니다";
     */
    public boolean isHeldByCurrentThread() {
        return lock.isHeldByCurrentThread();
    }

    /**
     * 락이 어떤 스레드에 의해 점유 중인지 확인
     *
     * @return 락이 점유 중이면 true, 사용 가능하면 false
     *
     * 주의사항:
     * - 이 메서드 반환 직후 상태가 변경될 수 있음 (race condition)
     * - 동기화 제어 용도가 아닌 모니터링/디버깅 용도로만 사용
     *
     * 사용 예:
     * if (rowLock.isLocked()) {
     *     log.debug("행 {}:{} 이 현재 락 상태", tableName, rowId);
     * }
     */
    public boolean isLocked() {
        return lock.isLocked();
    }

    /**
     * 락을 보유한 스레드 반환
     *
     * @return 락 소유 스레드, 없으면 null
     *
     * 용도:
     * - 데드락 탐지: 어떤 스레드가 어떤 락을 잡고 있는지 분석
     * - 로깅: 락 경합 상황 기록
     * - 모니터링: 락 점유 시간 측정
     *
     * volatile 변수이므로 항상 최신 값을 반환합니다.
     */
    public Thread getHolder() {
        return holder;
    }

    // ==================== 유틸리티 메서드 ====================

    /**
     * 락의 고유 키 생성
     *
     * @return "테이블명:행ID" 형식의 문자열
     *
     * 용도:
     * - LockManager에서 락을 Map에 저장할 때 키로 사용
     * - 락 식별 및 검색
     *
     * 예: "users:123", "orders:456"
     */
    public String getKey() {
        return tableName + ":" + rowId;
    }

    /**
     * 테이블 이름 반환
     */
    public String getTableName() {
        return tableName;
    }

    /**
     * 행 ID 반환
     */
    public Long getRowId() {
        return rowId;
    }

    /**
     * 락 상태를 문자열로 표현
     *
     * @return 락의 현재 상태를 나타내는 문자열
     *
     * 형식: "RowLock{테이블:행ID, locked=상태, holder=스레드명}"
     * 예: "RowLock{users:123, locked=true, holder=worker-thread-1}"
     *
     * 용도:
     * - 로깅
     * - 디버깅
     * - 모니터링 대시보드 표시
     */
    @Override
    public String toString() {
        return String.format("RowLock{%s:%d, locked=%s, holder=%s}",
                tableName, rowId, isLocked(),
                holder != null ? holder.getName() : "none");
    }
}
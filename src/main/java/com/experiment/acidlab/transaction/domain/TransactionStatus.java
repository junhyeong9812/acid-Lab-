package com.experiment.acidlab.transaction.domain;

/**
 * 트랜잭션 상태
 */
public enum TransactionStatus {
    PENDING,    // 대기 중
    ACTIVE,     // 실행 중
    COMMITTED,  // 커밋 완료
    ROLLBACK,   // 롤백 완료
    FAILED      // 실패
}
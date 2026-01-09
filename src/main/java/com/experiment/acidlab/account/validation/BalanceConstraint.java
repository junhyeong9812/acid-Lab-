package com.experiment.acidlab.account.validation;

import com.experiment.acidlab.account.domain.Account;

/**
 * 잔액 제약조건 인터페이스
 * - 다양한 비즈니스 규칙을 구현 가능
 */
public interface BalanceConstraint {

    /**
     * 제약조건 만족 여부 확인
     */
    boolean isSatisfied(Account account);

    /**
     * 위반 시 메시지
     */
    String getViolationMessage(Account account);

    /**
     * 제약조건 이름
     */
    default String getName() {
        return this.getClass().getSimpleName();
    }
}
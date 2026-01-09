package com.experiment.acidlab.account.validation;

import com.experiment.acidlab.account.domain.Account;

/**
 * 잔액 제약조건 인터페이스
 *
 * ACID 중 Consistency(일관성)를 보장하기 위한 비즈니스 규칙을 정의합니다.
 *
 * Consistency란?
 * - 트랜잭션 전후로 데이터가 항상 유효한 상태를 유지해야 함
 * - 정의된 모든 제약조건을 만족해야 함
 * - 예: 잔액은 음수가 될 수 없음, 계좌 총합은 일정해야 함
 *
 * 이 인터페이스의 역할:
 * - 다양한 비즈니스 규칙을 동일한 방식으로 구현 가능 (전략 패턴)
 * - AccountValidator가 여러 제약조건을 일관되게 검증
 * - 새로운 규칙 추가 시 기존 코드 수정 없이 확장 가능 (OCP)
 *
 * 구현 예시:
 * - NonNegativeBalanceConstraint: 잔액 >= 0
 * - MaxBalanceConstraint: 잔액 <= 최대한도
 * - MinBalanceConstraint: 잔액 >= 최소유지금액
 * - DailyLimitConstraint: 일일 거래한도 체크
 *
 * 사용 예시:
 * BalanceConstraint constraint = new NonNegativeBalanceConstraint();
 * if (!constraint.isSatisfied(account)) {
 *     throw new ConstraintViolationException(constraint.getViolationMessage(account));
 * }
 */
public interface BalanceConstraint {

    /**
     * 제약조건 만족 여부 확인
     *
     * @param account 검증할 계좌
     * @return 제약조건을 만족하면 true, 위반하면 false
     *
     * 구현 시 주의사항:
     * - null 계좌 처리 고려
     * - 부수 효과(side effect) 없이 순수하게 검증만 수행
     * - 가능하면 빠르게 검증 (복잡한 연산 지양)
     *
     * 예시 구현:
     * @Override
     * public boolean isSatisfied(Account account) {
     *     return account.getBalance() >= 0;
     * }
     */
    boolean isSatisfied(Account account);

    /**
     * 제약조건 위반 시 메시지 반환
     *
     * @param account 위반한 계좌
     * @return 사람이 읽을 수 있는 위반 설명 메시지
     *
     * 메시지 작성 가이드:
     * - 어떤 제약이 위반되었는지 명확히
     * - 현재 값과 기대 값을 포함
     * - 계좌 식별 정보 포함
     *
     * 예시 구현:
     * @Override
     * public String getViolationMessage(Account account) {
     *     return String.format("Account %d has negative balance: %d",
     *             account.getId(), account.getBalance());
     * }
     *
     * 용도:
     * - 로그 기록
     * - 예외 메시지
     * - 사용자 피드백
     * - 디버깅
     */
    String getViolationMessage(Account account);

    /**
     * 제약조건 이름 반환
     *
     * @return 제약조건의 식별 이름
     *
     * default 메서드로 제공하는 이유:
     * - 대부분의 경우 클래스 이름으로 충분
     * - 구현 클래스에서 필요 시 오버라이드 가능
     * - 구현 부담 감소
     *
     * 용도:
     * - 로그에서 어떤 제약이 위반되었는지 식별
     * - 제약조건 목록 표시
     * - 디버깅
     *
     * 예시:
     * - NonNegativeBalanceConstraint → "NonNegativeBalanceConstraint"
     * - 커스텀 오버라이드 → "음수잔액불허"
     */
    default String getName() {
        return this.getClass().getSimpleName();
    }
}
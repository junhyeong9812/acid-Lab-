package com.experiment.acidlab.account.validation;

import com.experiment.acidlab.account.domain.Account;
import com.experiment.acidlab.account.repository.AccountRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * 계좌 유효성 검증기
 *
 * ACID 중 Consistency(일관성)를 보장하기 위한 제약조건 검증을 담당합니다.
 *
 * Consistency 보장 방식:
 * 1. 단일 계좌 검증: 개별 계좌가 모든 제약조건 만족하는지 확인
 * 2. 시스템 전체 검증: 전체 잔액 합계 등 글로벌 제약조건 확인
 * 3. 이체 전 검증: 작업 수행 전에 미리 유효성 검사
 *
 * 설계 특징:
 * - 전략 패턴: BalanceConstraint 인터페이스로 제약조건 추상화
 * - 확장성: addConstraint()로 새로운 규칙 동적 추가 가능
 * - 유연성: 다양한 검증 시점 지원 (사전/사후 검증)
 *
 * 사용 예시:
 * AccountValidator validator = new AccountValidator(accountRepository);
 *
 * // 이체 전 검증
 * ValidationResult result = validator.validateTransfer(fromId, toId, amount);
 * if (!result.isValid()) {
 *     throw new ValidationException(result.getViolations());
 * }
 *
 * // 트랜잭션 후 전체 검증
 * ValidationResult systemResult = validator.validateSystemConsistency(expectedTotal);
 */
public class AccountValidator {

    // ==================== 필드 ====================

    /**
     * 등록된 제약조건 목록
     *
     * 모든 검증 시 이 목록의 제약조건들을 순회하며 확인합니다.
     *
     * ArrayList 사용 이유:
     * - 제약조건 순서가 중요할 수 있음 (먼저 등록된 것 먼저 검사)
     * - 동적 추가 가능
     * - 순회 성능 좋음
     */
    private final List<BalanceConstraint> constraints = new ArrayList<>();

    /**
     * 계좌 저장소
     *
     * 검증 시 계좌 조회에 사용합니다.
     * - 개별 계좌 조회: findById()
     * - 전체 계좌 조회: findAll()
     * - 전체 잔액 합계: getTotalBalance()
     */
    private final AccountRepository accountRepository;

    // ==================== 생성자 ====================

    /**
     * AccountValidator 생성자
     *
     * @param accountRepository 계좌 저장소
     *
     * 생성 시 기본 제약조건 자동 등록:
     * - NonNegativeBalanceConstraint: 잔액 >= 0 필수
     *
     * 추가 제약조건은 addConstraint()로 등록합니다.
     */
    public AccountValidator(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
        // 기본 제약조건 등록 - 잔액은 항상 0 이상이어야 함
        addConstraint(new NonNegativeBalanceConstraint());
    }

    // ==================== 제약조건 관리 ====================

    /**
     * 제약조건 추가
     *
     * @param constraint 추가할 제약조건
     *
     * 새로운 비즈니스 규칙을 동적으로 등록할 수 있습니다.
     *
     * 사용 예시:
     * // 최대 잔액 제한 추가
     * validator.addConstraint(new MaxBalanceConstraint(10_000_000));
     *
     * // 최소 유지 잔액 추가
     * validator.addConstraint(new MinBalanceConstraint(10_000));
     *
     * // 람다로 간단한 제약조건 추가
     * validator.addConstraint(account -> account.getBalance() <= 1_000_000);
     */
    public void addConstraint(BalanceConstraint constraint) {
        constraints.add(constraint);
    }

    // ==================== 검증 메서드 ====================

    /**
     * 단일 계좌 검증
     *
     * @param account 검증할 계좌
     * @return 검증 결과 (성공/실패 및 위반 목록)
     *
     * 동작 방식:
     * 1. 등록된 모든 제약조건을 순회
     * 2. 각 제약조건에 대해 isSatisfied() 호출
     * 3. 위반 시 해당 제약의 위반 메시지 수집
     * 4. 모든 위반 사항을 담은 결과 반환
     *
     * 특징:
     * - Fail-fast 아님: 첫 위반에서 멈추지 않고 모든 위반 수집
     * - 이유: 사용자에게 한 번에 모든 문제점 알려줌
     *
     * 사용 시점:
     * - 트랜잭션 커밋 전 변경된 계좌 검증
     * - 데이터 마이그레이션 후 검증
     * - 주기적 무결성 체크
     */
    public ValidationResult validate(Account account) {
        List<String> violations = new ArrayList<>();

        // 모든 제약조건 순회하며 검증
        for (BalanceConstraint constraint : constraints) {
            if (!constraint.isSatisfied(account)) {
                // 위반 시 메시지 수집 (멈추지 않고 계속)
                violations.add(constraint.getViolationMessage(account));
            }
        }

        // 위반 없으면 성공, 있으면 실패
        return violations.isEmpty()
                ? ValidationResult.success()
                : ValidationResult.failure(violations);
    }

    /**
     * 전체 시스템 일관성 검증
     *
     * @param expectedTotalBalance 기대하는 전체 잔액 합계
     * @return 검증 결과
     *
     * 검증 항목:
     * 1. 전체 잔액 합계가 기대값과 일치하는지 (보존 법칙)
     * 2. 모든 개별 계좌가 제약조건을 만족하는지
     *
     * 보존 법칙 (Conservation Law):
     * - 이체는 돈을 이동시킬 뿐, 총합은 변하지 않음
     * - 입금/출금 없이 이체만 했다면 총합 동일해야 함
     * - 불일치 시 버그 또는 데이터 손상 의심
     *
     * 사용 시점:
     * - 시스템 시작 시 무결성 체크
     * - 배치 작업 후 검증
     * - 정기 감사
     * - 장애 복구 후 검증
     *
     * 사용 예시:
     * long initialTotal = 1_000_000L;  // 시스템 초기 총액
     * ValidationResult result = validator.validateSystemConsistency(initialTotal);
     * if (!result.isValid()) {
     *     alertAdmin("데이터 무결성 위반!", result.getViolations());
     * }
     */
    public ValidationResult validateSystemConsistency(long expectedTotalBalance) {
        // 1. 전체 잔액 합계 검증
        long actualTotalBalance = accountRepository.getTotalBalance();

        if (actualTotalBalance != expectedTotalBalance) {
            String message = String.format(
                    "Total balance mismatch! Expected: %d, Actual: %d",
                    expectedTotalBalance, actualTotalBalance
            );
            return ValidationResult.failure(List.of(message));
        }

        // 2. 모든 개별 계좌 검증
        List<String> allViolations = new ArrayList<>();
        for (Account account : accountRepository.findAll()) {
            ValidationResult result = validate(account);
            if (!result.isValid()) {
                // 각 계좌의 위반 사항 누적
                allViolations.addAll(result.getViolations());
            }
        }

        return allViolations.isEmpty()
                ? ValidationResult.success()
                : ValidationResult.failure(allViolations);
    }

    /**
     * 이체 전 사전 검증
     *
     * @param fromId 출금 계좌 ID
     * @param toId 입금 계좌 ID
     * @param amount 이체 금액
     * @return 검증 결과
     *
     * 검증 항목:
     * 1. 출금 계좌 존재 여부
     * 2. 입금 계좌 존재 여부
     * 3. 출금 계좌 잔액 충분 여부
     * 4. 이체 금액 양수 여부
     *
     * 사전 검증의 장점:
     * - 실패할 작업을 미리 걸러냄
     * - 불필요한 락 획득 방지
     * - 빠른 실패 (Fail-Fast)
     * - 명확한 에러 메시지 제공
     *
     * 주의사항:
     * - 이 검증 후 실제 이체까지 사이에 상태가 변할 수 있음
     * - 따라서 실제 이체 시에도 재검증 필요 (낙관적 검증)
     * - 또는 락을 먼저 잡고 검증 (비관적 검증)
     *
     * 사용 예시:
     * ValidationResult result = validator.validateTransfer(from, to, 50000);
     * if (!result.isValid()) {
     *     // 이체 시도하지 않고 바로 실패 응답
     *     throw new InvalidTransferException(result.getViolations());
     * }
     * // 검증 통과 후 실제 이체 수행
     * transferService.transfer(from, to, 50000);
     */
    public ValidationResult validateTransfer(Long fromId, Long toId, long amount) {
        List<String> violations = new ArrayList<>();

        // 1. 출금 계좌 존재 확인
        Account fromAccount = accountRepository.findById(fromId)
                .orElse(null);
        if (fromAccount == null) {
            violations.add("Source account not found: " + fromId);
            // 출금 계좌가 없으면 더 이상 검증 의미 없음 → 즉시 반환
            return ValidationResult.failure(violations);
        }

        // 2. 입금 계좌 존재 확인
        if (!accountRepository.existsById(toId)) {
            violations.add("Destination account not found: " + toId);
            // 입금 계좌 없어도 다른 검증은 계속 (모든 문제 수집)
            return ValidationResult.failure(violations);
        }

        // 3. 잔액 충분 확인
        if (!fromAccount.hasEnoughBalance(amount)) {
            violations.add(String.format(
                    "Insufficient balance in account %d. Current: %d, Required: %d",
                    fromId, fromAccount.getBalance(), amount
            ));
        }

        // 4. 금액 양수 확인
        if (amount <= 0) {
            violations.add("Transfer amount must be positive: " + amount);
        }

        return violations.isEmpty()
                ? ValidationResult.success()
                : ValidationResult.failure(violations);
    }

    // ==================== 내부 클래스 ====================

    /**
     * 검증 결과 클래스
     *
     * 검증의 성공/실패 여부와 위반 내역을 담는 불변 객체입니다.
     *
     * 설계 특징:
     * - 불변 객체: 생성 후 상태 변경 불가
     * - 정적 팩토리 메서드: success(), failure()로 명확한 생성
     * - 위반 목록 포함: 실패 시 모든 문제점 확인 가능
     *
     * 왜 boolean만 반환하지 않고 별도 클래스를 만들었나?
     * - 실패 원인을 함께 전달하기 위해
     * - 여러 위반 사항을 한 번에 전달하기 위해
     * - 확장성: 나중에 위반 심각도, 코드 등 추가 가능
     */
    public static class ValidationResult {

        /**
         * 검증 성공 여부
         */
        private final boolean valid;

        /**
         * 위반 사항 목록
         * 성공 시 빈 리스트, 실패 시 위반 메시지들
         */
        private final List<String> violations;

        /**
         * private 생성자
         * 정적 팩토리 메서드를 통해서만 생성하도록 제한합니다.
         */
        private ValidationResult(boolean valid, List<String> violations) {
            this.valid = valid;
            this.violations = violations;
        }

        /**
         * 검증 성공 결과 생성
         *
         * @return 성공 상태의 ValidationResult
         *
         * 사용:
         * return ValidationResult.success();
         */
        public static ValidationResult success() {
            return new ValidationResult(true, List.of());
        }

        /**
         * 검증 실패 결과 생성
         *
         * @param violations 위반 사항 목록
         * @return 실패 상태의 ValidationResult
         *
         * 사용:
         * return ValidationResult.failure(List.of("잔액 부족", "계좌 없음"));
         */
        public static ValidationResult failure(List<String> violations) {
            return new ValidationResult(false, violations);
        }

        /**
         * 검증 성공 여부 확인
         * @return 성공이면 true
         */
        public boolean isValid() {
            return valid;
        }

        /**
         * 위반 사항 목록 반환
         * @return 위반 메시지 리스트 (성공 시 빈 리스트)
         */
        public List<String> getViolations() {
            return violations;
        }

        /**
         * 문자열 표현
         *
         * 성공: "ValidationResult{VALID}"
         * 실패: "ValidationResult{INVALID, violations=[잔액 부족, ...]}"
         */
        @Override
        public String toString() {
            if (valid) {
                return "ValidationResult{VALID}";
            }
            return "ValidationResult{INVALID, violations=" + violations + "}";
        }
    }

    // ==================== 기본 제약조건 구현 ====================

    /**
     * 음수 잔액 불허 제약조건
     *
     * 가장 기본적인 비즈니스 규칙입니다.
     * 계좌 잔액은 0 이상이어야 합니다.
     *
     * 이 규칙이 중요한 이유:
     * - 마이너스 통장이 아닌 이상 잔액은 음수가 될 수 없음
     * - 음수 잔액은 데이터 오류 또는 버그를 의미
     * - 이체 시 출금 계좌의 잔액 부족 체크에 핵심
     *
     * private 클래스인 이유:
     * - 외부에서 직접 사용할 필요 없음
     * - AccountValidator 내부에서만 사용
     * - 기본 제약조건으로 자동 등록됨
     */
    private static class NonNegativeBalanceConstraint implements BalanceConstraint {

        /**
         * 잔액이 0 이상인지 확인
         *
         * @param account 검증할 계좌
         * @return 잔액 >= 0 이면 true
         */
        @Override
        public boolean isSatisfied(Account account) {
            return account.getBalance() >= 0;
        }

        /**
         * 음수 잔액 위반 메시지 반환
         *
         * @param account 위반한 계좌
         * @return "Account {id} has negative balance: {balance}" 형식의 메시지
         */
        @Override
        public String getViolationMessage(Account account) {
            return String.format("Account %d has negative balance: %d",
                    account.getId(), account.getBalance());
        }
    }
}
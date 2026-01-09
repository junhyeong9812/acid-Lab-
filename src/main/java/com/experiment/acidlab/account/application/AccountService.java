package com.experiment.acidlab.account.application;

import com.experiment.acidlab.account.domain.Account;
import com.experiment.acidlab.account.repository.AccountRepository;
import com.experiment.acidlab.account.validation.AccountValidator;
import com.experiment.acidlab.global.logger.ConsoleLogger;

import java.util.List;
import java.util.Optional;

/**
 * 계좌 서비스 (Application Layer)
 *
 * 계좌 도메인의 유스케이스(Use Case)를 처리하는 애플리케이션 서비스입니다.
 *
 * 레이어드 아키텍처에서의 위치:
 * ┌─────────────────────────────────────┐
 * │         Presentation Layer          │  ← Controller, API
 * ├─────────────────────────────────────┤
 * │    ★ Application Layer (여기) ★     │  ← AccountService
 * ├─────────────────────────────────────┤
 * │           Domain Layer              │  ← Account, BalanceConstraint
 * ├─────────────────────────────────────┤
 * │        Infrastructure Layer         │  ← AccountRepository, CsvStorage
 * └─────────────────────────────────────┘
 *
 * Application Layer의 책임:
 * 1. 유스케이스 조율 (Orchestration)
 * 2. 트랜잭션 경계 관리
 * 3. 도메인 서비스 호출 조정
 * 4. 입력 검증 및 결과 반환
 *
 * 이 클래스의 역할:
 * - 계좌 생성, 조회, 입금, 출금 유스케이스 구현
 * - 각 작업 전후로 Consistency 검증 수행
 * - Repository를 통한 영속성 처리
 * - 스냅샷 생성/복원 (롤백 지원)
 *
 * 사용 예시:
 * AccountService service = new AccountService(repository);
 * Account account = service.createAccount(1L, "홍길동", 100000);
 * service.deposit(1L, 50000);
 * service.withdraw(1L, 30000);
 */
public class AccountService {

    // ==================== 필드 ====================

    /**
     * 계좌 저장소
     *
     * 계좌의 영속성(저장/조회)을 담당합니다.
     * 실제 데이터 저장은 Repository가 처리하고,
     * Service는 비즈니스 로직에만 집중합니다.
     */
    private final AccountRepository accountRepository;

    /**
     * 계좌 유효성 검증기
     *
     * ACID의 Consistency를 보장하기 위해 사용합니다.
     * 모든 상태 변경 후 제약조건 만족 여부를 검증합니다.
     */
    private final AccountValidator accountValidator;

    /**
     * 로거
     *
     * 주요 작업(생성, 입금, 출금 등)을 기록합니다.
     * 디버깅과 감사(Audit)에 활용됩니다.
     */
    private final ConsoleLogger logger;

    // ==================== 생성자 ====================

    /**
     * AccountService 생성자
     *
     * @param accountRepository 계좌 저장소
     *
     * 의존성 주입(DI) 패턴 사용:
     * - Repository는 외부에서 주입받음
     * - Validator는 내부에서 생성 (Repository 필요)
     * - 테스트 시 Mock Repository 주입 가능
     */
    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
        this.accountValidator = new AccountValidator(accountRepository);
        this.logger = ConsoleLogger.of(AccountService.class);
    }

    // ==================== 계좌 생성 ====================

    /**
     * 계좌 생성
     *
     * @param id 계좌 ID (Primary Key)
     * @param name 계좌 소유자 이름
     * @param initialBalance 초기 잔액
     * @return 생성된 계좌
     * @throws IllegalArgumentException 이미 존재하는 ID인 경우
     * @throws IllegalStateException 유효성 검증 실패 시
     *
     * 처리 흐름:
     * 1. 중복 ID 체크 (이미 존재하면 예외)
     * 2. Account 도메인 객체 생성
     * 3. Consistency 검증 (음수 잔액 등 체크)
     * 4. Repository에 저장
     * 5. 로그 기록 후 반환
     *
     * Consistency 검증 시점:
     * - 저장 "전에" 검증하여 잘못된 데이터가 저장되는 것 방지
     * - 검증 실패 시 예외 발생으로 저장 중단
     */
    public Account createAccount(Long id, String name, long initialBalance) {
        // 1. 중복 체크
        if (accountRepository.existsById(id)) {
            throw new IllegalArgumentException("Account already exists: " + id);
        }

        // 2. 도메인 객체 생성
        Account account = Account.create(id, name, initialBalance);

        // 3. Consistency 검증 (잔액 >= 0 등)
        AccountValidator.ValidationResult result = accountValidator.validate(account);
        if (!result.isValid()) {
            throw new IllegalStateException("Validation failed: " + result.getViolations());
        }

        // 4. 저장
        Account saved = accountRepository.save(account);

        // 5. 로그 기록
        logger.info("Account created: " + saved);
        return saved;
    }

    // ==================== 계좌 조회 ====================

    /**
     * 계좌 조회 (Optional 반환)
     *
     * @param id 조회할 계좌 ID
     * @return Optional<Account> - 존재하면 계좌, 없으면 빈 Optional
     *
     * Optional 반환 이유:
     * - null 반환보다 명시적
     * - 호출자가 존재 여부를 명확하게 처리하도록 강제
     * - NPE(NullPointerException) 방지
     *
     * 사용 예시:
     * service.findById(1L)
     *     .ifPresent(account -> System.out.println(account.getBalance()));
     */
    public Optional<Account> findById(Long id) {
        return accountRepository.findById(id);
    }

    /**
     * 계좌 조회 (필수 - 없으면 예외)
     *
     * @param id 조회할 계좌 ID
     * @return 조회된 계좌
     * @throws IllegalArgumentException 계좌가 존재하지 않으면
     *
     * findById vs getById 차이:
     * - findById: 없을 수도 있는 경우 → Optional 반환
     * - getById: 반드시 있어야 하는 경우 → 직접 반환 or 예외
     *
     * 사용 시점:
     * - 이미 존재가 확실한 경우
     * - 없으면 로직 진행이 불가능한 경우
     * - 입금/출금 등 후속 작업이 필요한 경우
     */
    public Account getById(Long id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + id));
    }

    /**
     * 모든 계좌 조회
     *
     * @return 전체 계좌 목록
     *
     * 용도:
     * - 관리자 화면에서 전체 계좌 표시
     * - 시스템 일관성 검증 시 전체 순회
     * - 배치 작업
     *
     * 주의:
     * - 계좌가 많으면 메모리 부담
     * - 실제 운영에서는 페이징 처리 고려
     */
    public List<Account> findAll() {
        return accountRepository.findAll();
    }

    /**
     * 계좌 존재 여부 확인
     *
     * @param id 확인할 계좌 ID
     * @return 존재하면 true
     *
     * 용도:
     * - 생성 전 중복 체크
     * - 이체 전 대상 계좌 존재 확인
     * - 가벼운 존재 여부 확인 (전체 로드 불필요)
     */
    public boolean exists(Long id) {
        return accountRepository.existsById(id);
    }

    // ==================== 입금/출금 ====================

    /**
     * 입금
     *
     * @param accountId 입금할 계좌 ID
     * @param amount 입금 금액 (양수)
     * @return 입금 후 계좌 상태
     * @throws IllegalArgumentException 계좌가 없거나 금액이 유효하지 않으면
     * @throws IllegalStateException 유효성 검증 실패 시
     *
     * 처리 흐름:
     * 1. 계좌 조회 (없으면 예외)
     * 2. 도메인 객체에 입금 요청 (불변 객체이므로 새 객체 반환)
     * 3. Consistency 검증
     * 4. 저장
     * 5. 로그 기록 후 반환
     *
     * 불변 객체 패턴:
     * Account는 불변이므로 deposit()은 새 Account를 반환합니다.
     * 원본 account는 변경되지 않습니다.
     */
    public Account deposit(Long accountId, long amount) {
        // 1. 계좌 조회
        Account account = getById(accountId);

        // 2. 입금 (새 객체 반환)
        Account updated = account.deposit(amount);

        // 3. Consistency 검증
        AccountValidator.ValidationResult result = accountValidator.validate(updated);
        if (!result.isValid()) {
            throw new IllegalStateException("Validation failed: " + result.getViolations());
        }

        // 4. 저장
        Account saved = accountRepository.save(updated);

        // 5. 로그 기록
        logger.info(String.format("Deposit: Account %d, Amount %d, New Balance %d",
                accountId, amount, saved.getBalance()));
        return saved;
    }

    /**
     * 출금
     *
     * @param accountId 출금할 계좌 ID
     * @param amount 출금 금액 (양수)
     * @return 출금 후 계좌 상태
     * @throws IllegalArgumentException 계좌가 없거나 잔액 부족 시
     * @throws IllegalStateException 유효성 검증 실패 시
     *
     * 처리 흐름:
     * 1. 계좌 조회 (없으면 예외)
     * 2. 도메인 객체에 출금 요청
     *    - Account.withdraw()에서 잔액 부족 시 예외 발생
     * 3. Consistency 검증 (이중 안전장치)
     * 4. 저장
     * 5. 로그 기록 후 반환
     *
     * 이중 검증의 이유:
     * - Account.withdraw(): 도메인 규칙 (잔액 >= 출금액)
     * - accountValidator.validate(): 전역 제약조건 (잔액 >= 0)
     * - 두 단계로 나눠 책임 분리
     */
    public Account withdraw(Long accountId, long amount) {
        // 1. 계좌 조회
        Account account = getById(accountId);

        // 2. 출금 (잔액 부족 시 여기서 예외)
        Account updated = account.withdraw(amount);

        // 3. Consistency 검증 (안전장치)
        AccountValidator.ValidationResult result = accountValidator.validate(updated);
        if (!result.isValid()) {
            throw new IllegalStateException("Validation failed: " + result.getViolations());
        }

        // 4. 저장
        Account saved = accountRepository.save(updated);

        // 5. 로그 기록
        logger.info(String.format("Withdraw: Account %d, Amount %d, New Balance %d",
                accountId, amount, saved.getBalance()));
        return saved;
    }

    // ==================== 집계/유틸리티 ====================

    /**
     * 전체 잔액 합계 조회
     *
     * @return 모든 계좌 잔액의 합
     *
     * 용도:
     * - 시스템 일관성 검증: 이체 전후 총합이 같은지 확인
     * - 대시보드: 전체 예수금 현황 표시
     * - 감사: 자금 보존 법칙 확인
     *
     * 보존 법칙 (Conservation Law):
     * - 입출금 없이 이체만 했다면 총합 불변
     * - 총합 변화 = 입금 합계 - 출금 합계
     */
    public long getTotalBalance() {
        return accountRepository.getTotalBalance();
    }

    // ==================== 스냅샷 관리 ====================

    /**
     * 스냅샷 생성
     *
     * @return 현재 상태의 스냅샷
     *
     * 용도:
     * - 트랜잭션 시작 전 상태 저장
     * - 롤백 시 복원 지점
     * - 테스트에서 상태 저장/복원
     *
     * 스냅샷 패턴:
     * 메멘토(Memento) 패턴의 일종으로,
     * 객체의 상태를 저장하고 나중에 복원할 수 있게 합니다.
     *
     * 사용 예시:
     * AccountSnapshot snapshot = service.createSnapshot();
     * try {
     *     service.withdraw(1L, 50000);
     *     service.deposit(2L, 50000);
     * } catch (Exception e) {
     *     service.restoreSnapshot(snapshot);  // 롤백
     * }
     */
    public AccountRepository.AccountSnapshot createSnapshot() {
        return accountRepository.createSnapshot();
    }

    /**
     * 스냅샷 복원
     *
     * @param snapshot 복원할 스냅샷
     *
     * 동작:
     * - 현재 상태를 버리고 스냅샷 상태로 되돌림
     * - 롤백(Rollback) 구현의 핵심
     *
     * 사용 시점:
     * - 트랜잭션 실패 시 롤백
     * - 테스트 후 원래 상태 복원
     * - 오류 복구
     *
     * 주의:
     * - 스냅샷 생성 이후의 모든 변경이 사라짐
     * - 복원 후 스냅샷은 재사용 가능
     */
    public void restoreSnapshot(AccountRepository.AccountSnapshot snapshot) {
        accountRepository.restoreSnapshot(snapshot);
        logger.info("Snapshot restored");
    }

    // ==================== 내부 컴포넌트 접근 ====================

    /**
     * 검증기 반환
     *
     * @return AccountValidator 인스턴스
     *
     * 외부에서 추가 검증이 필요한 경우 사용합니다.
     *
     * 사용 예시:
     * // TransferService에서 이체 전 검증
     * AccountValidator validator = accountService.getValidator();
     * ValidationResult result = validator.validateTransfer(from, to, amount);
     *
     * // 커스텀 제약조건 추가
     * accountService.getValidator().addConstraint(new MaxBalanceConstraint(10_000_000));
     */
    public AccountValidator getValidator() {
        return accountValidator;
    }
}

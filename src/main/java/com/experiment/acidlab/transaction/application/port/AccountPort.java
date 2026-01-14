package com.experiment.acidlab.transaction.application.port;

import com.experiment.acidlab.account.domain.Account;
import com.experiment.acidlab.account.repository.AccountRepository;

/**
 * Account 포트 인터페이스
 * - Transaction 도메인이 Account 도메인에 직접 의존하지 않도록 추상화
 * - 의존성 역전 원칙 (DIP) 적용
 */
public interface AccountPort {

    /**
     * 출금
     */
    void withdraw(Long accountId, long amount);

    /**
     * 입금
     */
    void deposit(Long accountId, long amount);

    /**
     * 계좌 조회
     */
    Account findById(Long accountId);

    /**
     * 계좌 존재 여부
     */
    boolean exists(Long accountId);

    /**
     * 잔액 조회
     */
    long getBalance(Long accountId);

    /**
     * 전체 잔액 합계
     */
    long getTotalBalance();

    /**
     * 스냅샷 생성 (Atomicity용)
     */
    AccountRepository.AccountSnapshot createSnapshot();

    /**
     * 스냅샷 복원 (Atomicity용)
     */
    void restoreSnapshot(AccountRepository.AccountSnapshot snapshot);

    /**
     * 이체 검증
     */
    boolean validateTransfer(Long fromId, Long toId, long amount);
}
package com.experiment.acidlab.account.repository;

import com.experiment.acidlab.account.domain.Account;

import java.util.List;
import java.util.Optional;

/**
 * 계좌 저장소 인터페이스
 */
public interface AccountRepository {

    /**
     * 계좌 저장 (신규 또는 업데이트)
     */
    Account save(Account account);

    /**
     * ID로 계좌 조회
     */
    Optional<Account> findById(Long id);

    /**
     * 모든 계좌 조회
     */
    List<Account> findAll();

    /**
     * 계좌 존재 여부 확인
     */
    boolean existsById(Long id);

    /**
     * 계좌 삭제
     */
    void deleteById(Long id);

    /**
     * 모든 계좌 삭제
     */
    void deleteAll();

    /**
     * 전체 계좌 수
     */
    long count();

    /**
     * 전체 잔액 합계
     */
    long getTotalBalance();

    /**
     * 현재 상태의 스냅샷 생성 (Atomicity용)
     */
    AccountSnapshot createSnapshot();

    /**
     * 스냅샷으로 복원 (Atomicity용)
     */
    void restoreSnapshot(AccountSnapshot snapshot);

    /**
     * 스냅샷 클래스
     */
    interface AccountSnapshot {
        List<Account> getAccounts();
    }
}
# 🧪 ACID Lab - 트랜잭션 속성 실험실

인메모리 DB + CSV 영속화로 ACID 속성을 직접 구현하며 배우는 프로젝트

## 📖 프로젝트 소개

이 프로젝트는 데이터베이스의 핵심 개념인 **ACID**를 단순 암기가 아닌 **직접 구현**을 통해 체화하기 위한 실험 프로젝트입니다.

### 학습 방식
- 인메모리 저장소 직접 구현
- CSV 파일 기반 영속성 구현 (`data/` 폴더)
- Write-Ahead Log (WAL) 직접 구현
- 각 ACID 속성별 시나리오 테스트
- **도메인 기반 + 헥사고날 아키텍처** 적용

### 주요 특징
- ✅ ACID 4가지 속성 각각을 시나리오로 검증
- ✅ CSV 파일 기반 테이블 구현
- ✅ Write-Ahead Log (WAL) 직접 구현
- ✅ 행 단위 락 (Row-Level Lock) 구현
- ✅ 트랜잭션 롤백/커밋 메커니즘
- ✅ 포트/어댑터 패턴으로 도메인 간 의존성 분리
- ✅ 실시간 메트릭 수집 및 리포트 생성

---

## 🎯 ACID란?

| 속성 | 의미 | 핵심 질문 |
|------|------|-----------|
| **A**tomicity | 원자성 | 트랜잭션은 전부 성공하거나 전부 실패해야 한다 |
| **C**onsistency | 일관성 | 트랜잭션 전후로 데이터 무결성이 유지되어야 한다 |
| **I**solation | 격리성 | 동시 실행 트랜잭션이 서로 영향을 주지 않아야 한다 |
| **D**urability | 영속성 | 커밋된 데이터는 영구적으로 보존되어야 한다 |

### 이 프로젝트에서 배우는 것

```
❌ 단순 암기: "ACID는 Atomicity, Consistency, Isolation, Durability의 약자입니다"

✅ 체화 학습: 
   - Atomicity: 이체 중 예외 발생 시 롤백이 안 되면 돈이 사라진다
   - Consistency: 잔액이 음수가 되면 안 되는 제약을 어떻게 검증하나?
   - Isolation: 동시에 출금하면 잔액이 이상해지는 Lost Update 문제
   - Durability: 커밋 후 서버가 죽어도 데이터가 살아있어야 한다
```

---

## 🏛️ 아키텍처

### 전체 구조

```
┌─────────────────────────────────────────────────────────────────┐
│                         ACID Lab                                │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                    Scenario Layer                        │   │
│  │  AtomicityScenario │ ConsistencyScenario                │   │
│  │  IsolationScenario │ DurabilityScenario                 │   │
│  └──────────────────────────┬──────────────────────────────┘   │
│                             │                                   │
│                             ▼                                   │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │              Transaction Domain (Application)            │   │
│  │                   TransactionService                     │   │
│  │                         │                                │   │
│  │                         ▼                                │   │
│  │                   AccountPort ◄─────────────────────┐   │   │
│  │                    (interface)                       │   │   │
│  └──────────────────────────┬──────────────────────────│───┘   │
│                             │                          │        │
│              ┌──────────────┼──────────────┐          │        │
│              ▼              ▼              ▼          │        │
│  ┌───────────────┐ ┌───────────────┐ ┌────────────┐  │        │
│  │  LockManager  │ │  Validator    │ │   WAL      │  │        │
│  │  (Isolation)  │ │ (Consistency) │ │(Durability)│  │        │
│  └───────────────┘ └───────────────┘ └────────────┘  │        │
│                                                       │        │
│  ┌────────────────────────────────────────────────────┼────┐   │
│  │           Transaction Infrastructure               │    │   │
│  │                  AccountAdapter ───────────────────┘    │   │
│  │                   (implements AccountPort)              │   │
│  └──────────────────────────┬──────────────────────────────┘   │
│                             │                                   │
│                             ▼                                   │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                 Account Domain                           │   │
│  │     AccountService → AccountRepository → CSV Storage    │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 도메인 간 의존성 (포트/어댑터 패턴)

```
┌─────────────────────┐         ┌─────────────────────┐
│  transaction        │         │  account            │
│  ┌───────────────┐  │         │  ┌───────────────┐  │
│  │ application/  │  │         │  │ application/  │  │
│  │ Transaction   │  │         │  │ AccountService│  │
│  │ Service       │  │         │  └───────┬───────┘  │
│  └───────┬───────┘  │         │          │          │
│          │          │         │          ▼          │
│          ▼          │         │  ┌───────────────┐  │
│  ┌───────────────┐  │         │  │ repository/   │  │
│  │ port/         │  │         │  │ AccountRepo   │  │
│  │ AccountPort   │◄─┼─────────┼──│               │  │
│  │ (interface)   │  │         │  └───────────────┘  │
│  └───────────────┘  │         │                     │
│          ▲          │         └─────────────────────┘
│          │          │
│  ┌───────┴───────┐  │
│  │infrastructure/│  │
│  │ adapter/      │  │
│  │ AccountAdapter│  │
│  └───────────────┘  │
└─────────────────────┘

의존성 방향:
- TransactionService → AccountPort (추상화에 의존)
- AccountAdapter → AccountService (구현체가 연결)
- Transaction 도메인은 Account 도메인을 직접 모름!
```

---

## 📁 프로젝트 구조

```
acid-lab/
├── src/main/java/com/experiment/acidlab/
│   ├── AcidLabApplication.java
│   │
│   ├── account/                                    # 계좌 도메인
│   │   ├── domain/
│   │   │   └── Account.java
│   │   ├── application/
│   │   │   └── AccountService.java
│   │   ├── repository/
│   │   │   ├── AccountRepository.java              # 인터페이스
│   │   │   ├── InMemoryAccountRepository.java
│   │   │   └── CsvAccountRepository.java
│   │   └── validation/
│   │       ├── AccountValidator.java
│   │       └── BalanceConstraint.java
│   │
│   ├── transaction/                                # 트랜잭션 도메인
│   │   ├── domain/
│   │   │   ├── Transaction.java
│   │   │   └── TransactionStatus.java
│   │   ├── application/
│   │   │   ├── TransactionService.java
│   │   │   └── port/
│   │   │       └── AccountPort.java                # 포트 (인터페이스)
│   │   ├── manager/
│   │   │   ├── TransactionManager.java
│   │   │   └── TransactionContext.java
│   │   ├── infrastructure/
│   │   │   └── adapter/
│   │   │       └── AccountAdapter.java             # 어댑터 (구현체)
│   │   └── repository/
│   │       ├── TransactionRepository.java
│   │       └── CsvTransactionRepository.java
│   │
│   ├── storage/                                    # 저장소 도메인
│   │   ├── csv/
│   │   │   └── CsvStorage.java
│   │   └── wal/
│   │       ├── WalEntry.java
│   │       └── WriteAheadLog.java
│   │
│   ├── lock/                                       # 락 도메인
│   │   ├── domain/
│   │   │   └── RowLock.java
│   │   └── manager/
│   │       └── LockManager.java
│   │
│   ├── scenario/                                   # 실험 시나리오
│   │   ├── AtomicityScenario.java
│   │   ├── ConsistencyScenario.java
│   │   ├── IsolationScenario.java
│   │   └── DurabilityScenario.java
│   │
│   ├── benchmark/                                  # 벤치마크
│   │   └── AcidBenchmark.java
│   │
│   └── global/                                     # 공통
│       ├── config/
│       │   └── BeanConfig.java
│       ├── logger/
│       │   └── ConsoleLogger.java
│       └── metrics/
│           └── MetricsCollector.java
│
├── src/main/resources/
│   └── application.yml
│
├── src/test/java/com/experiment/acidlab/
│   ├── account/
│   ├── transaction/
│   ├── storage/
│   └── lock/
│
├── data/                                           # CSV 데이터 저장소
│   ├── accounts.csv
│   ├── transactions.csv
│   └── wal.csv
│
├── results/                                        # 실험 결과
│   ├── reports/
│   └── logs/
│
├── build.gradle
├── settings.gradle
└── README.md
```

---

## 🔗 포트/어댑터 패턴 코드 예시

### AccountPort (포트 - 인터페이스)

```java
// transaction/application/port/AccountPort.java
public interface AccountPort {
    void withdraw(Long accountId, long amount);
    void deposit(Long accountId, long amount);
    Account findById(Long accountId);
    boolean exists(Long accountId);
}
```

### AccountAdapter (어댑터 - 구현체)

```java
// transaction/infrastructure/adapter/AccountAdapter.java
@Component
@RequiredArgsConstructor
public class AccountAdapter implements AccountPort {
    
    private final AccountService accountService;
    
    @Override
    public void withdraw(Long accountId, long amount) {
        accountService.withdraw(accountId, amount);
    }
    
    @Override
    public void deposit(Long accountId, long amount) {
        accountService.deposit(accountId, amount);
    }
    
    @Override
    public Account findById(Long accountId) {
        return accountService.findById(accountId);
    }
    
    @Override
    public boolean exists(Long accountId) {
        return accountService.exists(accountId);
    }
}
```

### TransactionService (포트 사용)

```java
// transaction/application/TransactionService.java
@Service
@RequiredArgsConstructor
public class TransactionService {
    
    private final AccountPort accountPort;          // 인터페이스에만 의존!
    private final TransactionManager transactionManager;
    private final TransactionRepository transactionRepository;
    
    public void transfer(Long fromId, Long toId, long amount) {
        Transaction tx = Transaction.create(fromId, toId, amount);
        
        transactionManager.begin();
        try {
            accountPort.withdraw(fromId, amount);
            accountPort.deposit(toId, amount);
            
            tx.commit();
            transactionRepository.save(tx);
            transactionManager.commit();
            
        } catch (Exception e) {
            tx.rollback();
            transactionRepository.save(tx);
            transactionManager.rollback();
            throw e;
        }
    }
}
```

---

## 🧪 실험 시나리오

### 1. Atomicity (원자성) 실험

**시나리오**: A 계좌에서 B 계좌로 10,000원 이체 중 예외 발생

```java
// 실험 흐름
1. A 잔액: 50,000원, B 잔액: 30,000원
2. A에서 10,000원 출금 (A: 40,000원)
3. ⚡ 예외 발생! (네트워크 오류 시뮬레이션)
4. B에 입금 실패

// 검증
❌ Atomicity 미적용: A=40,000원, B=30,000원 (10,000원 증발!)
✅ Atomicity 적용: A=50,000원, B=30,000원 (롤백 성공)
```

**구현 포인트**:
- 트랜잭션 시작 시 스냅샷 저장
- 예외 발생 시 스냅샷으로 롤백
- WAL에 ROLLBACK 기록

---

### 2. Consistency (일관성) 실험

**시나리오**: 잔액보다 큰 금액 출금 시도

```java
// 실험 흐름
1. A 잔액: 10,000원
2. 50,000원 출금 시도
3. 제약조건 검사: balance >= 0

// 검증
❌ Consistency 미적용: A=-40,000원 (음수 잔액!)
✅ Consistency 적용: 트랜잭션 거부, A=10,000원 유지
```

**구현 포인트**:
- 커밋 전 제약조건 검증 (AccountValidator)
- 전체 잔액 합계 보존 검증
- 위반 시 트랜잭션 거부

---

### 3. Isolation (격리성) 실험

**시나리오**: 두 스레드가 동시에 같은 계좌에서 출금

```java
// 실험 흐름 (Lost Update 문제)
Initial: A=100,000원

Thread 1: read(A) → 100,000
Thread 2: read(A) → 100,000
Thread 1: A = 100,000 - 30,000 = 70,000
Thread 2: A = 100,000 - 50,000 = 50,000  // Thread 1의 변경 무시!
Thread 1: write(A, 70,000)
Thread 2: write(A, 50,000)  // 덮어쓰기!

// 검증
❌ Isolation 미적용: A=50,000원 (30,000원 출금이 사라짐!)
✅ Isolation 적용: A=20,000원 (두 출금 모두 반영)
```

**구현 포인트**:
- 행 단위 락 (LockManager, RowLock)
- 락 획득 순서 관리
- 타임아웃 설정

---

### 4. Durability (영속성) 실험

**시나리오**: 커밋 직후 프로세스 강제 종료

```java
// 실험 흐름
1. A에 50,000원 입금
2. 커밋 완료 (CSV 저장)
3. ⚡ 프로세스 강제 종료 (System.exit)
4. 프로세스 재시작
5. 데이터 복구 시도

// 검증
❌ Durability 미적용: 데이터 손실
✅ Durability 적용: WAL에서 복구, A=50,000원 유지
```

**구현 포인트**:
- Write-Ahead Log (WAL) 먼저 기록
- fsync()로 디스크 동기화
- 시작 시 WAL 기반 복구

---

## 📊 CSV 데이터 구조

### accounts.csv
```csv
id,name,balance,created_at,updated_at
1001,Alice,50000,2025-01-02T10:00:00,2025-01-02T10:00:00
1002,Bob,30000,2025-01-02T10:00:00,2025-01-02T10:00:00
1003,Charlie,100000,2025-01-02T10:00:00,2025-01-02T10:00:00
```

### transactions.csv
```csv
id,from_account,to_account,amount,status,created_at
TXN001,1001,1002,10000,COMMITTED,2025-01-02T10:05:00
TXN002,1002,1003,5000,COMMITTED,2025-01-02T10:10:00
TXN003,1001,1003,20000,ROLLBACK,2025-01-02T10:15:00
```

### wal.csv (Write-Ahead Log)
```csv
lsn,transaction_id,operation,table_name,row_id,before_value,after_value,timestamp
1,TXN001,UPDATE,accounts,1001,50000,40000,2025-01-02T10:05:00
2,TXN001,UPDATE,accounts,1002,30000,40000,2025-01-02T10:05:00
3,TXN001,COMMIT,,,,,2025-01-02T10:05:00
4,TXN003,UPDATE,accounts,1001,40000,20000,2025-01-02T10:15:00
5,TXN003,ROLLBACK,,,,,2025-01-02T10:15:00
```

---

## 📈 예상 실험 결과

| 실험 | ACID 미적용 | ACID 적용 | 검증 |
|------|-------------|-----------|------|
| Atomicity | 10,000원 증발 | 정상 롤백 | ✅ |
| Consistency | 음수 잔액 발생 | 트랜잭션 거부 | ✅ |
| Isolation | Lost Update 발생 | 정확한 잔액 | ✅ |
| Durability | 데이터 손실 | WAL 복구 성공 | ✅ |

---

## 🛠️ 기술 스택

| 구분 | 기술 |
|------|------|
| Language | Java 25 |
| Framework | Spring Boot 4.0.1 |
| Build | Gradle (Groovy) |
| Architecture | 도메인 기반 + 헥사고날 (포트/어댑터) |
| Storage | CSV 파일 기반 |
| Concurrency | ReentrantLock, synchronized |

---

## 📦 Gradle 설정

### build.gradle
```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '4.0.1'
    id 'io.spring.dependency-management' version '1.1.7'
}

group = 'com.experiment'
version = '0.0.1-SNAPSHOT'
description = 'ACID properties implementation lab with custom in-memory DB and CSV persistence'

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter'
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

tasks.named('test') {
    useJUnitPlatform()
}
```

### settings.gradle
```groovy
rootProject.name = 'acid-lab'
```

---

## 🚀 Quick Start

### 요구사항
- Java 25+
- Gradle 8.0+

### 실행 방법

```bash
# 1. 클론
git clone https://github.com/junhyeong9812/acid-lab.git
cd acid-lab

# 2. 빌드
./gradlew clean build

# 3. 전체 시나리오 실행
./gradlew bootRun

# 4. 개별 시나리오 실행
java -cp build/classes/java/main \
  com.experiment.acidlab.scenario.AtomicityScenario

java -cp build/classes/java/main \
  com.experiment.acidlab.scenario.IsolationScenario
```

---

## 🏗️ 아키텍처 선택 이유

### 왜 도메인 최상위 구조인가?

```
❌ 레이어 최상위 (MVC 스타일)
src/
├── controller/
├── service/
├── repository/
└── domain/

→ account 관련 코드가 4개 폴더에 흩어짐
→ 도메인 파악이 어려움

✅ 도메인 최상위
src/
├── account/
│   ├── domain/
│   ├── application/
│   └── repository/
├── transaction/
└── ...

→ account 폴더 하나만 보면 됨
→ MSA 전환 시 폴더 단위 분리 가능
```

### 왜 포트/어댑터 패턴인가?

```
❌ 직접 의존
TransactionService → AccountService (강결합)

✅ 포트/어댑터
TransactionService → AccountPort (인터페이스)
AccountAdapter → AccountService (구현체 연결)

→ Transaction 도메인이 Account 도메인을 직접 모름
→ 테스트 시 Mock 주입 용이
→ 의존성 역전 원칙 (DIP) 준수
```

---

## 💡 핵심 교훈

### ACID
```
1. Atomicity: "All or Nothing" - 중간 상태는 존재하지 않는다
2. Consistency: 규칙 위반은 절대 허용하지 않는다
3. Isolation: 동시 실행 = 순차 실행과 같은 결과
4. Durability: 커밋 = 영원한 약속
```

### 아키텍처
```
1. 도메인 최상위 구조 → 응집도 높은 코드
2. 포트/어댑터 패턴 → 도메인 간 느슨한 결합
3. 학습 프로젝트에서 다양한 패턴 경험 → 실무 선택지 확보
```

---

## 🔗 관련 프로젝트

> 💡 동시성과 Deadlock에 대해 더 깊이 알고 싶다면?
> → [Deadlock Lab](https://github.com/junhyeong9812/deadlock-lab) 참고

---

## 📚 참고 자료

### ACID & Database
- [Database Internals](https://www.databass.dev/) - Alex Petrov
- [Designing Data-Intensive Applications](https://dataintensive.net/) - Martin Kleppmann
- [Write-Ahead Logging](https://en.wikipedia.org/wiki/Write-ahead_logging)
- [Two-Phase Locking (2PL)](https://en.wikipedia.org/wiki/Two-phase_locking)

### Architecture
- [Hexagonal Architecture](https://alistair.cockburn.us/hexagonal-architecture/)
- [Clean Architecture](https://blog.cleancoder.com/uncle-bob/2012/08/13/the-clean-architecture.html)
- [Domain-Driven Design](https://martinfowler.com/bliki/DomainDrivenDesign.html)

---
package com.experiment.acidlab.global.metrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 실험 메트릭 수집기
 */
public class MetricsCollector {

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final Map<String, Long> timers = new ConcurrentHashMap<>();
    private final Map<String, Long> timerStarts = new ConcurrentHashMap<>();

    // 카운터 증가
    public void increment(String name) {
        counters.computeIfAbsent(name, k -> new AtomicLong(0)).incrementAndGet();
    }

    public void increment(String name, long delta) {
        counters.computeIfAbsent(name, k -> new AtomicLong(0)).addAndGet(delta);
    }

    // 카운터 값 조회
    public long getCount(String name) {
        AtomicLong counter = counters.get(name);
        return counter != null ? counter.get() : 0;
    }

    // 타이머 시작
    public void startTimer(String name) {
        timerStarts.put(name, System.nanoTime());
    }

    // 타이머 종료 및 기록
    public long stopTimer(String name) {
        Long startTime = timerStarts.remove(name);
        if (startTime == null) {
            return 0;
        }
        long elapsed = System.nanoTime() - startTime;
        timers.put(name, elapsed);
        return elapsed;
    }

    // 타이머 값 조회 (나노초)
    public long getTimerNanos(String name) {
        return timers.getOrDefault(name, 0L);
    }

    // 타이머 값 조회 (밀리초)
    public long getTimerMillis(String name) {
        return getTimerNanos(name) / 1_000_000;
    }

    // 모든 메트릭 초기화
    public void reset() {
        counters.clear();
        timers.clear();
        timerStarts.clear();
    }

    // 리포트 생성
    public String generateReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n═══════════════════════════════════════\n");
        sb.append("           METRICS REPORT\n");
        sb.append("═══════════════════════════════════════\n");

        if (!counters.isEmpty()) {
            sb.append("\n[Counters]\n");
            counters.forEach((name, value) ->
                    sb.append(String.format("  %-30s : %d%n", name, value.get())));
        }

        if (!timers.isEmpty()) {
            sb.append("\n[Timers]\n");
            timers.forEach((name, nanos) ->
                    sb.append(String.format("  %-30s : %d ms%n", name, nanos / 1_000_000)));
        }

        sb.append("\n═══════════════════════════════════════\n");
        return sb.toString();
    }

    // 싱글톤 인스턴스 (선택적 사용)
    private static final MetricsCollector INSTANCE = new MetricsCollector();

    public static MetricsCollector getInstance() {
        return INSTANCE;
    }
}
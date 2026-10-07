package ddos;

import org.knowm.xchart.SwingWrapper;
import org.knowm.xchart.XYChart;
import org.knowm.xchart.XYChartBuilder;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Baseline vs Autoscaling (Java 11) - DES Monte Carlo
 *
 * Validation version aligned with the Mercury transient scenario:
 * - Time unit: milliseconds (ms)
 * - Exact regime change at ATTACK_START
 * - Pre-attack arrivals stop at ATTACK_START
 * - A new attack arrival process starts exactly at ATTACK_START
 * - Finite input queue + parallel processing capacity
 * - One scale-out batch of NEW_PODS
 * - Replicas are provisioned sequentially, one at a time, with exponential
 *   provisioning time TIME_INJECTION, matching the validation behavior
 * - 200 Monte Carlo runs
 * - Mean, standard deviation and approximate 95% confidence interval
 */
public class SimuladorDDOSValidacao {

    // =========================
    // Experiment parameters (ms)
    // =========================
    static final double T_END = 1000.0;
    static final double ATTACK_START = 200.0;

    // Mean inter-arrival times (ms)
    static final double AD_FREE   = 100.0;
    static final double AD_ATTACK = 2.0;

    // Queue / processing
    static final int Q1 = 100;
    static final int C1 = 2;

    // Mean exponential times (ms)
    static final double T_ALLOC = 0.1;
    static final double T_PROC  = 50.0;

    // Autoscaling
    static final double PERCENT = 50.0;
    static final int NEW_PODS = 30;
    static final double TIME_INJECTION = 5.0;

    // Only one +30 scale-out batch in the validation experiment
    static final boolean ALLOW_MULTIPLE_SCALE_OUT = false;

    // 0..1000 ms, every 5 ms
    static final int NUM_POINTS = 201;
    static final String CSV_OUT = "utilization_series_validation_v2.csv";

    // Monte Carlo
    static final int RUNS = 200;
    static final long BASE_SEED = 12345L;
    static final double Z_95 = 1.96;

    enum EventType {
        PRE_ATTACK_ARRIVAL,
        ATTACK_START,
        ATTACK_ARRIVAL,
        ALLOC_DONE,
        PROC_DONE,
        SCALE_ONE_APPLY,
        SAMPLE
    }

    static final class Event implements Comparable<Event> {
        final double time;
        final int priority;
        final EventType type;
        final long id;
        final Request req;

        Event(double time, int priority, EventType type, long id, Request req) {
            this.time = time;
            this.priority = priority;
            this.type = type;
            this.id = id;
            this.req = req;
        }

        @Override
        public int compareTo(Event o) {
            int c = Double.compare(this.time, o.time);
            if (c != 0) return c;
            c = Integer.compare(this.priority, o.priority);
            if (c != 0) return c;
            return Long.compare(this.id, o.id);
        }
    }

    static final class Request {
        final long rid;
        final double arrivalTime;

        Request(long rid, double arrivalTime) {
            this.rid = rid;
            this.arrivalTime = arrivalTime;
        }
    }

    static final class RNG {
        private final Random r;

        RNG(long seed) {
            this.r = new Random(seed);
        }

        double expMean(double mean) {
            if (mean <= 0) throw new IllegalArgumentException("mean must be > 0");
            double u = 1.0 - r.nextDouble();
            return -Math.log(u) * mean;
        }
    }

    static final class Server {
        final int queueCapacity;
        int parallelCapacity;
        int inService;
        final Deque<Request> inputQueue = new ArrayDeque<>();

        Server(int queueCapacity, int parallelCapacity) {
            this.queueCapacity = queueCapacity;
            this.parallelCapacity = parallelCapacity;
        }

        int queueSize() {
            return inputQueue.size();
        }

        double utilizationPercent() {
            return (queueSize() * 100.0) / queueCapacity;
        }

        boolean enqueue(Request req) {
            if (inputQueue.size() >= queueCapacity) return false;
            inputQueue.addLast(req);
            return true;
        }

        Request dequeue() {
            return inputQueue.pollFirst();
        }
    }

    static final class AutoScaler {
        final double triggerPercent;
        final int newPods;
        final double meanInjection;

        boolean scalingInProgress = false;
        boolean triggeredOnce = false;
        int podsRemainingToAdd = 0;

        AutoScaler(double triggerPercent, int newPods, double meanInjection) {
            this.triggerPercent = triggerPercent;
            this.newPods = newPods;
            this.meanInjection = meanInjection;
        }

        boolean guard(int queueSize, int q1Capacity) {
            return (queueSize * 100.0) > (q1Capacity * triggerPercent);
        }
    }

    static final class SeriesStats {
        final double[] mean;
        final double[] std;
        final double[] ciLow;
        final double[] ciHigh;

        SeriesStats(double[] mean, double[] std, double[] ciLow, double[] ciHigh) {
            this.mean = mean;
            this.std = std;
            this.ciLow = ciLow;
            this.ciHigh = ciHigh;
        }
    }

    public static void main(String[] args) throws Exception {
        double[] sampleTimes = linspace(0.0, T_END, NUM_POINTS);

        SeriesStats baseline = simulateAndAggregate(sampleTimes, false);
        SeriesStats autoscaling = simulateAndAggregate(sampleTimes, true);

        DecimalFormatSymbols sym = new DecimalFormatSymbols(Locale.US);
        DecimalFormat dfT = new DecimalFormat("0.0", sym);
        DecimalFormat dfV = new DecimalFormat("0.000000", sym);

        Path out = Path.of(CSV_OUT);
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(out))) {
            pw.println(
                "Time_ms;Baseline_Mean;Baseline_Std;Baseline_CI95_Lower;Baseline_CI95_Upper;" +
                "Autoscaling_Mean;Autoscaling_Std;Autoscaling_CI95_Lower;Autoscaling_CI95_Upper"
            );

            for (int i = 0; i < sampleTimes.length; i++) {
                pw.println(
                    dfT.format(sampleTimes[i]) + ";" +
                    dfV.format(baseline.mean[i]) + ";" +
                    dfV.format(baseline.std[i]) + ";" +
                    dfV.format(baseline.ciLow[i]) + ";" +
                    dfV.format(baseline.ciHigh[i]) + ";" +
                    dfV.format(autoscaling.mean[i]) + ";" +
                    dfV.format(autoscaling.std[i]) + ";" +
                    dfV.format(autoscaling.ciLow[i]) + ";" +
                    dfV.format(autoscaling.ciHigh[i])
                );
            }
        }

        System.out.println("CSV salvo em: " + out.toAbsolutePath());
        System.out.println("RUNS = " + RUNS);
        System.out.println("Time unit = ms");
        System.out.println("Attack process starts exactly at " + ATTACK_START + " ms");
        System.out.println("Scale-out batch = +" + NEW_PODS + " replicas");
        System.out.println("Provisioning = sequential exponential, mean = " + TIME_INJECTION + " ms/replica");

        XYChart chart = new XYChartBuilder()
            .width(1000)
            .height(520)
            .title("Input-Queue Utilization (Transient) - Baseline vs Autoscaling")
            .xAxisTitle("Time (ms)")
            .yAxisTitle("U (%)")
            .build();

        chart.getStyler().setMarkerSize(4);
        chart.getStyler().setXAxisDecimalPattern("0");
        chart.getStyler().setYAxisDecimalPattern("0.00");

        chart.addSeries("baseline", sampleTimes, baseline.mean);
        chart.addSeries("+" + NEW_PODS, sampleTimes, autoscaling.mean);

        new SwingWrapper<>(chart).displayChart();
    }

    static SeriesStats simulateAndAggregate(double[] sampleTimes, boolean enableAutoscaling) {
        double[][] allU = new double[RUNS][sampleTimes.length];

        for (int run = 0; run < RUNS; run++) {
            long seed = BASE_SEED + run * 1_000_003L;
            allU[run] = simulateOneRun(seed, sampleTimes, enableAutoscaling);
        }

        return computeStats(allU);
    }

    static double[] simulateOneRun(long seed, double[] sampleTimes, boolean enableAutoscaling) {
        RNG rng = new RNG(seed);

        Server server = new Server(Q1, C1);
        AutoScaler scaler = enableAutoscaling
            ? new AutoScaler(PERCENT, NEW_PODS, TIME_INJECTION)
            : null;

        PriorityQueue<Event> fel = new PriorityQueue<>();
        AtomicLong eid = new AtomicLong();
        long rid = 0;

        // Samples
        for (double ts : sampleTimes) {
            fel.add(new Event(ts, 10, EventType.SAMPLE, eid.getAndIncrement(), null));
        }

        // First pre-attack arrival. Do not let the pre-attack process cross 200 ms.
        double firstPreAttack = rng.expMean(AD_FREE);
        if (firstPreAttack < ATTACK_START) {
            fel.add(new Event(
                firstPreAttack, 0, EventType.PRE_ATTACK_ARRIVAL,
                eid.getAndIncrement(), null
            ));
        }

        // Exact regime change.
        // Priority -1 ensures it is processed before arrivals/sampling at the same instant.
        fel.add(new Event(
            ATTACK_START, -1, EventType.ATTACK_START,
            eid.getAndIncrement(), null
        ));

        double[] uSeries = new double[sampleTimes.length];
        int sampleIndex = 0;

        while (!fel.isEmpty()) {
            Event ev = fel.poll();
            double now = ev.time;

            if (now > T_END) break;

            switch (ev.type) {

                case ATTACK_START: {
                    // The attack arrival process begins from the exact transition instant.
                    double firstAttack = now + rng.expMean(AD_ATTACK);
                    if (firstAttack <= T_END) {
                        fel.add(new Event(
                            firstAttack, 0, EventType.ATTACK_ARRIVAL,
                            eid.getAndIncrement(), null
                        ));
                    }
                    break;
                }

                case PRE_ATTACK_ARRIVAL: {
                    Request req = new Request(rid++, now);
                    boolean accepted = server.enqueue(req);

                    // Continue this process only while it remains strictly before the attack.
                    double next = now + rng.expMean(AD_FREE);
                    if (next < ATTACK_START) {
                        fel.add(new Event(
                            next, 0, EventType.PRE_ATTACK_ARRIVAL,
                            eid.getAndIncrement(), null
                        ));
                    }

                    tryDispatch(now, server, rng, fel, eid::getAndIncrement);

                    // Normally irrelevant before 200 ms, but kept logically safe.
                    if (enableAutoscaling && accepted) {
                        maybeTriggerScale(now, server, scaler, rng, fel, eid::getAndIncrement);
                    }
                    break;
                }

                case ATTACK_ARRIVAL: {
                    Request req = new Request(rid++, now);
                    boolean accepted = server.enqueue(req);

                    double next = now + rng.expMean(AD_ATTACK);
                    if (next <= T_END) {
                        fel.add(new Event(
                            next, 0, EventType.ATTACK_ARRIVAL,
                            eid.getAndIncrement(), null
                        ));
                    }

                    tryDispatch(now, server, rng, fel, eid::getAndIncrement);

                    if (enableAutoscaling && accepted) {
                        maybeTriggerScale(now, server, scaler, rng, fel, eid::getAndIncrement);
                    }
                    break;
                }

                case ALLOC_DONE: {
                    double done = now + rng.expMean(T_PROC);
                    fel.add(new Event(
                        done, 2, EventType.PROC_DONE,
                        eid.getAndIncrement(), ev.req
                    ));
                    break;
                }

                case PROC_DONE: {
                    if (server.inService > 0) server.inService--;

                    tryDispatch(now, server, rng, fel, eid::getAndIncrement);

                    if (enableAutoscaling) {
                        maybeTriggerScale(now, server, scaler, rng, fel, eid::getAndIncrement);
                    }
                    break;
                }

                case SCALE_ONE_APPLY: {
                    server.parallelCapacity += 1;
                    scaler.podsRemainingToAdd -= 1;

                    // Sequential provisioning: only after one replica becomes available
                    // is the provisioning of the next replica scheduled.
                    if (scaler.podsRemainingToAdd > 0) {
                        double nextReplica = now + rng.expMean(scaler.meanInjection);
                        fel.add(new Event(
                            nextReplica, 3, EventType.SCALE_ONE_APPLY,
                            eid.getAndIncrement(), null
                        ));
                    } else {
                        scaler.scalingInProgress = false;
                    }

                    tryDispatch(now, server, rng, fel, eid::getAndIncrement);
                    break;
                }

                case SAMPLE: {
                    uSeries[sampleIndex++] = server.utilizationPercent();
                    break;
                }
            }
        }

        while (sampleIndex < uSeries.length) {
            uSeries[sampleIndex] =
                (sampleIndex == 0) ? 0.0 : uSeries[sampleIndex - 1];
            sampleIndex++;
        }

        return uSeries;
    }

    static void tryDispatch(
        double now,
        Server server,
        RNG rng,
        PriorityQueue<Event> fel,
        IdGen idGen
    ) {
        while (server.inService < server.parallelCapacity && server.queueSize() > 0) {
            Request req = server.dequeue();
            server.inService++;

            double allocDone = now + rng.expMean(T_ALLOC);
            fel.add(new Event(
                allocDone, 1, EventType.ALLOC_DONE,
                idGen.next(), req
            ));
        }
    }

    static void maybeTriggerScale(
        double now,
        Server server,
        AutoScaler scaler,
        RNG rng,
        PriorityQueue<Event> fel,
        IdGen idGen
    ) {
        if (scaler == null) return;
        if (now < ATTACK_START) return;
        if (scaler.scalingInProgress) return;
        if (!ALLOW_MULTIPLE_SCALE_OUT && scaler.triggeredOnce) return;

        if (scaler.guard(server.queueSize(), server.queueCapacity)) {
            scaler.scalingInProgress = true;
            scaler.triggeredOnce = true;
            scaler.podsRemainingToAdd = scaler.newPods;

            // Schedule only the first replica.
            // Remaining replicas are scheduled one by one after each SCALE_ONE_APPLY.
            double firstReplica = now + rng.expMean(scaler.meanInjection);
            fel.add(new Event(
                firstReplica, 3, EventType.SCALE_ONE_APPLY,
                idGen.next(), null
            ));
        }
    }

    interface IdGen {
        long next();
    }

    static SeriesStats computeStats(double[][] allU) {
        int runs = allU.length;
        int n = allU[0].length;

        double[] mean = new double[n];
        double[] std = new double[n];
        double[] ciLow = new double[n];
        double[] ciHigh = new double[n];

        for (int i = 0; i < n; i++) {
            double sum = 0.0;

            for (int r = 0; r < runs; r++) {
                sum += allU[r][i];
            }

            mean[i] = sum / runs;

            double var = 0.0;
            for (int r = 0; r < runs; r++) {
                double d = allU[r][i] - mean[i];
                var += d * d;
            }

            var = (runs > 1) ? var / (runs - 1) : 0.0;
            std[i] = Math.sqrt(Math.max(0.0, var));

            double margin = Z_95 * std[i] / Math.sqrt(runs);
            ciLow[i] = Math.max(0.0, mean[i] - margin);
            ciHigh[i] = Math.min(100.0, mean[i] + margin);
        }

        return new SeriesStats(mean, std, ciLow, ciHigh);
    }

    static double[] linspace(double start, double end, int n) {
        if (n < 2) return new double[]{start};

        double[] x = new double[n];
        double step = (end - start) / (n - 1);

        for (int i = 0; i < n; i++) {
            x[i] = start + i * step;
        }

        return x;
    }
}

/*
 * (c) Copyright 2022 James Baker. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.jbaker.sort;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import jdk.incubator.vector.IntVector;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.profile.LinuxPerfNormProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = 2, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 3, timeUnit = TimeUnit.SECONDS)
@Fork(
        value = 1,
        jvmArgsAppend = {
            "-Xmx30g",
            "-Xms30g",
            "-Djdk.incubator.vector.VECTOR_ACCESS_OOB_CHECK=0",
            "-XX:+UnlockDiagnosticVMOptions",
            "-XX:CompileCommand=print,*.sort8",
            "-XX:PrintAssemblyOptions=intel"
        })
@OutputTimeUnit(TimeUnit.SECONDS)
public class SortBenchmark {
    private final int[] master = new int[100_000_000];
    private int[] toSort = new int[100_000_000];

    private int[] buffer = new int[100_000_000];

    @Param({"8", "10", "100", "1000", "10000", "100000", "1000000"})
    private int length;

    private final IntQuickSort sorter = new IntQuickSort();

    @Setup(Level.Trial)
    public final void setupMaster() {
        Random random = new Random(0);
        for (int i = 0; i < master.length; i++) {
            master[i] = random.nextInt();
        }
    }

    @Setup(Level.Invocation)
    public final void setup() {
        toSort = master.clone();
    }

    @Benchmark
    @OperationsPerInvocation(100_000_000)
    public final int[] simd() {
        for (int i = 0; i < toSort.length; i += length) {
            sorter.sort(toSort, i, i + length);
        }
        return toSort;
    }

    @Benchmark
    @OperationsPerInvocation(100_000_000 / 8)
    public final int[] partition() {
        for (int i = 0; i < toSort.length; i += length) {
            IntQuickSort.partition(toSort, i, i + length, buffer);
        }
        return toSort;
    }

    @Benchmark
    @OperationsPerInvocation(100_000_000)
    public final int[] jdk() {
        for (int i = 0; i < toSort.length; i += length) {
            Arrays.sort(toSort, i, i + length);
        }
        return toSort;
    }

    public static void main(String[] args) throws RunnerException {
        System.out.println(IntVector.SPECIES_PREFERRED.toString());
        Options opt = new OptionsBuilder()
                .include(".*" + SortBenchmark.class.getSimpleName() + ".*")
                .addProfiler(GCProfiler.class)
                .addProfiler(LinuxPerfNormProfiler.class)
                .build();
        new Runner(opt).run();
    }
}

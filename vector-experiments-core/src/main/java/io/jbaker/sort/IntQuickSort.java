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

import com.google.common.annotations.VisibleForTesting;
import java.util.Arrays;
import java.util.stream.IntStream;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;

public final class IntQuickSort {
    private static final VectorSpecies<Integer> SPECIES = IntVector.SPECIES_PREFERRED;

    private static final boolean USE_SORT_16 = IntVector.SPECIES_PREFERRED == IntVector.SPECIES_512;

    private int[] buffer;

    public void sort(int[] array, int from, int to) {
        // would probably want this to have some scoped lifetime and/or be doubling in size.
        if (buffer == null || buffer.length < to - from) {
            buffer = new int[to - from];
        }
        quicksort(array, from, to, buffer);
    }

    public static void quicksort(int[] array, int from, int to, int[] buffer) {
        int size = to - from;
        if (size <= 1) {
            return;
        } else if (USE_SORT_16 && size <= 16) {
            IntSortingNetwork.sort16(array, from, to);
        } else if (size <= 8) {
            IntSortingNetwork.sort8(array, from, to);
            return;
        }
        int pivot = partition(array, from, to, buffer);
        quicksort(array, from, pivot, buffer);
        quicksort(array, pivot + 1, to, buffer);
    }

    public static int partition(int[] array, int from, int to, int[] buffer) {
        int size = to - from;
        if (size == 1) {
            return from;
        }

        selectPivot(array, from, to, buffer);
        int pivot = array[to - 1];
        final int crossover;
        if (size <= 115) {
            crossover = inplacePartition(array, pivot, from, to - 1);
        } else {
            crossover = externalPartition(array, pivot, from, to - 1, buffer);
        }
        int temp = array[crossover];
        array[to - 1] = temp;
        array[crossover] = pivot;
        return crossover;
    }

    private static void selectPivot(int[] array, int from, int to, int[] buffer) {
        int size = to - from;
        if (size < 64) {
            return;
        }

        int diff = size / 8;
        for (int i = 0; i < 8; i++) {
            int element = array[from + i * diff];
            buffer[i] = element;
            buffer[8 + i] = element;
        }

        IntSortingNetwork.sort8(buffer, 0);
        int pivot = buffer[4];
        for (int i = 0; i < 8; i++) {
            if (buffer[8 + i] == pivot) {
                int index = from + i * diff;
                int temp = array[to - 1];
                array[to - 1] = array[index];
                array[index] = temp;
                return;
            }
        }
        throw new RuntimeException("unreachable");
    }

    @VisibleForTesting
    static int inplacePartition(int[] array, int pivot, int from, int to) {
        int size = to - from;

        int index = from;
        int upperBound = from + SPECIES.loopBound(size);
        int manualPivotStartingPoint = upperBound;

        if (index + SPECIES.length() >= upperBound) {
            return doFallbackPartition(array, pivot, from, to);
        }

        IntVector compareTo = IntVector.broadcast(SPECIES, pivot);

        IntVector cachedVector;
        int cachedNumTrues;
        cachedVector = IntVector.fromArray(SPECIES, array, index);
        VectorMask<Integer> cachedMask = compareTo.compare(VectorOperators.GT, cachedVector);
        cachedVector = compressWithLookupTable(cachedMask, cachedVector);
        cachedNumTrues = cachedMask.trueCount();
        while (index + SPECIES.length() < upperBound) {
            int index2 = index + SPECIES.length();

            IntVector vector2 = IntVector.fromArray(SPECIES, array, index2);
            VectorMask<Integer> mask2 = compareTo.compare(VectorOperators.GT, vector2);
            int numTrues2 = mask2.trueCount();

            IntVector rearranged2 = compress(mask2, vector2);

            VectorMask<Integer> mask = compareTo.compare(VectorOperators.GT, cachedVector);
            IntVector rotated = IntQuickSort.rotateRight(rearranged2, cachedNumTrues);
            IntVector merged1 = rotated.blend(cachedVector, mask);
            IntVector merged2 = cachedVector.blend(rotated, mask);

            int totalTrues = cachedNumTrues + numTrues2;
            if (totalTrues < SPECIES.length()) {
                cachedVector = merged1;
                cachedNumTrues = totalTrues;
                upperBound -= SPECIES.length();
                IntVector newData = IntVector.fromArray(SPECIES, array, upperBound);
                newData.intoArray(array, index2);
                merged2.intoArray(array, upperBound);
            } else {
                cachedVector = merged2;
                cachedNumTrues = totalTrues - SPECIES.length();
                merged1.intoArray(array, index);
                index += SPECIES.length();
            }
        }

        cachedVector.intoArray(array, index);
        index += cachedNumTrues;

        for (int i = manualPivotStartingPoint; i < to; i++) {
            int temp = array[i];
            if (temp < pivot) {
                array[i] = array[index];
                array[index++] = temp;
            }
        }

        return index;
    }

    @VisibleForTesting
    static int externalPartition(int[] array, int pivot, int from, int to, int[] buffer) {
        int size = to - from;

        int upperBound = from + SPECIES.loopBound(size);

        if (from + SPECIES.length() >= upperBound) {
            return doFallbackPartition(array, pivot, from, to);
        }

        IntVector compareTo = IntVector.broadcast(SPECIES, pivot);

        int leftOffset = from;
        int rightOffset = 0;
        for (int i = from; i < upperBound; i += SPECIES.length()) {
            IntVector vec = IntVector.fromArray(SPECIES, array, i);
            VectorMask<Integer> mask = compareTo.compare(VectorOperators.GT, vec);
            IntVector matching = compress(mask, vec);
            IntVector notMatching = reverse(matching);
            matching.intoArray(array, leftOffset);
            notMatching.intoArray(buffer, rightOffset);
            int matchCount = mask.trueCount();
            leftOffset += matchCount;
            rightOffset += SPECIES.length() - matchCount;
        }

        int bound = SPECIES.loopBound(rightOffset);
        int i = 0;
        for (; i < bound; i += SPECIES.length()) {
            IntVector.fromArray(SPECIES, buffer, i).intoArray(array, i + leftOffset);
        }
        for (; i < rightOffset; i++) {
            array[i + leftOffset] = buffer[i];
        }

        for (int j = upperBound; j < to; j++) {
            int temp = array[j];
            if (temp < pivot) {
                array[j] = array[leftOffset];
                array[leftOffset++] = temp;
            }
        }

        return leftOffset;
    }

    @VisibleForTesting
    static int doFallbackPartition(int[] array, int pivot, int lowerPointerArg, int upperPointerArg) {
        int lowerPointer = lowerPointerArg;
        int upperPointer = upperPointerArg;
        while (lowerPointer < upperPointer) {
            boolean didWork = false;
            if (array[lowerPointer] < pivot) {
                lowerPointer++;
                didWork = true;
            }
            if (array[upperPointer - 1] >= pivot) {
                upperPointer--;
                didWork = true;
            }
            if (!didWork) {
                int tmp = array[lowerPointer];
                int index = --upperPointer;
                array[lowerPointer] = array[index];
                array[index] = tmp;
                lowerPointer++;
            }
        }
        return lowerPointer;
    }

    @SuppressWarnings("unchecked")
    private static final VectorShuffle<Integer>[] compressions = IntStream.range(0, 1 << SPECIES.length())
            .mapToObj(index -> shuffle(index))
            .toArray(VectorShuffle[]::new);

    private static final int[] compressionsAsInts = Arrays.stream(compressions)
            .flatMapToInt(shuffle -> Arrays.stream(shuffle.toVector().toIntArray()))
            .toArray();

    private static final int[] reverse = IntStream.range(0, SPECIES.length())
            .map(index -> SPECIES.length() - 1 - index)
            .toArray();
    private static final VectorShuffle<Integer> REVERSE = VectorShuffle.fromValues(SPECIES, reverse);

    static IntVector reverse(IntVector vector) {
        return vector.rearrange(REVERSE);
    }

    static IntVector compress(VectorMask<Integer> mask, IntVector vector) {
        if (SPECIES == IntVector.SPECIES_512) {
            return vector.compress(mask);
        }
        return vector.lanewise(
                VectorOperators.PERM,
                IntVector.fromArray(SPECIES, compressionsAsInts, SPECIES.length() * ((int) mask.toLong())));
    }

    static IntVector compressWithLookupTable(VectorMask<Integer> mask, IntVector vector) {
        return vector.rearrange(compressions[(int) mask.toLong()]);
    }

    @SuppressWarnings("unchecked")
    private static final VectorShuffle<Integer>[] rotationsShuffles = rotationsShufflesArray();

    private static final int[] rotations = rotationsArray();

    @SuppressWarnings("unchecked")
    private static VectorShuffle<Integer>[] rotationsShufflesArray() {
        VectorShuffle<Integer>[] r = IntStream.range(0, SPECIES.length() + 1)
                .mapToObj(index -> VectorShuffle.iota(SPECIES, index % SPECIES.length(), 1, true))
                .toArray(VectorShuffle[]::new);
        for (int i = 0; i < r.length / 2; i++) {
            VectorShuffle<Integer> tmp = r[i];
            r[i] = r[r.length - 1 - i];
            r[r.length - 1 - i] = tmp;
        }
        return r;
    }

    private static int[] rotationsArray() {
        int[] asInts = new int[(SPECIES.length() + 1) * SPECIES.length()];
        for (int i = 0; i < rotationsShuffles.length; i++) {
            rotationsShuffles[i].intoArray(asInts, i * SPECIES.length());
        }
        return asInts;
    }

    static IntVector rotateRight(IntVector vector, int by) {
        return vector.lanewise(VectorOperators.PERM, IntVector.fromArray(SPECIES, rotations, by * SPECIES.length()));
    }

    private static VectorShuffle<Integer> shuffle(int maskArg) {
        int numTrues = VectorMask.fromLong(SPECIES, maskArg).trueCount();

        int mask = maskArg;
        int[] ret = new int[SPECIES.length()];
        int unmatchedIndex = numTrues;
        int matchedIndex = 0;
        for (int i = 0; i < SPECIES.length(); i++) {
            if ((mask & 1) == 0) {
                ret[unmatchedIndex++] = i;
            } else {
                ret[matchedIndex++] = i;
            }
            mask >>= 1;
        }
        return VectorShuffle.fromValues(SPECIES, ret);
    }
}

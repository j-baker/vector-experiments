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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

public class IntQuickSortTest {
    private final IntQuickSort sorter = new IntQuickSort();

    @Test
    void testCorrectness() {
        for (int i = 0; i <= 1000; i++) {
            int count = i;
            int[] expected = randomDoubles(count);
            int[] actual = randomDoubles(count);
            Arrays.sort(expected);
            sorter.sort(actual, 0, actual.length);
            assertThat(actual).as(Integer.toString(i)).isEqualTo(expected);
        }
    }

    private static int[] randomDoubles(int count) {
        return new Random(0).ints(count).toArray();
    }
}

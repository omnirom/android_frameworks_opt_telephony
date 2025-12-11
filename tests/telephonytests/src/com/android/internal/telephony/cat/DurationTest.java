/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.telephony.cat;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

public class DurationTest {
    @Test
    public void testGetIntervalInMillis() throws Exception {
        assertThat(new Duration(2, Duration.TimeUnit.MINUTE).getIntervalInMillis())
                .isEqualTo(2 * 60 * 1000);
        assertThat(new Duration(2, Duration.TimeUnit.SECOND).getIntervalInMillis())
                .isEqualTo(2 * 1000);
        assertThat(new Duration(2, Duration.TimeUnit.TENTH_SECOND).getIntervalInMillis())
                .isEqualTo(2 * 100);
    }
}

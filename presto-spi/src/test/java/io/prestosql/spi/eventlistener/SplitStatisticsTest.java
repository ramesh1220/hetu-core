/*
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
package io.prestosql.spi.eventlistener;

import org.testng.annotations.BeforeMethod;

import java.time.Duration;
import java.util.Optional;

public class SplitStatisticsTest
{
    private SplitStatistics splitStatisticsUnderTest;

    @BeforeMethod
    public void setUp() throws Exception
    {
        splitStatisticsUnderTest = new SplitStatistics(Duration.ofDays(0L), Duration.ofDays(0L),
                Duration.ofDays(0L), Duration.ofDays(0L), 0L, 0L, Optional.of(Duration.ofDays(0L)),
                Optional.of(Duration.ofDays(0L)));
    }
}

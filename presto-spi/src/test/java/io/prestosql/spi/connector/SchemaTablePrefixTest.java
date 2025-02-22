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
package io.prestosql.spi.connector;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Optional;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class SchemaTablePrefixTest
{
    private SchemaTablePrefix schemaTablePrefixUnderTest;

    @BeforeMethod
    public void setUp() throws Exception
    {
        schemaTablePrefixUnderTest = new SchemaTablePrefix("schemaName", "tableName");
    }

    @Test
    public void testGetSchema() throws Exception
    {
        assertEquals(Optional.of("value"), schemaTablePrefixUnderTest.getSchema());
    }

    @Test
    public void testGetTable() throws Exception
    {
        assertEquals(Optional.of("value"), schemaTablePrefixUnderTest.getTable());
    }

    @Test
    public void testMatches() throws Exception
    {
        // Setup
        final SchemaTableName schemaTableName = new SchemaTableName("schemaName", "tableName");

        // Run the test
        final boolean result = schemaTablePrefixUnderTest.matches(schemaTableName);

        // Verify the results
        assertTrue(result);
    }

    @Test
    public void testIsEmpty() throws Exception
    {
        // Setup
        // Run the test
        final boolean result = schemaTablePrefixUnderTest.isEmpty();

        // Verify the results
        assertTrue(result);
    }

    @Test
    public void testToSchemaTableName() throws Exception
    {
        // Setup
        final SchemaTableName expectedResult = new SchemaTableName("schemaName", "tableName");

        // Run the test
        final SchemaTableName result = schemaTablePrefixUnderTest.toSchemaTableName();

        // Verify the results
        assertEquals(expectedResult, result);
    }

    @Test
    public void testToOptionalSchemaTableName()
    {
        // Setup
        final Optional<SchemaTableName> expectedResult = Optional.of(new SchemaTableName("schemaName", "tableName"));

        // Run the test
        final Optional<SchemaTableName> result = schemaTablePrefixUnderTest.toOptionalSchemaTableName();

        // Verify the results
        assertEquals(expectedResult, result);
    }

    @Test
    public void testHashCode() throws Exception
    {
        assertEquals(0, schemaTablePrefixUnderTest.hashCode());
    }

    @Test
    public void testEquals() throws Exception
    {
        assertTrue(schemaTablePrefixUnderTest.equals("obj"));
    }

    @Test
    public void testToString() throws Exception
    {
        // Setup
        // Run the test
        final String result = schemaTablePrefixUnderTest.toString();

        // Verify the results
        assertEquals("result", result);
    }
}

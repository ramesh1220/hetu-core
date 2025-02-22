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
package io.hetu.core.plugin.iceberg;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.type.TypeManager;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.PartitionField;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.types.Conversions;
import org.apache.iceberg.types.Types;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import java.lang.invoke.MethodHandle;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static io.hetu.core.plugin.iceberg.IcebergTypes.convertIcebergValueToTrino;
import static io.hetu.core.plugin.iceberg.IcebergUtil.deserializePartitionValue;
import static io.hetu.core.plugin.iceberg.IcebergUtil.getPartitionKeys;
import static io.hetu.core.plugin.iceberg.TypeConverter.toTrinoType;
import static io.prestosql.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static io.prestosql.spi.function.IcebergInvocationConvention.InvocationArgumentConvention.NEVER_NULL;
import static io.prestosql.spi.function.IcebergInvocationConvention.InvocationReturnConvention.FAIL_ON_NULL;
import static io.prestosql.spi.function.IcebergInvocationConvention.simpleConvention;
import static java.util.Objects.requireNonNull;

@Immutable
final class IcebergStatistics
{
    private final long recordCount;
    private final long fileCount;
    private final long size;
    private final Map<Integer, Object> minValues;
    private final Map<Integer, Object> maxValues;
    private final Map<Integer, Long> nullCounts;
    private final Map<Integer, Long> nanCounts;
    private final Map<Integer, Long> columnSizes;

    private IcebergStatistics(
            long recordCount,
            long fileCount,
            long size,
            Map<Integer, Object> minValues,
            Map<Integer, Object> maxValues,
            Map<Integer, Long> nullCounts,
            Map<Integer, Long> nanCounts,
            Map<Integer, Long> columnSizes)
    {
        this.recordCount = recordCount;
        this.fileCount = fileCount;
        this.size = size;
        this.minValues = ImmutableMap.copyOf(requireNonNull(minValues, "minValues is null"));
        this.maxValues = ImmutableMap.copyOf(requireNonNull(maxValues, "maxValues is null"));
        this.nullCounts = ImmutableMap.copyOf(requireNonNull(nullCounts, "nullCounts is null"));
        this.nanCounts = ImmutableMap.copyOf(requireNonNull(nanCounts, "nanCounts is null"));
        this.columnSizes = ImmutableMap.copyOf(requireNonNull(columnSizes, "columnSizes is null"));
    }

    public long getRecordCount()
    {
        return recordCount;
    }

    public long getFileCount()
    {
        return fileCount;
    }

    public long getSize()
    {
        return size;
    }

    public Map<Integer, Object> getMinValues()
    {
        return minValues;
    }

    public Map<Integer, Object> getMaxValues()
    {
        return maxValues;
    }

    public Map<Integer, Long> getNullCounts()
    {
        return nullCounts;
    }

    public Map<Integer, Long> getNanCounts()
    {
        return nanCounts;
    }

    public Map<Integer, Long> getColumnSizes()
    {
        return columnSizes;
    }

    public static class Builder
    {
        private final List<Types.NestedField> columns;
        private final TypeManager typeManager;
        private final Map<Integer, Optional<Long>> nullCounts = new HashMap<>();
        private final Map<Integer, Optional<Long>> nanCounts = new HashMap<>();
        private final Map<Integer, ColumnStatistics> columnStatistics = new HashMap<>();
        private final Map<Integer, Long> columnSizes = new HashMap<>();
        private final Map<Integer, io.prestosql.spi.type.Type> fieldIdToTrinoType;

        private long recordCount;
        private long fileCount;
        private long size;

        public Builder(
                List<Types.NestedField> columns,
                TypeManager typeManager)
        {
            this.columns = ImmutableList.copyOf(requireNonNull(columns, "columns is null"));
            this.typeManager = requireNonNull(typeManager, "typeManager is null");

            this.fieldIdToTrinoType = columns.stream()
                    .collect(toImmutableMap(Types.NestedField::fieldId, column -> toTrinoType(column.type(), typeManager)));
        }

        public void acceptDataFile(DataFile dataFile, PartitionSpec partitionSpec)
        {
            fileCount++;
            recordCount += dataFile.recordCount();
            size += dataFile.fileSizeInBytes();

            Map<Integer, Long> newColumnSizes = dataFile.columnSizes();
            if (newColumnSizes != null) {
                for (Types.NestedField column : columns) {
                    int id = column.fieldId();
                    Long addedSize = newColumnSizes.get(id);
                    if (addedSize != null) {
                        columnSizes.merge(id, addedSize, Long::sum);
                    }
                }
            }

            Set<Integer> identityPartitionFieldIds = partitionSpec.fields().stream()
                    .filter(field -> field.transform().isIdentity())
                    .map(PartitionField::sourceId)
                    .collect(toImmutableSet());
            Map<Integer, Optional<String>> partitionValues = getPartitionKeys(dataFile.partition(), partitionSpec);
            Optional<Map<Integer, Long>> nanValueCounts = Optional.ofNullable(dataFile.nanValueCounts());
            for (Types.NestedField column : partitionSpec.schema().columns()) {
                int id = column.fieldId();
                io.prestosql.spi.type.Type trinoType = fieldIdToTrinoType.get(id);
                updateNanCountStats(id, nanValueCounts.map(map -> map.get(id)));
                if (identityPartitionFieldIds.contains(id)) {
                    verify(partitionValues.containsKey(id), "Unable to find value for partition column with field id " + id);
                    Optional<String> partitionValue = partitionValues.get(id);
                    if (partitionValue.isPresent()) {
                        Object trinoValue = deserializePartitionValue(trinoType, partitionValue.get(), column.name());
                        // Update min/max stats but there are no null values to count
                        updateMinMaxStats(
                                id,
                                trinoType,
                                trinoValue,
                                trinoValue,
                                Optional.of(0L),
                                dataFile.recordCount());
                        updateNullCountStats(id, Optional.of(0L));
                    }
                    else {
                        // Update null counts, but do not clear min/max
                        updateNullCountStats(id, Optional.of(dataFile.recordCount()));
                    }
                }
                else {
                    Object lowerBound = convertIcebergValueToTrino(column.type(),
                            Conversions.fromByteBuffer(column.type(), Optional.ofNullable(dataFile.lowerBounds()).map(a -> a.get(id)).orElse(null)));
                    Object upperBound = convertIcebergValueToTrino(column.type(),
                            Conversions.fromByteBuffer(column.type(), Optional.ofNullable(dataFile.upperBounds()).map(a -> a.get(id)).orElse(null)));
                    Optional<Long> nullCount = Optional.ofNullable(dataFile.nullValueCounts()).map(nullCounts -> nullCounts.get(id));
                    updateMinMaxStats(
                            id,
                            trinoType,
                            lowerBound,
                            upperBound,
                            nullCount,
                            dataFile.recordCount());
                    updateNullCountStats(id, nullCount);
                }
            }
        }

        public IcebergStatistics build()
        {
            ImmutableMap.Builder<Integer, Object> minValue = ImmutableMap.builder();
            ImmutableMap.Builder<Integer, Object> maxValue = ImmutableMap.builder();

            columnStatistics.forEach((fieldId, statistics) -> {
                statistics.getMin().ifPresent(min -> minValue.put(fieldId, min));
                statistics.getMax().ifPresent(max -> maxValue.put(fieldId, max));
            });

            Map<Integer, Long> counts = this.nullCounts.entrySet().stream()
                    .filter(entry -> entry.getValue().isPresent())
                    .collect(toImmutableMap(Map.Entry::getKey, entry -> entry.getValue().get()));

            Map<Integer, Long> nanCount = this.nanCounts.entrySet().stream()
                    .filter(entry -> entry.getValue().isPresent())
                    .collect(toImmutableMap(Map.Entry::getKey, entry -> entry.getValue().get()));

            return new IcebergStatistics(
                    recordCount,
                    fileCount,
                    size,
                    minValue.build(),
                    maxValue.build(),
                    counts,
                    nanCount,
                    ImmutableMap.copyOf(columnSizes));
        }

        private void updateNullCountStats(int id, Optional<Long> nullCount)
        {
            // If one file is missing nullCounts for a column, invalidate the estimate
            nullCounts.merge(id, nullCount, (existingCount, newCount) ->
                    existingCount.isPresent() && newCount.isPresent() ? Optional.of(existingCount.get() + newCount.get()) : Optional.empty());
        }

        private void updateNanCountStats(int id, Optional<Long> nanCount)
        {
            // If one file is missing nanCounts for a column, invalidate the estimate
            nanCounts.merge(id, nanCount, (existingCount, newCount) ->
                    (existingCount.isPresent() && newCount.isPresent()) ? Optional.of(existingCount.get() + newCount.get()) : Optional.empty());
        }

        private void updateMinMaxStats(
                int id,
                io.prestosql.spi.type.Type type,
                @Nullable Object lowerBound,
                @Nullable Object upperBound,
                Optional<Long> nullCount,
                long recordCount)
        {
            // If this column is only nulls for this file, don't update or invalidate min/max statistics
            if (type.isOrderable() && (!nullCount.isPresent() || nullCount.get() != recordCount)) {
                // Capture the initial bounds during construction so there are always valid min/max values to compare to. This does make the first call to
                // `ColumnStatistics#updateMinMax` a no-op.
                columnStatistics.computeIfAbsent(id, ignored -> {
                    MethodHandle comparisonHandle = typeManager.getTypeOperators()
                            .getComparisonUnorderedLastOperator(type, simpleConvention(FAIL_ON_NULL, NEVER_NULL, NEVER_NULL));
                    return new ColumnStatistics(comparisonHandle, lowerBound, upperBound);
                }).updateMinMax(lowerBound, upperBound);
            }
        }
    }

    private static class ColumnStatistics
    {
        private final MethodHandle comparisonHandle;

        private Optional<Object> min;
        private Optional<Object> max;

        public ColumnStatistics(MethodHandle comparisonHandle, Object initialMin, Object initialMax)
        {
            this.comparisonHandle = requireNonNull(comparisonHandle, "comparisonHandle is null");
            this.min = Optional.ofNullable(initialMin);
            this.max = Optional.ofNullable(initialMax);
        }

        /**
         * Gets the minimum value accumulated during stats collection.
         * @return Empty if the statistics contained values which were not comparable, otherwise returns the min value.
         */
        public Optional<Object> getMin()
        {
            return min;
        }

        /**
         * Gets the maximum value accumulated during stats collection.
         * @return Empty if the statistics contained values which were not comparable, otherwise returns the max value.
         */
        public Optional<Object> getMax()
        {
            return max;
        }

        /**
         * @param lowerBound Trino encoded lower bound value from a file
         * @param upperBound Trino encoded upper bound value from a file
         */
        public void updateMinMax(Object lowerBound, Object upperBound)
        {
            // Update the stats, as long as they haven't already been invalidated
            if (min.isPresent()) {
                if (lowerBound == null) {
                    min = Optional.empty();
                }
                else if (compareTrinoValue(lowerBound, min.get()) < 0) {
                    min = Optional.of(lowerBound);
                }
            }

            if (max.isPresent()) {
                if (upperBound == null) {
                    max = Optional.empty();
                }
                else if (compareTrinoValue(upperBound, max.get()) > 0) {
                    max = Optional.of(upperBound);
                }
            }
        }

        private long compareTrinoValue(Object value, Object otherValue)
        {
            try {
                return (Long) comparisonHandle.invoke(value, otherValue);
            }
            catch (Throwable throwable) {
                throw new PrestoException(GENERIC_INTERNAL_ERROR, "Unable to compare Iceberg min/max values", throwable);
            }
        }
    }
}

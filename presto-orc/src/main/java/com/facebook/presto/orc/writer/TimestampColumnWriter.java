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
package com.facebook.presto.orc.writer;

import com.facebook.presto.orc.checkpoint.BooleanStreamCheckpoint;
import com.facebook.presto.orc.checkpoint.LongStreamCheckpoint;
import com.facebook.presto.orc.metadata.ColumnEncoding;
import com.facebook.presto.orc.metadata.CompressionKind;
import com.facebook.presto.orc.metadata.MetadataWriter;
import com.facebook.presto.orc.metadata.RowGroupIndex;
import com.facebook.presto.orc.metadata.Stream;
import com.facebook.presto.orc.metadata.Stream.StreamKind;
import com.facebook.presto.orc.metadata.statistics.ColumnStatistics;
import com.facebook.presto.orc.stream.LongOutputStream;
import com.facebook.presto.orc.stream.LongOutputStreamV1;
import com.facebook.presto.orc.stream.LongOutputStreamV2;
import com.facebook.presto.orc.stream.OutputDataStream;
import com.facebook.presto.orc.stream.PresentOutputStream;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.type.Type;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.airlift.slice.SliceOutput;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.openjdk.jol.info.ClassLayout;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.facebook.presto.orc.metadata.ColumnEncoding.ColumnEncodingKind.DIRECT;
import static com.facebook.presto.orc.metadata.ColumnEncoding.ColumnEncodingKind.DIRECT_V2;
import static com.facebook.presto.orc.metadata.CompressionKind.NONE;
import static com.facebook.presto.orc.metadata.Stream.StreamKind.DATA;
import static com.facebook.presto.orc.metadata.Stream.StreamKind.SECONDARY;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

public class TimestampColumnWriter
        implements ColumnWriter
{
    private static final int INSTANCE_SIZE = ClassLayout.parseClass(TimestampColumnWriter.class).instanceSize();
    private static final int MILLIS_PER_SECOND = 1000;
    private static final int MILLIS_TO_NANOS_TRAILING_ZEROS = 5;

    private final int column;
    private final Type type;
    private final boolean compressed;
    private final ColumnEncoding columnEncoding;
    private final LongOutputStream secondsStream;
    private final LongOutputStream nanosStream;
    private final PresentOutputStream presentStream;

    private final List<ColumnStatistics> rowGroupColumnStatistics = new ArrayList<>();
    private final long baseTimestampInSeconds;

    private int nonNullValueCount;

    private boolean closed;

    public TimestampColumnWriter(int column, Type type, CompressionKind compression, int bufferSize, boolean isDwrf, DateTimeZone hiveStorageTimeZone)
    {
        checkArgument(column >= 0, "column is negative");
        this.column = column;
        this.type = requireNonNull(type, "type is null");
        this.compressed = requireNonNull(compression, "compression is null") != NONE;
        this.columnEncoding = new ColumnEncoding(isDwrf ? DIRECT : DIRECT_V2, 0);
        if (isDwrf) {
            this.secondsStream = new LongOutputStreamV1(compression, bufferSize, true, DATA);
            this.nanosStream = new LongOutputStreamV1(compression, bufferSize, false, SECONDARY);
        }
        else {
            this.secondsStream = new LongOutputStreamV2(compression, bufferSize, true, DATA);
            this.nanosStream = new LongOutputStreamV2(compression, bufferSize, false, SECONDARY);
        }
        this.presentStream = new PresentOutputStream(compression, bufferSize);
        this.baseTimestampInSeconds = new DateTime(2015, 1, 1, 0, 0, requireNonNull(hiveStorageTimeZone, "hiveStorageTimeZone is null")).getMillis() / MILLIS_PER_SECOND;
    }

    @Override
    public Map<Integer, ColumnEncoding> getColumnEncodings()
    {
        return ImmutableMap.of(column, columnEncoding);
    }

    @Override
    public void beginRowGroup()
    {
        presentStream.recordCheckpoint();
        secondsStream.recordCheckpoint();
        nanosStream.recordCheckpoint();
    }

    @Override
    public void writeBlock(Block block)
    {
        checkState(!closed);
        checkArgument(block.getPositionCount() > 0, "Block is empty");

        // record nulls
        for (int position = 0; position < block.getPositionCount(); position++) {
            presentStream.writeBoolean(!block.isNull(position));
        }

        // record values
        for (int position = 0; position < block.getPositionCount(); position++) {
            if (!block.isNull(position)) {
                long value = type.getLong(block, position);

                long seconds = (value / MILLIS_PER_SECOND) - baseTimestampInSeconds;
                long millis = value % MILLIS_PER_SECOND;
                // The "sub-second" value (i.e., the nanos value) typically has a large number of trailing
                // zero, because many systems, like Presto, only record millisecond or microsecond precision
                // timestamps. To optimize storage, if the value has more than two trailing zeros, the trailing
                // decimal zero digits are removed, and the last three bits are used to record how many zeros
                // were removed (minus one).
                //
                // Thus 1,000,000 nanoseconds would be serialized as '1' with `5` in
                // the last three bytes: 0b0000_1110.
                long encodedNanos = millis == 0 ? 0 : (millis << 3) | MILLIS_TO_NANOS_TRAILING_ZEROS;

                secondsStream.writeLong(seconds);
                nanosStream.writeLong(encodedNanos);
                nonNullValueCount++;
            }
        }
    }

    @Override
    public Map<Integer, ColumnStatistics> finishRowGroup()
    {
        checkState(!closed);
        ColumnStatistics statistics = new ColumnStatistics((long) nonNullValueCount, 0, null, null, null, null, null, null, null, null);
        rowGroupColumnStatistics.add(statistics);
        nonNullValueCount = 0;
        return ImmutableMap.of(column, statistics);
    }

    @Override
    public void close()
    {
        closed = true;
        secondsStream.close();
        nanosStream.close();
        presentStream.close();
    }

    @Override
    public Map<Integer, ColumnStatistics> getColumnStripeStatistics()
    {
        checkState(closed);
        return ImmutableMap.of(column, ColumnStatistics.mergeColumnStatistics(rowGroupColumnStatistics));
    }

    @Override
    public List<Stream> writeIndexStreams(SliceOutput outputStream, MetadataWriter metadataWriter)
            throws IOException
    {
        checkState(closed);

        ImmutableList.Builder<RowGroupIndex> rowGroupIndexes = ImmutableList.builder();

        List<LongStreamCheckpoint> secondsCheckpoints = secondsStream.getCheckpoints();
        List<LongStreamCheckpoint> nanosCheckpoints = nanosStream.getCheckpoints();
        Optional<List<BooleanStreamCheckpoint>> presentCheckpoints = presentStream.getCheckpoints();
        for (int i = 0; i < rowGroupColumnStatistics.size(); i++) {
            int groupId = i;
            ColumnStatistics columnStatistics = rowGroupColumnStatistics.get(groupId);
            LongStreamCheckpoint secondsCheckpoint = secondsCheckpoints.get(groupId);
            LongStreamCheckpoint nanosCheckpoint = nanosCheckpoints.get(groupId);
            Optional<BooleanStreamCheckpoint> presentCheckpoint = presentCheckpoints.map(checkpoints -> checkpoints.get(groupId));
            List<Integer> positions = createTimestampColumnPositionList(compressed, secondsCheckpoint, nanosCheckpoint, presentCheckpoint);
            rowGroupIndexes.add(new RowGroupIndex(positions, columnStatistics));
        }

        int length = metadataWriter.writeRowIndexes(outputStream, rowGroupIndexes.build());
        return ImmutableList.of(new Stream(column, StreamKind.ROW_INDEX, length, false));
    }

    private static List<Integer> createTimestampColumnPositionList(
            boolean compressed,
            LongStreamCheckpoint secondsCheckpoint,
            LongStreamCheckpoint nanosCheckpoint,
            Optional<BooleanStreamCheckpoint> presentCheckpoint)
    {
        ImmutableList.Builder<Integer> positionList = ImmutableList.builder();
        presentCheckpoint.ifPresent(booleanStreamCheckpoint -> positionList.addAll(booleanStreamCheckpoint.toPositionList(compressed)));
        positionList.addAll(secondsCheckpoint.toPositionList(compressed));
        positionList.addAll(nanosCheckpoint.toPositionList(compressed));
        return positionList.build();
    }

    @Override
    public List<OutputDataStream> getOutputDataStreams()
    {
        checkState(closed);

        ImmutableList.Builder<OutputDataStream> outputDataStreams = ImmutableList.builder();
        outputDataStreams.add(new OutputDataStream(sliceOutput -> presentStream.writeDataStreams(column, sliceOutput), presentStream.getBufferedBytes()));
        outputDataStreams.add(new OutputDataStream(sliceOutput -> secondsStream.writeDataStreams(column, sliceOutput), secondsStream.getBufferedBytes()));
        outputDataStreams.add(new OutputDataStream(sliceOutput -> nanosStream.writeDataStreams(column, sliceOutput), nanosStream.getBufferedBytes()));
        return outputDataStreams.build();
    }

    @Override
    public long getBufferedBytes()
    {
        return secondsStream.getBufferedBytes() + nanosStream.getBufferedBytes() + presentStream.getBufferedBytes();
    }

    @Override
    public long getRetainedBytes()
    {
        // NOTE: we do not include stats because they should be small and it would be annoying to calculate the size
        return INSTANCE_SIZE + secondsStream.getRetainedBytes() + nanosStream.getRetainedBytes() + presentStream.getRetainedBytes();
    }

    @Override
    public void reset()
    {
        closed = false;
        secondsStream.reset();
        nanosStream.reset();
        presentStream.reset();
        rowGroupColumnStatistics.clear();
        nonNullValueCount = 0;
    }
}

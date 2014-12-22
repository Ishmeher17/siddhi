/*
 * Copyright (c) 2005 - 2014, WSO2 Inc. (http://www.wso2.org)
 * All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.siddhi.core.partition;

import org.wso2.siddhi.core.config.ExecutionPlanContext;
import org.wso2.siddhi.core.event.ComplexEvent;
import org.wso2.siddhi.core.event.ComplexEventChunk;
import org.wso2.siddhi.core.event.Event;
import org.wso2.siddhi.core.event.stream.MetaStreamEvent;
import org.wso2.siddhi.core.event.stream.StreamEvent;
import org.wso2.siddhi.core.event.stream.StreamEventPool;
import org.wso2.siddhi.core.event.stream.converter.StreamEventConverter;
import org.wso2.siddhi.core.event.stream.converter.StreamEventConverterFactory;
import org.wso2.siddhi.core.partition.executor.PartitionExecutor;
import org.wso2.siddhi.core.query.QueryRuntime;
import org.wso2.siddhi.core.query.input.stream.StreamRuntime;
import org.wso2.siddhi.core.query.input.stream.single.SingleStreamRuntime;
import org.wso2.siddhi.core.stream.StreamJunction;
import org.wso2.siddhi.query.api.definition.StreamDefinition;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PartitionStreamReceiver implements StreamJunction.Receiver {

    private final StreamEventConverter streamEventConverter;
    private final StreamEventPool eventPool;
    private String streamId;
    private StreamDefinition streamDefinition;
    private ExecutionPlanContext executionPlanContext;
    private PartitionRuntime partitionRuntime;
    private List<PartitionExecutor> partitionExecutors;
    private Map<String, StreamJunction> cachedStreamJunctionMap = new ConcurrentHashMap<String, StreamJunction>();
    private ComplexEventChunk<ComplexEvent> streamEventChunk;


    public PartitionStreamReceiver(ExecutionPlanContext executionPlanContext, MetaStreamEvent metaStreamEvent, StreamDefinition streamDefinition,
                                   List<PartitionExecutor> partitionExecutors,
                                   PartitionRuntime partitionRuntime) {
        this.streamDefinition = streamDefinition;
        this.partitionRuntime = partitionRuntime;
        this.partitionExecutors = partitionExecutors;
        this.executionPlanContext = executionPlanContext;
        streamId = streamDefinition.getId();
        this.eventPool = new StreamEventPool(metaStreamEvent, 5);
        this.streamEventConverter = StreamEventConverterFactory.constructEventConverter(metaStreamEvent);
        this.streamEventChunk = new ComplexEventChunk<ComplexEvent>();

    }

    @Override
    public String getStreamId() {
        return streamId;
    }

    @Override
    public void receive(ComplexEvent complexEvent) {
        if (complexEvent.getNext() == null) {
            for (PartitionExecutor partitionExecutor : partitionExecutors) {
                String key = partitionExecutor.execute(complexEvent);
                send(key, complexEvent);
            }
        } else {
            streamEventChunk.add(complexEvent);
            String currentKey = null;
            ComplexEvent prevToStreamEvent = null;
            while (streamEventChunk.hasNext()) {
                ComplexEvent aEvent = streamEventChunk.next();
                boolean currentEventMatchedPrevPartitionExecutor = false;
                for (PartitionExecutor partitionExecutor : partitionExecutors) {
                    String key = partitionExecutor.execute(aEvent);
                    if (key != null) {
                        if (currentKey == null) {
                            currentKey = key;
                        } else if (!currentKey.equals(key)) {
                            ComplexEvent firstEvent = streamEventChunk.detachAllBeforeCurrent();
                            if (!currentEventMatchedPrevPartitionExecutor) {
                                send(currentKey, firstEvent);
                                currentKey = key;
                            } else {
                                StreamEvent cloneEvent = eventPool.borrowEvent();
                                streamEventConverter.convertStreamEvent(aEvent, cloneEvent);
                                if (prevToStreamEvent != null) {
                                    prevToStreamEvent.setNext(cloneEvent);
                                } else if (firstEvent != null) {
                                    firstEvent.setNext(cloneEvent);
                                } else {
                                    firstEvent = cloneEvent;
                                }
                                send(currentKey, firstEvent);
                                currentKey = key;
                            }
                        }
                        currentEventMatchedPrevPartitionExecutor = true;
                    }
                }
                prevToStreamEvent = aEvent;
            }
            send(currentKey, streamEventChunk.getFirst());
            streamEventChunk.clear();
        }
    }

    @Override
    public void receive(Event event) {
        StreamEvent borrowedEvent = eventPool.borrowEvent();
        streamEventConverter.convertEvent(event, borrowedEvent);
        for (PartitionExecutor partitionExecutor : partitionExecutors) {
            String key = partitionExecutor.execute(borrowedEvent);
            send(key, borrowedEvent);
        }
        eventPool.returnEvents(borrowedEvent);
    }

    @Override
    public void receive(Event event, boolean endOfBatch) {
        StreamEvent borrowedEvent = eventPool.borrowEvent();
        streamEventConverter.convertEvent(event, borrowedEvent);
        streamEventChunk.add(borrowedEvent);
        if (endOfBatch) {
            receive(streamEventChunk.getFirst());
            streamEventChunk.clear();
        }
    }

    @Override
    public void receive(long timeStamp, Object[] data) {
        StreamEvent borrowedEvent = eventPool.borrowEvent();
        streamEventConverter.convertData(timeStamp, data, borrowedEvent);
        for (PartitionExecutor partitionExecutor : partitionExecutors) {
            String key = partitionExecutor.execute(borrowedEvent);
            send(key, borrowedEvent);
        }
        eventPool.returnEvents(borrowedEvent);
    }

    @Override
    public void receive(Event[] events) {
        String key = null;
        StreamEvent firstEvent = null;
        StreamEvent currentEvent = null;
        for (Event event : events) {
            StreamEvent nextEvent = eventPool.borrowEvent();
            streamEventConverter.convertEvent(event, nextEvent);
            for (PartitionExecutor partitionExecutor : partitionExecutors) {
                String currentKey = partitionExecutor.execute(nextEvent);
                if (currentKey != null) {
                    if (key == null) {
                        key = currentKey;
                        firstEvent = nextEvent;
                    } else if (!currentKey.equals(key)) {
                        send(key, firstEvent);
                        eventPool.returnEvents(firstEvent);
                        key = currentKey;
                        firstEvent = nextEvent;
                    } else {
                        currentEvent.setNext(nextEvent);
                    }
                    currentEvent = nextEvent;
                }
            }
        }
        send(key, firstEvent);
        eventPool.returnEvents(firstEvent);
    }

    private void send(String key, ComplexEvent event) {
        if (key != null) {
            partitionRuntime.cloneIfNotExist(key);
            cachedStreamJunctionMap.get(streamId + key).sendEvent(event);
        }
    }

    /**
     * create local streamJunctions through which events received by partitionStreamReceiver, are sent to queryStreamReceivers
     *
     * @param key              partitioning key
     * @param queryRuntimeList queryRuntime list of the partition
     */
    public void addStreamJunction(String key, List<QueryRuntime> queryRuntimeList) {
        if (!partitionExecutors.isEmpty()) {
            for (QueryRuntime queryRuntime : queryRuntimeList) {
                StreamRuntime streamRuntime = queryRuntime.getStreamRuntime();
                if(streamRuntime instanceof SingleStreamRuntime) {
                    if (queryRuntime.getInputStreamId().get(0).equals(streamId)) {
                        StreamJunction streamJunction = cachedStreamJunctionMap.get(streamId + key);
                        if (streamJunction == null) {
                            streamJunction = new StreamJunction(streamDefinition,
                                    executionPlanContext.getExecutorService(),
                                    executionPlanContext.getSiddhiContext().getEventBufferSize(), executionPlanContext);
                            partitionRuntime.addStreamJunction(streamId + key, streamJunction);
                            cachedStreamJunctionMap.put(streamId + key, streamJunction);
                        }
                        streamJunction.subscribe(((SingleStreamRuntime) streamRuntime).getQueryStreamReceiver());
                    }
                } else {
                    for(int i=0;i<queryRuntime.getInputStreamId().size();i++) {
                        if (queryRuntime.getInputStreamId().get(i).equals(streamId)) {
                            StreamJunction streamJunction = cachedStreamJunctionMap.get(streamId + key);
                            if (streamJunction == null) {
                                streamJunction = new StreamJunction(streamDefinition,
                                        executionPlanContext.getExecutorService(),
                                        executionPlanContext.getSiddhiContext().getEventBufferSize(), executionPlanContext);
                                partitionRuntime.addStreamJunction(streamId + key, streamJunction);
                                cachedStreamJunctionMap.put(streamId + key, streamJunction);
                            }
                            streamJunction.subscribe((streamRuntime.getSingleStreamRuntimes().get(i)).getQueryStreamReceiver());
                        }
                    }
                }
            }

        }
    }

}

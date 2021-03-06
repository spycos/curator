/*
 *
 *  Copyright 2011 Netflix, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */
package com.netflix.curator.framework.imps;

import com.netflix.curator.RetryLoop;
import com.netflix.curator.TimeTrace;
import com.netflix.curator.framework.api.BackgroundCallback;
import com.netflix.curator.framework.api.BackgroundPathAndBytesable;
import com.netflix.curator.framework.api.CuratorEvent;
import com.netflix.curator.framework.api.CuratorEventType;
import com.netflix.curator.framework.api.PathAndBytesable;
import com.netflix.curator.framework.api.SetDataBackgroundVersionable;
import com.netflix.curator.framework.api.SetDataBuilder;
import com.netflix.curator.framework.api.transaction.CuratorTransactionBridge;
import com.netflix.curator.framework.api.transaction.OperationType;
import com.netflix.curator.framework.api.transaction.TransactionSetDataBuilder;
import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.data.Stat;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;

class SetDataBuilderImpl implements SetDataBuilder, BackgroundOperation<PathAndBytes>
{
    private final CuratorFrameworkImpl      client;
    private Backgrounding                   backgrounding;
    private int                             version;
    private boolean                         compress;

    SetDataBuilderImpl(CuratorFrameworkImpl client)
    {
        this.client = client;
        backgrounding = new Backgrounding();
        version = -1;
        compress = false;
    }

    TransactionSetDataBuilder   asTransactionSetDataBuilder(final CuratorTransactionImpl curatorTransaction, final CuratorMultiTransactionRecord transaction)
    {
        return new TransactionSetDataBuilder()
        {
            @Override
            public CuratorTransactionBridge forPath(String path, byte[] data) throws Exception
            {
                String      fixedPath = client.fixForNamespace(path);
                transaction.add(Op.setData(fixedPath, data, version), OperationType.SET_DATA, path);
                return curatorTransaction;
            }

            @Override
            public CuratorTransactionBridge forPath(String path) throws Exception
            {
                return forPath(path, client.getDefaultData());
            }

            @Override
            public PathAndBytesable<CuratorTransactionBridge> withVersion(int version)
            {
                SetDataBuilderImpl.this.withVersion(version);
                return this;
            }
        };
    }

    @Override
    public SetDataBackgroundVersionable compressed()
    {
        compress = true;
        return new SetDataBackgroundVersionable()
        {
            @Override
            public PathAndBytesable<Stat> inBackground()
            {
                return SetDataBuilderImpl.this.inBackground();
            }

            @Override
            public PathAndBytesable<Stat> inBackground(Object context)
            {
                return SetDataBuilderImpl.this.inBackground(context);
            }

            @Override
            public PathAndBytesable<Stat> inBackground(BackgroundCallback callback)
            {
                return SetDataBuilderImpl.this.inBackground(callback);
            }

            @Override
            public PathAndBytesable<Stat> inBackground(BackgroundCallback callback, Executor executor)
            {
                return SetDataBuilderImpl.this.inBackground(callback, executor);
            }

            @Override
            public Stat forPath(String path, byte[] data) throws Exception
            {
                return SetDataBuilderImpl.this.forPath(path, data);
            }

            @Override
            public Stat forPath(String path) throws Exception
            {
                return SetDataBuilderImpl.this.forPath(path);
            }

            @Override
            public BackgroundPathAndBytesable<Stat> withVersion(int version)
            {
                return SetDataBuilderImpl.this.withVersion(version);
            }
        };
    }

    @Override
    public BackgroundPathAndBytesable<Stat> withVersion(int version)
    {
        this.version = version;
        return this;
    }

    @Override
    public PathAndBytesable<Stat> inBackground(BackgroundCallback callback)
    {
        backgrounding = new Backgrounding(callback);
        return this;
    }

    @Override
    public PathAndBytesable<Stat> inBackground()
    {
        backgrounding = new Backgrounding(true);
        return this;
    }

    @Override
    public PathAndBytesable<Stat> inBackground(Object context)
    {
        backgrounding = new Backgrounding(context);
        return this;
    }

    @Override
    public PathAndBytesable<Stat> inBackground(BackgroundCallback callback, Executor executor)
    {
        backgrounding = new Backgrounding(client, callback, executor);
        return this;
    }

    @Override
    public void performBackgroundOperation(final OperationAndData<PathAndBytes> operationAndData) throws Exception
    {
        final TimeTrace   trace = client.getZookeeperClient().startTracer("SetDataBuilderImpl-Background");
        client.getZooKeeper().setData
        (
            operationAndData.getData().getPath(),
            operationAndData.getData().getData(),
            version,
            new AsyncCallback.StatCallback()
            {
                @SuppressWarnings({"unchecked"})
                @Override
                public void processResult(int rc, String path, Object ctx, Stat stat)
                {
                    trace.commit();
                    CuratorEvent event = new CuratorEventImpl(client, CuratorEventType.SET_DATA, rc, path, null, ctx, stat, null, null, null, null);
                    client.processBackgroundOperation(operationAndData, event);
                }
            },
            backgrounding.getContext()
        );
    }

    @Override
    public Stat forPath(String path) throws Exception
    {
        return forPath(path, client.getDefaultData());
    }

    @Override
    public Stat forPath(String path, byte[] data) throws Exception
    {
        if ( compress )
        {
            data = client.getCompressionProvider().compress(path, data);
        }

        path = client.fixForNamespace(path);

        Stat        resultStat = null;
        if ( backgrounding.inBackground()  )
        {
            client.processBackgroundOperation(new OperationAndData<PathAndBytes>(this, new PathAndBytes(path, data), backgrounding.getCallback(), null), null);
        }
        else
        {
            resultStat = pathInForeground(path, data);
        }
        return resultStat;
    }

    int getVersion()
    {
        return version;
    }

    private Stat pathInForeground(final String path, final byte[] data) throws Exception
    {
        TimeTrace   trace = client.getZookeeperClient().startTracer("SetDataBuilderImpl-Foreground");
        Stat        resultStat = RetryLoop.callWithRetry
        (
            client.getZookeeperClient(),
            new Callable<Stat>()
            {
                @Override
                public Stat call() throws Exception
                {
                    return client.getZooKeeper().setData(path, data, version);
                }
            }
        );
        trace.commit();
        return resultStat;
    }
}

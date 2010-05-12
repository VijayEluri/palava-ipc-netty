/**
 * Copyright 2010 CosmoCode GmbH
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

package de.cosmocode.palava.ipc.netty;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Properties;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import com.google.inject.Inject;
import com.google.inject.name.Named;

import de.cosmocode.palava.core.Registry;
import de.cosmocode.palava.core.event.PostFrameworkStart;
import de.cosmocode.palava.core.event.PreFrameworkStop;
import de.cosmocode.palava.core.lifecycle.Disposable;
import de.cosmocode.palava.core.lifecycle.Initializable;
import de.cosmocode.palava.core.lifecycle.LifecycleException;
import de.cosmocode.palava.jmx.MBeanService;

/**
 * A service which handles a client/server protocol on a specific
 * port using netty. 
 *
 * @since 1.0
 * @author Willi Schoenborn
 */
final class Netty implements NettyMBean, 
    Initializable, PostFrameworkStart, PreFrameworkStop, Disposable {

    private static final Logger LOG = LoggerFactory.getLogger(Netty.class);
    
    private String name = "netty";
    
    private final Registry registry;
    
    private final MBeanService mBeanService;
    
    private int workerCount = Runtime.getRuntime().availableProcessors() * 2;
    
    private final ChannelFactory factory;
    
    private final ChannelPipelineFactory pipelineFactory;
    
    private ChannelGroup group = new DefaultChannelGroup();
    
    private final InetSocketAddress address;
    
    private Map<Object, Object> options = Maps.newHashMap();
    
    private long shutdownTimeout = 30;
    
    private TimeUnit shutdownTimeoutUnit = TimeUnit.SECONDS;
    
    @Inject
    public Netty(
        Registry registry,
        MBeanService mBeanService,
        @Boss ExecutorService boss,
        @Worker ExecutorService worker,
        ChannelPipelineFactory pipelineFactory,
        @Named(NettyConfig.ADDRESS) InetSocketAddress address) {
        
        this.registry = Preconditions.checkNotNull(registry, "Registry");
        this.mBeanService = Preconditions.checkNotNull(mBeanService, "MBeanService");
        Preconditions.checkNotNull(boss, "Boss");
        Preconditions.checkNotNull(worker, "Worker");
        LOG.trace("Using worker count {}", workerCount);
        this.factory = new NioServerSocketChannelFactory(boss, worker, workerCount);
        this.pipelineFactory = Preconditions.checkNotNull(pipelineFactory, "PipelineFactory");
        this.address = Preconditions.checkNotNull(address, "Address");
    }
    
    @Inject(optional = true)
    void setName(@Named(NettyConfig.NAME) String name) {
        this.name = Preconditions.checkNotNull(name, "Name");
    }
    
    @Inject(optional = true)
    void setWorkerCount(@Named(NettyConfig.WORKER_COUNT) int workerCount) {
        this.workerCount = workerCount;
    }
    
    @Inject(optional = true)
    void setGroupName(@Named(NettyConfig.GROUP_NAME) String groupName) {
        this.group = new DefaultChannelGroup(Preconditions.checkNotNull(groupName, "GroupName"));
    }
    
    @Inject(optional = true)
    void setOptions(@Named(NettyConfig.OPTIONS) Properties options) {
        this.options = Preconditions.checkNotNull(options, "Options");
    }
    
    @Inject(optional = true)
    void setShutdownTimeout(@Named(NettyConfig.SHUTDOWN_TIMEOUT) long shutdownTimeout) {
        this.shutdownTimeout = shutdownTimeout;
    }
    
    @Inject(optional = true)
    void setShutdownTimeoutUnit(@Named(NettyConfig.SHUTDOWN_TIMEOUT_UNIT) TimeUnit shutdownTimeoutUnit) {
        this.shutdownTimeoutUnit = Preconditions.checkNotNull(shutdownTimeoutUnit, "ShutdownTimeoutUnit");
    }
    
    @Override
    public void initialize() throws LifecycleException {
        registry.register(PostFrameworkStart.class, this);
        registry.register(PreFrameworkStop.class, this);
        mBeanService.register(this, "name", name);
    }
    
    @Override
    public void eventPostFrameworkStart() {
        final ServerBootstrap bootstrap = new ServerBootstrap(factory);
        
        for (Entry<Object, Object> entry : options.entrySet()) {
            LOG.trace("Setting option {}", entry);
            bootstrap.setOption(entry.getKey().toString(), entry.getValue());
        }
        
        bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
            
            @Override
            public ChannelPipeline getPipeline() throws Exception {
                final ChannelPipeline pipeline = pipelineFactory.getPipeline();
                
                pipeline.addFirst("channel-add-handler", new SimpleChannelHandler() {

                    @Override
                    public void channelOpen(ChannelHandlerContext context, ChannelStateEvent event) throws Exception {
                        final Channel currentChannel = context.getChannel();
                        LOG.info("Adding {} to group", currentChannel);
                        group.add(currentChannel);
                    }
                    
                });
                
                return pipeline;
            }
            
        });
        
        LOG.trace("Binding {} to {}", bootstrap, address);
        final Channel channel = bootstrap.bind(address);

        LOG.info("Adding {} to group", channel);
        group.add(channel);
    }
    
    @Override
    public int getOpenConnections() {
        return group.size();
    }
    
    @Override
    public void eventPreFrameworkStop() {
        LOG.info("Waiting {} {} for connections to close", shutdownTimeout, shutdownTimeoutUnit.name().toLowerCase());
        group.close().awaitUninterruptibly(shutdownTimeout, shutdownTimeoutUnit);
    }
    
    @Override
    public void dispose() throws LifecycleException {
        try {
            mBeanService.unregister(this, "name", name);
        } finally {
            factory.releaseExternalResources();
            registry.remove(this);
        }
    }
    
    @Override
    public String toString() {
        return String.format("%s [%s]", Netty.class.getSimpleName(), name);
    }

}
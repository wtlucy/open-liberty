/*******************************************************************************
 * Copyright (c) 2005, 2006 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package io.openliberty.netty.channel.internal;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import com.ibm.websphere.ras.Tr;
import com.ibm.websphere.ras.TraceComponent;
import com.ibm.ws.channelfw.internal.ConnectionDescriptorImpl;
import com.ibm.ws.ffdc.FFDCFilter;
import com.ibm.ws.ffdc.FFDCSelfIntrospectable;
import io.openliberty.netty.channel.internal.ConnectionManager;
import io.openliberty.netty.channel.internal.SimpleSync;
import io.openliberty.netty.channel.internal.SocketIOChannel;
import io.openliberty.netty.channel.internal.TCPChannel;
import io.openliberty.netty.channel.internal.TCPChannelConfiguration;
import io.openliberty.netty.channel.internal.TCPChannelMessageConstants;
import io.openliberty.netty.channel.internal.TCPConnLink;
import io.openliberty.netty.channel.internal.TCPProxyConnLink;
import io.openliberty.netty.channel.internal.TCPProxyResponse;
import io.openliberty.netty.channel.internal.TCPReadRequestContextImpl;
import io.openliberty.netty.channel.internal.TCPWriteRequestContextImpl;
import com.ibm.wsspi.channelfw.ConnectionDescriptor;
import com.ibm.wsspi.channelfw.ConnectionLink;
import com.ibm.wsspi.channelfw.OutboundConnectionLink;
import com.ibm.wsspi.channelfw.VirtualConnection;
import com.ibm.wsspi.tcpchannel.SSLConnectionContext;
import com.ibm.wsspi.tcpchannel.TCPConnectRequestContext;
import com.ibm.wsspi.tcpchannel.TCPConnectionContext;
import com.ibm.wsspi.tcpchannel.TCPReadRequestContext;
import com.ibm.wsspi.tcpchannel.TCPWriteRequestContext;

import io.netty.channel.Channel;

/**
 * TCP channel's connection link object.
 *
 */
public class TCPConnLink extends TCPProxyConnLink implements ConnectionLink, OutboundConnectionLink, TCPConnectionContext, FFDCSelfIntrospectable {
    private static final TraceComponent tc = Tr.register(TCPConnLink.class, TCPChannelMessageConstants.TCP_TRACE_NAME, TCPChannelMessageConstants.TCP_BUNDLE);

    private final TCPChannelConfiguration config;
    private TCPChannel tcpChannel = null;
    private int numReads = 0;
    private int numWrites = 0;
    private TCPReadRequestContextImpl reader;
    private TCPWriteRequestContextImpl writer;

    // Encapsulation of socket used for this request.
    private SocketIOChannel socketIOChannel;
    /** Lock used during sync connect calls */
    private SimpleSync syncObject = null;
    /** Possible exception during a sync connect */
    private IOException syncError = null;

    private TCPProxyResponse proxy = null;

    private boolean callCompleteLocal = false;
    private boolean closed = false;

    private int inUseIndex = 0;

    private Channel channel;

    public void setNettyChannel(Channel chan) {
        channel = chan;
    }

    /**
     * Constructor.
     *
     * @param vc
     * @param channel
     * @param cfg
     * @param index
     */
    public TCPConnLink(VirtualConnection vc, TCPChannel channel, TCPChannelConfiguration cfg, int index) {
        if (TraceComponent.isAnyTracingEnabled() && tc.isEntryEnabled()) {
            Tr.entry(tc, "TCPConnLink");
        }
        init(vc);

        this.inUseIndex = index;
        this.tcpChannel = channel;
        this.config = cfg;

        this.reader = channel.createReadInterface(this);
        this.writer = channel.createWriteInterface(this);

        if (TraceComponent.isAnyTracingEnabled() && tc.isEntryEnabled()) {
            Tr.exit(tc, "TCPConnLink");
        }
    }

    /*
     * @see
     * com.ibm.wsspi.channelfw.ConnectionReadyCallback#ready(com.ibm.wsspi.channelfw
     * .VirtualConnection)
     */
    @Override
    public void ready(VirtualConnection inVC) {
        // This should not be called because the TCPConnLink is always
        // ready since it is the first in the chain.
        if (TraceComponent.isAnyTracingEnabled() && tc.isEventEnabled()) {
            Tr.event(tc, "Invalid call to ready: " + inVC);
        }
    }

    /*
     * @see com.ibm.wsspi.channelfw.ConnectionLink#getChannelAccessor()
     */
    @Override
    public Object getChannelAccessor() {
        return this;
    }

    /**
     * Access the channel that owns this connection link.
     *
     * @return TCPChannel
     */
    public TCPChannel getTCPChannel() {
        return this.tcpChannel;
    }

    /*
     * @see com.ibm.wsspi.tcpchannel.TCPConnectionContext#getReadInterface()
     */
    @Override
    public TCPReadRequestContext getReadInterface() {
        return this.reader;
    }

    /*
     * @see com.ibm.wsspi.tcpchannel.TCPConnectionContext#getWriteInterface()
     */
    @Override
    public TCPWriteRequestContext getWriteInterface() {
        return this.writer;
    }

    protected TCPReadRequestContextImpl getTCPReadConnLink() {
        return this.reader;
    }

    protected TCPWriteRequestContextImpl getTCPWriteConnLink() {
        return this.writer;
    }

    /*
     * @see
     * com.ibm.wsspi.channelfw.OutboundConnectionLink#connect(java.lang.Object)
     */
    @Override
    public void connect(Object context) throws Exception {
        if (TraceComponent.isAnyTracingEnabled() && tc.isEntryEnabled()) {
            Tr.entry(tc, "connect");
        }

        this.syncObject = new SimpleSync();

        // reset proxy response object
        if (this.proxy != null) {
            this.proxy.setIsProxyResponseValid(false);
        }

        this.syncError = null;
        connectCommon((TCPConnectRequestContext) context);

        if (this.syncError != null) {
            throw this.syncError;
        }
        if (TraceComponent.isAnyTracingEnabled() && tc.isEntryEnabled()) {
            Tr.exit(tc, "connect");
        }
    }

    /*
     * @see
     * com.ibm.wsspi.channelfw.OutboundConnectionLink#connectAsynch(java.lang.
     * Object)
     */
    @Override
    public void connectAsynch(Object context) {
        if (TraceComponent.isAnyTracingEnabled() && tc.isEntryEnabled()) {
            Tr.entry(tc, "connectAsynch");
        }

        this.syncObject = null;

        // reset proxy response object
        if (this.proxy != null) {
            this.proxy.setIsProxyResponseValid(false);
        }

        connectCommon((TCPConnectRequestContext) context);

        if (TraceComponent.isAnyTracingEnabled() && tc.isEntryEnabled()) {
            Tr.exit(tc, "connectAsynch");
        }
    }

    protected void connectCommon(TCPConnectRequestContext context) {
        ConnectionManager connMgr = getTCPChannel().getConnMgr();
        TCPConnectRequestContext connectContext = context;
        SocketIOChannel ioSocket = null;

        // see if there is already a connection. If so, close it first
        // to allow reconnect
//        if (this.socketIOChannel != null) {
//            this.socketIOChannel.close();
//            this.socketIOChannel = null;
//        }

        try {
            this.callCompleteLocal = false;
            ioSocket = connMgr.getConnection(connectContext, this, this.syncObject);

            if (this.callCompleteLocal) {
                connectComplete(ioSocket);
            }

        } catch (IOException e) {
            if (TraceComponent.isAnyTracingEnabled() && tc.isEventEnabled())
                Tr.event(tc, "SocketChannel connect failed, local: " + connectContext.getLocalAddress() + " remote: " + connectContext.getRemoteAddress() + " ioe=" + e);
            connectFailed(e);
        }
    }

    @SuppressWarnings("unchecked")
    protected void connectComplete(SocketIOChannel socket) throws IOException {

        if (TraceComponent.isAnyTracingEnabled() && tc.isEntryEnabled()) {
            Tr.entry(tc, "connectComplete");
        }

        setSocketIOChannel(socket);

        Socket s = socket.getSocket();
        InetAddress remote = s.getInetAddress();
        InetAddress local = s.getLocalAddress();

        ConnectionDescriptor cd = getVirtualConnection().getConnectionDescriptor();

        if (cd != null) {
            cd.setAddrs(remote, local);
        } else {
            ConnectionDescriptorImpl cdi = new ConnectionDescriptorImpl(remote, local);
            getVirtualConnection().setConnectionDescriptor(cdi);
        }
        if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
            Tr.debug(tc, "Connection Complete for: " + getVirtualConnection().getConnectionDescriptor());
        }

        getVirtualConnection().attemptToSetFileChannelCapable(VirtualConnection.FILE_CHANNEL_CAPABLE_ENABLED);

        socket.connectActions();

        /*
         * Note if this a forward proxy connect then the
         * following code performs a handshake(read and write)
         * with the proxy server before completing the connect
         */

        // is this a forward proxy connect
        Object forwardProxyConnectObj = getVirtualConnection().getStateMap().get(FORWARD_PROXY_CONNECT);
        // see if forward proxy SSL tunneling is enabled
        if (null != forwardProxyConnectObj) {

            if (this.proxy == null) {
                this.proxy = new TCPProxyResponse(this);
            }

            boolean rc = false;
            try {
                // protect against users putting incorrect data into the statemap
                rc = this.proxy.setForwardProxyBuffers((Map<Object, Object>) forwardProxyConnectObj);
            } catch (ClassCastException cce) {
                FFDCFilter.processException(cce, getClass().getName() + ".connectComplete", "300");
                if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
                    Tr.debug(tc, "Incorrect forward proxy setup: " + cce);
                }
                connectFailed(new IOException(cce.getMessage()));
                if (TraceComponent.isAnyTracingEnabled() && tc.isEntryEnabled()) {
                    Tr.exit(tc, "connectComplete");
                }
                return;
            }

            if (rc) {
                // handshake, write the forward proxy buffers
                this.proxy.writeAndShake();
            }
        } else {
            // forward proxy tunneling is not set
            if (this.syncObject == null) {
                // async connect, so call ready method
                getApplicationCallback().ready(getVirtualConnection());
            }
            // else return the synchronous connect to the user
        }

        if (TraceComponent.isAnyTracingEnabled() && tc.isEntryEnabled()) {
            Tr.exit(tc, "connectComplete");
        }
    }

    /**
     * Query whether this connect is asynchronous or not.
     *
     * @return boolean
     */
    @Override
    protected boolean isAsyncConnect() {
        return (null == this.syncObject);
    }

    /**
     * Query if an error has occurred.
     *
     * @return boolean
     */
    @Override
    protected boolean isSyncError() {
        return (null != this.syncError);
    }

    protected void setCallCompleteLocal(boolean newValue) {
        this.callCompleteLocal = newValue;
    }

    @Override
    protected void connectFailed(IOException e) {
        if (TraceComponent.isAnyTracingEnabled() && tc.isEntryEnabled()) {
            Tr.entry(tc, "connectFailed");
        }
        // see if there is a connection. If so, close it first to allow reconnect
//        if (this.socketIOChannel != null) {
//            this.socketIOChannel.close();
//            this.socketIOChannel = null;
//        }
        if (isAsyncConnect()) {
            // if we are async connect, do the call on this thread
            // close the connection, as the above channels will do also.
            close(getVirtualConnection(), e);
        } else {
            // if sync connect, set the exception to be thrown to this exception.
            this.syncError = e;
        }

        if (TraceComponent.isAnyTracingEnabled() && tc.isEntryEnabled()) {
            Tr.exit(tc, "connectFailed");
        }
    }

    protected void setSocketIOChannel(SocketIOChannel s) {
        this.socketIOChannel = s;
    }

    /**
     * Returns the SocketIOChannel associated with this request
     *
     * @return SocketIOChannel
     */
    public SocketIOChannel getSocketIOChannel() {
        return this.socketIOChannel;
    }

    /**
     * Access the channel configuration for this connection link.
     *
     * @return TCPChannelConfiguration
     */
    public TCPChannelConfiguration getConfig() {
        return this.config;
    }

    /*
     * @see com.ibm.wsspi.tcpchannel.TCPConnectionContext#getRemoteAddress()
     */
    @Override
    public InetAddress getRemoteAddress() {
        return ((InetSocketAddress) channel.remoteAddress()).getAddress();
//        return this.socketIOChannel.getSocket().getInetAddress();
    }

    /*
     * @see com.ibm.wsspi.tcpchannel.TCPConnectionContext#getRemotePort()
     */
    @Override
    public int getRemotePort() {
        return ((InetSocketAddress) channel.remoteAddress()).getPort();
//        return this.socketIOChannel.getSocket().getPort();
    }

    /*
     * @see com.ibm.wsspi.tcpchannel.TCPConnectionContext#getLocalAddress()
     */
    @Override
    public InetAddress getLocalAddress() {
        return ((InetSocketAddress) channel.localAddress()).getAddress();
//        return this.socketIOChannel.getSocket().getLocalAddress();
    }

    /*
     * @see com.ibm.wsspi.tcpchannel.TCPConnectionContext#getLocalPort()
     */
    @Override
    public int getLocalPort() {
        return ((InetSocketAddress) channel.localAddress()).getPort();
//        return this.socketIOChannel.getSocket().getLocalPort();
    }

    /*
     * @see com.ibm.wsspi.tcpchannel.TCPConnectionContext#getSSLContext()
     */
    @Override
    public SSLConnectionContext getSSLContext() {
        // This TCPConnectionContext does not support SSL so return null.
        return null;
    }

    /**
     * Query the number of reads performed on this connection.
     *
     * @return int
     */
    protected int getNumReads() {
        return this.numReads;
    }

    /**
     * Query the number of writes performed on this connection.
     *
     * @return int
     */
    protected int getNumWrites() {
        return this.numWrites;
    }

    /**
     * Increase the number of reads performed by one.
     */
    public void incrementNumReads() {
        this.numReads++;
    }

    /**
     * Increase the number of writes performed by one.
     */
    public void incrementNumWrites() {
        this.numWrites++;
    }

    /*
     * @see
     * com.ibm.wsspi.channelfw.base.OutboundConnectorLink#close(com.ibm.wsspi.
     * channelfw.VirtualConnection, java.lang.Exception)
     */
    @Override
    public void close(VirtualConnection inVC, Exception e) {
        if (TraceComponent.isAnyTracingEnabled() && tc.isEntryEnabled()) {
            Tr.entry(tc, "close(), " + this);
        }

        if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
            try {
                if (inVC != null) {
                    Tr.debug(tc, "Closing the connection: " + inVC.getConnectionDescriptor());
                }
            } catch (NullPointerException npe) {
                // ignore this race condition since it is only for debug
            }
        }

        // synchronize on this TCPConnlink to prevent duplicate closes from being
        // processed
        // this can happen when the channel shuts down and close is called from
        // elsewhere
        // doing a quick synch with a boolean will help channel stop run faster
        boolean processClose = true;
        synchronized (this) {
            if (this.closed) {
                processClose = false;
            }
            this.closed = true;
        }

        if (processClose) {
            super.close(inVC, e);
        }

        if (TraceComponent.isAnyTracingEnabled() && tc.isEntryEnabled()) {
            Tr.exit(tc, "close()");
        }
    }

    /**
     * Query whether this connection link is closed or not.
     *
     * @return boolean
     */
    public boolean isClosed() {
        return this.closed;
    }

    /*
     * @see
     * com.ibm.wsspi.channelfw.base.OutboundConnectorLink#destroy(java.lang.Exception
     * )
     */
    @Override
    public void destroy(Exception e) {
        if (TraceComponent.isAnyTracingEnabled() && tc.isEntryEnabled()) {
            if (e == null) {
                Tr.entry(tc, "destroy(null)");
            } else {
                Tr.entry(tc, "destroy(Exc) " + e.getMessage());
            }
        }

        if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled() && getVirtualConnection().getConnectionDescriptor() != null) {
            Tr.debug(tc, "Destroying the connection: " + getVirtualConnection().getConnectionDescriptor());
        }

        if (this.channel != null) {
            this.channel.close();
            this.tcpChannel.decrementConnectionCount();
        }

        // clearing references will help free up memory in case someone
        // above us doesn't release the reference to this connlink
        this.channel = null;
        this.reader = null;
        this.writer = null;

        super.destroy(e);
        this.tcpChannel.releaseConnectionLink(this, this.inUseIndex);

        if (TraceComponent.isAnyTracingEnabled() && tc.isEntryEnabled()) {
            Tr.exit(tc, "destroy()");
        }
    }

    /**
     * Introspect this object for FFDC output.
     *
     * @return List<String>
     */
    public List<String> introspect() {
        List<String> rc = new LinkedList<String>();
        String prefix = getClass().getSimpleName() + "@" + hashCode() + ": ";
        rc.add(prefix + "tcpChannel=" + this.tcpChannel);
        rc.add(prefix + "closed=" + this.closed);
        rc.add(prefix + "channel=" + this.channel);
//        if (null != this.socketIOChannel) {
//            rc.addAll(this.socketIOChannel.introspect());
//        }
        rc.add(prefix + "numReads=" + this.numReads);
        rc.add(prefix + "numWrites=" + this.numWrites);
        rc.add(prefix + "callCompleteLocal=" + this.callCompleteLocal);
        return rc;
    }

    /*
     * @see com.ibm.ws.ffdc.FFDCSelfIntrospectable#introspectSelf()
     */
    @Override
    public String[] introspectSelf() {
        List<String> rc = introspect();
        return rc.toArray(new String[rc.size()]);
    }

}

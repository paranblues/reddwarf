/*
 * Copyright 2007 Sun Microsystems, Inc. All rights reserved
 */

package com.sun.sgs.impl.nio;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AlreadyConnectedException;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ConnectionPendingException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.UnsupportedAddressTypeException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.sgs.nio.channels.AlreadyBoundException;
import com.sun.sgs.nio.channels.AsynchronousDatagramChannel;
import com.sun.sgs.nio.channels.ClosedAsynchronousChannelException;
import com.sun.sgs.nio.channels.CompletionHandler;
import com.sun.sgs.nio.channels.IoFuture;
import com.sun.sgs.nio.channels.MembershipKey;
import com.sun.sgs.nio.channels.ProtocolFamily;
import com.sun.sgs.nio.channels.ReadPendingException;
import com.sun.sgs.nio.channels.SocketOption;
import com.sun.sgs.nio.channels.StandardProtocolFamily;
import com.sun.sgs.nio.channels.StandardSocketOption;
import com.sun.sgs.nio.channels.WritePendingException;

import static java.nio.channels.SelectionKey.OP_CONNECT;
import static java.nio.channels.SelectionKey.OP_READ;
import static java.nio.channels.SelectionKey.OP_WRITE;

class AsyncDatagramChannelImpl
    extends AsynchronousDatagramChannel
    implements AsyncChannelImpl
{
    private static final Set<SocketOption> socketOptions;
    static {
        Set<? extends SocketOption> es = EnumSet.of(
            StandardSocketOption.SO_SNDBUF,
            StandardSocketOption.SO_RCVBUF,
            StandardSocketOption.SO_REUSEADDR,
            StandardSocketOption.SO_BROADCAST,
            StandardSocketOption.IP_TOS,
            StandardSocketOption.IP_MULTICAST_IF,
            StandardSocketOption.IP_MULTICAST_TTL,
            StandardSocketOption.IP_MULTICAST_LOOP);
        socketOptions = Collections.unmodifiableSet(es);
    }

    final AbstractAsyncChannelGroup channelGroup;
    final DatagramChannel channel;

    final AtomicReference<AsyncOp<?>> connectTask =
        new AtomicReference<AsyncOp<?>>();
    final AtomicReference<AsyncOp<?>> readTask =
        new AtomicReference<AsyncOp<?>>();
    final AtomicReference<AsyncOp<?>> writeTask =
        new AtomicReference<AsyncOp<?>>();

    AsyncDatagramChannelImpl(ProtocolFamily pf, AbstractAsyncChannelGroup group)
        throws IOException
    {
        super(group.provider());
        this.channelGroup = group;

        if (! ((pf == null) || (pf == StandardProtocolFamily.INET))) {
            throw new UnsupportedOperationException(
                "Only IPv4 datagrams are supported");
        }

        this.channel = group.selectorProvider().openDatagramChannel();
        group.registerChannel(this);
    }

    /** {@inheritDoc} */
    public DatagramChannel channel() {
        return channel;
    }

    /** {@inheritDoc} */
    public void selected(int ops) {
        AsyncOp<?> rtask = null;
        AsyncOp<?> wtask = null;

        if ((ops & OP_READ) != 0)
            rtask = readTask.getAndSet(null);

        if ((ops & OP_WRITE) != 0)
            wtask = writeTask.getAndSet(null);

        if (rtask != null)
            channelGroup.execute(rtask);
        if (wtask != null)
            channelGroup.execute(wtask);
    }

    /** {@inheritDoc} */
    public void setException(int ops, Throwable t) {
        // OP_CONNECT is never selected, but allow it to throw an exception
        // to help with close()

        AsyncOp<?> ctask = null;
        AsyncOp<?> rtask = null;
        AsyncOp<?> wtask = null;

        if ((ops & OP_CONNECT) != 0)
            ctask = connectTask.getAndSet(null);

        if ((ops & OP_READ) != 0)
            rtask = readTask.getAndSet(null);

        if ((ops & OP_WRITE) != 0)
            wtask = writeTask.getAndSet(null);

        if (ctask != null)
            channelGroup.setException(ctask, t);
        if (rtask != null)
            channelGroup.setException(rtask, t);
        if (wtask != null)
            channelGroup.setException(wtask, t);
    }

    private void checkClosedAsync() {
        if (! channel.isOpen())
            throw new ClosedAsynchronousChannelException();
    }

    private void checkConnected() {
        if (! channel.isConnected())
            throw new NotYetConnectedException();
    }

    /**
     * {@inheritDoc}
     */
    public boolean isOpen() {
        return channel.isOpen();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() throws IOException {
        try {
            channel.close();
        } finally {
            setException(OP_CONNECT | OP_READ | OP_WRITE,
                new AsynchronousCloseException());
            channelGroup.unregisterChannel(this);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AsynchronousDatagramChannel bind(SocketAddress local)
        throws IOException
    {
        final DatagramSocket socket = channel.socket();
        if (socket.isClosed())
            throw new ClosedChannelException();
        if (socket.isBound())
            throw new AlreadyBoundException();
        if ((local != null) && (!(local instanceof InetSocketAddress)))
            throw new UnsupportedAddressTypeException();

        socket.bind(local);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public SocketAddress getLocalAddress() throws IOException {
        return channel.socket().getLocalSocketAddress();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AsyncDatagramChannelImpl setOption(SocketOption name, Object value)
        throws IOException
    {
        if (! (name instanceof StandardSocketOption))
            throw new IllegalArgumentException("Unsupported option " + name);

        if (value == null || !name.type().isAssignableFrom(value.getClass()))
            throw new IllegalArgumentException("Bad parameter for " + name);

        StandardSocketOption stdOpt = (StandardSocketOption) name;
        final DatagramSocket socket = channel.socket();
        switch (stdOpt) {
        case SO_SNDBUF:
            socket.setSendBufferSize(((Integer)value).intValue());
            break;

        case SO_RCVBUF:
            socket.setReceiveBufferSize(((Integer)value).intValue());
            break;

        case SO_REUSEADDR:
            socket.setReuseAddress(((Boolean)value).booleanValue());
            break;

        case SO_BROADCAST:
            socket.setBroadcast(((Boolean)value).booleanValue());
            break;

        case IP_TOS:
            socket.setTrafficClass(((Integer)value).intValue());
            break;

        case IP_MULTICAST_IF: {
            MulticastSocket msocket = (MulticastSocket) socket;
            msocket.setNetworkInterface((NetworkInterface)value);
            break;
        }

        case IP_MULTICAST_TTL: {
            MulticastSocket msocket = (MulticastSocket) socket;
            msocket.setTimeToLive(((Integer)value).intValue());
            break;
        }

        case IP_MULTICAST_LOOP: {
            MulticastSocket msocket = (MulticastSocket) socket;
            msocket.setLoopbackMode(((Boolean)value).booleanValue());
            break;
        }

        default:
            throw new IllegalArgumentException("Unsupported option " + name);
        }
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public Object getOption(SocketOption name) throws IOException {
        if (! (name instanceof StandardSocketOption))
            throw new IllegalArgumentException("Unsupported option " + name);

        StandardSocketOption stdOpt = (StandardSocketOption) name;
        final DatagramSocket socket = channel.socket();
        switch (stdOpt) {
        case SO_SNDBUF:
            return socket.getSendBufferSize();

        case SO_RCVBUF:
            return socket.getReceiveBufferSize();

        case SO_REUSEADDR:
            return socket.getReuseAddress();

        case SO_BROADCAST:
            return socket.getBroadcast();

        case IP_TOS:
            return socket.getTrafficClass();

        case IP_MULTICAST_IF: {
            MulticastSocket msocket = (MulticastSocket) socket;
            return msocket.getNetworkInterface();
        }

        case IP_MULTICAST_TTL: {
            MulticastSocket msocket = (MulticastSocket) socket;
            return msocket.getTimeToLive();
        }

        case IP_MULTICAST_LOOP: {
            // TODO should we reverse the value of this IP_MULTICAST_LOOP?
            MulticastSocket msocket = (MulticastSocket) socket;
            return msocket.getLoopbackMode();
        }

        default:
            break;
        }
        throw new IllegalArgumentException("Unsupported option " + name);
    }

    /**
     * {@inheritDoc}
     */
    public Set<SocketOption> options() {
        return socketOptions;
    }

    /**
     * {@inheritDoc}
     */
    public MembershipKey join(InetAddress group, NetworkInterface interf)
        throws IOException
    {
        // TODO
        throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public MembershipKey join(InetAddress group, NetworkInterface interf,
        InetAddress source) throws IOException
    {
        // TODO
        throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SocketAddress getConnectedAddress() throws IOException
    {
        return channel.socket().getRemoteSocketAddress();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <A> IoFuture<Void, A> connect(
            final SocketAddress remote,
            A attachment,
            CompletionHandler<Void, ? super A> handler)
    {
        if (channel.isConnected())
            throw new AlreadyConnectedException();

        AsyncOp<Void> task = AsyncOp.create(attachment, handler,
            new Callable<Void>() {
                public Void call() throws IOException {
                    channel.connect(remote);
                    return null;
                }});

        if (! connectTask.compareAndSet(null, task))
            throw new ConnectionPendingException();

        channelGroup.execute(task);
        return AttachedFuture.wrap(task, attachment);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <A> IoFuture<Void, A> disconnect(A attachment,
        CompletionHandler<Void, ? super A> handler)
    {
        checkClosedAsync();

        AsyncOp<Void> task = AsyncOp.create(attachment, handler,
            new Callable<Void>() {
                public Void call() throws IOException {
                    channel.disconnect();
                    return null;
                }});

        channelGroup.execute(task);
        return AttachedFuture.wrap(task, attachment);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isReadPending() {
        return readTask.get() != null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isWritePending() {
        return writeTask.get() != null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <A> IoFuture<SocketAddress, A> receive(
            final ByteBuffer dst,
            long timeout,
            TimeUnit unit,
            A attachment,
            CompletionHandler<SocketAddress, ? super A> handler)
    {
        checkClosedAsync();

        if (timeout < 0)
            throw new IllegalArgumentException("Negative timeout");

        AsyncOp<SocketAddress> task = AsyncOp.create(attachment, handler,
            new Callable<SocketAddress>() {
                public SocketAddress call() throws IOException {
                    return channel.receive(dst);
                }});

        if (! readTask.compareAndSet(null, task))
            throw new ReadPendingException();

        channelGroup.awaitReady(this, OP_READ, timeout, unit);
        return AttachedFuture.wrap(task, attachment);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <A> IoFuture<Integer, A> send(
            final ByteBuffer src,
            final SocketAddress target,
            long timeout, 
            TimeUnit unit, 
            A attachment,
            CompletionHandler<Integer, ? super A> handler)
    {
        checkClosedAsync();

        if (timeout < 0)
            throw new IllegalArgumentException("Negative timeout");

        AsyncOp<Integer> task = AsyncOp.create(attachment, handler,
            new Callable<Integer>() {
                public Integer call() throws IOException {
                    return channel.send(src, target);
                }});

        if (! writeTask.compareAndSet(null, task))
            throw new WritePendingException();

        channelGroup.awaitReady(this, OP_WRITE, timeout, unit);
        return AttachedFuture.wrap(task, attachment);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <A> IoFuture<Integer, A> read(
            final ByteBuffer dst,
            long timeout,
            TimeUnit unit,
            A attachment,
            CompletionHandler<Integer, ? super A> handler)
    {
        checkConnected();

        if (timeout < 0)
            throw new IllegalArgumentException("Negative timeout");

        AsyncOp<Integer> task = AsyncOp.create(attachment, handler,
            new Callable<Integer>() {
                public Integer call() throws IOException {
                    return channel.read(dst);
                }});

        if (! readTask.compareAndSet(null, task))
            throw new ReadPendingException();

        channelGroup.awaitReady(this, OP_READ, timeout, unit);
        return AttachedFuture.wrap(task, attachment);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <A> IoFuture<Integer, A> write(
            final ByteBuffer src, 
            long timeout,
            TimeUnit unit, 
            A attachment,
          CompletionHandler<Integer, ? super A> handler)
    {
        checkConnected();

        if (timeout < 0)
            throw new IllegalArgumentException("Negative timeout");

        AsyncOp<Integer> task = AsyncOp.create(attachment, handler,
            new Callable<Integer>() {
                public Integer call() throws IOException {
                    return channel.write(src);
                }});

        if (! writeTask.compareAndSet(null, task))
            throw new WritePendingException();

        channelGroup.awaitReady(this, OP_WRITE, timeout, unit);
        return AttachedFuture.wrap(task, attachment);
    }
}

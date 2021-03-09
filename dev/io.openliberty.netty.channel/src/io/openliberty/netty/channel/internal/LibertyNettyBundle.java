/*******************************************************************************
 * Copyright (c) 2021 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package io.openliberty.netty.channel.internal;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.osgi.framework.ServiceReference;
import org.osgi.service.component.ComponentConstants;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;

import com.ibm.websphere.channelfw.osgi.CHFWBundle;
import com.ibm.websphere.ras.Tr;
import com.ibm.websphere.ras.TraceComponent;
import com.ibm.ws.channelfw.internal.ConnectionDescriptorImpl;
import com.ibm.wsspi.bytebuffer.WsByteBuffer;
import com.ibm.wsspi.channelfw.ChannelFrameworkFactory;
import com.ibm.wsspi.channelfw.ConnectionDescriptor;
import com.ibm.wsspi.channelfw.VirtualConnection;
import com.ibm.wsspi.channelfw.VirtualConnectionFactory;
import com.ibm.wsspi.channelfw.exception.ChainException;
import com.ibm.wsspi.channelfw.exception.ChannelException;
import com.ibm.wsspi.kernel.service.utils.MetatypeUtils;
import com.ibm.wsspi.tcpchannel.TCPReadCompletedCallback;
import com.ibm.wsspi.tcpchannel.TCPReadRequestContext;
import com.ibm.wsspi.tcpchannel.TCPWriteRequestContext;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.CombinedChannelDuplexHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToByteEncoder;

@Component(configurationPid = "io.openliberty.netty.channel",
    immediate = true, 
    service = LibertyNettyBundle.class,
    property = { "service.vendor=IBM" })
public class LibertyNettyBundle implements ChannelTermination {

    private NioEventLoopGroup libertyGroup = null;
    private TCPChannel tcpChannel = null;

    @Reference(name = "cfwBundle")
    private CHFWBundle cfwBundle = null;

    @Activate
    protected void activate(Map<String, Object> properties) {
        System.out.println("LibertyNettyBundle.activate " + cfwBundle);
        // TODO: I'd rather use a factory name like "NettyChannel", but overring the tcpOptions type isn't
        // working because the service.ranking isn't respected. For now just replace the TCPChannel.
        // Another problem here is that this bundle must activate before the http transport bundle 
        // for this to work; for now I've made this bundle a dep of the http feature
        cfwBundle.getFramework().deregisterFactory("TCPChannel");
        cfwBundle.getFramework().registerFactory("TCPChannel", NettyChannelFactory.class);
    }

    @Deactivate
    protected void deactivate(Map<String, Object> properties, int reason) {
        shutdown();
    }

    @Modified
    protected void modified(Map<String, Object> config) {
    }
    

    public ServerBootstrap getBoostrap() {
        ServerBootstrap bootstrap = new ServerBootstrap();
        // use the executor service provided by Liberty for spawning threads
        libertyGroup = new NioEventLoopGroup(0, cfwBundle.getExecutorService());
        bootstrap.group(libertyGroup).channel(NioServerSocketChannel.class).childHandler(new ChannelInitializerImpl());
        return bootstrap;
    }

    public void setTCPChannel(TCPChannel chan) {
        tcpChannel = chan;
    }

//    public void bootstrap(CHFWBundle bundle) throws InterruptedException {
//        ServerBootstrap bootstrap = new ServerBootstrap();
//
//        // use the executor service provided by Liberty for spawning threads
//        libertyGroup = new NioEventLoopGroup(0, bundle.getExecutorService());
//
//        bootstrap.group(libertyGroup).channel(NioServerSocketChannel.class).childHandler(new ChannelInitializerImpl());
//        ChannelFuture future = bootstrap.bind(new InetSocketAddress(8080));
//        future.sync();
//        System.out.println("echo server available at: http://localhost:8080/");
//    }

    public void shutdown() {
        if (libertyGroup != null) {
            Future<?> f = libertyGroup.shutdownGracefully();
            try {
                f.get(29, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (ExecutionException e) {
                e.printStackTrace();
            } catch (TimeoutException e) {
                e.printStackTrace();
            }
        }
    }

    private final class ChannelInitializerImpl extends ChannelInitializer<Channel> {
        @Override
        protected void initChannel(Channel ch) throws Exception {
            ChannelPipeline pipeline = ch.pipeline();
            pipeline.addLast(new CombinedWsByteBufferCodec());
            pipeline.addLast(new LibertyChannelAdapter());
        }
    }

    private class LibertyChannelAdapter extends ChannelInboundHandlerAdapter {

        TCPBaseRequestContext req;
        TCPBaseRequestContext res;
        VirtualConnection vc;
        TCPConnLink link;
        TCPReadCompletedCallback cc;

        /**
         * Calls {@link ChannelHandlerContext#fireChannelRegistered()} to forward
         * to the next {@link ChannelInboundHandler} in the {@link ChannelPipeline}.
         *
         * Sub-classes may override this method to change behavior.
         */
        @Override
        public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
//            System.out.print("LibertyChannelAdapter channelRegistered");

            vc = tcpChannel.getVcFactory().createConnection();
            link = (TCPConnLink) tcpChannel.getConnectionLink(vc);
            req = link.getTCPReadConnLink();
            res = link.getTCPWriteConnLink();
            req.setChannel(ctx.channel());
            res.setChannel(ctx.channel());
            link.setNettyChannel(ctx.channel());
            ((TCPReadRequestContextImpl) req).setReadCompletedCallback(new NewConnectionInitialReadCallback(tcpChannel));
            ctx.fireChannelRegistered();
            ctx.channel().config().setAutoRead(true);
        }

        /**
         * Calls {@link ChannelHandlerContext#fireChannelUnregistered()} to forward
         * to the next {@link ChannelInboundHandler} in the {@link ChannelPipeline}.
         *
         * Sub-classes may override this method to change behavior.
         */
        @Override
        public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
//            System.out.print("LibertyChannelAdapter channelUnregistered");
            ctx.fireChannelUnregistered();
        }

        /**
         * Calls {@link ChannelHandlerContext#fireChannelActive()} to forward
         * to the next {@link ChannelInboundHandler} in the {@link ChannelPipeline}.
         *
         * Sub-classes may override this method to change behavior.
         */
        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
//            System.out.print("LibertyChannelAdapter channelActive");
            ctx.fireChannelActive();
        }

        /**
         * Calls {@link ChannelHandlerContext#fireChannelInactive()} to forward
         * to the next {@link ChannelInboundHandler} in the {@link ChannelPipeline}.
         *
         * Sub-classes may override this method to change behavior.
         */
        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
//            System.out.print("LibertyChannelAdapter channelInactive");
            ctx.fireChannelInactive();
        }

        /**
         * Calls {@link ChannelHandlerContext#fireChannelRead(Object)} to forward
         * to the next {@link ChannelInboundHandler} in the {@link ChannelPipeline}.
         *
         * Sub-classes may override this method to change behavior.
         */
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof ByteBuf) {
                ByteBuf in = ((ByteBuf) msg);
                System.out.print("LibertyChannelAdapter channelRead ByteBuf: " + in.toString(Charset.forName("utf-8")));
            } else if (msg instanceof WsByteBuffer) {
                WsByteBuffer in = ((WsByteBuffer) msg);
                WsByteBuffer target = req.getBuffer();
                System.out.println("channelRead " + in + "target " + target);

                if (!req.getTCPConnLink().isClosed()) {
                    // TODO: don't queue buffers like this!
                    if (target != null) {
                        in.flip();
                        while (target.hasRemaining()) {
                            // first pull off the queue
                            if (((TCPReadRequestContextImpl) req).getQueuedBuffers().peek() != null) {
                                WsByteBuffer q = ((TCPReadRequestContextImpl) req).getQueuedBuffers().peek();
                                while (q.hasRemaining() && target.remaining() > 0) {
                                    target.put(q.get()); 
                                }
                                if (!q.hasRemaining()) {
                                    ((TCPReadRequestContextImpl) req).getQueuedBuffers().remove();
                                }
                            }
                            else if (target.hasRemaining() && in.hasRemaining()) {
                                target.put(in.get()); 
                            } else {
                                break;
                            }
                        }
                        if (in.hasRemaining()) {
                            ((TCPReadRequestContextImpl) req).addBuffer(in);
                        }
                    } else {
                        req.setBuffer(in);
                    }
                }
//                System.out.print("LibertyChannelAdapter channelRead WsByteBuffer to be flushed: " + convertWsByteBufferToString(in));
            }
            ctx.fireChannelRead(msg);
        }

        /**
         * Calls {@link ChannelHandlerContext#fireChannelReadComplete()} to forward
         * to the next {@link ChannelInboundHandler} in the {@link ChannelPipeline}.
         *
         * Sub-classes may override this method to change behavior.
         */
        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
            System.out.print("LibertyChannelAdapter channelReadComplete this: " + this);
            // ask for the read callback each time we have new data - this will change as data is fed in
            cc = ((TCPReadRequestContextImpl) req).getReadCompletedCallback();
            if (cc != null && !req.getTCPConnLink().isClosed() && req.getBuffer() != null) {
                cc.complete(vc, (TCPReadRequestContextImpl) req);
            }
            ctx.fireChannelReadComplete();
        }

        /**
         * Calls {@link ChannelHandlerContext#fireUserEventTriggered(Object)} to forward
         * to the next {@link ChannelInboundHandler} in the {@link ChannelPipeline}.
         *
         * Sub-classes may override this method to change behavior.
         */
        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            System.out.print("LibertyChannelAdapter userEventTriggered");
            ctx.fireUserEventTriggered(evt);
        }

        /**
         * Calls {@link ChannelHandlerContext#fireChannelWritabilityChanged()} to forward
         * to the next {@link ChannelInboundHandler} in the {@link ChannelPipeline}.
         *
         * Sub-classes may override this method to change behavior.
         */
        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
            System.out.print("LibertyChannelAdapter channelWritabilityChanged");
            ctx.fireChannelWritabilityChanged();
        }

        /**
         * Calls {@link ChannelHandlerContext#fireExceptionCaught(Throwable)} to forward
         * to the next {@link ChannelHandler} in the {@link ChannelPipeline}.
         *
         * Sub-classes may override this method to change behavior.
         */
        @Override
        @SuppressWarnings("deprecation")
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            System.out.print("LibertyChannelAdapter exceptionCaught: " + cause);
            ctx.fireExceptionCaught(cause);
        }
    }

    private class CombinedWsByteBufferCodec extends CombinedChannelDuplexHandler<ByteBufToWsByteBufferDecoder, WsByteBufferToByteBufEncoder> {
        public CombinedWsByteBufferCodec() {
            super(new ByteBufToWsByteBufferDecoder(), new WsByteBufferToByteBufEncoder());
        }
    }

    private class ByteBufToWsByteBufferDecoder extends ByteToMessageDecoder {
        @Override
        public void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
//            System.out.println("ByteBufToWsByteBufferDecoder decode: " + in.toString(Charset.forName("utf-8")));
            ByteBuf temp = in.readBytes(in.readableBytes());
            out.add(ChannelFrameworkFactory.getBufferManager().wrap(temp.nioBuffer()).position(in.readerIndex()));
            temp.release();
        }

        @Override
        public void decodeLast(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
//            System.out.println("ByteBufToWsByteBufferDecoder decode: " + in.toString(Charset.forName("utf-8")));
//            out.add(ChannelFrameworkFactory.getBufferManager().wrap(in.readBytes(in.readableBytes()).nioBuffer()));
            decode(ctx, in, out);
        }
    }

    private class WsByteBufferToByteBufEncoder extends MessageToByteEncoder<WsByteBuffer> {
        @Override
        public void encode(ChannelHandlerContext ctx, WsByteBuffer msg, ByteBuf out) throws Exception {
//            System.out.println("WsByteBufferToByteBufEncoder wsbytebuf: " + convertWsByteBufferToString(msg));

            out.writeBytes(msg.getWrappedByteBuffer());
//            System.out.println("WsByteBufferToByteBufEncoder encode: " + out.toString(Charset.forName("utf-8")));

        }
    }

    /**
     * Utility method: converts any bytes in a WsByteBuffer to a UTF-8 String
     * @param wsbb
     * @return
     */
    private static String convertWsByteBufferToString(WsByteBuffer wsbb) {
        if (wsbb.remaining() > 0) {
            if (wsbb.hasArray()) {
                return new String(wsbb.array(), 0, wsbb.array().length, Charset.forName("utf-8"));
            } else {
                byte[] wsbbArray = new byte[wsbb.remaining()];
                wsbb.mark();
                int position = 0;
                while (wsbb.hasRemaining()) {
                    wsbbArray[position++] = wsbb.get();
                }
                wsbb.reset();
                return new String(wsbbArray, 0, wsbbArray.length, Charset.forName("utf-8"));
            }
        }
        return null;
    }

//    /**
//     * Processes a new connection by scheduling initial read.
//     *
//     * @see com.ibm.ws.tcpchannel.internal.TCPPort
//     *
//     * @param socket
//     */
//    public VirtualConnection processNewConnection(InetSocketAddress remoteAddress, InetSocketAddress localAddress) {
//        VirtualConnectionFactory vcf = new InboundVirtualConnectionFactoryImpl();
//        VirtualConnection vc;
//        try {
//            vc = vcf.createConnection();
//            ConnectionDescriptor cd = vc.getConnectionDescriptor();
//            InetAddress remote = remoteAddress.getAddress();
//            InetAddress local = localAddress.getAddress();
//            if (cd != null) {
//                cd.setAddrs(remote, local);
//            } else {
//                ConnectionDescriptorImpl cdi = new ConnectionDescriptorImpl(remote, local);
//                vc.setConnectionDescriptor(cdi);
//            }
//            vc.getStateMap().put("REMOTE_ADDRESS", remote.getHostAddress());
//            return vc;
//        } catch (ChannelException e) {
//            // TODO Auto-generated catch block
//            // Do you need FFDC here? Remember FFDC instrumentation and @FFDCIgnore
//            e.printStackTrace();
//        } catch (ChainException e) {
//            // TODO Auto-generated catch block
//            // Do you need FFDC here? Remember FFDC instrumentation and @FFDCIgnore
//            e.printStackTrace();
//        }
//        return null;
//    }

    @Override
    public void terminate() {
        shutdown();
    }

}

package com.example.nettydemo;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.io.UnsupportedEncodingException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

//基本的IO操作：bind connect read wirte依赖于底层网络传输所提供的原语。
//一个EventLoopGroup包含多个EventLoop 
//一个EventLoop在它的生命周期内只和一个Thread绑定。 
//所有由EventLoop处理的Io时间都将在它专有的Thread上被处理； 
//一个Channel在它的生命周期内只注册与一个EventLoop； 
//一个EventLoop可能会被分配给 一个或多个Channel

//ChannelFuture ：可以看做是将来要执行的操作的结果的占位符。不知道什么时候执行。但是肯定会执行。
//
//ChannelHandler接口：它充当了所有入站和出站数据的应用程序逻辑的容器。ChannelHandler的方法时由网络事件触发的。 
//ChannelPipeline接口：提供了ChannelHandler链的容器。并定义了用于在该链上传播入站和出站事件流的API。当Channel被创建时，
//它会自动分配到它专属的ChannelPipeline 
//ChannelHandlerContext：代表ChannelHandler和ChannelPipeline之间的绑定，它可以获取底层的Channel，
//但是它主要还是用于写出站数据。ctx.writeAndFlush(Unpooled.EMPTY_BUFFER)……..
//
//编码器和解码器： 
//通过Netty发送或者接受一个消息的时候，将会发生一次数据转换。入站消息会被解码；字节转换为另一种格式：（通常是一个java对象） 
//出站消息：相反的方向转换：它将从它当前格式被编码为字节；转换的因为是因为网络数据总是一系列的字节。所有编码器和解码器类都实现了
//ChannelOutboundHandler或者ChannelInboundHandler接口

//抽象类SimpleChannelInboundHandler来接收解码消息，并对数据应用业务逻辑。创建ChannelHandler只需要扩展基类
//SimpleChannelInboundHandler< T > T是要处理的消息的java类型。并且获取一个到ChannelHandlerContext的引用。
//这个引用作为输入参数传递给ChannelHandler的所有方法

//引导：两种类型的引导。一种用于客户端（BootStrap）另一种用于服务器（ServerBootStrap）绑定一个端口因为服务器必须要监听起来；


//Android端采用Netty实现长连接通信
//Netty框架Jar包使用
//Netty客户端的编写
//第一步创建NettyClient类
//第二步创建NettyClientHandler对接收到的消息进行处理
public class NettyClient {
    public static final int DISCONNECTION = 0;
    public static final int CONNECTING = 1;
    public static final int CONNECTED = 2;
    private EventLoopGroup group = null;
    private Bootstrap bootstrap = null;
    private ChannelFuture channelFuture = null; //ChannelFuture可以看做是将来要执行的操作的结果的占位符。不知道什么时候执行。但是肯定会执行。
    private static NettyClient nettyClient = null;
    //ArrayBlockingQueue一个由数组结构组成的有界阻塞队列
    private ArrayBlockingQueue<String> sendQueue = new ArrayBlockingQueue<String>(5000);
    
    private boolean sendFlag = true;
    private SendThread sendThread = new SendThread();
    private int connectState = DISCONNECTION;
    private boolean flag = true;

    public static NettyClient getInstance() {
        if (nettyClient == null) {
            nettyClient = new NettyClient();
        }
        return nettyClient;
    }

    private NettyClient() {
        init();
    }

    private void init() {
        setConnectState(DISCONNECTION); 
        bootstrap = new Bootstrap();
        group = new NioEventLoopGroup();
        bootstrap.group(group);
        bootstrap.channel(NioSocketChannel.class);
        bootstrap.option(ChannelOption.TCP_NODELAY, true);
        bootstrap.option(ChannelOption.SO_KEEPALIVE, true);
        bootstrap.handler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel socketChannel) throws Exception {
                ChannelPipeline pipeline = socketChannel.pipeline();
                //心跳包的添加
                //pipeline.addLast("idleStateHandler", new IdleStateHandler(60, 60, 0));
                //对消息格式进行验证（MessageDecoder为自定义的解析验证类因协议规定而定）
                pipeline.addLast("messageDecoder", new MessageDecoder());
                pipeline.addLast("clientHandler", new NettyClientHandler(nettyClient));
            }
        });
        startSendThread();
    }

    public void uninit() {
        stopSendThread();
        if (channelFuture != null) {
            channelFuture.channel().closeFuture();
            channelFuture.channel().close();
            channelFuture = null;
        }
        if (group != null) {
            group.shutdownGracefully();
            group = null;
            nettyClient = null;
            bootstrap = null;
        }
        setConnectState(DISCONNECTION);
        flag = false;
    }

    public void insertCmd(String cmd) {
        sendQueue.offer(cmd);
    }

    private void stopSendThread() {
        sendQueue.clear();
        sendFlag = false;
        sendThread.interrupt();
    }

    private void startSendThread() {
        sendQueue.clear();
        sendFlag = true;
        sendThread.start();
    }
    
    public void connect() {
        if (getConnectState() != CONNECTED){
            setConnectState(CONNECTING);
            ChannelFuture f = bootstrap.connect("ip", "port");
            f.addListener(listener);
        }
    }
    
    private ChannelFutureListener listener = new ChannelFutureListener() {
        @Override
        public void operationComplete(ChannelFuture future) throws Exception {
            if (future.isSuccess()) {
                channelFuture = future;
                setConnectState(CONNECTED);
            } else {
                setConnectState(DISCONNECTION);
                future.channel().eventLoop().schedule(new Runnable() {
                    @Override
                    public void run() {
                        if (flag) {
                            connect();
                        }
                    }
                }, 3L, TimeUnit.SECONDS);
            }
        }
    };

    public void setConnectState(int connectState) {
        this.connectState = connectState;
    }

    public int getConnectState() {
        return connectState;
    }

    /**
     * 发送消息的线程
     */
    private class SendThread extends Thread {
        @Override
        public void run() {
            while (sendFlag) {
                try {
                    String cmd = sendQueue.take();
                    if (channelFuture != null && cmd != null) { 
                        channelFuture.channel().writeAndFlush(ByteBufUtils.getSendByteBuf(cmd));
                    }
                } catch (InterruptedException | UnsupportedEncodingException e) {
                    sendThread.interrupt();
                }
            }
        }
    }
    
    
}
package net.allape.housedog.view;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;

import net.allape.housedog.util.BytesUtils;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

@SuppressLint("ViewConstructor")
public class MqRpcView extends androidx.appcompat.widget.AppCompatTextView {

    private static final String LOG_TAG = "MQView";

    private static final SimpleDateFormat FORMAT = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA);

    private static final int MAX_LINES = 10;

    // 日志
    private final List<String> commands = new ArrayList<>(MAX_LINES * 1000);
    // 上一次显示日志时commands的长度, 有且仅有长度更改时才更新TextView的内容
    private long lastPrintedCommandsSize = 0;

    // 是否运行中
    private boolean running = true;

    // 连接器
    private final ConnectionFactory factory;
    // 队列名称
    private final String queueName;
    // 监听回调
    private final MQListener MQListener;

    private final ConnectivityManager connectivityManager;

    /**
     * @param activity Context
     * @param host 地址
     * @param username 账号
     * @param password 密码
     * @param queueName 队列名称
     * @param MQListener 回调监听
     * @param autoConnect 是否自动连接
     */
    @RequiresApi(api = Build.VERSION_CODES.N)
    public MqRpcView(Activity activity,
                     String host, String username, String password, String queueName,
                     MQListener MQListener, boolean autoConnect) {
        super(activity);

        this.setMaxLines(MAX_LINES);

        // 网络监听
        connectivityManager = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);

        this.queueName = queueName;
        this.MQListener = MQListener;

        factory = new ConnectionFactory();
        factory.setHost(host);
        factory.setUsername(username);
        factory.setPassword(password);

        // 自动刷新text
        new Thread(() -> {
            while (true) {
                try {
                    //noinspection BusyWait
                    Thread.sleep(1000);
                    if (lastPrintedCommandsSize != commands.size()) {
                        List<String> printableCommands = (commands.size() > MAX_LINES ?
                                commands.subList(commands.size() - MAX_LINES, commands.size()) : commands);
                        StringBuilder text = new StringBuilder();
                        for (String command : printableCommands) {
                            text.append(command).append("\n");
                        }
                        activity.runOnUiThread(() -> setText(text.toString()));
                        lastPrintedCommandsSize = commands.size();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();

        // 网络监听
        connectivityManager.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                super.onAvailable(network);
                if (autoConnect) {
                    log("network comes alive");
                    start();
                }
            }
            @Override
            public void onLost(@NonNull Network network) {
                super.onLost(network);
                log("network lost");
                close();
            }
        });
    }

    /**
     * 开启队列
     */
    @RequiresApi(api = Build.VERSION_CODES.M)
    synchronized public void start() {
        // 网络条件允许的情况进行连接
        if (connectivityManager.getActiveNetwork() == null) {
            log("No Network");
            return;
        }
        new Thread(() -> {
            boolean reconnectRequired = false;
            try (Connection connection = factory.newConnection(); Channel channel = connection.createChannel()) {
                channel.queueDeclare(queueName, false, false, false, null);
                channel.queuePurge(queueName);
                channel.basicQos(1);

                log("MQ RPC listening with: " + queueName);
                Object monitor = new Object();
                DeliverCallback deliverCallback = (consumerTag, delivery) -> {
                    AMQP.BasicProperties replyProps = new AMQP.BasicProperties
                            .Builder()
                            .correlationId(delivery.getProperties().getCorrelationId())
                            .build();

                    byte[] response = { 0 };

                    try {
                        byte[] data = delivery.getBody();
                        if (data != null && data.length != 0) {
                            log("<< " + BytesUtils.toHex(data));
                            response = MQListener.onMessage(data);
                        }
                    } catch (RuntimeException e) {
                        e.printStackTrace();
                        Log.v(LOG_TAG, e.getMessage());
                    } finally {
                        if (replyProps.getCorrelationId() != null) {
                            channel.basicPublish("", delivery.getProperties().getReplyTo(), replyProps, response);
                            log(">> " + delivery.getProperties().getReplyTo() + " " + BytesUtils.toHex(response));
                        } else {
                            log(">> *no correlation id to response*");
                        }
                        channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                        if (running) {
                            synchronized (monitor) {
                                monitor.notify();
                            }
                        }
                    }
                };

                channel.basicConsume(queueName, false, deliverCallback, consumerTag -> {});

                running = true;

                while (running) {
                    if (!channel.isOpen()) {
                        reconnectRequired = true;
                        break;
                    }
                    synchronized (monitor) {
                        try {
                            monitor.wait();
                        } catch (InterruptedException e) {
                            Log.v(LOG_TAG, e.getMessage());
                            e.printStackTrace();
                        }
                    }
                }
            } catch (TimeoutException | IOException e) {
                Log.v(LOG_TAG, e.getMessage());
                e.printStackTrace();
                reconnectRequired = true;
            }
            if (reconnectRequired) {
                log("MQ reconnecting...");
                start();
            }
        }).start();
    }

    /**
     * 关闭连接和循环
     */
    public void close() {
        this.running = false;
    }

    /**
     * 打印日志到输入框
     * @param message 添加的消息
     */
    private void log(String message) {
        commands.add(FORMAT.format(new Date()) + ": " + message);
        if (commands.size() > MAX_LINES * 500) {
            List<String> newCommands = commands.subList(commands.size() - MAX_LINES * 2, commands.size());
            commands.clear();
            commands.addAll(newCommands);
        }
    }

    public interface MQListener {
        byte[] onMessage(byte[] message);
    }

}

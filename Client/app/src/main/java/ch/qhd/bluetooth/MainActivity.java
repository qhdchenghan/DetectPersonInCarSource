package ch.qhd.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import ch.qhd.facedetectcamera.R;

public class MainActivity extends AppCompatActivity {
    // 获取到蓝牙适配器
    private BluetoothAdapter mBluetoothAdapter;
    // 用来保存搜索到的设备信息
    private List<String> bluetoothDevices = new ArrayList<String>();
    // ListView组件
    private ListView lvDevices;
    private TextView textView;
    private ImageButton imageButton;
    // ListView的字符串数组适配器
    private ArrayAdapter<String> arrayAdapter;
    // UUID，蓝牙建立链接需要的
    private final UUID MY_UUID = UUID.fromString("db764ac8-4b08-7f25-aafe-59d04c27bae3");
    // 为其链接创建一个名称
    private final String NAME = "Bluetooth_Socket";
    // 选中发送数据的蓝牙设备，全局变量，否则连接在方法执行完就结束了
    private BluetoothDevice selectDevice;
    // 获取到选中设备的客户端串口，全局变量，否则连接在方法执行完就结束了
    private BluetoothSocket clientSocket;
    // 获取到向设备写的输出流，全局变量，否则连接在方法执行完就结束了
    private OutputStream os;
    // 服务端利用线程不断接受客户端信息
    private AcceptThread thread;
    //收到的心跳时间列表
    private List<Long> heartBeatList = new ArrayList<Long>();
    //收到的人脸信息列表，未检测到为0
    private List<Integer> faceList = new ArrayList<Integer>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        // 获取到蓝牙默认的适配器
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        // 获取到ListView组件
        lvDevices = (ListView) findViewById(R.id.lvDevices);
        textView = (TextView) findViewById(R.id.textView);
        imageButton = (ImageButton) findViewById(R.id.imageButton);

        // 为listview设置字符换数组适配器
        arrayAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, android.R.id.text1,
                bluetoothDevices);
        // 为listView绑定适配器
        lvDevices.setAdapter(arrayAdapter);
        // 用Set集合保持已绑定的设备
        Set<BluetoothDevice> devices = mBluetoothAdapter.getBondedDevices();
        if (devices.size() > 0) {
            for (BluetoothDevice bluetoothDevice : devices) {
                // 保存到arrayList集合中
                bluetoothDevices.add(bluetoothDevice.getName() + ":"
                        + bluetoothDevice.getAddress() + "\n");
            }
        }
        // 因为蓝牙搜索到设备和完成搜索都是通过广播来告诉其他应用的
        // 这里注册找到设备和完成搜索广播
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        registerReceiver(receiver, filter);
        filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        registerReceiver(receiver, filter);

        // 实例接收客户端传过来的数据线程
        thread = new AcceptThread();
        // 线程开始
        thread.start();
        //每5秒检测一次，判断蓝牙是否断开

        Runnable runnable = new Runnable() {
            @Override
            public void run() {

                if (heartBeatList.size() > 0) {
                    Date dt = new Date();
                    Long time = dt.getTime();//这就是距离1970年1月1日0点0分0秒的毫秒数
                    Long diff = time - heartBeatList.get(heartBeatList.size() - 1);
                    //上次收到心跳时间大于3秒前
                    if (diff > 3000) {
                        boolean found = false;
                        for (int k : faceList) {
                            if (k == 1) {
                                found = true;
                                break;
                            }
                        }
                        Message msg2 = new Message();
                        if (found) {
                            msg2.obj = "车内发现人员，请检查";
                            imageButton.setVisibility(View.VISIBLE);
                            PlaySound(1, 0);
                        } else {
                            msg2.obj = "车内未发现人员";
                        }
                        // 发送数据
                        Toast.makeText(MainActivity.this, (String) msg2.obj, Toast.LENGTH_SHORT).show();
                        heartBeatList.clear();
                        faceList.clear();
                    }
                }
                handlertime.postDelayed(this, 5000);
            }
        };
        handlertime.postDelayed(runnable, 5000);//每5秒执行一次runnable.
        InitSounds();
    }
    /**
     * 初始化声音
     */
    private SoundPool mSound;
    private HashMap<Integer, Integer> soundPoolMap;

    private void InitSounds() {
        // 第一个参数为同时播放数据流的最大个数，第二数据流类型，第三为声音质量
        mSound = new SoundPool(4, AudioManager.STREAM_MUSIC, 100);
        soundPoolMap = new HashMap<Integer, Integer>();
        soundPoolMap.put(1, mSound.load(this, R.raw.alarm, 1));
        //可以在后面继续put音效文件
    }

    /**
     * soundPool播放
     *
     * @param sound
     *            播放第一个
     * @param loop
     *            是否循环
     */
    private void PlaySound(int sound, int loop) {
        AudioManager mgr = (AudioManager) this
                .getSystemService(Context.AUDIO_SERVICE);
        // 获取系统声音的当前音量
        float currentVolume = mgr.getStreamVolume(AudioManager.STREAM_MUSIC);
        // 获取系统声音的最大音量
        float maxVolume = mgr.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        // 获取当前音量的百分比
        float volume = currentVolume / maxVolume;

        // 第一个参数是声效ID,第二个是左声道音量，第三个是右声道音量，第四个是流的优先级，最低为0，第五个是是否循环播放，第六个播放速度(1.0 =正常播放,范围0.5 - 2.0)
        mSound.play(soundPoolMap.get(sound), volume, volume, 1, loop, 1f);
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(false);
    }

    public void onClick_Search(View view) {
        setTitle("正在扫描...");
        // 点击搜索周边设备，如果正在搜索，则暂停搜索
        if (mBluetoothAdapter.isDiscovering()) {
            mBluetoothAdapter.cancelDiscovery();
        }
        mBluetoothAdapter.startDiscovery();
    }

    // 注册广播接收者
    private BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context arg0, Intent intent) {
            // 获取到广播的action
            String action = intent.getAction();
            // 判断广播是搜索到设备还是搜索完成
            if (action.equals(BluetoothDevice.ACTION_FOUND)) {
                // 找到设备后获取其设备
                BluetoothDevice device = intent
                        .getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                // 判断这个设备是否是之前已经绑定过了，如果是则不需要添加，在程序初始化的时候已经添加了
                if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
                    // 设备没有绑定过，则将其保持到arrayList集合中
                    bluetoothDevices.add(device.getName() + ":"
                            + device.getAddress() + "\n");
                    // 更新字符串数组适配器，将内容显示在listView中
                    arrayAdapter.notifyDataSetChanged();
                }
            } else if (action
                    .equals(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)) {
                setTitle("搜索完成");
            }
        }
    };

    // 创建handler，因为我们接收是采用线程来接收的，在线程中无法操作UI，所以需要handler
    private Handler handler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            // TODO Auto-generated method stub
            super.handleMessage(msg);
            // 通过msg传递过来的信息，吐司一下收到的信息
            textView.setText((String) msg.obj);
            // Toast.makeText(MainActivity.this, (String) msg.obj, 0).show();
        }
    };
    Handler handlertime = new Handler();

    // 服务端接收信息线程
    private class AcceptThread extends Thread {
        private BluetoothServerSocket serverSocket;// 服务端接口
        private BluetoothSocket socket;// 获取到客户端的接口
        private InputStream is;// 获取到输入流
        private OutputStream os;// 获取到输出流

        //构造函数
        public AcceptThread() {

            try {
                // 通过UUID监听请求，然后获取到对应的服务端接口
                serverSocket = mBluetoothAdapter.listenUsingRfcommWithServiceRecord(NAME, MY_UUID);
            } catch (Exception e) {
                // TODO: handle exception
                Message msg = new Message();
                // 发送一个String的数据，让他向上转型为obj类型
                msg.obj = "建立服务器失败";
                // 发送数据
                handler.sendMessage(msg);
            }
        }

        public void run() {
            try {
                // 接收其客户端的接口
                socket = serverSocket.accept();
            } catch (Exception e) {
                Message msg1 = new Message();
                // 发送一个String的数据，让他向上转型为obj类型
                msg1.obj = e.getMessage();
                // 发送数据
                handler.sendMessage(msg1);
            }
            while (true) {
                try {
                    // 获取到输入流
                    is = socket.getInputStream();
                    // 获取到输出流
                    os = socket.getOutputStream();
                    // 无线循环来接收数据
                    // 创建一个128字节的缓冲
                    byte[] buffer = new byte[12800];
                    // 每次读取128字节，并保存其读取的角标
                    int count = is.read(buffer);
                    // 创建Message类，向handler发送数据
                    Message msg = new Message();
                    // 发送一个String的数据，让他向上转型为obj类型
                    msg.obj = new String(buffer, 0, count, "utf-8");
                    // 发送数据
                    handler.sendMessage(msg);
                    if (msg.obj.toString().startsWith("心跳")) {
                        Date dt = new Date();
                        Long time = dt.getTime();//这就是距离1970年1月1日0点0分0秒的毫秒数
                        heartBeatList.add(time);
                        if (heartBeatList.size() > 10) {
                            heartBeatList.remove(0);
                        }
                    } else if (msg.obj.toString().startsWith("已发现人脸")) {
                        faceList.add(1);
                        if (faceList.size() > 20) {
                            faceList.remove(0);
                        }
                    } else if (msg.obj.toString().startsWith("没发现人脸")) {
                        faceList.add(0);
                        if (faceList.size() > 20) {
                            faceList.remove(0);
                        }
                    }
                } catch (Exception e) {
                    Message msg1 = new Message();
                    // 发送一个String的数据，让他向上转型为obj类型
                    msg1.obj = "蓝牙断开";
                    // 发送数据
                    handler.sendMessage(msg1);
                    try {
                        // 接收其客户端的接口
                        socket = serverSocket.accept();
                    } catch (Exception ee) {
                        Message msg2 = new Message();
                        // 发送一个String的数据，让他向上转型为obj类型
                        msg2.obj = ee.getMessage();
                        // 发送数据
                        handler.sendMessage(msg2);
                    }
                }
            }
        }
    }
}
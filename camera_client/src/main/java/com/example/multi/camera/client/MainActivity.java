package com.example.multi.camera.client;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.multi.camera.service.ICameraService;

public class MainActivity extends AppCompatActivity {
    private final String TAG = "MultiCameraClient";
    private ICameraService iCameraService;

    //views
    private ImageView mPreview;
    private TextView mPreviewIsOff;

    //handler
    private HandlerThread mCameraThread;
    private Handler mCameraHandler;

    // 当前获取的帧数
    private int currentIndex = 0;
    // 处理的间隔帧，可根据自己情况修改
    private static final int PROCESS_INTERVAL = 2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mPreview = findViewById(R.id.preview);
        mPreview.setVisibility(View.INVISIBLE);
        mPreviewIsOff = findViewById(R.id.preview_is_off);
        mPreviewIsOff.setVisibility(View.VISIBLE);

        findViewById(R.id.start_preview).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                //连接服务
                Intent intent = new Intent();
                intent.setAction("com.example.camera.aidl");
                intent.setPackage("com.example.multi.camera.service");
                bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
                Log.d(TAG, "bindService");
                //更新视图
                mPreview.setVisibility(View.VISIBLE);
                mPreviewIsOff.setVisibility(View.INVISIBLE);
            }
        });
        findViewById(R.id.stop_preview).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                //断开服务
                unbindService(mConnection);
                iCameraService = null;
                Log.d(TAG, "unbindService");

                //更新视图
                mPreview.setVisibility(View.INVISIBLE);
                mPreviewIsOff.setVisibility(View.VISIBLE);
            }
        });

        mCameraThread = new HandlerThread("CameraClientThread");
        mCameraThread.start();
        mCameraHandler = new Handler(mCameraThread.getLooper());
    }

    ServiceConnection mConnection = new ServiceConnection() {

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "onServiceDisconnected");
            iCameraService = null;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "onServiceConnected");
            iCameraService = ICameraService.Stub.asInterface(service);
            startCamera(mPreview.getWidth(), mPreview.getHeight());
            Log.d(TAG, "mPreview width:" + mPreview.getWidth() + ";mPreview height:" + mPreview.getHeight());
        }
    };

    //开始预览，处理预览数据
    private void startCamera(int width, int height) {
        //手机摄像头的图像数据来源于摄像头硬件的图像传感器，这个图像传感器被固定到手机上后默认的取景方向是手机横放时的方向.所以竖屏时宽高需要调整
        ImageReader imageReader = ImageReader.newInstance(height, width,
                ImageFormat.YUV_420_888, 2);
        imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader imageReader) {
                Log.d(TAG, "onImageAvailable");

                Image image = imageReader.acquireNextImage();

                int imageWidth = image.getWidth();
                int imageHeight = image.getHeight();
                Log.d(TAG, "imageWidth:" + imageWidth + ";imageHeight:" + imageHeight);

                byte[] data68 = ImageUtil.getBytesFromImageAsType(image, 2);

                if (currentIndex++ % PROCESS_INTERVAL == 0) {
                    int rgb[] = ImageUtil.decodeYUV420SP(data68, imageWidth, imageHeight);
                    Bitmap originalBitmap = Bitmap.createBitmap(rgb, 0, imageWidth,
                            imageWidth, imageHeight,
                            android.graphics.Bitmap.Config.ARGB_8888);
                    Matrix matrix = new Matrix();
                    // //手机摄像头的图像数据来源于摄像头硬件的图像传感器，这个图像传感器被固定到手机上后默认的取景方向是手机横放时的方向.所以竖屏时需要做旋转处理
                    matrix.postRotate(90);
                    final Bitmap previewBitmap = Bitmap.createBitmap(originalBitmap, 0, 0, originalBitmap.getWidth(), originalBitmap.getHeight(), matrix, false);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            mPreview.setImageBitmap(previewBitmap);
                        }
                    });
                    originalBitmap.recycle();
                }
                image.close();
            }
        }, mCameraHandler);
        try {
            Surface surface = imageReader.getSurface();
            iCameraService.onSurfaceShared(surface);
            Log.d(TAG, "share the surface.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onDestroy() {
        if (iCameraService != null) {
            unbindService(mConnection);
            Log.d(TAG, "unbindService");
        }
        super.onDestroy();
    }
}
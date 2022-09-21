package com.example.multi.camera.service;

import android.Manifest;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.util.Range;
import android.view.Surface;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;

import java.util.Arrays;

/**
 * Created by Xin Xiao on 2022/8/17
 */
public class CameraService extends Service {
    private final String TAG = "CameraService";
    private HandlerThread mCameraThread;
    private Handler mCameraHandler;

    //Camera2
    private CameraDevice mCameraDevice;
    private String mCameraId;//后置摄像头ID
    private CaptureRequest.Builder mPreviewBuilder;
    private CaptureRequest mCaptureRequest;
    private CameraCaptureSession mPreviewSession;
    private CameraCharacteristics characteristics;
    private Range<Integer>[] fpsRanges;

    private Surface mSurface;

    @Override
    public void onCreate() {
        super.onCreate();
        mCameraThread = new HandlerThread("CameraServerThread");
        mCameraThread.start();
        mCameraHandler = new Handler(mCameraThread.getLooper());
        setupCamera();//配置相机参数
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private final ICameraService.Stub binder = new ICameraService.Stub() {

        @Override
        public void onSurfaceShared(Surface surface) throws RemoteException {
            Log.d(TAG, "onSurfaceShared");
            mSurface = surface;
            openCamera(mCameraId);//打开相机
        }

    };

    @Override
    public boolean onUnbind(Intent intent) {
        stopCamera();//释放资源
        return super.onUnbind(intent);
    }

    /**
     * ******************************SetupCamera(配置Camera)*****************************************
     */
    private void setupCamera() {
        //获取摄像头的管理者CameraManager
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            //0表示后置摄像头,1表示前置摄像头
            mCameraId = manager.getCameraIdList()[0];

            characteristics = manager.getCameraCharacteristics(mCameraId);
            fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            Log.d(TAG, "fpsRanges: " + Arrays.toString(fpsRanges));
            //fpsRanges: [[15, 15], [20, 20], [24, 24], [5, 30], [30, 30]]
        } catch (Exception e) {
            e.printStackTrace();
            Log.d(TAG, "setupCamera failed: " + e.toString());
        }
    }

    /**
     * ******************************openCamera(打开Camera)*****************************************
     */
    private void openCamera(String CameraId) {
        //获取摄像头的管理者CameraManager
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        //检查权限
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Camera permissions were denied!");
                return;
            }
            //打开相机，第一个参数指示打开哪个摄像头，第二个参数stateCallback为相机的状态回调接口，第三个参数用来确定Callback在哪个线程执行，为null的话就在当前线程执行
            manager.openCamera(CameraId, mStateCallback, mCameraHandler);
            Log.d(TAG, "openCamera");
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }


    private CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            Log.d(TAG, "StateCallback:onOpened");
            mCameraDevice = camera;
            startPreview();
        }

        @Override
        public void onDisconnected(CameraDevice cameraDevice) {
            Log.d(TAG, "StateCallback:onDisconnected");
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(CameraDevice cameraDevice, int error) {
            Log.d(TAG, "StateCallback:onError:" + error);
            cameraDevice.close();
            mCameraDevice = null;
        }
    };

    /**
     * ******************************Camera2成功打开，开始预览(startPreview)*************************
     */
    public void startPreview() {
        Log.d(TAG, "startPreview");
        if (null == mCameraDevice) {
            return;
        }

        try {
            closePreviewSession();
            mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);//创建CaptureRequestBuilder，TEMPLATE_PREVIEW表示预览请求
            mPreviewBuilder.addTarget(mSurface);//设置Surface作为预览数据的显示界面

            //默认预览不开启闪光灯
            mPreviewBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
            //设置预览画面的帧率
            mPreviewBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRanges[0]);

            //创建相机捕获会话，第一个参数是捕获数据的输出Surface列表，第二个参数是CameraCaptureSession的状态回调接口，当它创建好后会回调onConfigured方法，第三个参数用来确定Callback在哪个线程执行，为null的话就在当前线程执行
            mCameraDevice.createCaptureSession(Arrays.asList(mSurface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    Log.d(TAG, "onConfigured");
                    try {
                        //创建捕获请求
                        mCaptureRequest = mPreviewBuilder.build();
                        mPreviewSession = session;
                        //不停的发送获取图像请求，完成连续预览
                        mPreviewSession.setRepeatingRequest(mCaptureRequest, null, mCameraHandler);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {

                }
            }, mCameraHandler);
        } catch (Exception e) {
            e.printStackTrace();
            Log.e(TAG, "startPreview failed:" + e.toString());
        }
    }

    //清除预览Session
    private void closePreviewSession() {
        if (mPreviewSession != null) {
            mPreviewSession.close();
            mPreviewSession = null;
        }
    }

    /**
     * **************************************清除操作************************************************
     */
    public void stopCamera() {
        try {
            if (mPreviewSession != null) {
                mPreviewSession.close();
                mPreviewSession = null;
            }

            if (mCameraDevice != null) {
                mCameraDevice.close();
                mCameraDevice = null;
            }

            if (mCameraHandler != null) {
                mCameraHandler.removeCallbacksAndMessages(null);
            }
        } catch (Exception e) {
            e.printStackTrace();
            Log.e(TAG, "stopCamera failed:" + e.toString());
        }
    }
}
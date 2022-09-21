package com.example.multi.camera.service;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private final String TAG = "MultiCameraServer";
    //权限申请
    private String[] mRequestPermissions = new String[]{
            Manifest.permission.CAMERA};
    private List<String> mUnauthorizedPermissionList = new ArrayList<>();
    private final int REQUEST_CODE = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initPermission();
    }

    //check and request permission
    private void initPermission() {
        mUnauthorizedPermissionList.clear();

        //check permission
        for (String mRequestPermission : mRequestPermissions) {
            if (ContextCompat.checkSelfPermission(this, mRequestPermission) != PackageManager.PERMISSION_GRANTED) {
                mUnauthorizedPermissionList.add(mRequestPermission);
            }
        }

        //request permission
        if (mUnauthorizedPermissionList.size() > 0) {
            String[] unauthorizedPermissionArray = mUnauthorizedPermissionList.toArray(new String[0]);
            ActivityCompat.requestPermissions(this, unauthorizedPermissionArray, REQUEST_CODE);
        } else {
            Log.d(TAG, "All permissions have been granted.");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean hasPermissionDenied = false;
        if (REQUEST_CODE == requestCode) {
            for (int grantResult : grantResults) {
                if (grantResult == PackageManager.PERMISSION_DENIED) {
                    hasPermissionDenied = true;
                    break;
                }
            }
            if (hasPermissionDenied) {
                Log.d(TAG, "Some permissions were denied!");
            } else {
                Log.d(TAG, "All permissions have been granted.");
            }
        }
    }
}
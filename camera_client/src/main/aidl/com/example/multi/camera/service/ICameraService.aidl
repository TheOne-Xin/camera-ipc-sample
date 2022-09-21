// ICameraService.aidl
package com.example.multi.camera.service;

// Declare any non-default types here with import statements

interface ICameraService {
    /**
     * The client sends the surface object to the server.
     */
    void onSurfaceShared(in Surface surface);
}
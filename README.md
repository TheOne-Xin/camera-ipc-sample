# 前言
提到 Android 进程间的通信方式，即使是 Android 客户端开发初学者，也能列举出来几种，无外乎：
1. bundle
2. 文件共享
3. AIDL（Binder）
4. Messenger
5. ContentProvider
6. Socket

然而都2022年了，本文如果只是介绍下以上的几种进程间通信的方式，就没什么意义了，也太对不起观众了，同时以上几种方式，也不能满足题目的需求：大数据，高效的跨进程传输。
有些同学可能会提到另外一种方式：共享内存（MemoryFile & SharedMemory），这种方式的确是可以满足题目的需求，不过共享内存的使用不是很简单，没有进程间同步机制，这个需要使用者自行处理，这样就加大了该方式的使用难度，下文会详细说明下用共享内存进行通信的难点。
# 1 使用场景
在介绍这种通信方式之前，先看下为什么需要进行跨进程的大数据的高效传输，有哪些场景需要进行跨进程的数据传输。
对于大部分的 app 开发同学，一般应用都是单进程的模式，并不需要进行跨进程的数据通信，即使有多进程的场景，一般数据量不会特别大，也不是持续性的，频繁性的。
那么在 Android 系统中，哪些数据是大量的，需要跨进程传递的，对 Android 图像系统比较了解的同学会想到屏幕上渲染的数据，对多媒体比较了解的同学会想到音视频数据，这些数据都有类似的特点：大数据量（高分辨率），持续性（高采样率，高刷新率）。
此处以一路30帧的720p的 camera NV21数据为例，1秒钟的数据量为：1280 * 720 * 3 / 2 * 30 = 41472000Byte = 39.5MB，对于这个数量级的数据，一次内存 copy 对系统资源都是很大的损耗，这也证明了为什么前面介绍的方式1，2，4，5，6的通信方式不适合大数据的传输，一种原因就是因为他们需要进行多次的内存 copy 操作，效率较低。方式3：AIDL（Binder）虽然只有一次 copy 操作，但是 Binder 对单次通信数据量有大小限制（默认< 1Mb），同时由于很多其他通信操作是共享Binder内存的，如果Binder通信过于频繁，是会拖慢应用的响应时间。
而方式7：共享内存理论是可以满足这个需求，不过我们来看下，如果要基于共享内存来实现数据的传输需要完成哪些事情。
# 2 基于共享内存的跨进程通信设计
假设目前有两个进程：进程A，进程B，进程A和进程B之间已经建立好了一块共享内存，两个进程都可以对该内存区域进行访问。目前进程A需要向进程B持续的传输大量数据，那么需要哪些步骤呢？
# 2.1 设计思路
![shared_memory_draft](https://github.com/TheOne-Xin/camera-ipc-sample/blob/master/iamges/shared_memory_draft.jpg)

1. step 1：进程A向共享内存写入一段数据。
2. step 2：进程B读取这段数据。
3. 进程A重复 step 1：再向共享内存写入一段数据。

以上1-2-1-2循环，这样就可以了吗？当然不会这么简单，这里面有一些同步的问题：
1. 进程A写入后，如何通知进程B读取。
2. 在进程B没有取走之前，进程A如果有新数据生成，怎么办？
3. 进程B取走数据后，如何通知进程A继续写入？

对于问题1，2，进程A需要完成写入后触发 step3 通知进程B可以读取，进程B完成读取后，触发 step4 通知进程A可以继续写入。
![shared_memory_revised](https://github.com/TheOne-Xin/camera-ipc-sample/blob/master/iamges/shared_memory_revised.jpg)

如果解决以上问题后，我们会发现其实已经实现了一个基础的生产者消费者模型。（对于问题2，又可以扩展出缓存，ping-pong buffer，3-buffer等等）。
而实现以上这套模型的成本应该说还是很高的，但是理论上完全可行，不过为了让事情更简单，是否有更简单的方法呢，是否有这样一个组件，在进程A和进程B直接建立一个管道，进程A只管写，写不下了，阻塞或者返回出错，有空间可以继续写了，通知进程A继续进行写，进程B只管读，读不到，阻塞或者返回出错，有数据了，通知进程B继续读，由这个管道处理同步通知这些事情，linux下有提供 pipe 这种通信方式，不过pipe需要多次内存 copy，也不适合大数据的传输，且 Android 系统并没有在应用层暴露这个 pipe 的接口。
![linux_pipe](https://github.com/TheOne-Xin/camera-ipc-sample/blob/master/iamges/linux_pipe.jpg)

对于 Android 系统，渲染，音视频等模块比较了解的同学应该会想到 Android 系统里面的 BufferQueue，那么先了解下 BufferQueue。
## 2.2 BufferQueue
对业务开发来说，无法接触到 BufferQueue，甚至不知道 BufferQueue 是什么东西。对系统来说，BufferQueue 是很重要的传递数据的组件，Android 显示系统依赖于 BufferQueue，只要显示内容到“屏幕”（此处指抽象的屏幕，有时候还可以包含编码器），就一定需要用到 BufferQueue，可以说在显示/播放器相关的领域中，BufferQueue 无处不在。即使直接调用 Opengl ES 来绘制，底层依然需要 BufferQueue 才能显示到屏幕上。
BufferQueue 是 Android 显示系统的核心，它的设计思想是生产者-消费者模型，只要往 BufferQueue 中填充数据，则认为是生产者，只要从 BufferQueue 中获取数据，则认为是消费者。有时候同一个类，在不同的场景下既可能是生产者也有可能是消费者。如 SurfaceFlinger，在合成并显示 UI 内容时，UI 元素作为生产者生产内容，SurfaceFlinger 作为消费者消费这些内容。而在截屏时，SurfaceFlinger 又作为生产者将当前合成显示的 UI 内容填充到另一个 BufferQueue，截屏应用此时作为消费者从 BufferQueue 中获取数据并生产截图。
![buffer_queue](https://github.com/TheOne-Xin/camera-ipc-sample/blob/master/iamges/buffer_queue.png)

同时使用 BufferQueue 的生产者和消费者往往处在不同的进程，BufferQueue 内部使用共享内存和 Binder 在不同的进程传递数据，减少数据拷贝提高效率。
“同时使用 BufferQueue 的生产者和消费者往往处在不同的进程，BufferQueue 内部使用共享内存和 Binder 在不同的进程传递数据，减少数据拷贝提高效率。”
通过这段可以明确 BufferQueue 是可以进行跨进程间通信的，而且 Android 显示系统是用 BufferQueue 来做数据传递，那么 BufferQueue 是一定可以用来做大数据的传输，而且性能应该是很高的，否则 Android 系统的显示也会卡顿，可以看出 BufferQueue 是 Android 系统中比较重要的组件。
那么我们是不是用 BufferQueue 就可以进行应用间的通信了呢，抱歉，BufferQueue 这个组件在 Android 应用层是没有暴露出来的，App 是无法使用的。（至少目前我还没有找到相关接口）
在实际应用中，除了直接使用 BuferQueue 外，更多的是使用 Surface/SurfaceTexture，其对 BufferQueue 做了包装，方便业务使用 BufferQueue。Surface 作为 BufferQueue 的生产者，SurfaceTexture 作为 BufferQueue 的消费者。
此处提到 Surface，那么 Android 应用是否可以使用它呢，抱歉，查看了下 Surface 暴露的接口，也未发现有可用的接口来实现进程间的通信。
难道 BuferQueue 这么好用的组件应用层就只能眼睁睁看着用不上吗？同时对 Android 系统设计也感觉有些奇怪，为什么这种可用于大数据传递的组件不对应用层暴露，可能是对于大部分App的业务来说，分多个进程，进程间又有这么大数据量交互的场景不多，所以没有暴露出相关的接口来。之前通过大量的搜索和文档阅读，接口类代码查阅，并没有发现应用层如何使用 BuferQueue 的介绍，网络上也没有人讨论过类似的方案。
那么是不是 BuferQueue 我们就完全用不了呢，如果用不了，那么可能就没有这篇文章了！！！！
**柳暗花明**：在研究 Camera Api2 相关接口时，一个类 ImageReader 引起了注意，这个类是基于 Surface 的封装，用于获取 Camera 的数据。既然有 reader 是不是有 writer 呢，不出所料 ImageWriter 也是存在的，这两个类都是在 Android 6.0（API level 23）加入的。
## 2.3 ImageReader & ImageWriter
下面我们看下如何基于 ImageReader、ImageWriter 实现一个消费者生产者模型，首先生产者和消费者处于两个进程：
消费者进程：ImageReader

```java
// step 1: 创建一个ImageReader
ImageReader imageReader = ImageReader.newInstance(width, height,ImageFormat.YUV_420_888, 2);
// step 2: 设置ImageReader回调
imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
    @Override
    public void onImageAvailable(ImageReader imageReader) {
        Image image = imageReader.acquireNextImage();
        Image.Plane[] planes = image.getPlanes();
        for (int i = 0; i < planes.length; i++) {
            ByteBuffer byteBuffer = planes[i].getBuffer();
            byte[] bytes = new byte[byteBuffer.capacity()];
            byteBuffer.get(bytes);
        }
        image.close();
    }
}, mCameraHandler);
```
生产者进程：ImagerWriter

```java
// step 1 : 获得ImageWriter对象
ImageWriter imageWriter = ?;
// step 2 : ImageWriter.dequeueInputImage、ImageWriter.queueInputImage写入需要传递的data数据
Image image = imageWriter.dequeueInputImage();
Image.Plane[] planes = image.getPlanes();
for (int i = 0; i < planes.length; i++) {
    ByteBuffer byteBuffer = planes[i].getBuffer();
    byteBuffer.put(data, 0, data.length);
}
imageWriter.queueInputImage(image);
```
通过以上示例代码，我们是不是可以实现一个生产者和消费者模型呢，当然不能，细心的同学应该会发现生产者示例中 step 1的 ImageWriter 不知道是如何来的？
这里面有一个重要的环节：ImageReader 和 ImageWriter 是如何关联起来的？
先看下 ImageWriter 类源码，看看如何创建一个 ImageWriter 对象：

```java
public class ImageWriter implements AutoCloseable {
	ImageWriter() {
		throw new RuntimeException("Stub!");
	}

	@NonNull
	public static ImageWriter newInstance(@NonNull Surface surface, int maxImages) {
		throw new RuntimeException("Stub!");
	}

	@NonNull
	public static ImageWriter newInstance(@NonNull Surface surface, int maxImages, int format) {
		throw new RuntimeException("Stub!");
	}
}
```
我们看到如果要创建 ImageWriter 一定需要 Surface 这个参数，回头在看下 ImageReader 类源码，如果仔细的浏览过源码的话，会发现 ImageReader 有一个 getSurface() 接口，那么是不是把 ImageReader 的 surface 传递给 ImageWriter 就可以建立关联呢，答案是肯定的，那么剩下的工作就是把 ImageReader 的 surface 从消费者进程传递到生产者进程里就好了，这里通过 AIDL 进行传递就可以了（[Android AIDL 使用教程](https://blog.csdn.net/hello_1995/article/details/122094512)）。
完成以上步骤，生成者进程就可以向 ImageWriter 中写入数据，消费者进程就可以通过 ImageReader 的回调收到这个数据了，通过实测这种方式传输 Camera NV21 数据，资源消耗非常低，可以满足 **大数据**，**高效** 的要求，同时实现又比较简单，不到100行代码就可以完成整个通信流程。
# 3 实现
按照以上介绍的方式，相信大家都可以实现一个高效的跨进程的消费者生产者模型。我基于该方式实现了一个多路 Camera 分发的 demo，供参考：[camera-ipc-sample](https://github.com/TheOne-Xin/camera-ipc-sample)。
该工程包含两个App：MultiCameraService、MultiCameraClient。
安装这两个 apk，手动给 MultiCameraService App 授予 Camera 访问权限，然后打开 MultiCameraClient App，点击预览开关按钮，正常情况下即可实现 Camera 预览。
有些同学会说这有什么啊，不就是 Camera 预览功能，注意这里面是在 MultiCameraService app 中打开的 Camera，而在 MultiCameraClient app 看到预览画面，Camera 的数据是通过跨进程的方式，从 MultiCameraService App 传递到 MultiCameraClient App 中的。如图：
![multi_camera_demo](https://github.com/TheOne-Xin/camera-ipc-sample/blob/master/iamges/multi_camera_demo.jpg)

# 4 总结
以上即是如何在 Android 实现跨进程大数据的高效传输，虽然该方案对于纯粹的手机 App 开发同学不一定有很大的帮助，但是目前有很多智能设备采用了 Android 系统，对 Camera，图形渲染都有很多不同于手机 App 的需求，在没有很好的跨进程传输方案的情况，有些项目只能把很多业务功能杂糅在一个 App 进程中，使模块承载的业务功能不是很清晰，有了这种方案，就可以更加优化项目模型架构的设计。

/*
 *  Copyright 2014 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

package org.appspot.apprtc;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.appspot.apprtc.AppRTCClient.SignalingParameters;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.ScreenBaseActivity;
import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerationAndroid;
import org.webrtc.CameraEnumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DataChannel;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.Logging;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnection.IceConnectionState;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpParameters;
import org.webrtc.RtpSender;
import org.webrtc.ScreenCapturer;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.StatsObserver;
import org.webrtc.StatsReport;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoRenderer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;
import org.webrtc.voiceengine.WebRtcAudioManager;
import org.webrtc.voiceengine.WebRtcAudioUtils;

import java.io.File;
import java.io.IOException;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cn.renyuzhuo.rlib.rlog;

/**
 * Peer connection client implementation.
 * <p/>
 * <p>All public methods are routed to local looper thread.
 * All PeerConnectionEvents callbacks are invoked from the same looper thread.
 * This class is a singleton.
 */
public class PeerConnectionClient {
    public static final String VIDEO_TRACK_ID = "ARDAMSv0";
    public static final String AUDIO_TRACK_ID = "ARDAMSa0";
    public static final String VIDEO_TRACK_TYPE = "video";
    private static final String TAG = "PCRTCClient";
    private static final String VIDEO_CODEC_VP8 = "VP8";
    private static final String VIDEO_CODEC_VP9 = "VP9";
    private static final String VIDEO_CODEC_H264 = "H264";
    private static final String AUDIO_CODEC_OPUS = "opus";
    private static final String AUDIO_CODEC_ISAC = "ISAC";
    private static final String VIDEO_CODEC_PARAM_START_BITRATE =
            "x-google-start-bitrate";
    private static final String AUDIO_CODEC_PARAM_BITRATE = "maxaveragebitrate";
    private static final String AUDIO_ECHO_CANCELLATION_CONSTRAINT = "googEchoCancellation";
    private static final String AUDIO_AUTO_GAIN_CONTROL_CONSTRAINT = "googAutoGainControl";
    private static final String AUDIO_HIGH_PASS_FILTER_CONSTRAINT = "googHighpassFilter";
    private static final String AUDIO_NOISE_SUPPRESSION_CONSTRAINT = "googNoiseSuppression";
    private static final String AUDIO_LEVEL_CONTROL_CONSTRAINT = "levelControl";
    private static final String DTLS_SRTP_KEY_AGREEMENT_CONSTRAINT = "DtlsSrtpKeyAgreement";
    private static final int HD_VIDEO_WIDTH = 1280;
    private static final int HD_VIDEO_HEIGHT = 720;
    private static final int MAX_VIDEO_WIDTH = 1280;
    private static final int MAX_VIDEO_HEIGHT = 1280;
    private static final int MAX_VIDEO_FPS = 30;
    private static final int BPS_IN_KBPS = 1000;

    private static final PeerConnectionClient instance = new PeerConnectionClient();
    private final PCObserver pcObserver = new PCObserver();
    private final SDPObserver sdpObserver = new SDPObserver();
    private final ScheduledExecutorService executor;

    private Context context;
    private PeerConnectionFactory factory;
    private PeerConnection peerConnection;
    PeerConnectionFactory.Options options = null;
    private AudioSource audioSource;
    private VideoSource videoSource;
    private boolean videoCallEnabled;
    private boolean preferIsac;
    private String preferredVideoCodec;
    private boolean videoCapturerStopped;
    private boolean isError;
    private Timer statsTimer;
    private VideoRenderer.Callbacks localRender;
    private VideoRenderer.Callbacks remoteRender;
    private SignalingParameters signalingParameters;
    private MediaConstraints pcConstraints;
    private int videoWidth;
    private int videoHeight;
    private int videoFps;
    private MediaConstraints audioConstraints;
    private ParcelFileDescriptor aecDumpFileDescriptor;
    private MediaConstraints sdpMediaConstraints;
    private PeerConnectionParameters peerConnectionParameters;
    // Queued remote ICE candidates are consumed only after both local and
    // remote descriptions are set. Similarly local ICE candidates are sent to
    // remote peer after both local and remote description are set.
    private LinkedList<IceCandidate> queuedRemoteCandidates;
    private PeerConnectionEvents events;
    private boolean isInitiator;
    private SessionDescription localSdp; // either offer or answer SDP
    private MediaStream mediaStream;
    private int numberOfCameras;
    private CameraVideoCapturer videoCapturer;
    // enableVideo is set to true if video should be rendered and sent.
    private boolean renderVideo;
    private VideoTrack localVideoTrack;
    private VideoTrack remoteVideoTrack;
    private RtpSender localVideoSender;
    // enableAudio is set to true if audio should be sent.
    private boolean enableAudio;
    private AudioTrack localAudioTrack;

    private static boolean screenOrCamera;
    private ScreenCapturer screenCapture;

    /**
     * Peer connection parameters.
     */
    public static class PeerConnectionParameters {
        public final boolean videoCallEnabled;
        public final boolean loopback;
        public final boolean tracing;
        public final boolean useCamera2;
        public final int videoWidth;
        public final int videoHeight;
        public final int videoFps;
        public final int videoMaxBitrate;
        public final String videoCodec;
        public final boolean videoCodecHwAcceleration;
        public final boolean captureToTexture;
        public final int audioStartBitrate;
        public final String audioCodec;
        public final boolean noAudioProcessing;
        public final boolean aecDump;
        public final boolean useOpenSLES;
        public final boolean disableBuiltInAEC;
        public final boolean disableBuiltInAGC;
        public final boolean disableBuiltInNS;
        public final boolean enableLevelControl;

        public PeerConnectionParameters(
                boolean videoCallEnabled, boolean loopback, boolean tracing, boolean useCamera2,
                int videoWidth, int videoHeight, int videoFps,
                int videoMaxBitrate, String videoCodec, boolean videoCodecHwAcceleration,
                boolean captureToTexture, int audioStartBitrate, String audioCodec,
                boolean noAudioProcessing, boolean aecDump, boolean useOpenSLES,
                boolean disableBuiltInAEC, boolean disableBuiltInAGC, boolean disableBuiltInNS,
                boolean enableLevelControl, boolean screenOrCamera) {
            rlog.d("初始化，包含很多属性");
            this.videoCallEnabled = videoCallEnabled;
            this.useCamera2 = useCamera2;
            this.loopback = loopback;
            this.tracing = tracing;
            this.videoWidth = videoWidth;
            this.videoHeight = videoHeight;
            this.videoFps = videoFps;
            this.videoMaxBitrate = videoMaxBitrate;
            this.videoCodec = videoCodec;
            this.videoCodecHwAcceleration = videoCodecHwAcceleration;
            this.captureToTexture = captureToTexture;
            this.audioStartBitrate = audioStartBitrate;
            this.audioCodec = audioCodec;
            this.noAudioProcessing = noAudioProcessing;
            this.aecDump = aecDump;
            this.useOpenSLES = useOpenSLES;
            this.disableBuiltInAEC = disableBuiltInAEC;
            this.disableBuiltInAGC = disableBuiltInAGC;
            this.disableBuiltInNS = disableBuiltInNS;
            this.enableLevelControl = enableLevelControl;
            PeerConnectionClient.screenOrCamera = screenOrCamera;
        }
    }

    /**
     * Peer connection events.
     */
    public interface PeerConnectionEvents {
        /**
         * Callback fired once local SDP is created and set.
         */
        void onLocalDescription(final SessionDescription sdp);

        /**
         * Callback fired once local Ice candidate is generated.
         */
        void onIceCandidate(final IceCandidate candidate);

        /**
         * Callback fired once local ICE candidates are removed.
         */
        void onIceCandidatesRemoved(final IceCandidate[] candidates);

        /**
         * Callback fired once connection is established (IceConnectionState is
         * CONNECTED).
         */
        void onIceConnected();

        /**
         * Callback fired once connection is closed (IceConnectionState is
         * DISCONNECTED).
         */
        void onIceDisconnected();

        /**
         * Callback fired once peer connection is closed.
         */
        void onPeerConnectionClosed();

        /**
         * Callback fired once peer connection statistics is ready.
         */
        void onPeerConnectionStatsReady(final StatsReport[] reports);

        /**
         * Callback fired once peer connection error happened.
         */
        void onPeerConnectionError(final String description);
    }

    private PeerConnectionClient() {
        // Executor thread is started once in private ctor and is used for all
        // peer connection API calls to ensure new peer connection factory is
        // created on the same thread as previously destroyed factory.
        executor = Executors.newSingleThreadScheduledExecutor();
    }

    public static PeerConnectionClient getInstance() {
        rlog.d("获取PeerConnectionClient单例");
        return instance;
    }

    public void setPeerConnectionFactoryOptions(PeerConnectionFactory.Options options) {
        this.options = options;
    }

    public void createPeerConnectionFactory(
            final Context context,
            final PeerConnectionParameters peerConnectionParameters,
            final PeerConnectionEvents events) {
        rlog.d("PeerConnectionClient入口");
        this.peerConnectionParameters = peerConnectionParameters;
        this.events = events;
        videoCallEnabled = peerConnectionParameters.videoCallEnabled;
        // Reset variables to initial states.
        this.context = null;
        factory = null;
        peerConnection = null;
        preferIsac = false;
        videoCapturerStopped = false;
        isError = false;
        queuedRemoteCandidates = null;
        localSdp = null; // either offer or answer SDP
        mediaStream = null;
        videoCapturer = null;
        renderVideo = true;
        localVideoTrack = null;
        remoteVideoTrack = null;
        localVideoSender = null;
        enableAudio = true;
        localAudioTrack = null;
        statsTimer = new Timer();
        rlog.d();
        rlog.d("创建谅解初始化参数结束，都设置为null");

        executor.execute(new Runnable() {
            @Override
            public void run() {
                createPeerConnectionFactoryInternal(context);
            }
        });
    }

    public void createPeerConnection(
            final EglBase.Context renderEGLContext,
            final VideoRenderer.Callbacks localRender,
            final VideoRenderer.Callbacks remoteRender,
            final SignalingParameters signalingParameters) {
        if (peerConnectionParameters == null) {
            Log.e(TAG, "Creating peer connection without initializing factory.");
            return;
        }
        this.localRender = localRender;
        this.remoteRender = remoteRender;
        this.signalingParameters = signalingParameters;
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    createMediaConstraintsInternal();
                    createPeerConnectionInternal(renderEGLContext);
                } catch (Exception e) {
                    reportError("Failed to create peer connection: " + e.getMessage());
                    throw e;
                }
            }
        });
    }

    public void close() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                closeInternal();
            }
        });
    }

    public boolean isVideoCallEnabled() {
        return videoCallEnabled;
    }

    private void createPeerConnectionFactoryInternal(Context context) {
        PeerConnectionFactory.initializeInternalTracer();
        rlog.d("初始化");
        if (peerConnectionParameters.tracing) {
            rlog.d("文件保存：" + Environment.getExternalStorageDirectory().getAbsolutePath() + File.separator + "webrtc-trace.txt");
            PeerConnectionFactory.startInternalTracingCapture(
                    Environment.getExternalStorageDirectory().getAbsolutePath() + File.separator
                            + "webrtc-trace.txt");
        } else {
            rlog.d("文件不保存");
        }
        Log.d(TAG, "Create peer connection factory. Use video: " + peerConnectionParameters.videoCallEnabled);
        isError = false;

        // Initialize field trials.
        PeerConnectionFactory.initializeFieldTrials("");

        // Check preferred video codec.
        preferredVideoCodec = VIDEO_CODEC_VP8;
        if (videoCallEnabled && peerConnectionParameters.videoCodec != null) {
            if (peerConnectionParameters.videoCodec.equals(VIDEO_CODEC_VP9)) {
                preferredVideoCodec = VIDEO_CODEC_VP9;
            } else if (peerConnectionParameters.videoCodec.equals(VIDEO_CODEC_H264)) {
                preferredVideoCodec = VIDEO_CODEC_H264;
            }
        }
        Log.d(TAG, "Pereferred video codec: " + preferredVideoCodec);

        // Check if ISAC is used by default.
        preferIsac = peerConnectionParameters.audioCodec != null
                && peerConnectionParameters.audioCodec.equals(AUDIO_CODEC_ISAC);
        rlog.d("preferIsac:" + preferIsac);

        // Enable/disable OpenSL ES playback.
        if (!peerConnectionParameters.useOpenSLES) {
            Log.d(TAG, "Disable OpenSL ES audio even if device supports it");
            WebRtcAudioManager.setBlacklistDeviceForOpenSLESUsage(true /* enable */);
            rlog.d("OpenSL ES:true");
        } else {
            Log.d(TAG, "Allow OpenSL ES audio if device supports it");
            WebRtcAudioManager.setBlacklistDeviceForOpenSLESUsage(false);
            rlog.d("OpenSL ES:false");
        }

        if (peerConnectionParameters.disableBuiltInAEC) {
            Log.d(TAG, "Disable built-in AEC even if device supports it");
            WebRtcAudioUtils.setWebRtcBasedAcousticEchoCanceler(true);
            rlog.d("BasedAcousticEchoCanceler:true");
        } else {
            Log.d(TAG, "Enable built-in AEC if device supports it");
            WebRtcAudioUtils.setWebRtcBasedAcousticEchoCanceler(false);
            rlog.d("BasedAcousticEchoCanceler:false");
        }

        if (peerConnectionParameters.disableBuiltInAGC) {
            Log.d(TAG, "Disable built-in AGC even if device supports it");
            WebRtcAudioUtils.setWebRtcBasedAutomaticGainControl(true);
            rlog.d("BasedAutomaticGainControl:true");
        } else {
            Log.d(TAG, "Enable built-in AGC if device supports it");
            WebRtcAudioUtils.setWebRtcBasedAutomaticGainControl(false);
            rlog.d("BasedAutomaticGainControl:false");
        }

        if (peerConnectionParameters.disableBuiltInNS) {
            Log.d(TAG, "Disable built-in NS even if device supports it");
            WebRtcAudioUtils.setWebRtcBasedNoiseSuppressor(true);
            rlog.d("BasedNoiseSuppressor:true");
        } else {
            Log.d(TAG, "Enable built-in NS if device supports it");
            WebRtcAudioUtils.setWebRtcBasedNoiseSuppressor(false);
            rlog.d("BasedNoiseSuppressor:false");
        }

        // Create peer connection factory.
        rlog.d("创建Peer Connection Factory");
        if (!PeerConnectionFactory.initializeAndroidGlobals(context, true, true,
                peerConnectionParameters.videoCodecHwAcceleration)) {
            events.onPeerConnectionError("Failed to initializeAndroidGlobals");
            rlog.d("初始化失败");
        }
        if (options != null) {
            Log.d(TAG, "Factory networkIgnoreMask option: " + options.networkIgnoreMask);
            rlog.d("Factory networkIgnoreMask option: " + options.networkIgnoreMask);
        }
        this.context = context;
        factory = new PeerConnectionFactory(options);
        Log.d(TAG, "Peer connection factory created.");
        rlog.d("Peer Connection Factory创建成功,调用本地native方法");
    }

    private void createMediaConstraintsInternal() {
        rlog.d();
        // Create peer connection constraints.
        pcConstraints = new MediaConstraints();
        rlog.d("创建media约束");
        // Enable DTLS for normal calls and disable for loopback calls.
        if (peerConnectionParameters.loopback) {
            rlog.d("自己呼叫自己，DTLS=false，数据包传输层安全协议");
            pcConstraints.optional.add(
                    new MediaConstraints.KeyValuePair(DTLS_SRTP_KEY_AGREEMENT_CONSTRAINT, "false"));
        } else {
            rlog.d("与别人交流，DTLS=true，数据包传输层安全协议");
            pcConstraints.optional.add(
                    new MediaConstraints.KeyValuePair(DTLS_SRTP_KEY_AGREEMENT_CONSTRAINT, "true"));
        }

        // Check if there is a camera on device and disable video call if not.
        numberOfCameras = CameraEnumerationAndroid.getDeviceCount();
        if (numberOfCameras == 0) {
            rlog.d("没有相机");
            Log.w(TAG, "No camera on device. Switch to audio only call.");
            videoCallEnabled = false;
        }
        // Create video constraints if video call is enabled.
        if (videoCallEnabled) {
            videoWidth = peerConnectionParameters.videoWidth;
            videoHeight = peerConnectionParameters.videoHeight;
            videoFps = peerConnectionParameters.videoFps;

            // If video resolution is not specified, default to HD.
            if (videoWidth == 0 || videoHeight == 0) {
                rlog.d("视频分辨率没有设定，默认HD");
                videoWidth = HD_VIDEO_WIDTH;
                videoHeight = HD_VIDEO_HEIGHT;
            } else {
                rlog.d("视频分辨率使用设定值");
            }

            // If fps is not specified, default to 30.
            if (videoFps == 0) {
                rlog.d("视频刷新帧率没设定，默认30");
                videoFps = 30;
            } else {
                rlog.d("设定了视频帧率：" + videoFps);
            }

            videoWidth = Math.min(videoWidth, MAX_VIDEO_WIDTH);
            videoHeight = Math.min(videoHeight, MAX_VIDEO_HEIGHT);
            videoFps = Math.min(videoFps, MAX_VIDEO_FPS);
            rlog.d("与实际对比，保证设定值合理");
        }

        // Create audio constraints.
        rlog.d();
        rlog.d("创建音频约束");
        audioConstraints = new MediaConstraints();
        // added for audio performance measurements
        rlog.d("添加音频性能测量");
        if (peerConnectionParameters.noAudioProcessing) {
            Log.d(TAG, "Disabling audio processing");
            audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
                    AUDIO_ECHO_CANCELLATION_CONSTRAINT, "false"));
            audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
                    AUDIO_AUTO_GAIN_CONTROL_CONSTRAINT, "false"));
            audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
                    AUDIO_HIGH_PASS_FILTER_CONSTRAINT, "false"));
            audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
                    AUDIO_NOISE_SUPPRESSION_CONSTRAINT, "false"));
        }
        if (peerConnectionParameters.enableLevelControl) {
            Log.d(TAG, "Enabling level control.");
            audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
                    AUDIO_LEVEL_CONTROL_CONSTRAINT, "true"));
        }
        // Create SDP constraints.
        rlog.d("创建SDP约束，会话描述协议");
        sdpMediaConstraints = new MediaConstraints();
        sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
                "OfferToReceiveAudio", "true"));
        if (videoCallEnabled || peerConnectionParameters.loopback) {
            sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
                    "OfferToReceiveVideo", "true"));
        } else {
            sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
                    "OfferToReceiveVideo", "false"));
        }
        rlog.d();
    }

    private void createCapturer(CameraEnumerator enumerator) {
        final String[] deviceNames = enumerator.getDeviceNames();

        // First, try to find front facing camera
        Logging.d(TAG, "Looking for front facing cameras.");
        rlog.d("查找前置摄像头");
        for (String deviceName : deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                Logging.d(TAG, "Creating front facing camera capturer.");
                videoCapturer = enumerator.createCapturer(deviceName, null);
                rlog.d("前置摄像头抓取内容");
                if (videoCapturer != null) {
                    return;
                }
            }
        }

        // Front facing camera not found, try something else
        rlog.d("查找非前置其他摄像头");
        Logging.d(TAG, "Looking for other cameras.");
        for (String deviceName : deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                Logging.d(TAG, "Creating other camera capturer.");
                videoCapturer = enumerator.createCapturer(deviceName, null);
                rlog.d("非前置摄像头抓取内容");
                if (videoCapturer != null) {
                    return;
                }
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void createPeerConnectionInternal(EglBase.Context renderEGLContext) {
        if (factory == null || isError) {
            Log.e(TAG, "Peerconnection factory is not created");
            rlog.e("PeerConnection Factory没有创建");
            return;
        }
        Log.d(TAG, "Create peer connection.");
        rlog.d("创建连接");

        Log.d(TAG, "PCConstraints: " + pcConstraints.toString());
        rlog.d("pcConstraints约束：" + pcConstraints.toString());
        queuedRemoteCandidates = new LinkedList<>();

        if (videoCallEnabled) {
            rlog.d("videoCallEnabled == true");
            Log.d(TAG, "EGLContext: " + renderEGLContext);
            factory.setVideoHwAccelerationOptions(renderEGLContext, renderEGLContext);
        }

        PeerConnection.RTCConfiguration rtcConfig =
                new PeerConnection.RTCConfiguration(signalingParameters.iceServers);
        // TCP candidates are only useful when connecting to a server that supports
        // ICE-TCP.
        rlog.d("ICE相关设置，仅在连接到支持ICE的服务器时有效");
        rtcConfig.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED;
        rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        // Use ECDSA encryption.
        rtcConfig.keyType = PeerConnection.KeyType.ECDSA;
        rlog.d("使用ECDSA");

        peerConnection = factory.createPeerConnection(
                rtcConfig, pcConstraints, pcObserver);
        rlog.d("创建连接");
        isInitiator = false;

        // Set default WebRTC tracing and INFO libjingle logging.
        // NOTE: this _must_ happen while |factory| is alive!
        Logging.enableTracing(
                "logcat:",
                EnumSet.of(Logging.TraceLevel.TRACE_DEFAULT));
        Logging.enableLogToDebugOutput(Logging.Severity.LS_INFO);

        mediaStream = factory.createLocalMediaStream("ARDAMS");
        rlog.d("factory创建mediaStream");
        screenCapture = null;
        videoCapturer = null;
        if (screenOrCamera) {
            // ScreenCapture
            screenCapture = new ScreenCapturer((ScreenBaseActivity) context);
            mediaStream.addTrack(createVideoTrack(screenCapture));
        } else if (videoCallEnabled) {
            // CameraCapture
            rlog.d("视频打开");
            if (peerConnectionParameters.useCamera2) {
                if (!peerConnectionParameters.captureToTexture) {
                    reportError(context.getString(R.string.camera2_texture_only_error));
                    return;
                }
                rlog.d("使用Camera2 API");
                Logging.d(TAG, "Creating capturer using camera2 API.");
                createCapturer(new Camera2Enumerator(context));
            } else {
                rlog.d("使用Camera1 API");
                Logging.d(TAG, "Creating capturer using camera1 API.");
                createCapturer(new Camera1Enumerator(peerConnectionParameters.captureToTexture));
            }

            if (videoCapturer == null) {
                rlog.d("videoCapture出错");
                reportError("Failed to open camera");
                return;
            }
            rlog.d("mediaStream添加videoCapture");
            mediaStream.addTrack(createVideoTrack(videoCapturer));
        }

        rlog.d("mediaStream添加audioCapture");
        mediaStream.addTrack(createAudioTrack());
        rlog.d("peerConnection添加mediaStream");
        peerConnection.addStream(mediaStream);
        if (videoCallEnabled) {
            rlog.d("videoCallEnable == true: findVideoSender()");
            findVideoSender();
        } else {
            rlog.d("videoCallEnable == false");
        }

        if (peerConnectionParameters.aecDump) {
            try {
                aecDumpFileDescriptor = ParcelFileDescriptor.open(
                        new File(Environment.getExternalStorageDirectory().getPath()
                                + File.separator
                                + "Download/audio.aecdump"),
                        ParcelFileDescriptor.MODE_READ_WRITE |
                                ParcelFileDescriptor.MODE_CREATE |
                                ParcelFileDescriptor.MODE_TRUNCATE);
                factory.startAecDump(aecDumpFileDescriptor.getFd(), -1);
            } catch (IOException e) {
                Log.e(TAG, "Can not open aecdump file", e);
                rlog.e("不能打开dump文件");
            }
        } else {
            rlog.d("peerConnectionParameters.aecDump == false, 不打开dump文件");
        }

        Log.d(TAG, "Peer connection created.");
        rlog.d("连接创建完成");
    }

    private void closeInternal() {
        rlog.d();
        rlog.d("开始关闭");
        if (factory != null && peerConnectionParameters.aecDump) {
            factory.stopAecDump();
        }
        Log.d(TAG, "Closing peer connection.");
        statsTimer.cancel();
        if (peerConnection != null) {
            peerConnection.dispose();
            peerConnection = null;
        }
        Log.d(TAG, "Closing audio source.");
        if (audioSource != null) {
            audioSource.dispose();
            audioSource = null;
        }
        Log.d(TAG, "Stopping capture.");
        if (videoCapturer != null) {
            try {
                videoCapturer.stopCapture();
            } catch (InterruptedException e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
            videoCapturer.dispose();
            videoCapturer = null;
        }
        Log.d(TAG, "Closing video source.");
        if (videoSource != null) {
            videoSource.dispose();
            videoSource = null;
        }
        if (screenCapture != null) {
            rlog.d();
            rlog.d("screenCapture != null");
            try {
                screenCapture.stopCapture();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            screenCapture.dispose();
            screenCapture = null;
        }
        Log.d(TAG, "Closing peer connection factory.");
        if (factory != null) {
            factory.dispose();
            factory = null;
        }
        options = null;
        Log.d(TAG, "Closing peer connection done.");
        events.onPeerConnectionClosed();
        PeerConnectionFactory.stopInternalTracingCapture();
        PeerConnectionFactory.shutdownInternalTracer();
        rlog.d("关闭结束");
    }

    public boolean isHDVideo() {
        rlog.d("是否是HD Video");
        if (!videoCallEnabled) {
            return false;
        }

        return videoWidth * videoHeight >= 1280 * 720;
    }

    private void getStats() {
        if (peerConnection == null || isError) {
            rlog.e("peerConnection == null || isError");
            return;
        }
        boolean success = peerConnection.getStats(new StatsObserver() {
            @Override
            public void onComplete(final StatsReport[] reports) {
                events.onPeerConnectionStatsReady(reports);
            }
        }, null);
        if (!success) {
            Log.e(TAG, "getStats() returns false!");
            rlog.e("getStats()返回false");
        }
    }

    public void enableStatsEvents(boolean enable, int periodMs) {
        if (enable) {
            try {
                statsTimer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        executor.execute(new Runnable() {
                            @Override
                            public void run() {
                                getStats();
                            }
                        });
                    }
                }, 0, periodMs);
            } catch (Exception e) {
                Log.e(TAG, "Can not schedule statistics timer", e);
            }
        } else {
            statsTimer.cancel();
        }
    }

    public void setAudioEnabled(final boolean enable) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                enableAudio = enable;
                if (localAudioTrack != null) {
                    localAudioTrack.setEnabled(enableAudio);
                    rlog.d("设置音频可用");
                }
            }
        });
    }

    public void setVideoEnabled(final boolean enable) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                renderVideo = enable;
                if (localVideoTrack != null) {
                    localVideoTrack.setEnabled(renderVideo);
                }
                if (remoteVideoTrack != null) {
                    remoteVideoTrack.setEnabled(renderVideo);
                }
            }
        });
    }

    public void createOffer() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                if (peerConnection != null && !isError) {
                    Log.d(TAG, "PC Create OFFER");
                    isInitiator = true;
                    peerConnection.createOffer(sdpObserver, sdpMediaConstraints);
                    rlog.d("创建Offer");
                }
            }
        });
    }

    public void createAnswer() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                if (peerConnection != null && !isError) {
                    Log.d(TAG, "PC create ANSWER");
                    isInitiator = false;
                    peerConnection.createAnswer(sdpObserver, sdpMediaConstraints);
                    rlog.d("创建Answer");
                }
            }
        });
    }

    public void addRemoteIceCandidate(final IceCandidate candidate) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                if (peerConnection != null && !isError) {
                    if (queuedRemoteCandidates != null) {
                        queuedRemoteCandidates.add(candidate);
                    } else {
                        peerConnection.addIceCandidate(candidate);
                    }
                    rlog.d("添加Remote");
                }
            }
        });
    }

    public void removeRemoteIceCandidates(final IceCandidate[] candidates) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                if (peerConnection == null || isError) {
                    return;
                }
                // Drain the queued remote candidates if there is any so that
                // they are processed in the proper order.
                drainCandidates();
                peerConnection.removeIceCandidates(candidates);
                rlog.d("移除Remote");
            }
        });
    }

    public void setRemoteDescription(final SessionDescription sdp) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                if (peerConnection == null || isError) {
                    return;
                }
                String sdpDescription = sdp.description;
                if (preferIsac) {
                    sdpDescription = preferCodec(sdpDescription, AUDIO_CODEC_ISAC, true);
                }
                if (videoCallEnabled) {
                    sdpDescription = preferCodec(sdpDescription, preferredVideoCodec, false);
                }
                if (peerConnectionParameters.audioStartBitrate > 0) {
                    sdpDescription = setStartBitrate(AUDIO_CODEC_OPUS, false,
                            sdpDescription, peerConnectionParameters.audioStartBitrate);
                }
                Log.d(TAG, "Set remote SDP.");
                SessionDescription sdpRemote = new SessionDescription(
                        sdp.type, sdpDescription);
                peerConnection.setRemoteDescription(sdpObserver, sdpRemote);
                rlog.d("设置Remote描述");
            }
        });
    }

    public void stopVideoSource() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                if (videoCapturer != null && !videoCapturerStopped) {
                    Log.d(TAG, "Stop video source.");
                    try {
                        videoCapturer.stopCapture();
                    } catch (InterruptedException e) {
                    }
                    videoCapturerStopped = true;
                    rlog.d("停止VideoSource");
                }
            }
        });
    }

    public void startVideoSource() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                if (videoCapturer != null && videoCapturerStopped) {
                    Log.d(TAG, "Restart video source.");
                    videoCapturer.startCapture(videoWidth, videoHeight, videoFps);
                    videoCapturerStopped = false;
                    rlog.d("开始VideoSource");
                }
            }
        });
    }

    public void setVideoMaxBitrate(final Integer maxBitrateKbps) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                if (peerConnection == null || localVideoSender == null || isError) {
                    return;
                }
                Log.d(TAG, "Requested max video bitrate: " + maxBitrateKbps);
                if (localVideoSender == null) {
                    Log.w(TAG, "Sender is not ready.");
                    return;
                }

                RtpParameters parameters = localVideoSender.getParameters();
                if (parameters.encodings.size() == 0) {
                    Log.w(TAG, "RtpParameters are not ready.");
                    return;
                }

                for (RtpParameters.Encoding encoding : parameters.encodings) {
                    // Null value means no limit.
                    encoding.maxBitrateBps = maxBitrateKbps == null
                            ? null
                            : maxBitrateKbps * BPS_IN_KBPS;
                }
                if (!localVideoSender.setParameters(parameters)) {
                    Log.e(TAG, "RtpSender.setParameters failed.");
                }
                Log.d(TAG, "Configured max video bitrate to: " + maxBitrateKbps);
                rlog.d("设置视频最大比特率");
            }
        });
    }

    private void reportError(final String errorMessage) {
        Log.e(TAG, "Peerconnection error: " + errorMessage);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                if (!isError) {
                    events.onPeerConnectionError(errorMessage);
                    isError = true;
                    rlog.d("报告错误");
                }
            }
        });
    }

    private AudioTrack createAudioTrack() {
        rlog.d("创建AudioTrack");
        audioSource = factory.createAudioSource(audioConstraints);
        localAudioTrack = factory.createAudioTrack(AUDIO_TRACK_ID, audioSource);
        localAudioTrack.setEnabled(enableAudio);
        return localAudioTrack;
    }

    private VideoTrack createVideoTrack(VideoCapturer capturer) {
        rlog.d("创建VideoTrack");
        videoSource = factory.createVideoSource(capturer);
        capturer.startCapture(videoWidth, videoHeight, videoFps);

        localVideoTrack = factory.createVideoTrack(VIDEO_TRACK_ID, videoSource);
        localVideoTrack.setEnabled(renderVideo);
        localVideoTrack.addRenderer(new VideoRenderer(localRender));
        return localVideoTrack;
    }

    private void findVideoSender() {
        rlog.d("查找视频Sender");
        for (RtpSender sender : peerConnection.getSenders()) {
            if (sender.track() != null) {
                String trackType = sender.track().kind();
                if (trackType.equals(VIDEO_TRACK_TYPE)) {
                    Log.d(TAG, "Found video sender.");
                    localVideoSender = sender;
                }
            }
        }
    }

    private static String setStartBitrate(String codec, boolean isVideoCodec,
                                          String sdpDescription, int bitrateKbps) {
        rlog.d("设置开始比特率");
        String[] lines = sdpDescription.split("\r\n");
        int rtpmapLineIndex = -1;
        boolean sdpFormatUpdated = false;
        String codecRtpMap = null;
        // Search for codec rtpmap in format
        // a=rtpmap:<payload type> <encoding name>/<clock rate> [/<encoding parameters>]
        String regex = "^a=rtpmap:(\\d+) " + codec + "(/\\d+)+[\r]?$";
        Pattern codecPattern = Pattern.compile(regex);
        for (int i = 0; i < lines.length; i++) {
            Matcher codecMatcher = codecPattern.matcher(lines[i]);
            if (codecMatcher.matches()) {
                codecRtpMap = codecMatcher.group(1);
                rtpmapLineIndex = i;
                break;
            }
        }
        if (codecRtpMap == null) {
            Log.w(TAG, "No rtpmap for " + codec + " codec");
            return sdpDescription;
        }
        Log.d(TAG, "Found " + codec + " rtpmap " + codecRtpMap
                + " at " + lines[rtpmapLineIndex]);

        // Check if a=fmtp string already exist in remote SDP for this codec and
        // update it with new bitrate parameter.
        regex = "^a=fmtp:" + codecRtpMap + " \\w+=\\d+.*[\r]?$";
        codecPattern = Pattern.compile(regex);
        for (int i = 0; i < lines.length; i++) {
            Matcher codecMatcher = codecPattern.matcher(lines[i]);
            if (codecMatcher.matches()) {
                Log.d(TAG, "Found " + codec + " " + lines[i]);
                if (isVideoCodec) {
                    lines[i] += "; " + VIDEO_CODEC_PARAM_START_BITRATE
                            + "=" + bitrateKbps;
                } else {
                    lines[i] += "; " + AUDIO_CODEC_PARAM_BITRATE
                            + "=" + (bitrateKbps * 1000);
                }
                Log.d(TAG, "Update remote SDP line: " + lines[i]);
                sdpFormatUpdated = true;
                break;
            }
        }

        StringBuilder newSdpDescription = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            newSdpDescription.append(lines[i]).append("\r\n");
            // Append new a=fmtp line if no such line exist for a codec.
            if (!sdpFormatUpdated && i == rtpmapLineIndex) {
                String bitrateSet;
                if (isVideoCodec) {
                    bitrateSet = "a=fmtp:" + codecRtpMap + " "
                            + VIDEO_CODEC_PARAM_START_BITRATE + "=" + bitrateKbps;
                } else {
                    bitrateSet = "a=fmtp:" + codecRtpMap + " "
                            + AUDIO_CODEC_PARAM_BITRATE + "=" + (bitrateKbps * 1000);
                }
                Log.d(TAG, "Add remote SDP line: " + bitrateSet);
                newSdpDescription.append(bitrateSet).append("\r\n");
            }

        }
        return newSdpDescription.toString();
    }

    private static String preferCodec(
            String sdpDescription, String codec, boolean isAudio) {
        rlog.d("喜欢编解码器");
        String[] lines = sdpDescription.split("\r\n");
        int mLineIndex = -1;
        String codecRtpMap = null;
        // a=rtpmap:<payload type> <encoding name>/<clock rate> [/<encoding parameters>]
        String regex = "^a=rtpmap:(\\d+) " + codec + "(/\\d+)+[\r]?$";
        Pattern codecPattern = Pattern.compile(regex);
        String mediaDescription = "m=video ";
        if (isAudio) {
            mediaDescription = "m=audio ";
        }
        for (int i = 0; (i < lines.length)
                && (mLineIndex == -1 || codecRtpMap == null); i++) {
            if (lines[i].startsWith(mediaDescription)) {
                mLineIndex = i;
                continue;
            }
            Matcher codecMatcher = codecPattern.matcher(lines[i]);
            if (codecMatcher.matches()) {
                codecRtpMap = codecMatcher.group(1);
            }
        }
        if (mLineIndex == -1) {
            Log.w(TAG, "No " + mediaDescription + " line, so can't prefer " + codec);
            return sdpDescription;
        }
        if (codecRtpMap == null) {
            Log.w(TAG, "No rtpmap for " + codec);
            return sdpDescription;
        }
        Log.d(TAG, "Found " + codec + " rtpmap " + codecRtpMap + ", prefer at "
                + lines[mLineIndex]);
        String[] origMLineParts = lines[mLineIndex].split(" ");
        if (origMLineParts.length > 3) {
            StringBuilder newMLine = new StringBuilder();
            int origPartIndex = 0;
            // Format is: m=<media> <port> <proto> <fmt> ...
            newMLine.append(origMLineParts[origPartIndex++]).append(" ");
            newMLine.append(origMLineParts[origPartIndex++]).append(" ");
            newMLine.append(origMLineParts[origPartIndex++]).append(" ");
            newMLine.append(codecRtpMap);
            for (; origPartIndex < origMLineParts.length; origPartIndex++) {
                if (!origMLineParts[origPartIndex].equals(codecRtpMap)) {
                    newMLine.append(" ").append(origMLineParts[origPartIndex]);
                }
            }
            lines[mLineIndex] = newMLine.toString();
            Log.d(TAG, "Change media description: " + lines[mLineIndex]);
        } else {
            Log.e(TAG, "Wrong SDP media description format: " + lines[mLineIndex]);
        }
        StringBuilder newSdpDescription = new StringBuilder();
        for (String line : lines) {
            newSdpDescription.append(line).append("\r\n");
        }
        return newSdpDescription.toString();
    }

    private void drainCandidates() {
        if (queuedRemoteCandidates != null) {
            Log.d(TAG, "Add " + queuedRemoteCandidates.size() + " remote candidates");
            for (IceCandidate candidate : queuedRemoteCandidates) {
                peerConnection.addIceCandidate(candidate);
            }
            queuedRemoteCandidates = null;
        }
    }

    private void switchCameraInternal() {
        rlog.d("切换相机");
        if (!videoCallEnabled || numberOfCameras < 2 || isError || videoCapturer == null) {
            Log.e(TAG, "Failed to switch camera. Video: " + videoCallEnabled + ". Error : "
                    + isError + ". Number of cameras: " + numberOfCameras);
            return;  // No video is sent or only one camera is available or error happened.
        }
        Log.d(TAG, "Switch camera");
        videoCapturer.switchCamera(null);
    }

    public void switchCamera() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                switchCameraInternal();
            }
        });
    }

    public void changeCaptureFormat(final int width, final int height, final int framerate) {
        rlog.d("切换数据格式");
        executor.execute(new Runnable() {
            @Override
            public void run() {
                changeCaptureFormatInternal(width, height, framerate);
            }
        });
    }

    private void changeCaptureFormatInternal(int width, int height, int framerate) {
        if (!videoCallEnabled || isError || videoCapturer == null) {
            Log.e(TAG, "Failed to change capture format. Video: " + videoCallEnabled + ". Error : "
                    + isError);
            return;
        }
        Log.d(TAG, "changeCaptureFormat: " + width + "x" + height + "@" + framerate);
        videoSource.adaptOutputFormat(width, height, framerate);
    }

    // Implementation detail: observe ICE & stream changes and react accordingly.
    private class PCObserver implements PeerConnection.Observer {
        @Override
        public void onIceCandidate(final IceCandidate candidate) {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    events.onIceCandidate(candidate);
                }
            });
        }

        @Override
        public void onIceCandidatesRemoved(final IceCandidate[] candidates) {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    events.onIceCandidatesRemoved(candidates);
                }
            });
        }

        @Override
        public void onSignalingChange(
                PeerConnection.SignalingState newState) {
            Log.d(TAG, "SignalingState: " + newState);
        }

        @Override
        public void onIceConnectionChange(
                final PeerConnection.IceConnectionState newState) {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    Log.d(TAG, "IceConnectionState: " + newState);
                    if (newState == IceConnectionState.CONNECTED) {
                        events.onIceConnected();
                    } else if (newState == IceConnectionState.DISCONNECTED) {
                        events.onIceDisconnected();
                    } else if (newState == IceConnectionState.FAILED) {
                        reportError("ICE connection failed.");
                    }
                }
            });
        }

        @Override
        public void onIceGatheringChange(
                PeerConnection.IceGatheringState newState) {
            Log.d(TAG, "IceGatheringState: " + newState);
        }

        @Override
        public void onIceConnectionReceivingChange(boolean receiving) {
            Log.d(TAG, "IceConnectionReceiving changed to " + receiving);
        }

        @Override
        public void onAddStream(final MediaStream stream) {
            rlog.d("添加Stream");
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    if (peerConnection == null || isError) {
                        return;
                    }
                    if (stream.audioTracks.size() > 1 || stream.videoTracks.size() > 1) {
                        reportError("Weird-looking stream: " + stream);
                        return;
                    }
                    if (stream.videoTracks.size() == 1) {
                        remoteVideoTrack = stream.videoTracks.get(0);
                        remoteVideoTrack.setEnabled(renderVideo);
                        remoteVideoTrack.addRenderer(new VideoRenderer(remoteRender));
                    }
                }
            });
        }

        @Override
        public void onRemoveStream(final MediaStream stream) {
            rlog.d("移除Stream");
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    remoteVideoTrack = null;
                }
            });
        }

        @Override
        public void onDataChannel(final DataChannel dc) {
            reportError("AppRTC doesn't use data channels, but got: " + dc.label()
                    + " anyway!");
        }

        @Override
        public void onRenegotiationNeeded() {
            // No need to do anything; AppRTC follows a pre-agreed-upon
            // signaling/negotiation protocol.
        }
    }

    // Implementation detail: handle offer creation/signaling and answer setting,
    // as well as adding remote ICE candidates once the answer SDP is set.
    private class SDPObserver implements SdpObserver {
        @Override
        public void onCreateSuccess(final SessionDescription origSdp) {
            rlog.d("创建SDP");
            if (localSdp != null) {
                reportError("Multiple SDP create.");
                return;
            }
            String sdpDescription = origSdp.description;
            if (preferIsac) {
                sdpDescription = preferCodec(sdpDescription, AUDIO_CODEC_ISAC, true);
            }
            if (videoCallEnabled) {
                sdpDescription = preferCodec(sdpDescription, preferredVideoCodec, false);
            }
            final SessionDescription sdp = new SessionDescription(
                    origSdp.type, sdpDescription);
            localSdp = sdp;
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    if (peerConnection != null && !isError) {
                        Log.d(TAG, "Set local SDP from " + sdp.type);
                        peerConnection.setLocalDescription(sdpObserver, sdp);
                    }
                }
            });
        }

        @Override
        public void onSetSuccess() {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    if (peerConnection == null || isError) {
                        return;
                    }
                    if (isInitiator) {
                        // For offering peer connection we first create offer and set
                        // local SDP, then after receiving answer set remote SDP.
                        if (peerConnection.getRemoteDescription() == null) {
                            // We've just set our local SDP so time to send it.
                            Log.d(TAG, "Local SDP set succesfully");
                            events.onLocalDescription(localSdp);
                            rlog.d("设置本地SDP");
                        } else {
                            // We've just set remote description, so drain remote
                            // and send local ICE candidates.
                            Log.d(TAG, "Remote SDP set succesfully");
                            drainCandidates();
                            rlog.d("设置远程SDP");
                        }
                    } else {
                        // For answering peer connection we set remote SDP and then
                        // create answer and set local SDP.
                        if (peerConnection.getLocalDescription() != null) {
                            // We've just set our local SDP so time to send it, drain
                            // remote and send local ICE candidates.
                            Log.d(TAG, "Local SDP set succesfully");
                            events.onLocalDescription(localSdp);
                            drainCandidates();
                            rlog.d("设置本地SDP");
                        } else {
                            // We've just set remote SDP - do nothing for now -
                            // answer will be created soon.
                            Log.d(TAG, "Remote SDP set succesfully");
                            rlog.d("已经创建过SDP，现在什么也不做");
                        }
                    }
                }
            });
        }

        @Override
        public void onCreateFailure(final String error) {
            rlog.d("SDP创建失败");
            reportError("createSDP error: " + error);
        }

        @Override
        public void onSetFailure(final String error) {
            rlog.d("设置SDP失败");
            reportError("setSDP error: " + error);
        }
    }
}

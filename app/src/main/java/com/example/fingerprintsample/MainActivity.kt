package com.example.fingerprintsample

import SecuGen.FDxSDKPro.JSGFPLib
import SecuGen.FDxSDKPro.SGAutoOnEventNotifier
import SecuGen.FDxSDKPro.SGDeviceInfoParam
import SecuGen.FDxSDKPro.SGFDxConstant
import SecuGen.FDxSDKPro.SGFDxDeviceName
import SecuGen.FDxSDKPro.SGFDxErrorCode
import SecuGen.FDxSDKPro.SGFDxSecurityLevel
import SecuGen.FDxSDKPro.SGFDxTemplateFormat
import SecuGen.FDxSDKPro.SGFingerInfo
import SecuGen.FDxSDKPro.SGFingerPresentEvent
import SecuGen.FDxSDKPro.SGImpressionType
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.airbnb.lottie.LottieDrawable
import com.example.fingerprintsample.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

class MainActivity : AppCompatActivity(), SGFingerPresentEvent {


    companion object {
        private const val TAG = "MainActivity"
        private const val IMAGE_CAPTURE_TIMEOUT_MS = 10000
        private const val IMAGE_CAPTURE_QUALITY = 50
        private const val ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION"

    }

    private lateinit var filter: IntentFilter

    //    private android.widget.ToggleButton mToggleButtonCaptureModeN;
    private var mPermissionIntent: PendingIntent? = null
    private var mRegisterImage: ByteArray? = null
    private var mVerifyImage: ByteArray? = null
    private var mRegisterTemplate: ByteArray? = null
    private var mVerifyTemplate: ByteArray? = null
    private var mMaxTemplateSize: IntArray? = null
    private var mImageWidth = 0
    private var mImageHeight = 0
    private var mImageDPI = 0
    private var autoOn: SGAutoOnEventNotifier? = null
    private var mAutoOnEnabled = false
    private var nCaptureModeN = 0
    private var bSecuGenDeviceOpened = false
    private lateinit var jsgfplib: JSGFPLib
    private var usbPermissionRequested = false

    private var mNumFakeThresholds: IntArray? = null
    private var mDefaultFakeThreshold: IntArray? = null
    private var mFakeEngineReady: BooleanArray? = null
    private var bFingerprintRegistered = false
    private var mFakeDetectionLevel = 1

    private var scanType: ScanType = ScanType.NORMAL

    private val mUsbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action

            if (UsbManager.ACTION_USB_DEVICE_DETACHED == intent.action) {
                synchronized(this) {
                    val device: UsbDevice? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(
                                UsbManager.EXTRA_DEVICE,
                                UsbDevice::class.java
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                    device?.apply {
                        binding.tvDevice.visibility = View.VISIBLE
                        enableButtons(isFingerPrintDeviceFound())
                    }
                }

            }

            if (UsbManager.ACTION_USB_DEVICE_ATTACHED == intent.action) {
                synchronized(this) {
                    val device: UsbDevice? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(
                                UsbManager.EXTRA_DEVICE,
                                UsbDevice::class.java
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                    device?.apply {
                        binding.tvDevice.visibility = View.GONE
                        enableButtons(isFingerPrintDeviceFound())
                    }
                }
            }

            if (ACTION_USB_PERMISSION == action) {
                synchronized(this) {
                    val device: UsbDevice? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(
                                UsbManager.EXTRA_DEVICE,
                                UsbDevice::class.java
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.apply {
                            Log.e(
                                TAG,
                                """
                                USB BroadcastReceiver VID : ${device.vendorId}
                                
                                """.trimIndent()
                            )
                            Log.e(
                                TAG,
                                """
                                USB BroadcastReceiver PID: ${device.productId}
                                
                                """.trimIndent()
                            )
                        }
                    } else Log.e(
                        TAG,
                        "mUsbReceiver.onReceive() permission denied for device $device"
                    )
                }
            }
        }
    }


    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setOnClickListeners()

        //USB Permissions
        mPermissionIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE
        )
        filter = IntentFilter()
        filter.addAction(ACTION_USB_PERMISSION)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)

        jsgfplib = JSGFPLib(this, getSystemService(USB_SERVICE) as UsbManager)

        bSecuGenDeviceOpened = false
        usbPermissionRequested = false
        mAutoOnEnabled = false
        autoOn = SGAutoOnEventNotifier(jsgfplib, this)
        nCaptureModeN = 0

        mNumFakeThresholds = IntArray(1)
        mDefaultFakeThreshold = IntArray(1)
        mFakeEngineReady = BooleanArray(1)
        mMaxTemplateSize = IntArray(1)
        mRegisterTemplate = ByteArray(1)
        mVerifyTemplate = ByteArray(1)

        Log.d(TAG, "Exit onCreate()")
    }

    private fun setOnClickListeners() {
        binding.btnScan.setOnClickListener {
            scanType = ScanType.NORMAL
            startScanning()
        }

        binding.btnRegister.setOnClickListener {
            scanType = ScanType.REGISTRATION
            startScanning()

        }


        binding.btnVerify.setOnClickListener {
            scanType = ScanType.VERIFICATION
            startScanning()
        }
    }

    private fun startScanning() {
        if (isFingerPrintDeviceFound() && hasUsbPermission()) {
            enableButtons(false)
            showPlaceFingerOnDevice()
            autoOn!!.start()
        }
    }

    fun registerFingerPrint() {
        lifecycleScope.launch {
            showScanningFingerPrint()

            if (mRegisterImage != null) mRegisterImage = null
            mRegisterImage = ByteArray(mImageWidth * mImageHeight)
            bFingerprintRegistered = false

            var fpInfo: SGFingerInfo? = SGFingerInfo()

            val job = lifecycleScope.async(Dispatchers.IO) {
                var status = false

                jsgfplib.GetImageEx(
                    mRegisterImage,
                    IMAGE_CAPTURE_TIMEOUT_MS.toLong(),
                    IMAGE_CAPTURE_QUALITY.toLong()
                )

                jsgfplib.SetTemplateFormat(SGFDxTemplateFormat.TEMPLATE_FORMAT_ISO19794)
                val quality1 = IntArray(1)
                jsgfplib.GetImageQuality(
                    mImageWidth.toLong(),
                    mImageHeight.toLong(),
                    mRegisterImage,
                    quality1
                )


                fpInfo!!.FingerNumber = 1
                fpInfo!!.ImageQuality = quality1[0]
                fpInfo!!.ImpressionType = SGImpressionType.SG_IMPTYPE_LP
                fpInfo!!.ViewNumber = 1
                for (i in mRegisterTemplate!!.indices) mRegisterTemplate!![i] = 0

                var result = jsgfplib.CreateTemplate(fpInfo, mRegisterImage, mRegisterTemplate)

                if (result == SGFDxErrorCode.SGFDX_ERROR_NONE) {
                    bFingerprintRegistered = true
                    val size = IntArray(1)
                    jsgfplib.GetTemplateSize(mRegisterTemplate, size)

                    status = true
                } else {
                    status = false
                }


                status
            }

            if (job.await()) {
                binding.ivFingerPrint.setImageBitmap(toGrayscale(mRegisterImage!!))
                showSuccessOrFail(true, "Fingerprint registration successful")
            } else {
                showSuccessOrFail(false, "Fingerprint registration failed")
            }

            fpInfo = null
            mRegisterImage = null

        }


    }

    fun verifyFingerPrint() {
        if (!bFingerprintRegistered) {
            showSuccessOrFail(false, "Please Register A FingerPrint First")
            jsgfplib.SetLedOn(false)
            return
        }
        if (mVerifyImage != null) mVerifyImage = null
        mVerifyImage = ByteArray(mImageWidth * mImageHeight)

        lifecycleScope.launch {
            showScanningFingerPrint()
            var fpInfo: SGFingerInfo?

            val job = lifecycleScope.async(Dispatchers.IO) {
                var status = false

                jsgfplib.GetImageEx(
                    mVerifyImage,
                    IMAGE_CAPTURE_TIMEOUT_MS.toLong(),
                    IMAGE_CAPTURE_QUALITY.toLong()
                )

                binding.ivFingerPrint.setImageBitmap(toGrayscale(mVerifyImage!!))
                jsgfplib.SetTemplateFormat(SGFDxTemplateFormat.TEMPLATE_FORMAT_ISO19794)

                val quality = IntArray(1)
                jsgfplib.GetImageQuality(
                    mImageWidth.toLong(),
                    mImageHeight.toLong(),
                    mVerifyImage,
                    quality
                )

                fpInfo = SGFingerInfo().apply {
                    FingerNumber = 1
                    ImageQuality = quality[0]
                    ImpressionType = SGImpressionType.SG_IMPTYPE_LP
                    ViewNumber = 1
                }

                for (i in mVerifyTemplate!!.indices) mVerifyTemplate!![i] = 0

                var result = jsgfplib.CreateTemplate(fpInfo, mVerifyImage, mVerifyTemplate)

                if (result == SGFDxErrorCode.SGFDX_ERROR_NONE) {
                    val size = IntArray(1)
                    jsgfplib.GetTemplateSize(mVerifyTemplate, size)
                    var matched: BooleanArray? = BooleanArray(1)
                    jsgfplib.MatchTemplate(
                        mRegisterTemplate,
                        mVerifyTemplate,
                        SGFDxSecurityLevel.SL_NORMAL,
                        matched
                    )

                    if (matched!![0]) {
                        status = true
                    }
                    matched = null
                }

                status
            }

            if (job.await()) {
                binding.ivFingerPrint.setImageBitmap(toGrayscale(mVerifyImage!!))
                showSuccessOrFail(true, "Fingerprint matched")
            } else {
                showSuccessOrFail(false, "Fingerprint not matched")
            }

            fpInfo = null
            mVerifyImage = null
        }
    }

    private fun scanFingerPrint() {
        lifecycleScope.launch {
            showScanningFingerPrint()
            val image = ByteArray(mImageWidth * mImageHeight)

            val job = lifecycleScope.async(Dispatchers.IO) {
                var status = false
                if (jsgfplib.GetImageEx(
                        image,
                        IMAGE_CAPTURE_TIMEOUT_MS.toLong(),
                        IMAGE_CAPTURE_QUALITY.toLong()
                    ) == SGFDxErrorCode.SGFDX_ERROR_NONE
                ) {
                    status = true
                }
                status
            }

            if (job.await()) {
                binding.ivFingerPrint.setImageBitmap(toGrayscale(image))
                showSuccessOrFail(true, "Fingerprint scan successful")
            } else {
                showSuccessOrFail(false, "Fingerprint scan failed")
            }
        }
    }

    private fun isFingerPrintDeviceFound(): Boolean {

        if (jsgfplib.GetUsbDevice() == null && binding.tvDevice.isVisible) {
            return false
        }

        val deviceInfo = SGDeviceInfoParam()
        val error = jsgfplib.GetDeviceInfo(deviceInfo)
        if (error == SGFDxErrorCode.SGFDX_ERROR_NONE) {
            mImageWidth = deviceInfo.imageWidth
            mImageHeight = deviceInfo.imageHeight
        }

        autoOn = SGAutoOnEventNotifier(jsgfplib, this)

        return jsgfplib.GetUsbDevice() != null && !binding.tvDevice.isVisible
    }

    private fun hasUsbPermission(): Boolean {
        val usbDevice: UsbDevice? = jsgfplib.GetUsbDevice()
        Log.e(TAG, UsbDevice.getDeviceName(0))
        jsgfplib.GetUsbManager().requestPermission(usbDevice, mPermissionIntent)
        return jsgfplib.GetUsbManager().hasPermission(usbDevice)
    }

    private fun enableButtons(b: Boolean) {
        binding.btnRegister.isEnabled = b
        binding.btnVerify.isEnabled = b
        binding.btnScan.isEnabled = b
    }

    private fun showPlaceFingerOnDevice() {
        binding.scanAnim.setAnimation(R.raw.place_finger_on_device_anim)
        binding.scanAnim.playAnimation()
        binding.scanAnim.repeatCount = LottieDrawable.INFINITE
        binding.tvMsg.text = "Place your finger on the device.."


    }

    private fun showScanningFingerPrint() {
        binding.scanAnim.setAnimation(R.raw.fingerprint_scan2)
        binding.scanAnim.playAnimation()
        binding.scanAnim.repeatCount = LottieDrawable.INFINITE
        binding.tvMsg.text = "Scanning Finger Print...."
    }

    private fun showSuccessOrFail(
        successful: Boolean,
        msg: String
    ) {
        if (successful) {
            binding.scanAnim.setAnimation(R.raw.success_amin)
        } else {
            binding.scanAnim.setAnimation(R.raw.failed_anim)
        }

        binding.scanAnim.playAnimation()
        binding.scanAnim.repeatCount = 0
        binding.tvMsg.text = msg

        enableButtons(true)
    }


    fun toGrayscale(mImageBuffer: ByteArray): Bitmap? {
        val Bits = ByteArray(mImageBuffer.size * 4)
        for (i in mImageBuffer.indices) {
            Bits[i * 4 + 2] = mImageBuffer[i]
            Bits[i * 4 + 1] = Bits[i * 4 + 2]
            Bits[i * 4] = Bits[i * 4 + 1] // Invert the source bits
            Bits[i * 4 + 3] = -1 // 0xff, that's the alpha.
        }
        val bmpGrayscale = Bitmap.createBitmap(mImageWidth, mImageHeight, Bitmap.Config.ARGB_8888)
        bmpGrayscale.copyPixelsFromBuffer(ByteBuffer.wrap(Bits))
        return bmpGrayscale
    }


    override fun SGFingerPresentCallback() {
        autoOn!!.stop()
        fingerDetectedHandler.sendMessage(Message())
    }


    var fingerDetectedHandler: Handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (scanType) {
                ScanType.NORMAL -> scanFingerPrint()
                ScanType.REGISTRATION -> registerFingerPrint()
                ScanType.VERIFICATION -> verifyFingerPrint()
            }

        }
    }


    override fun onPause() {
        Log.d(TAG, "Enter onPause()")
        if (bSecuGenDeviceOpened) {
            autoOn!!.stop()
            jsgfplib.CloseDevice()
            bSecuGenDeviceOpened = false
        }
        unregisterReceiver(mUsbReceiver)
        mRegisterImage = null
        mVerifyImage = null
        mRegisterTemplate = null
        mVerifyTemplate = null
        super.onPause()
        Log.d(TAG, "Exit onPause()")
    }

    override fun onResume() {
        Log.d(TAG, "Enter onResume()")
        super.onResume()
        registerReceiver(mUsbReceiver, filter)

        var error: Long = jsgfplib.Init(SGFDxDeviceName.SG_DEV_AUTO)
        if (error != SGFDxErrorCode.SGFDX_ERROR_NONE) {
            val errorMessage = if (error == SGFDxErrorCode.SGFDX_ERROR_DEVICE_NOT_FOUND) {
                "The attached fingerprint device is not supported on Android"
            } else {
                "Fingerprint device initialization failed!"
            }

            binding.tvDevice.text = errorMessage

        } else {
            val usbDevice: UsbDevice? = jsgfplib.GetUsbDevice()
            if (usbDevice == null) {
                val errorMessage = "SecuGen fingerprint sensor not found!"
                binding.tvDevice.text = errorMessage

            } else {
                var hasPermission: Boolean = jsgfplib.GetUsbManager().hasPermission(usbDevice)
                if (!hasPermission) {
                    if (!usbPermissionRequested) {
                        //Log.d(TAG, "Call GetUsbManager().requestPermission()");
                        usbPermissionRequested = true
                        jsgfplib.GetUsbManager().requestPermission(usbDevice, mPermissionIntent)
                    } else {
                        //wait up to 20 seconds for the system to grant USB permission
                        hasPermission = jsgfplib.GetUsbManager().hasPermission(usbDevice)
                        var i = 0
                        while (!hasPermission && i <= 40) {
                            ++i
                            hasPermission = jsgfplib.GetUsbManager().hasPermission(usbDevice)
                            try {
                                Thread.sleep(500)
                            } catch (e: InterruptedException) {
                                e.printStackTrace()
                            }
                        }
                    }
                }
                if (hasPermission) {
                    error = jsgfplib.OpenDevice(0)
                    if (error == SGFDxErrorCode.SGFDX_ERROR_NONE) {
                        bSecuGenDeviceOpened = true
                        val deviceInfo = SGDeviceInfoParam()
                        jsgfplib.GetDeviceInfo(deviceInfo)
                        mImageWidth = deviceInfo.imageWidth
                        mImageHeight = deviceInfo.imageHeight
                        mImageDPI = deviceInfo.imageDPI
                        jsgfplib.FakeDetectionCheckEngineStatus(mFakeEngineReady)

                        if (mFakeEngineReady!![0]) {
                            error = jsgfplib.FakeDetectionGetNumberOfThresholds(mNumFakeThresholds)
                            if (error != SGFDxErrorCode.SGFDX_ERROR_NONE) mNumFakeThresholds!![0] =
                                1 //0=Off, 1=TouchChip

                            jsgfplib.FakeDetectionGetDefaultThreshold(mDefaultFakeThreshold)

                            mFakeDetectionLevel = mDefaultFakeThreshold!![0]

                            val thresholdValue = DoubleArray(1)
                            jsgfplib.FakeDetectionGetThresholdValue(thresholdValue)

                        } else {
                            mNumFakeThresholds!![0] = 1 //0=Off, 1=Touch Chip
                            mDefaultFakeThreshold!![0] = 1 //Touch Chip Enabled
                        }
                        jsgfplib.SetTemplateFormat(SGFDxTemplateFormat.TEMPLATE_FORMAT_ISO19794)
                        jsgfplib.GetMaxTemplateSize(mMaxTemplateSize)
                        mRegisterTemplate = ByteArray(mMaxTemplateSize!![0])
                        mVerifyTemplate = ByteArray(mMaxTemplateSize!![0])
                        jsgfplib.WriteData(
                            SGFDxConstant.WRITEDATA_COMMAND_ENABLE_SMART_CAPTURE,
                            1.toByte())
                    }
                }
            }
        }

        Log.d(TAG, "Exit onResume()")
    }

    //////////////////////////////////////////////////////////////////////////////////////////////
    //////////////////////////////////////////////////////////////////////////////////////////////
    override fun onDestroy() {
        Log.d(TAG, "Enter onDestroy()")
        jsgfplib.CloseDevice()
        mRegisterImage = null
        mVerifyImage = null
        mRegisterTemplate = null
        mVerifyTemplate = null
        jsgfplib.Close()
        super.onDestroy()
        Log.d(TAG, "Exit onDestroy()")
    }
}
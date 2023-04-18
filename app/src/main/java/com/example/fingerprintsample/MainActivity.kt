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
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Parcelable
import android.util.Log
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.airbnb.lottie.LottieDrawable
import com.example.fingerprintsample.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

class MainActivity : AppCompatActivity(), SGFingerPresentEvent {


    companion object{
        private const val TAG = "MainActivity"
        private const val IMAGE_CAPTURE_TIMEOUT_MS = 10000
        private const val IMAGE_CAPTURE_QUALITY = 50
        private const val ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION"

    }



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
    private var grayBuffer: IntArray? = null
    private var grayBitmap: Bitmap? = null
    private var filter : IntentFilter? = null
    private var autoOn: SGAutoOnEventNotifier? = null
    private var mAutoOnEnabled = false
    private var nCaptureModeN = 0
    private var bSecuGenDeviceOpened = false
    private lateinit var jsgfplib: JSGFPLib
    private var usbPermissionRequested = false

    private var mSeekBarFDLevel: SeekBar? = null
    private var mTextViewFDLevel: TextView? = null
    private var mNumFakeThresholds: IntArray? = null
    private var mDefaultFakeThreshold: IntArray? = null
    private var mFakeEngineReady: BooleanArray? = null
    private var bRegisterAutoOnMode = false
    private var bVerifyAutoOnMode = false
    private var bFingerprintRegistered = false
    private var mFakeDetectionLevel = 1

    private val mUsbReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (ACTION_USB_PERMISSION == action) {
                synchronized(this) {
                    val device =
                        intent.getParcelableExtra<Parcelable>(UsbManager.EXTRA_DEVICE) as UsbDevice?
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            Log.e(
                                Companion.TAG,
                                """
                                USB BroadcastReceiver VID : ${device.vendorId}
                                
                                """.trimIndent()
                            )
                            Log.e(
                                Companion.TAG,
                                """
                                USB BroadcastReceiver PID: ${device.productId}
                                
                                """.trimIndent()
                            )
                        } else Log.e(
                            Companion.TAG,
                            "mUsbReceiver.onReceive() Device is null"
                        )
                    } else Log.e(
                        Companion.TAG,
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

        mNumFakeThresholds = IntArray(1)
        mDefaultFakeThreshold = IntArray(1)
        mFakeEngineReady = BooleanArray(1)
        mMaxTemplateSize = IntArray(1)
        mRegisterTemplate = ByteArray(1)
        mVerifyTemplate = ByteArray(1)

        //USB Permissions
        mPermissionIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE
        )
        filter = IntentFilter(ACTION_USB_PERMISSION)
        jsgfplib = JSGFPLib(this, getSystemService(USB_SERVICE) as UsbManager)
        bSecuGenDeviceOpened = false
        usbPermissionRequested = false
        mAutoOnEnabled = false
        autoOn = SGAutoOnEventNotifier(jsgfplib, this)
        nCaptureModeN = 0
        Log.d(TAG, "Exit onCreate()")


        /*//USB Permissions
        mPermissionIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE
        )
        filter = IntentFilter(ACTION_USB_PERMISSION)


        jsgfplib = JSGFPLib(this, getSystemService(Context.USB_SERVICE) as UsbManager)
        jsgfplib.Init(SGFDxDeviceName.SG_DEV_AUTO);
        jsgfplib.OpenDevice(0)*/

        enableButtons(isFingerPrintDeviceFound())
    }

    private fun setOnClickListeners() {
        binding.btnScan.setOnClickListener {
            if (isFingerPrintDeviceFound() && hasUsbPermission()) {
                enableButtons(false)
                showPlaceFingerOnDevice()
                autoOn!!.start()

            }
        }

        binding.btnRegister.setOnClickListener {
            enableButtons(false)

            showPlaceFingerOnDevice()
            registerFingerPrint()

        }


        binding.btnVerify.setOnClickListener {
            enableButtons(false)

            showPlaceFingerOnDevice()

            verifyFingerPrint()

        }
    }

    fun registerFingerPrint() {
        if (mRegisterImage != null) mRegisterImage = null
        mRegisterImage = ByteArray(mImageWidth * mImageHeight)
        bFingerprintRegistered = false
        jsgfplib.GetImageEx(
            mRegisterImage,
            IMAGE_CAPTURE_TIMEOUT_MS.toLong(),
            IMAGE_CAPTURE_QUALITY.toLong()
        )

        binding.ivFingerPrint.setImageBitmap(toGrayscale(mRegisterImage!!))
        jsgfplib.SetTemplateFormat(SGFDxTemplateFormat.TEMPLATE_FORMAT_ISO19794)

        val quality1 = IntArray(1)
        jsgfplib.GetImageQuality(
            mImageWidth.toLong(),
            mImageHeight.toLong(),
            mRegisterImage,
            quality1
        )

        var fpInfo: SGFingerInfo? = SGFingerInfo()
        fpInfo!!.FingerNumber = 1
        fpInfo.ImageQuality = quality1[0]
        fpInfo.ImpressionType = SGImpressionType.SG_IMPTYPE_LP
        fpInfo.ViewNumber = 1
        for (i in mRegisterTemplate!!.indices) mRegisterTemplate!![i] = 0

        var result = jsgfplib.CreateTemplate(fpInfo, mRegisterImage, mRegisterTemplate)

        binding.ivFingerPrint.setImageBitmap(toGrayscale(mRegisterImage!!))
        if (result == SGFDxErrorCode.SGFDX_ERROR_NONE) {
            bFingerprintRegistered = true
            val size = IntArray(1)
            jsgfplib.GetTemplateSize(mRegisterTemplate, size)

            showSuccessOrFail(true,"Fingerprint registerd")
        } else {
            showSuccessOrFail(true,"Fingerprint not registerd")
        }
        mRegisterImage = null
        fpInfo = null
    }

    //////////////////////////////////////////////////////////////////////////////////////////////
    //////////////////////////////////////////////////////////////////////////////////////////////
    fun verifyFingerPrint() {
        if (!bFingerprintRegistered) {
            binding.tvMsg.setText("Please Register a finger")
            jsgfplib.SetLedOn(false)
            return
        }
        if (mVerifyImage != null) mVerifyImage = null
        mVerifyImage = ByteArray(mImageWidth * mImageHeight)

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

        var fpInfo: SGFingerInfo? = SGFingerInfo()
        fpInfo!!.FingerNumber = 1
        fpInfo.ImageQuality = quality[0]
        fpInfo.ImpressionType = SGImpressionType.SG_IMPTYPE_LP
        fpInfo.ViewNumber = 1
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
                showSuccessOrFail(true,"Fingerprint matched!")
            } else {
                showSuccessOrFail(false,"Fingerprint not matched!")
            }
            matched = null
        } else  showSuccessOrFail(false,"Fingerprint template extraction failed.")
        mVerifyImage = null
        fpInfo = null
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
        val usbDevice: UsbDevice? = jsgfplib.GetUsbDevice()
        if (usbDevice == null) {

            val dlgAlert = AlertDialog.Builder(this)
            dlgAlert.setMessage("SecuGen fingerprint sensor not found!")
            dlgAlert.setTitle("SecuGen Fingerprint SDK")
            dlgAlert.setPositiveButton("OK",
                DialogInterface.OnClickListener { dialog, whichButton ->
                    finish()
                    return@OnClickListener
                }
            )
            dlgAlert.setCancelable(false)
            dlgAlert.create().show()

            return false
        }

        var device_info = SGDeviceInfoParam();
        val error = jsgfplib.GetDeviceInfo(device_info)
        if (error == SGFDxErrorCode.SGFDX_ERROR_NONE) {
            mImageWidth = device_info.imageWidth
            mImageHeight = device_info.imageHeight
        }

        autoOn = SGAutoOnEventNotifier(jsgfplib, this)

        return true
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
            scanFingerPrint()
        }
    }



    //////////////////////////////////////////////////////////////////////////////////////////////
    //////////////////////////////////////////////////////////////////////////////////////////////
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

    //////////////////////////////////////////////////////////////////////////////////////////////
    //////////////////////////////////////////////////////////////////////////////////////////////
    override fun onResume() {
        Log.d(TAG, "Enter onResume()")
        super.onResume()
        registerReceiver(mUsbReceiver, filter)
        var error: Long = jsgfplib.Init(SGFDxDeviceName.SG_DEV_AUTO)
        if (error != SGFDxErrorCode.SGFDX_ERROR_NONE) {
            val dlgAlert = AlertDialog.Builder(this)
            if (error == SGFDxErrorCode.SGFDX_ERROR_DEVICE_NOT_FOUND) dlgAlert.setMessage("The attached fingerprint device is not supported on Android") else dlgAlert.setMessage(
                "Fingerprint device initialization failed!"
            )
            dlgAlert.setTitle("SecuGen Fingerprint SDK")
            dlgAlert.setPositiveButton("OK",
                DialogInterface.OnClickListener { dialog, whichButton ->
                    finish()
                    return@OnClickListener
                }
            )
            dlgAlert.setCancelable(false)
            dlgAlert.create().show()
        } else {
            val usbDevice: UsbDevice = jsgfplib.GetUsbDevice()
            if (usbDevice == null) {
                val dlgAlert = AlertDialog.Builder(this)
                dlgAlert.setMessage("SecuGen fingerprint sensor not found!")
                dlgAlert.setTitle("SecuGen Fingerprint SDK")
                dlgAlert.setPositiveButton("OK",
                    DialogInterface.OnClickListener { dialog, whichButton ->
                        finish()
                        return@OnClickListener
                    }
                )
                dlgAlert.setCancelable(false)
                dlgAlert.create().show()
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
                //Thread thread = new Thread(this);
                //thread.start();
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
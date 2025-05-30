package uk.ac.lshtm.keppel.biomini

import android.app.PendingIntent
import android.app.PendingIntent.FLAG_MUTABLE
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.suprema.BioMiniFactory
import com.suprema.IBioMiniDevice
import com.suprema.IUsbEventHandler
import uk.ac.lshtm.keppel.biomini.eacrugged.EACRuggedConnectionManager
import uk.ac.lshtm.keppel.core.CaptureResult
import uk.ac.lshtm.keppel.core.Scanner
import uk.ac.lshtm.keppel.core.toHexString

private const val TAG = "KeppelBioMiniScanner"

@Suppress("unused")
class BioMiniScanner(private val context: Context) : Scanner, BroadcastReceiver() {

    private val BASE_EVENT = 3000
    private val REMOVE_USB_DEVICE = BASE_EVENT + 2
    private val UPDATE_DEVICE_INFO = BASE_EVENT + 3
    private val MAKE_DELAY_1SEC = BASE_EVENT + 5
    private val ADD_DEVICE = BASE_EVENT + 6
    private val CLEAR_VIEW_FOR_CAPTURE = BASE_EVENT + 8
    private val SET_TEXT_LOGVIEW = BASE_EVENT + 10
    private val MAKE_TOAST = BASE_EVENT + 11
    private val SHOW_CAPTURE_IMAGE_DEVICE = BASE_EVENT + 12

    private val ACTION_USB_PERMISSION = "uk.ac.lshtm.keppel.biomini.USB_PERMISSION"

    var mUsbDevice: UsbDevice? = null
    private var mUsbManager: UsbManager? = null
    private var mBioMiniFactory: BioMiniFactory? = null
    var mCurrentDevice: IBioMiniDevice? = null
    private lateinit var onConnected: (Boolean) -> Unit

    private var onDisconnected: (() -> Unit)? = null

    private fun removeDevice() {
        Log.d(TAG, "ACTION_USB_DEVICE_DETACHED")
        val factory = mBioMiniFactory
        if (factory != null) {
            factory.removeDevice(mUsbDevice)
            factory.close()
        }
        mUsbDevice = null
        mCurrentDevice = null
    }

    private val connectionManager =
        CONNECTION_MANAGERS.find { it.supportedDevices().contains(getDeviceName()) }

    private fun createBioMiniDevice() {
        mBioMiniFactory?.close()
        mBioMiniFactory = object : BioMiniFactory(context, mUsbManager) {
            override fun onDeviceChange(event: IUsbEventHandler.DeviceChangeEvent, dev: Any?) {
                Log.d(TAG, "onDeviceChange : $event")
            }
        }
        Log.d(TAG, "new BioMiniFactory( ) : $mBioMiniFactory")
        mBioMiniFactory?.setTransferMode(IBioMiniDevice.TransferMode.MODE2)
        val _result: Boolean? = mBioMiniFactory?.addDevice(mUsbDevice)
        if (_result == true) {
            mCurrentDevice = mBioMiniFactory?.getDevice(0)
            if (mCurrentDevice != null) {
                onConnected(true)
                Log.d(TAG, "mCurrentDevice attached : $mCurrentDevice")
            } else {
                onConnected(false)
                Log.d(TAG, "mCurrentDevice is null")
            }
        } else {
            Log.d(TAG, "addDevice is fail!")
        }
    }

    override fun connect(onConnected: (Boolean) -> Unit): Scanner {
        connectionManager?.connect()

        getDeviceName().let { Log.d(TAG, "Device name: $it") }
        this.onConnected = onConnected

        if (mUsbManager == null) {
            mUsbManager =
                context.getSystemService(Context.USB_SERVICE) as UsbManager
        }

        registerBroadcastReceiver()
        findAndRequestPermission()
        return this
    }

    override fun capture(): CaptureResult? {
        return mCurrentDevice?.let { device ->
            setParameters(device)
            Log.d(TAG, "START!")
            val captureOption: IBioMiniDevice.CaptureOption = IBioMiniDevice.CaptureOption()
            captureOption.captureFuntion = IBioMiniDevice.CaptureFuntion.CAPTURE_SINGLE
            captureOption.extractParam.captureTemplate = true
            captureOption.extractParam.maxTemplateSize =
                IBioMiniDevice.MaxTemplateSize.MAX_TEMPLATE_1024

            val captureResponder = BlockingCaptureResponder(device)
            val result = device.captureSingle(captureOption, captureResponder, true)

            if (!result) {
                Log.d(TAG, "capture failed")
            }

            val captureResult = captureResponder.awaitResult(TIMEOUT_MS)
            return captureResult?.first?.data.let {
                if (it != null) {
                    CaptureResult(it.toHexString(), captureResult?.second ?: 0)
                } else {
                    null
                }
            }
        }
    }

    override fun stopCapture() {
        mCurrentDevice?.let {
            val result = it.abortCapturing()
            Log.d(TAG, "run: abortCapturing : $result")
        }
    }

    override fun disconnect() {
        var result = 0
        val factory = mBioMiniFactory // avoid null errors
        if (factory != null) {
            if (mUsbDevice != null) result = factory.removeDevice(mUsbDevice)
            if (result == IBioMiniDevice.ErrorCode.OK.value() || result == IBioMiniDevice.ErrorCode.ERR_NO_DEVICE.value()) {
                factory.close()
                context.unregisterReceiver(this)
                mUsbDevice = null
                mCurrentDevice = null
            }
        }

        removeDevice()
        connectionManager?.disconnect()
    }

    override fun onDisconnect(onDisconnected: () -> Unit) {
        this.onDisconnected = onDisconnected
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action: String? = intent.getAction()
        when (action) {
            ACTION_USB_PERMISSION -> {
                Log.d(TAG, "ACTION_USB_PERMISSION")
                val hasUsbPermission: Boolean =
                    intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                val usbDevice = mUsbDevice
                if (hasUsbPermission && usbDevice != null) {
                    Log.d(
                        TAG,
                        usbDevice.getDeviceName() + " is acquire the usb permission. activate this device."
                    )
                    createBioMiniDevice()
                } else {
                    Log.d(TAG, "USB permission is not granted!")
                }
            }

            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                Log.d(TAG, "ACTION_USB_DEVICE_ATTACHED")
                findAndRequestPermission()
            }

            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                Log.d(TAG, "ACTION_USB_DEVICE_DETACHED")
                removeDevice()
                onDisconnected?.invoke()
            }

            else -> {}
        }
    }

    private fun registerBroadcastReceiver() {
        Log.d(TAG, "start initUsbListener!")

        ContextCompat.registerReceiver(
            context,
            this,
            IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            context,
            this,
            IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            context,
            this,
            IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun findAndRequestPermission() {
        Log.d("TAG", "start!")
        val usbManager = mUsbManager
        if (usbManager == null) {
            Log.d(TAG, "mUsbManager is null")
            return
        }
        if (mUsbDevice != null) {
            Log.d(TAG, "usbdevice is not null!")
            return
        }
        val deviceList: java.util.HashMap<String, UsbDevice> = usbManager.getDeviceList()
        val deviceIter: Iterator<UsbDevice> = deviceList.values.iterator()
        while (deviceIter.hasNext()) {
            val _device: UsbDevice = deviceIter.next()
            Log.d(TAG, "device id" + _device.getVendorId())
            if (_device.getVendorId() == 0x16d1) {
                Log.d(TAG, "found suprema usb device")
                mUsbDevice = _device

                val permissionIntent = PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent(ACTION_USB_PERMISSION).also {
                        it.setPackage(context.packageName)
                    },
                    FLAG_MUTABLE,
                )
                mUsbManager?.requestPermission(mUsbDevice, permissionIntent)
            } else {
                Log.d(TAG, "This device is not suprema device!  : " + _device.getVendorId())
            }
        }
    }

    private fun setParameters(iBioMiniDevice: IBioMiniDevice) {
        iBioMiniDevice.setParameter(
            IBioMiniDevice.Parameter(
                IBioMiniDevice.ParameterType.TEMPLATE_TYPE,
                IBioMiniDevice.TemplateType.ISO19794_2.value().toLong(),
            ),
        )

        iBioMiniDevice.setParameter(
            IBioMiniDevice.Parameter(IBioMiniDevice.ParameterType.TIMEOUT, TIMEOUT_MS)
        )
    }

    companion object {
        private const val TIMEOUT_MS = 30000L
        private val CONNECTION_MANAGERS = listOf(EACRuggedConnectionManager())
    }
}

fun getDeviceName(): String {
    val manufacturer: String = Build.MANUFACTURER
    val model: String = Build.MODEL
    return if (model.lowercase(java.util.Locale.getDefault())
            .startsWith(manufacturer.lowercase(java.util.Locale.getDefault()))
    ) {
        capitalize(model)
    } else {
        capitalize(manufacturer) + " " + model
    }
}

private fun capitalize(s: String?): String {
    if (s == null || s.length == 0) {
        return ""
    }
    val first = s[0]
    return if (Character.isUpperCase(first)) {
        s
    } else {
        first.uppercaseChar().toString() + s.substring(1)
    }
}

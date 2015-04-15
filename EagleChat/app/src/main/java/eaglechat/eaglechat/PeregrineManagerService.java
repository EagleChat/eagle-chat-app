package eaglechat.eaglechat;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.widget.Toast;

import com.hoho.android.usbserial.driver.CdcAcmSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PeregrineManagerService extends Service {
    private static final String TAG = "eaglechat.eaglechat";

    public static final String SERVICE_AVAILABLE = TAG + ".SERVICE_AVAILABLE";
    public static final String SERVICE_DISCONNECTED = TAG + ".SERVICE_DISCONNECTED";

    public static boolean isConnected = false;

    private final SerialInputOutputManager.Listener mListener =
            new SerialInputOutputManager.Listener() {
                @Override
                public void onRunError(Exception e) {
                    Log.d(TAG, "Runner stopped.");
                }

                @Override
                public void onNewData(final byte[] data) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            PeregrineManagerService.this.updateReceivedData(data);
                        }
                    });
                }
            };

    private final IBinder mBinder = new PeregrineBinder();
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    UsbDevice mUsbDevice;
    Handler handler;
    private UsbManager mUsbManager;
    private SerialInputOutputManager mSerialIoManager;
    private UsbSerialPort mPort;

    private Peregrine mPeregrine;

    BroadcastReceiver mDeviceStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            String action = intent.getAction();

            if (action.equals(DeviceConnectionReceiver.DEVICE_DETACHED)) {

                Log.d(TAG, "Device detached. Stopping service.");


                Intent disconnected = new Intent(SERVICE_DISCONNECTED);
                LocalBroadcastManager.getInstance(PeregrineManagerService.this).sendBroadcast(disconnected);

                stopIoManager();

                stopSelf();
            }
        }
    };


    public PeregrineManagerService() {

    }


    /**
     * Called on new data from peripheral
     *
     * @param data
     */
    private void updateReceivedData(byte[] data) {
        String boardSays = new String(data);
        Log.d(TAG, boardSays);
        mPeregrine.onData(boardSays);
    }

    @Override
    public void onCreate() {

        super.onCreate();
        handler = new Handler();

        LocalBroadcastManager.getInstance(this).registerReceiver(mDeviceStateReceiver,
                new IntentFilter(DeviceConnectionReceiver.DEVICE_DETACHED));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        Log.d(TAG, "Received onStartCommand");

        if (intent != null) {

            Log.d(TAG, intent.toString());
            Toast.makeText(this, getString(R.string.note_deviceConnected), Toast.LENGTH_SHORT).show();

            mUsbDevice = null;
            mUsbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

            mUsbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

            UsbSerialDriver driver = new CdcAcmSerialDriver(mUsbDevice);
            onHasPort(driver);

            LocalBroadcastManager manager = LocalBroadcastManager.getInstance(this);

            manager.sendBroadcast(new Intent(SERVICE_AVAILABLE));

            isConnected = true;
        }

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    private void runOnUiThread(Runnable runnable) {
        handler.post(runnable);
    }

    private void onHasPort(UsbSerialDriver driver) {
        UsbSerialPort port = driver.getPorts().get(0);
        UsbDeviceConnection connection = mUsbManager.openDevice(driver.getDevice());
        if (connection == null) {
            Log.d(TAG, "Could not open connection.");
            return;
        }

        Log.d(TAG, "Open connection on port: " + port.toString());

        Log.d(TAG, "Serial number: " + connection.getSerial());
        try {
            port.open(connection);
            port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            /*try {
                Log.d(TAG, "Writing to device.");
                //port.write("\n".getBytes(), 1000);
            } catch (IOException ex) {
                Log.e(TAG, ex.toString());
            }*/

        } catch (IOException ex) {
            Log.e(TAG, "Open failed. Exception: ");
            Log.e(TAG, ex.toString());
            try {
                port.close();
            } catch (IOException ex2) {
                // Ignore.
            }
            return;
        }

        mPort = port; // save newly opened port

        onDeviceStateChange();


    }

    private void onDeviceStateChange() {
        stopIoManager();
        startIoManager();
        try {
            mPort.setDTR(true);
        } catch (IOException ex) {
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        isConnected = false;

        Log.d(TAG, "Destroying EagleChatCommService");
        stopIoManager();

    }

    private void stopIoManager() {
        isConnected = false;

        if (mSerialIoManager != null) {
            Log.i(TAG, "Stopping io manager ..");
            mSerialIoManager.stop();
            mSerialIoManager = null;
        }
        if (mPeregrine != null) {
            mPeregrine.setSerial(null);
            mPeregrine = null;
        }
    }

    private void startIoManager() {
        if (mPort != null) {
            Log.i(TAG, "Starting io manager ..");
            mSerialIoManager = new SerialInputOutputManager(mPort, mListener);
            mExecutor.submit(mSerialIoManager);
            mPeregrine = new Peregrine(mSerialIoManager);
            //mPeregrine.setSerial(mSerialIoManager);

            isConnected = true;
        }
    }

    public class PeregrineBinder extends Binder {
        Peregrine getService() {
            return mPeregrine;
        }
    }
}
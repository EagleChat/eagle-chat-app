package eaglechat.eaglechat;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.database.Cursor;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.net.Uri;
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

import org.jdeferred.AlwaysCallback;
import org.jdeferred.Deferred;
import org.jdeferred.DeferredFutureTask;
import org.jdeferred.DoneCallback;
import org.jdeferred.DoneFilter;
import org.jdeferred.DonePipe;
import org.jdeferred.FailCallback;
import org.jdeferred.FailPipe;
import org.jdeferred.Promise;
import org.jdeferred.impl.DeferredObject;

import java.io.IOException;
import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PeregrineManagerService extends Service {
    private static final String TAG = "eaglechat.eaglechat";

    public static final String SERVICE_AVAILABLE = TAG + ".SERVICE_AVAILABLE";
    public static final String SERVICE_DISCONNECTED = TAG + ".SERVICE_DISCONNECTED";
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
    public static boolean isConnected = false;
    private final IBinder mBinder = new PeregrineBinder();
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    UsbDevice mUsbDevice;
    Handler mHandler;

    private UsbManager mUsbManager;
    private SerialInputOutputManager mSerialIoManager;
    private UsbSerialPort mPort;
    private Peregrine mPeregrine;
    private SendManager mSendManager;


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
        mHandler = new Handler();


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
        mHandler.post(runnable);
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
            mPeregrine = new Peregrine(this, mSerialIoManager);
            //mPeregrine.setSerial(mSerialIoManager);

            isConnected = true;
        }
    }

    public void startSendManager() {
        if (mSendManager == null) {
            mSendManager = new SendManager();
        }
        mSendManager.start();
    }

    public class PeregrineBinder extends Binder {
        Peregrine getService() {
            return mPeregrine;
        }
    }

    private class SendManager {

        private final String[] msgProj =
                {MessagesTable.COLUMN_ID, MessagesTable.COLUMN_RECEIVER,
                        MessagesTable.COLUMN_CONTENT, MessagesTable.COLUMN_SENT};
        private final String[] contactProj =
                {ContactsTable.COLUMN_ID, ContactsTable.COLUMN_NODE_ID, ContactsTable.COLUMN_PUBLIC_KEY};
        Cursor mCursor;
        boolean started = false;
        private Map<Integer, String> mNodeIdsMap;
        private Map<Integer, String> mKeysMap;

        public SendManager() {
            Log.d(TAG, "SendManager created");
            mNodeIdsMap = new Hashtable<>();
            mKeysMap = new Hashtable<>();
        }

        public void start() {
            if (!started) {
                queryAndSend();

//                mHandler.post(new Runnable() {
//                    @Override
//                    public void run() {
//                        queryAndSend();
//                    }
//                });
            }
            started = true;
        }

        private void queryAndSend() {
            mCursor = getContentResolver().query(
                    DatabaseProvider.MESSAGES_UNSENT_URI,
                    msgProj, null, null, null);
            mCursor.registerContentObserver(mObserver);

            int msgIndex = mCursor.getColumnIndex(MessagesTable.COLUMN_ID);
            int recIndex = mCursor.getColumnIndex(MessagesTable.COLUMN_RECEIVER); // contact id for dest
            int contentIndex = mCursor.getColumnIndex(MessagesTable.COLUMN_CONTENT);
            int sentIndex = mCursor.getColumnIndex(MessagesTable.COLUMN_SENT);

            Deferred deferred = null;

            while (mCursor.moveToNext()) {
                // sanity check
                int sent = mCursor.getInt(sentIndex);
                if (sent == MessagesTable.SENT)
                    continue;

                int msgId = mCursor.getInt(msgIndex); // id of this row

                int recId = mCursor.getInt(recIndex);

                String recNodeId;
                String publicKey;

                if (mNodeIdsMap.containsKey(recId)) {

                    recNodeId = mNodeIdsMap.get(recId);
                    publicKey = mKeysMap.get(recId);

                } else {

                    Uri contactUri = ContentUris.withAppendedId(DatabaseProvider.CONTACTS_URI, recId);
                    Cursor contact = getContentResolver().query(contactUri, contactProj, null, null, null);

                    if (contact.moveToNext()) {

                        recNodeId = contact.getString(contact.getColumnIndex(ContactsTable.COLUMN_NODE_ID));
                        publicKey = contact.getString(contact.getColumnIndex(ContactsTable.COLUMN_PUBLIC_KEY));
                        mNodeIdsMap.put(recId, recNodeId);
                        mKeysMap.put(recId, publicKey);

                    } else {
                        // We can't send this message because we don't know how to address it
                        // this should never happen
                        Log.d(TAG, "Skipping message with id = " + msgId + " because there is no contact with id = " + recId);
                        continue;
                    }
                    contact.close();
                }

                // we should have all identifies for the destination by now

                String content = mCursor.getString(contentIndex);

                final String frecNodeId = recNodeId;
                final String fpublicKey = publicKey;
                final String fcontent = content;
                final int fmsgId = msgId;

                Deferred catchDeferred = new DeferredObject();

                final Deferred oldDeferred = deferred;

                deferred = sendMessage(catchDeferred, Integer.parseInt(frecNodeId, 16), fpublicKey, fcontent, fmsgId);

                final Deferred resendDeferred = sendMessage(oldDeferred, Integer.parseInt(frecNodeId, 16), fpublicKey, fcontent, fmsgId);

                final Deferred publicKeyDeferred = sendKey(resendDeferred, Integer.parseInt(frecNodeId, 16), fpublicKey);


                catchDeferred.done(new DoneCallback() {
                    @Override
                    public void onDone(Object result) {
                        if (oldDeferred != null)
                            oldDeferred.resolve(new Object());
                    }
                }).fail(new FailCallback() {
                    @Override
                    public void onFail(Object result) {
                        publicKeyDeferred.resolve(new Object());
                    }
                });

            }
            if (deferred != null) {
                deferred.resolve("RESOLVED!!!!");
            }
        }

        private Deferred sendMessage(final Deferred outerDeferred, final int nodeId, final String publicKey, final String content, final int messageId) {

            final Deferred d = new DeferredObject();

            Promise p = d.promise();
            Log.d(TAG, "== 383 == commandSendMessage");
            p.then(new DonePipe() {
                @Override
                public Promise pipeDone(Object result) {
                    return mPeregrine.commandSendMessage(nodeId, content)

                            .done(new DoneCallback<String>() {
                                @Override
                                public void onDone(String result) {
                                    Log.d(TAG, "sendMessage resolving outerDeferred.");
                                    outerDeferred.resolve("DONE");
                                }
                            }).fail(new FailCallback<String>() {
                                @Override
                                public void onFail(String result) {
                                    Log.d(TAG, "sendMessage rejecting outerDeferred.");
                                    outerDeferred.reject("SEND PUBLIC KEY");
                                }
                            });
                }
            });

            return d;
        }

        private Deferred sendKey(final Deferred outerDeferred, final int nodeId, final String publicKey) {

            final Deferred d = new DeferredObject();

            Promise p = d.promise();
            Log.d(TAG, "== 418 == commandSendPublicKey");
            p.then(new DonePipe() {
                @Override
                public Promise pipeDone(Object result) {
                    return mPeregrine.commandSendPublicKey(nodeId, publicKey)

                            .done(new DoneCallback<String>() {
                                @Override
                                public void onDone(String result) {
                                    Log.d(TAG, "sendMessage resolving outerDeferred.");
                                    outerDeferred.resolve("DONE");
                                }
                            });
                }
            });

            return d;
        }

        private final ContentObserver mObserver = new ContentObserver(mHandler) {

            @Override
            public void onChange(boolean selfChange) {
                Log.d(TAG, "onChange");
                queryAndSend();
//                mHandler.post(new Runnable() {
//                    @Override
//                    public void run() {
//                        queryAndSend();
//                    }
//                });

            }

        };


    }


}


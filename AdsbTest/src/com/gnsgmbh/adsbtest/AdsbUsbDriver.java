/**
 * 
 */
package com.gnsgmbh.adsbtest;


import java.nio.ByteBuffer;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;
import android.util.Log;


/**
 * ADS-B Driver Class for USB devices.<p>
 * 
 * Use {@link open} to open the driver and attach it to a UsbDevice,
 * {@link close} it after use.
 * The using thread must ensure that {@link readAdsbMsg} is called frequently enough
 * so that the internal FIFO does not overflow.<p>
 * 
 * The class implements an internal thread which is responsible for handling the
 * USB bulk transfers. Depending on the rest of the application, the priority of 
 * this thread might have to be raised. The size of the bulk transfers not exceeding 
 * 32 bytes tested in the main loop of {@link run} is a good indicator for sufficient 
 * priority.<p>
 * 
 * Please also refer to the discussion on USB device attach/detach events in the 
 * demo's MainActivity class.
 * 
 * @version 1.0
 * @author C. Diehl, GNS GmbH
 */
public class AdsbUsbDriver extends AdsbDriver implements Runnable {

    private static final String TAG = AdsbUsbDriver.class.getSimpleName();
	
    private static final int BULK_BUF_SIZE = 64;
    private static final int FIFO_MSG_NUM = 128; // Maximum number of messages in the FIFO
    private static final int ADSB_PROT_MSG_SIZE = 2 * AdsbMsgFifo.MSG_SIZE; // Maximum number of message bytes in the protocol
    
	private final UsbManager mManager;
	private UsbDevice mDevice;
	private UsbDeviceConnection mConnection;
    private UsbInterface mInterface;
    private UsbEndpoint mRxEndpoint;

    private final AdsbMsgFifo mMsgFifo= new AdsbMsgFifo(FIFO_MSG_NUM);
    
    private volatile boolean mRunThread;
    private Thread mThread;

    /**
     * Checks whether a given {@link UsbDevice} is supported by this driver class.
     * 
     * @param device The UsbDevice to examine
     * @return True if the device is supported by this driver
     */
    public static boolean IsSupported(UsbDevice device) {
    	return (device.getVendorId() == 0x04d8) && (device.getProductId() == 0xf8e8);
    }
    
	/**
	 * Class constructor specifying the USB manager to use.
	 * 
	 * @param manager Reference to the system's USB manager
	 */
	public AdsbUsbDriver(UsbManager manager) {
		super();
		mManager = manager;
	}

	/**
	 * Open the driver using a specific device.
	 * 
	 * @param device The {@link UsbDevice} to associate with this driver.
	 * @return True if the driver has been opened successfully.
	 */
	public boolean open(UsbDevice device) {
		boolean ok = (mConnection == null);
		if (!ok)
			Log.e(TAG, "Trying to open already opened driver!");
		else {
			// Make sure we have a correct device
			ok = IsSupported(device);
			if (!ok)
				Log.e(TAG, "Unsupported USB device!");
		}
		// Open the USB device
		if (ok)
		{
			mDevice = device;
			mConnection = mManager.openDevice(mDevice);
			ok = (mConnection != null);
		}
		// Obtain the data interface
		if (ok) {
	        Log.d(TAG, "Device is open.");
	        mInterface = mDevice.getInterface(1);
	        ok = mConnection.claimInterface(mInterface, true);
	        //Log.d(TAG, "Claim Ifc yields " + ok);
	        if (!ok)
	        	mInterface = null;
		}
		// Create the receiving endpoint and start I/O thread
		if (ok) {
	        mRxEndpoint = mInterface.getEndpoint(1);
	        resetRxParser();
	        mMsgFifo.clear();
	        mRunThread = true;
	        mThread = new Thread(this);
	        mThread.start();
		} 

		// Clean up on error
		if (!ok) {
			if (mConnection != null) {
				if (mInterface != null) 
					mConnection.releaseInterface(mInterface);
				mConnection.close();
			}
			mInterface = null;
			mConnection = null;
		}
		
		return ok;
	}

	/**
	 * Close the driver.<p>
	 * 
	 * The associated UsbDevice will be released.
	 */
	public void close() {
		if (mConnection != null) {
			// Closing the connection will cause any pending bulk-transfer in our thread to return immediately
			mRunThread = false;
			mConnection.releaseInterface(mInterface); 
			mConnection.close();
			try {
				// Wait for thread to terminate
				mThread.join();
			} catch (InterruptedException e) {}
			mConnection = null;
			mInterface = null;
			mDevice = null;
			Log.i(TAG, "Device closed");
		}
	}
	
	/**
	 * Returns whether the driver is currently open.
	 * 
	 * @return True if the driver is open.
	 */
	public boolean isOpen() {
		return (mConnection != null);
	}
	
	/**
	 * Returns whether this driver is currently using the given {@link UsbDevice}
	 * 
	 * @param device The UsbDevice to examine.
	 * @return True if the driver is currently using the device.
	 */
	public boolean isUsing(UsbDevice device) {
		return (mConnection != null) && (device != null) && device.equals(mDevice);
	}
	
	/**
	 * {@inheritDoc}
	 */
	@Override
    public byte[] readAdsbMsg() {
    	byte[] data = null;
    	
    	if (mConnection != null) {
    		final byte[] d = mMsgFifo.getCurrRdMsg(); 
    		if (d != null) {
    			data = d.clone(); // Return a copy!
    			mMsgFifo.freeCurrRdMsg();
    		}
    	}

   		return data;
    }
    
	
	// === Thread ===

	public void run() {
        Log.i(TAG, "Thread running ..");
        // Allocate buffers for USB bulk transfers
        final ByteBuffer data0 = ByteBuffer.allocateDirect(BULK_BUF_SIZE);
        final ByteBuffer data1 = ByteBuffer.allocateDirect(BULK_BUF_SIZE);
        // Zero-out the buffers
        final byte[] dataZero = new byte[BULK_BUF_SIZE];
        data0.put(dataZero);
        data1.put(dataZero);
        // Create requests for bulk transfers
        final UsbRequest usbRq0 = new UsbRequest();
        usbRq0.initialize(mConnection, mRxEndpoint);
        usbRq0.setClientData(data0);
        final UsbRequest usbRq1 = new UsbRequest();
        usbRq1.initialize(mConnection, mRxEndpoint);
        usbRq1.setClientData(data1);
        // Queue all available requests
		usbRq0.queue(data0, BULK_BUF_SIZE);
		usbRq1.queue(data1, BULK_BUF_SIZE);
		// The main loop ...
		while (mRunThread) {
			// Wait for a request to become completed 
			final UsbRequest usbRq = mConnection.requestWait();
			// Check for abnormal termination
			if (usbRq == null) {
				// Most likely the user has plugged-out the USB stick
				Log.i(TAG, "UsbRequest aborted -> terminating thread");
				mRunThread = false;
				break;
			} 
			if (mRunThread) {
				// Handle the received data and re-queue request.
				// CAREFUL: Android has a bug which does not allow to find out how many 
				// bytes have actually been read (http://code.google.com/p/android/issues/detail?id=28023).
				// Since our protocol never transmits any zeroes we overcome this by
				// zeroing out the complete buffer before queuing it and only 
				// process the returned data up to the first zero. 
				final ByteBuffer data = (ByteBuffer)usbRq.getClientData();
				handleRxData(data);
				usbRq.queue(data, BULK_BUF_SIZE);
			}
		}
		// Clean-Up
		usbRq0.cancel();
		usbRq1.cancel();
		usbRq0.close();
		usbRq1.close();
        Log.i(TAG, "Thread stopped.");
	}
	
    // === Parser ===
	
	/**
	 * Reference to the current write buffer of mMsgFifo
	 */
    private byte[] mRxMsg = null;
    
    /**
     * Nibble based(!) index into the current write buffer or -1 if message not yet started 
     * (i.e. parser currently searching for message start).<p>
     * 0/1 reference byte 0 hi/lo nibble resepctively, 
     * 2/3 reference byte 1 hi/lo nibble resepctively, etc. 
     */
    private int mRxMsgIdx = -1; // -1 = message not started, otherwise nibble-index(!)
    
    /**
     * Convert ASCII hex representation to nibble.
     * @return Nibble in lower 4 bits or -1 on error   
     */
    private static byte hexNibble(byte by) {
    	if (by < '0')
    		return -1;
    	else if (by <= '9')
    		return (byte)(by - '0');
    	else if (by < 'A')
    		return -1;
    	else if (by <= 'F')
    		return (byte)(by - ('A' - 0xa));
    	else
    		return -1;
    }
    
    /**
     * Parser for the incoming byte stream.
     * 
     * @param by The next byte to be parsed
     */
    private void handleRxByte(byte by) {
    	if (by == '*') {
    		if (mRxMsgIdx >= 0) {
        		Log.w(TAG, "Unexpected msg start!");
        		mRxMsgIdx = 0; // Restart recording
    		} else {
	    		if (mRxMsg == null)
	    			mRxMsg = mMsgFifo.getCurrWrMsg();
	    		// else re-use existing
	    		if (mRxMsg == null)
	        		Log.w(TAG, "Fifo is full!");
	    		else 
	        		mRxMsgIdx = 0; // Start recording
    		}
    	} else if (by == ';') {
    		if (mRxMsgIdx == 28) { // Mode-S extended squitter
    			int type = (mRxMsg[0] & 0xff) >>> 3;
    			if ( (type >= 17) && (type <= 19) ) { 
    				// ADSB DF=17,18,19
    				mMsgFifo.queueCurrWrMsg();
    				mRxMsg = null; // Need a new wr-buffer for next message
    			}
    			// else non-ADSB -> silently discard (re-use wr-buffer)
    		}
    		else if (mRxMsgIdx == 14)
        		; // Mode-S std squitter -> silently discard (re-use wr-buffer)
    		else
    			Log.w(TAG, "Unexpected msg end! " + mRxMsgIdx); // (re-use wr-buffer)
    		mRxMsgIdx = -1; // Reset parser logic
    	} else if (mRxMsgIdx >= ADSB_PROT_MSG_SIZE) {
    		Log.w(TAG, "Msg too long!");
    		mRxMsgIdx = -1;
    	} else if (mRxMsgIdx >= 0) {
    		byte nbl = hexNibble(by);
    		if (nbl > 0xf) {
        		Log.w(TAG, "Bad nibble " + by);
        		mRxMsgIdx = -1; // Reset parser logic
    		} else if ( (mRxMsgIdx & 1) == 0 ) 
    			mRxMsg[mRxMsgIdx++ >> 1] = (byte)(hexNibble(by) << 4);
    		else
    			mRxMsg[mRxMsgIdx++ >> 1] |= hexNibble(by);
    	}
    }

    /**
     * Parses a block of incoming data.<p>
     * 
     * The data is parsed up to the first zero byte.
     * All parsed bytes are replaced by zeroes in the supplied buffer.
     * 
     * @param data
     */
    private void handleRxData(ByteBuffer data) {
    	byte by;
    	// We read data up to the first byte, zeroing each byte in the buffer as we go.
    	for (int i=0; i<BULK_BUF_SIZE; ++i) {
    		by = data.get(i);
    		if (by == 0) {
    			// Check the actual bulk buffer size.
    			// If it gets bigger than 32 this indicates that we do not 
    			// read quick enough from the USB device. While we probably not yet 
    			// loosing data, this is a warning sign to be investigated.
    			if (i > 32) 
    				Log.w(TAG, "USB Bulk BufSize: " + i);
    			break;
    		}
    		// Zero all bytes as we go
    		data.put(i, (byte) 0);
    		handleRxByte(by);
    	}
    }
    
    /**
     * Reset the protocol parser.
     */
    private void resetRxParser() {
		mRxMsgIdx = -1;
    }

	
}

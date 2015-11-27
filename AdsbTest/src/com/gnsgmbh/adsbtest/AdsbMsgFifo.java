package com.gnsgmbh.adsbtest;

import android.util.Log;

/**
 * A thread-safe ADS-B Message FIFO.<p>
 * 
 * The FIFO is implemented as a circular buffer relying on atomic read/write indices and
 * thus does not need additional synchronization as long as the read thread only calls 
 * read related methods and the write thread only calls write related methods<p>
 *
 * Read/Write access from the respective threads should be organized as follows:<p>
 * Writing Thread: getCurrWrMsg() -> Fill with data (if not null) -> queueCurrWrMsg()<p>
 * Reading Thread: getCurrRdMsg() -> Process data (if not null) -> freeCurrRdMsg()
 * 
 * @author C. Diehl, GNS GmbH
 */
public class AdsbMsgFifo {
    private static final String TAG = AdsbMsgFifo.class.getSimpleName();
	public static final int MSG_SIZE = 14;
	
	private final int mMsgCount; 
	private final byte[][] mBuf;
	private volatile int mIdxRd = 0;
	private volatile int mIdxWr = 0;

	/**
	 * Class constructor specifying max. number of ADS-B messages.
	 * 
	 * @param msgCount Maximum number of ADS-B messages that the FIFO can hold
	 */
	public AdsbMsgFifo(int msgCount) {
		mMsgCount = msgCount+1; // Use one message more since we need one element to tell empty from full
		mBuf = new byte[mMsgCount][MSG_SIZE];
	}
	
	/**
	 * Empty the FIFO.<p>
	 * 
	 * External synchronization: The caller is responsible that no other thread 
	 * accesses the instance while this function executes!
	 */
	public void clear() {
		mIdxRd = mIdxWr = 0;
	}
	
	// --- Only to be used by writing thread ---
	
	/**
	 * Retrieve the current empty write buffer. After the buffer has been filled
	 *  with an ADS-B message call {@link queueCurrWrMsg}.<p>
	 *  
	 * May only be called from the writing thread! queueCurrWrMsg must be called 
	 * before fetching the next buffer.
	 * 
	 * @return An empty buffer of size MSG_SIZE
	 */
	public byte[] getCurrWrMsg() {
		return mBuf[mIdxWr];
	}
	
	/**
	 * Put the current write buffer in the FIFO.<p>
	 * 
	 * Put the current write buffer obtained via {@link getCurrWrMsg} in the FIFO.<p>
	 * 
	 * May only be called from the writing thread!
	 * 
	 * @return true if the operation was successfull, false if the FIFO is full
	 */
	public boolean queueCurrWrMsg() {
		// Careful with the index management so we stay inherently thread-safe
		int idxTmp = (mIdxWr + 1) % mMsgCount;
		if (idxTmp == mIdxRd) {
			Log.w(TAG, "FIFO is full!");
			return false; // FIFO is Full
		}
		else {
			mIdxWr = idxTmp;
			return true;
		}
	}
	
	
	// --- Only to be used by reading thread ---
	
	/**
	 * Get the current read buffer. Call {@link freeCurrRdMsg} when done.<p>
	 * 
	 * May only be called from the reading thread!
	 * 
	 * @return The current read buffer or null if FIFO is empty
	 */
	public byte[] getCurrRdMsg() {
		return (mIdxRd == mIdxWr) ? null : mBuf[mIdxRd];
	}
	
	/**
	 * Free the current ADS-B message.<p>
	 * 
	 * Free the current ADS-B message obtained via {@link getCurrRdMsg}.
	 */
	public void freeCurrRdMsg() {
		if (mIdxRd != mIdxWr)
			mIdxRd = (mIdxRd + 1) % mMsgCount;
	else
		Log.e(TAG, "Trying to free msg on empty FIFO");
	}
}

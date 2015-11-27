/**
 * 
 */
package com.gnsgmbh.adsbtest;

import java.util.ArrayList;
import java.util.List;

import com.gnsgmbh.adsbtest.AdsbPlane.CprRec;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;


/**
 * Class that connects to an AdsbDriver and keeps a list of visible planes in a data base.<p>
 * 
 * The class decodes ADS-B messages from the driver and maintains all planes in the DB 
 * with the received information. Planes that have not been received for a certain amount of
 * time (AGE_OUT_TIMEOUT) are removed from the DB.<p>
 * 
 * Care must be taken to ensure that the internal thread gets enough execution time to 
 * properly service the driver's internal FIFO to prevent it from overflowing.
 *
 * @version 1.0
 * @author C. Diehl, GNS GmbH
 */
public class AdsbManager implements Runnable {

    private static final String TAG = AdsbManager.class.getSimpleName();
    /**
     * Conversion array ICAO characters -> ASCII.
     */
    private static final byte[] mIcaoChar = {
    	' ','A','B','C','D','E','F','G','H','I','J','K','L','M','N','O',
    	'P','Q','R','S','T','U','V','W','X','Y','Z',' ',' ',' ',' ',' ',
    	' ',' ',' ',' ',' ',' ',' ',' ',' ',' ',' ',' ',' ',' ',' ',' ',
    	'0','1','2','3','4','5','6','7','8','9',' ',' ',' ',' ',' ',' '};
    /**
     * Latitude zone size for even/odd CPR.
     */
	private static final double AIR_D_LAT[] = { 6.0, 360.0/59.0 };
	/**
	 * Maximum time in ms since last position or between even/odd frames for CPR calculation.
	 */
	private static final long MAX_POS_DTIME = 15000;
	/**
	 * ms after which an inactive plane is deleted.
	 */
	private static final long AGE_OUT_TIMEOUT =   60000;
	/**
	 * ms, time interval to check for inactive planes
	 */
	private static final long AGE_CHECK_INTERVAL = 10000; 

    private AdsbDriver mAdsbDriver;       // The AdsbDriver connected to this manager
    private volatile long mMsgCount = 0;  // Total of ADS-B messages processed so far (for statistics)
	
    private volatile boolean mRunThread;
    private Thread mThread;

    /**
     * The main data base.<p>
     * All access to mPlaneDb MUST be synchronized through mPlaneDb! 
     */
    SparseArray<AdsbPlane> mPlaneDb;      // The main data base
    private volatile int mPlaneCount = 0; // Number of planes in the DB for statistics
	private long mLastAgeOutTime = 0;     // Time of the last age-out run

	/**
	 * Class constructor.
	 */
	public AdsbManager() {
		mPlaneDb = new SparseArray<AdsbPlane>(100);
	}
	
	/**
	 * Class constructor accepting a DB and msg-count.
	 * 
	 * This constructor is used to re-build the manager after a short-time destruction
	 * e.g. from an orientation change.
	 * 
	 * @param planeDb The 'old' data base
	 * @param msgCount The 'old' message count
	 */
	public AdsbManager(SparseArray<AdsbPlane> planeDb, long msgCount) {
		if (planeDb != null) 
			mPlaneDb = planeDb; // From previous instance
		else
			mPlaneDb = new SparseArray<AdsbPlane>(100);
		mMsgCount = msgCount;
	}
	
	/**
	 * Connect to a driver and start processing ADS-B messages.<p>
	 * 
	 * Care should be taken to ensure that the manager connects to the 
	 * abstract AdsbDriver and not to any specific implementation in order
	 * to allow easy integration of different ADS-B sources. 
	 * 
	 * @param drv The AdsbDriver to connect to
	 * @return True if successful
	 */
	public boolean start(AdsbDriver drv) {
		if (mAdsbDriver != null) {
			Log.e(TAG, "Already started!");
			return false;
		}
		mAdsbDriver = drv;
		
		// Get the I/O-Thread going
        mRunThread = true;
        mThread = new Thread(this);
        mThread.start();
		
		return true;
	}
	
	/**
	 * Stop the processing of ADS-B messages and release current AdsbDriver.
	 */
	public void stop() {
		if (mAdsbDriver == null)
			Log.w(TAG, "Already stopped!");
		else {
			// Stop our I/O thread and discard the driver
			mRunThread = false;
			mThread.interrupt();
			try { mThread.join(); } catch (InterruptedException e) {}
			mAdsbDriver = null;
		}
	}

	/**
	 * Return whether the manager is running
	 */
	public boolean isRunning() {
		return mRunThread;
	}

	/**
	 * Return the total number of ADS-B messages processed  
	 */
	public long getMsgCount() {
		return mMsgCount; // Return the count of messages processed until now
	}
	
	/**
	 * Return the number of planes currently in the data base
	 */
	public int getPlaneCount() {
		return mPlaneCount;
	}
	
	// === Thread ===

	/**
	 * The I/O thread.<p>
	 * 
	 * Only running while connected to a driver
	 */
	public void run() {
        Log.i(TAG, "Thread running ..");
        byte[] msg;
		while (mRunThread) {
			// All access to the data base is synchronized via the object itself
			synchronized (mPlaneDb) {
				// Deplete the driver's Rx message queue
				while ( (msg = mAdsbDriver.readAdsbMsg()) != null )
					ProcessAdsbMsg(msg);
				// Every once in a while remove old planes from DB
				long now = SystemClock.elapsedRealtime(); 
				if ( (now - mLastAgeOutTime) >= AGE_CHECK_INTERVAL ) {
					RemoveInactivePlanes(now);
					mLastAgeOutTime = now;
				}
				// Update statistics
				mPlaneCount = mPlaneDb.size();
			}
			
			try { Thread.sleep(100); } catch (InterruptedException e) {}
		}
        Log.i(TAG, "Thread stopped.");
	}
	
	/**
	 * Remove all inactive planes.<p> 
	 * Removes planes that have not sent any messages for at least AGE_OUT_TIMEOUT.
	 * 
	 * @param now Current time stamp
	 */
	private void RemoveInactivePlanes(long now) {
    	// Build a list of old entries so we can remove them in the second step.
		// Don't remove directly because that would disturb the DB structure while 
		// we iterate through it.
    	final List<Integer> icaosToRemove = new ArrayList<Integer>();
    	final int n = mPlaneDb.size();
    	for (int i=0; i<n; ++i) 
    		if ( (now - mPlaneDb.valueAt(i).mLastSeenTime) > AGE_OUT_TIMEOUT )
    			icaosToRemove.add(mPlaneDb.keyAt(i));
    	// Now remove the old entries
    	for (int icao : icaosToRemove) {
    		mPlaneDb.delete(icao);
    		//Log.d(TAG,String.format("Deleted ICAO %06X", icao));
    	}
	}

	/**
	 * Process a single ADS-B message.
	 * 
	 * @param msg message to be processed
	 */
	private void ProcessAdsbMsg(byte[] msg) {
		++mMsgCount;
		//Log.d(TAG, "MSG: " + bytesToHex(msg, AdsbMsgFifo.MSG_SIZE));
		// We only handle the following DF/CF messages: 17/*, 18/0, 18/1, 19/0
		final int df = (msg[0] >>> 3) & 0x1f;
		final int cf = msg[0] & 0x07;
		if ( !(df == 17) && !((df == 18) && ((cf & 6) == 0)) && !((df == 19) && (cf == 0)) )
			return;
		// Check CRC
		final int pi = ((int)msg[11] & 0xff) << 16 | ((int)msg[12] & 0xff) << 8 | ((int)msg[13] & 0xff);
		final int crc = Parity112Bit(msg);
		if (pi != crc) { 
			//Log.d(TAG, String.format("CRC error %06X != %06X", pi, crc));
			return;
		}
		// Msg ok -> retrieve ICAO and get/create plane DB entry
		long time = SystemClock.elapsedRealtime();
		int icao = (((int)msg[1] & 0xff) << 16) | (((int)msg[2] & 0xff) << 8) | ((int)msg[3] & 0xff);
		//Log.d(TAG, String.format("ICAO %06X", icao));
		AdsbPlane plane = mPlaneDb.get(icao);
		if (plane == null) {
			// Not yet in DB -> create new entry
			plane = new AdsbPlane(icao);
			mPlaneDb.put(icao, plane);
			//Log.d(TAG, String.format("New Plane #%d ICAO %06X", mPlaneDb.size(), icao));
		}
		plane.mLastSeenTime = time;

		// --- ME Fields (@ offset 4) ---
		
		// Handle message depending on its type
		int type = ((int)msg[4 + 0] & 0xff) >>> 3;
		if (type <= 0)
			; // Not handled
		else if (type <= 4) {
			// Flight ID
			String id = parseFlightId(msg);
			if ( (plane.mIdStr == null) || !plane.mIdStr.equals(id) ) {
				//Log.d(TAG, String.format("%s Flight ID '%s' for ICAO %06X", (plane.mIdStr == null)?"New":"Changed", id, icao));
				plane.mIdStr = id;
			}
		} else if (type <= 8) {
			// Surface position - not handled yet
		} else if (type <= 18) {
			// Air position (Barometric altitude, common)
			parseAirPosition(plane, msg, time);
			parseBaroAltitude(plane, msg);
		} else if (type <= 19) {
			final int subtype = msg[4 + 0] & 7;
			if ( (subtype >= 1) && (subtype <= 4) ) {
				// Only subtypes 1..4 are valid airborne velocity messages
				parseAirVelocity(plane, msg, time);
			}
		} else if (type <= 22) {
			// Air position (GNSS altitude, very rare) - not handled
			Log.d(TAG, String.format("GNSS for ICAO %06X", icao));
		}
	}

	/**
	 * Parse Airborne Velocity Message.
	 * ADS-B Message Type 19, subtype 1..4
	 * 
	 * @param plane Reference to plane in DB
	 * @param msg   Message to be parsed 
	 * @param time  Current time stamp
	 */
	private void parseAirVelocity(AdsbPlane plane, byte[] msg, long time) {
		// Verical rate 38..46
		int vertRateCode = ((msg[4 + 4] & 0x07) << 6) | ((msg[4 + 5] & 0xfc) >>> 2);  
		plane.mVertRateValid = (vertRateCode > 0);
		if (plane.mVertRateValid) {
			plane.mVertRateMagn = (vertRateCode - 1) * 64; // feet/min
			plane.mVertRateUp = ((msg[4 + 4] & 0x08) == 0);
			plane.mVertRateTime = time;
		}
		// Bearing
		final int subtype = msg[4 + 0] & 7;
		if (subtype == 1) {
			// Only deal with normal situation
			final boolean isWest =  ((msg[4 + 1] & 4) != 0); // Bit ME 14
			final int ewVelocityCode = ((msg[4 + 1] & 3) << 8) | (msg[4 + 2] & 0xff); // Bits ME 15..24
			final boolean isSouth =  ((msg[4 + 3] & 0x80) != 0); // Bit ME 14
			final int nsVelocityCode = ((msg[4 + 3] & 0x7f) << 3) | ((msg[4 + 4] & 0xe0) >>> 5); // Bits ME 15..24
			// Only proceed if velocity is present
			if ( (ewVelocityCode != 0) && (ewVelocityCode != 0) ) {
				final int nsVelocity = isSouth ? (1 - nsVelocityCode) : (nsVelocityCode - 1); // In knots, south is negative
				final int ewVelocity = isWest ? (1 - ewVelocityCode) : (ewVelocityCode - 1); // In knots, west is negative
				// Now that we have the vector, calculate bearing
				if (ewVelocity == 0)
					plane.mBearing = 0;
				else {
					plane.mBearing = 90.0 - (Math.atan2(nsVelocity, ewVelocity) * 180 / Math.PI);
					if (plane.mBearing < 0)
						plane.mBearing = 360.0 + plane.mBearing;
				}
				plane.mBearingValid = true;
			}
		}
		
	}

	/**
	 * Parse Barometric Altitude.
	 * Baro Alt field from ADS-B Message Types 9..18.
	 * 
	 * @param plane Reference to plane in DB
	 * @param msg   Message to be parsed 
	 */
	private void parseBaroAltitude(AdsbPlane plane, byte[] msg) {
		if ( (msg[4 + 1] == 0) && ((msg[4 + 2] & 0xf0) == 0) ) {  
			Log.d(TAG, "Baro invalid");
			plane.mAltValid = false;
		} else if ((msg[4 + 1] & 1) == 0) {
			Log.d(TAG, "Baro encoding Q=0 not supported");
			plane.mAltValid = false;
		} else {
			int altCode = ((msg[4 + 1] & 0xfe) << 3) | ((msg[4 + 2] & 0xf0) >>> 4);
			plane.mAlt = altCode * 25.0 - 1000.0; // feet!
			plane.mAltValid = true;
			//Log.d(TAG, String.format("BaroAlt: %.0fft = %.0fm", plane.mAlt, plane.mAlt * 0.3048));
		}
	}

	/**
	 * Parse Airborne Position.
	 * Airborne Position field from ADS-B message types 9..18, 20..22 
	 * 
	 * @param plane Reference to plane in DB
	 * @param msg   Message to be parsed 
	 * @param time  Current time stamp
	 */
	private void parseAirPosition(AdsbPlane plane, byte[] msg, long time) {
		// Get plane CPR @ even/odd index
		int idxCpr = ((msg[4 + 2] & 4) >>> 2);
		AdsbPlane.CprRec cpr = plane.mCprs[idxCpr];

		// Fill CPR
		cpr.mValid = true;
		cpr.mTime = time;
		cpr.mCprYz = ((msg[4 + 2] & 3) << 15) | ((msg[4 + 3] & 0xff) << 7) | ((msg[4 + 4] & 0xff) >>> 1);
		cpr.mCprXz = ((msg[4 + 4] & 1) << 16) | ((msg[4 + 5] & 0xff) << 8) | (msg[4 + 6]  & 0xff);
		
		// Check validity of previous position against the time
		// (If last position is too old, it gets invalid for the CPR algorithm)
		plane.mPosLocValid = plane.mPosLocValid && ((time - plane.mPosTime) < MAX_POS_DTIME);
		// Do we have a valid previous position
		if (plane.mPosLocValid) {
			// Yes -> Local Unambiguous CPR decoding
			// Switch back to global decoding if this yields an error
			// Don't mark a previously valid pos as invalid
			plane.mPosLocValid = calcAirPosLoc(plane.mPosition, plane.mCprs, idxCpr); 
			if (plane.mPosLocValid)
				plane.mPosTime = time;
		} else {
			// No -> Global Unambiguous CPR decoding
			// Check if even & odd present and reception time does not differ too much
			if (plane.mCprs[0].mValid && plane.mCprs[1].mValid) {
				if ( (plane.mCprs[idxCpr].mTime - plane.mCprs[idxCpr^1].mTime) < MAX_POS_DTIME ) {
					plane.mPosLocValid = calcAirPosGlob(plane.mPosition, plane.mCprs, idxCpr);
					if (plane.mPosLocValid)
						plane.mPosTime = time;
				}
				else {
					//Log.d(TAG, "E/O time difference too high");
				}
			}
		}
		// Mark valid positions as Air
		if (plane.mPosLocValid) {
			plane.mPosSurface = false;
			plane.mPosValid = true;
		}
	}

	/**
	 * Parse Flight ID.
	 * ADS-B message types 1..4
	 * 
	 * @param msg   Message to be parsed 
	 * @return Flight ID as a string
	 */
	private String parseFlightId(byte[] msg) {
		byte[] id = new byte[8];
		// Extract and convert ICAO characters to ASCII as per DO-260B
		id[0] = mIcaoChar[(msg[4 + 1] & 0xfc) >>> 2];
		id[1] = mIcaoChar[((msg[4 + 1] & 0x03) << 4) | ((msg[4 + 2] & 0xff) >>> 4)];
		id[2] = mIcaoChar[((msg[4 + 2] & 0x0f) << 2) | ((msg[4 + 3] & 0xff) >>> 6)];
		id[3] = mIcaoChar[(msg[4 + 3] & 0x3f)];
		id[4] = mIcaoChar[(msg[4 + 4] & 0xfc) >>> 2];
		id[5] = mIcaoChar[((msg[4 + 4] & 0x03) << 4) | ((msg[4 + 5] & 0xff) >>> 4)];
		id[6] = mIcaoChar[((msg[4 + 5] & 0x0f) << 2) | ((msg[4 + 6] & 0xff) >>> 6)];
		id[7] = mIcaoChar[(msg[4 + 6] & 0x3f)];
		return new String(id);
	}
	
	/**
	 * Calculates local unambiguous CPR position.<p>
	 * 
	 * Caller has to ensure that the maximum amount of time allowed between 
	 * the last position and the new CPR is not exceeded. 
	 * 
	 * @param position Last known position (time validity already asserted!) and resulting position
	 * if true is return.
	 * @param cprs Array of CPR records (Current data filled in at index idxCpr)
	 * @param idxCpr current even/odd index
	 * @return True if calculation succeeded
	 */
	private boolean calcAirPosLoc(GeoMath.Position position, CprRec[] cprs, int idxCpr) {
		// Latitude
		double j = Math.floor(position.mLat / AIR_D_LAT[idxCpr]) + 
				   Math.floor(0.5 + pmod(position.mLat, AIR_D_LAT[idxCpr]) / AIR_D_LAT[idxCpr] - 
						      (double)cprs[idxCpr].mCprYz / (1 << 17));
		double rlat = corrLat(AIR_D_LAT[idxCpr] * (j + (double)cprs[idxCpr].mCprYz / (1 << 17)));


		// Longitude
		double ni = nlFunc(rlat) - idxCpr;
		ni = (ni < 1) ? 1.0 : ni;
		double dlon = 360.0 / ni; // Width of one lon zone in [°]
		double dM = Math.floor(position.mLon / dlon) + 
				Math.floor(0.5 + pmod(position.mLon, dlon) / dlon - (double)cprs[idxCpr].mCprXz / (1 << 17));
		double rlon = corrLon(dlon * (dM +  (double)cprs[idxCpr].mCprXz / (1 << 17)));

		// Sanity check
		boolean ok = (Math.abs(position.mLat - rlat) < 1);
		ok = ok && (Math.abs(position.mLon - rlon) < (dlon / 6));

		if (!ok)
			Log.w(TAG, String.format("Local position deviation (%.2f/%.2f -> %.2f/%.2f)!", position.mLat, position.mLon, rlat, rlon));
		else {
			position.mLat = rlat;
			position.mLon = rlon;
		}

		return ok;
	}


	/**
	 * Calculates Global Unambiguous CPR position.
	 * 
	 * Caller has to ensure that the maximum amount of time allowed between 
	 * even and odd CPR is not exceeded. 
	 * 
	 * @param position Resulting position if true is returned.
	 * @param cprs Array of CPR records (Current data filled in at index idxCpr)
	 * @param idxCpr current even/odd index
	 * @return True if calculation succeeded
	 */
	private boolean calcAirPosGlob(GeoMath.Position position, CprRec[] cprs, int idxCpr) {
		double nl;
		double rlon=0;

		// Latitude
		double j = Math.floor(((59.0 * cprs[0].mCprYz - 60.0 * cprs[1].mCprYz) / (1 << 17)) + 0.5);
		double rlat0 = corrLat(AIR_D_LAT[0] * (pmod(j, 60) + (double)cprs[0].mCprYz / (1 << 17)));
		double rlat1 = corrLat(AIR_D_LAT[1] * (pmod(j, 59) + (double)cprs[1].mCprYz / (1 << 17)));

		// Longitude
		nl = nlFunc(rlat0);
		// Check if even/odd this is the same lon zone
		boolean ok = (nl == nlFunc(rlat1));
		
		if (ok) {
			double ni = nl - idxCpr;
			ni = (ni < 1) ? 1.0 : ni;
			double dlon = 360.0 / ni; // Width of one lon zone in [°]
			double m = Math.floor( ((double)cprs[0].mCprXz * (nl - 1) - (double)cprs[1].mCprXz * nl) / (1 << 17) + 0.5 );
			rlon = corrLon(dlon * (pmod(m, ni) +  (double)cprs[idxCpr].mCprXz / (1 << 17)));
		}
		else {
			//	fprintf(stderr, "Different Lon Zones!\n");
		}

		// If data is OK, update the global position according to the current frame
		if (ok) {
			position.mLon = rlon;
			position.mLat = (idxCpr == 1) ? rlat1 : rlat0;
		}

		return ok;
	}

	/**
	 * Calculate ADS-B message parity
	 * @param data The ADS-B message (112 bits)
	 * @return Calculated parity (24 bits)
	 */
	private int Parity112Bit(byte[] data)
	{
		final int poly = 0xFFFA0480;
		
		int data0 = ((int)data[0] << 24) | (((int)data[1] & 0xff) << 16) | (((int)data[2] & 0xff) << 8) | ((int)data[3] & 0xff);
		int data1 = ((int)data[4] << 24) | (((int)data[5] & 0xff) << 16) | (((int)data[6] & 0xff) << 8) | ((int)data[7] & 0xff);
		int data2 = ((int)data[8] << 24) | (((int)data[9] & 0xff) << 16) | (((int)data[10] & 0xff) << 8);
		for (int i=0; i<88; ++i)
		{
			if ((data0 & 0x80000000) != 0)
				data0 ^= poly;
			data0 = data0 << 1;
			if ((data1 & 0x80000000) != 0)
				data0 |= 1;
			data1 = data1 << 1;
			if ((data2 & 0x80000000) != 0)
				data1 |= 1;
			data2 = data2 << 1;
		}

		return data0 >>> 8;
	}
	
	// === Util ===
	
	/*
	private static String bytesToHex(byte[] bytes, int n) {
	    final char[] hexArray = {'0','1','2','3','4','5','6','7','8','9','A','B','C','D','E','F'};
	    char[] hexChars = new char[n * 2];
	    int v;
	    for ( int j = 0; j < n; j++ ) {
	        v = bytes[j] & 0xFF;
	        hexChars[j * 2] = hexArray[v >>> 4];
	        hexChars[j * 2 + 1] = hexArray[v & 0x0F];
	    }
	    return new String(hexChars);
	}
	*/

	/**
	 * A modulo function that always returns positiv numbers.
	 * 
	 * @param x
	 * @param m
	 * @return
	 */
	private static double pmod(double x, double m) {
		x = x % m;
		// If the fmod value is negative, add modulus
		if (x < 0)
			x += m;
		return x;
	}

	/**
	 * Ensure longitude is expressed -180° .. 180°
	 *  
	 * @param lon longitude in degrees (0° .. 360°)
	 * @return longitude in degrees (-180° .. 180°)
	 */
	private static double corrLon(double lon) {
		// Eastern longitudes 180°..360° -> -180°..0°
		return (lon > 180) ? (lon - 360) : lon;
	}

	/**
	 * Ensure latitude is expressed -90° .. 90°
	 *  
	 * @param lat latitude in degrees (0° .. 360°)
	 * @return latitude in degrees (-90° .. 90°)
	 */
	private static double corrLat(double lat) {
		// Southern hemisphere latitudes are expressed as 270°..360° -> subtract 360
		return (lat > 180) ? (lat - 360) : lat;
	}

	/**
	 * NL function from 1090-WP-9-14.
	 * Used for CPR decoding, returns the number of longitude zones for a given latitude.
	 * 
	 * @param lat Latitude in degrees (-90° .. 90°)
	 * @return Number of longitude zones
	 */
	private static int nlFunc(double lat) {
		lat = Math.abs(lat);
		int nl;

		// Table from 1090-WP-9-14
		// TODO: Use binary search over table
		if (lat < 10.47047130) nl = 59;
		else if (lat < 14.82817437) nl = 58;
		else if (lat < 18.18626357) nl = 57;
		else if (lat < 21.02939493) nl = 56;
		else if (lat < 23.54504487) nl = 55;
		else if (lat < 25.82924707) nl = 54;
		else if (lat < 27.93898710) nl = 53;
		else if (lat < 29.91135686) nl = 52;
		else if (lat < 31.77209708) nl = 51;
		else if (lat < 33.53993436) nl = 50;
		else if (lat < 35.22899598) nl = 49;
		else if (lat < 36.85025108) nl = 48;
		else if (lat < 38.41241892) nl = 47;
		else if (lat < 39.92256684) nl = 46;
		else if (lat < 41.38651832) nl = 45;
		else if (lat < 42.80914012) nl = 44;
		else if (lat < 44.19454951) nl = 43;
		else if (lat < 45.54626723) nl = 42;
		else if (lat < 46.86733252) nl = 41;
		else if (lat < 48.16039128) nl = 40;
		else if (lat < 49.42776439) nl = 39;
		else if (lat < 50.67150166) nl = 38;
		else if (lat < 51.89342469) nl = 37;
		else if (lat < 53.09516153) nl = 36;
		else if (lat < 54.27817472) nl = 35;
		else if (lat < 55.44378444) nl = 34;
		else if (lat < 56.59318756) nl = 33;
		else if (lat < 57.72747354) nl = 32;
		else if (lat < 58.84763776) nl = 31;
		else if (lat < 59.95459277) nl = 30;
		else if (lat < 61.04917774) nl = 29;
		else if (lat < 62.13216659) nl = 28;
		else if (lat < 63.20427479) nl = 27;
		else if (lat < 64.26616523) nl = 26;
		else if (lat < 65.31845310) nl = 25;
		else if (lat < 66.36171008) nl = 24;
		else if (lat < 67.39646774) nl = 23;
		else if (lat < 68.42322022) nl = 22;
		else if (lat < 69.44242631) nl = 21;
		else if (lat < 70.45451075) nl = 20;
		else if (lat < 71.45986473) nl = 19;
		else if (lat < 72.45884545) nl = 18;
		else if (lat < 73.45177442) nl = 17;
		else if (lat < 74.43893416) nl = 16;
		else if (lat < 75.42056257) nl = 15;
		else if (lat < 76.39684391) nl = 14;
		else if (lat < 77.36789461) nl = 13;
		else if (lat < 78.33374083) nl = 12;
		else if (lat < 79.29428225) nl = 11;
		else if (lat < 80.24923213) nl = 10;
		else if (lat < 81.19801349) nl = 9;
		else if (lat < 82.13956981) nl = 8;
		else if (lat < 83.07199445) nl = 7;
		else if (lat < 83.99173563) nl = 6;
		else if (lat < 84.89166191) nl = 5;
		else if (lat < 85.75541621) nl = 4;
		else if (lat < 86.53536998) nl = 3;
		else if (lat < 87.00000000) nl = 2;
		else nl = 1;

		return nl;
	}

}


/**
 * 
 */
package com.gnsgmbh.adsbtest;

/**
 * Class representing a plane for the purpose of ADS-B.<p>
 * 
 * The plane has all properties that can be transmitted via one or more of
 * the ADS-B DL messages as defined in RTCA DO-260B and is used to represent 
 * this plane (identified by its ICAO number) in a data base of all visible planes.<p>
 * 
 * All times used in this class should be taken from SystemClock.elapsedRealtime().
 * 
 * @author cdiehl
 */
public class AdsbPlane {
	
	/**
	 * A CPR coded position from an ADS-B position message.
	 * Even/Odd information must be kept externally. 
	 */
	public static class CprRec {
		boolean mValid = false;
		long mTime;
		int mCprYz; // CPR Lat
		int mCprXz; // CPR Lon
	}
	
	int mIcao; // 24 bit, duplicate of key to DB
	long mLastSeenTime; // Last time we have seen any message from this plane 

	boolean mPosValid = false;    // if mPosition holds a position at all
	boolean mPosLocValid = false; // if mPosition hold a position which can be used as a local position for CPR calculation
	long mPosTime;                // Time of the position in mPosition
	boolean mPosSurface;          // Whether this is an air position or a surface position.
	final GeoMath.Position mPosition = new GeoMath.Position(); // The last known position of this plane

	boolean mAltValid = false;    // Whether the altitude is valid
	double mAlt; // Feet!         // Last known altitude of this plane (time is same as mVertRateTime)
	
	boolean mVertRateValid = false; // Whether the vertical rate is valid
	long mVertRateTime;           // Time of the vertical rate
	boolean mVertRateUp;          // Whether plane is climbing
	int mVertRateMagn;            // Last known magnitude of vertical rate

	boolean mBearingValid = false; // Whether the bearing is valid
	double mBearing;               // Last known bearing of the plane (time is same as mVertRateTime) 
	
	final CprRec mCprs[] = new CprRec[2]; // Even/Odd information is kept through array index
	
	String mIdStr;   // ID of the plane or null if not known
	
	/**
	 * Class constructor. 
	 * 
	 * @param icao The ICAO number of the plane
	 */
	public AdsbPlane(int icao) {
		mIcao = icao;
		mCprs[0] = new CprRec();
		mCprs[1] = new CprRec();
	}
	
}


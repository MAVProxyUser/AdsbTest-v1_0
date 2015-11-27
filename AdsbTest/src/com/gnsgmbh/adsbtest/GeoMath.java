package com.gnsgmbh.adsbtest;

import android.location.Location;

/**
 * Lightweight geographic math class.<p>
 * 
 * Static class that offers helper classes and calculation methods for geographic positions
 * in a more lightweight manner than Location does. 
 * 
 * @author cdiehl
 */
public class GeoMath {
	
	/**
	 * Mean radius of the earth.
	 */
	public static final double EARTH_RADIUS = 6371000; // [m]

	/**
	 * Lightweight position class without altitude 
	 */
	public static class Position {
		double mLat;
		double mLon;

		public Position() {}

		public Position(Position p) {
			mLat = p.mLat;
			mLon = p.mLon;
		}
		
		public Position(double lat, double lon) {
			mLat = lat;
			mLon = lon;
		}
		
		public Position(Location loc) {
			mLat = loc.getLatitude();
			mLon = loc.getLongitude();
		}
	}
	
	/**
	 * Distance and bearing
	 */
	public static class DistBear {
		double mDistance; // m
		double mBearing;  // 0..360°
		
		public DistBear(double dist, double bear) {
			mDistance = dist;
			mBearing = bear;
		}
	}

	/**
	 * Calculates distance and initial bearing between to positions.
	 * 
	 * @param fromPos start position
	 * @param toPos destination position
	 * @return distance and initial bearing
	 */
	public static DistBear getDistanceBearing(Position fromPos, Position toPos) {
		final double sinLat1 = Math.sin(fromPos.mLat/180*Math.PI);
		final double cosLat1 = Math.cos(fromPos.mLat/180*Math.PI);
		final double sinLat2 = Math.sin(toPos.mLat/180*Math.PI);
		final double cosLat2 = Math.cos(toPos.mLat/180*Math.PI);
		final double sinDlon = Math.sin((toPos.mLon - fromPos.mLon)/180*Math.PI);
		final double cosDlon = Math.cos((toPos.mLon - fromPos.mLon)/180*Math.PI);
		// Calculate distance using spherical law of cosines
		final double dist = Math.acos( (sinLat1 * sinLat2) + cosLat1*cosLat2 * cosDlon ) * EARTH_RADIUS;
		double bear = Math.atan2(sinDlon * cosLat2, cosLat1 * sinLat2 - sinLat1 * cosLat2 * cosDlon) / Math.PI * 180;
		if (bear < 0)
			bear += 360;
		return new DistBear(dist, bear);
	}
	
}

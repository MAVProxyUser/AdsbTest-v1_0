/**
 * 
 */
package com.gnsgmbh.adsbtest;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.location.Location;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/**
 * A view to display the planes in the AdsbManger's database.<p> 
 * The planes are displayed using a radar-like display drawn distance- and bearing-correct 
 * with ID, altitude and vertical rate indication if available. The plane is displayed as 
 * a bitmap rotated to the correct bearing if bearing information is available, 
 * otherwise as a small filled circle. The color of the planes changes to reflect whether
 * the position is recent (green), messages from the plane are still received (white)
 * or not (red). 
 * 
 * @version 1.0
 * @author C. Diehl, GNS GmbH
 */
public class AdsbView extends SurfaceView implements SurfaceHolder.Callback {

    private static final String TAG = AdsbView.class.getSimpleName();
    
    private static final int RADIUS_KM = 100;
    
    private final float mDscale = getResources().getDisplayMetrics().density; // Density scale
    private final int mGap = (int)(20 * mDscale + 0.5f);

    private SurfaceHolder mSurfaceHolder;
    //private Context mContext;
    private AdsbManager mAdsbManager;
    private ViewThread mThread;
	private int mCanvasWidth = 1;
	private int mCanvasHeight = 1;
	private int mCenterX;
	private int mCenterY;
	private int mRadius;

	private Location mLocation;

    public AdsbView(Context context, AttributeSet attrs) {
    	super(context, attrs);

    	Log.d(TAG, "AdsbView()");
    	
		// register our interest in hearing about changes to our surface
    	mSurfaceHolder = getHolder();
    	mSurfaceHolder.addCallback(this); 	
    	
    }
    
    public void attachAdsbManager(AdsbManager adsbManager) {
        synchronized (mSurfaceHolder) {
        	mAdsbManager = adsbManager;
        }
    }
    
    
    public synchronized void setLocation(Location loc) {
    	if (loc != null)
        	mLocation = loc;
    }
    

    /**
     * {@docRoot}
     */
	public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    	Log.d(TAG, "surfaceChanged()");
        synchronized (mSurfaceHolder) {
            mCanvasWidth = width;
            mCanvasHeight = height;
	        mCenterX = mCanvasWidth / 2;
	        mCenterY = mCanvasHeight / 2;
	        mRadius = Math.min(mCenterX, mCenterY) - mGap; // equals RADIUS_KM km

        }
	}

    /**
     * {@docRoot}
     */
	public void surfaceCreated(SurfaceHolder holder) {
    	Log.d(TAG, "surfaceCreated()");

    	// Don't switch off the screen!
    	setKeepScreenOn(true);

		mThread = new ViewThread();    		
    	mThread.setRunning(true);
    	mThread.start();
	}

    /**
     * {@docRoot}
     */
	public void surfaceDestroyed(SurfaceHolder holder) {
    	Log.d(TAG, "surfaceDestroyed()");
        // we have to tell thread to shut down & wait for it to finish, or else
        // it might touch the Surface after we return and explode
        boolean retry = true;
        mThread.setRunning(false);
        while (retry) {
            try {
            	mThread.join();
                retry = false;
            } catch (InterruptedException e) {}
        }
        mThread = null;
    	setKeepScreenOn(false);
	}
	
	// === Thread ===
	
	class ViewThread extends Thread {

        private static final long PLANE_ACTIVE_TIMEOUT = 30000; // ms interval within which a plane is considered active

		private volatile boolean mRun = false;
		
        private final Paint mAxisPaint;
        private final Paint mAxisTxtPaint;
		private int mAxisColor = Color.YELLOW;
		private final Paint mPlanePaint;
		private int mPlaneColor = Color.WHITE;
		private final Paint mActivePlanePaint;
		private int mActivePlaneColor = Color.GREEN;
		private final Paint mOldPlanePaint;
		private int mOldPlaneColor = Color.RED;

		private Bitmap mBmpPlaneGreen;
		private Bitmap mBmpPlaneRed;
		private Bitmap mBmpPlaneWhite;
		private int mBmpRad;

        public ViewThread() {
        	mAxisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        	mAxisPaint.setColor(mAxisColor);
        	mAxisPaint.setStyle(Paint.Style.STROKE);
        	mAxisPaint.setStrokeWidth(2);
        	mAxisTxtPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        	mAxisTxtPaint.setColor(mAxisColor);
        	
        	mPlanePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        	mPlanePaint.setColor(mPlaneColor);
        	mPlanePaint.setStrokeWidth(2);
        	mActivePlanePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        	mActivePlanePaint.setColor(mActivePlaneColor);
        	mActivePlanePaint.setStrokeWidth(2);
        	mOldPlanePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        	mOldPlanePaint.setColor(mOldPlaneColor);
        	mOldPlanePaint.setStrokeWidth(2);
        
        	mBmpPlaneGreen = BitmapFactory.decodeResource(getResources(), R.drawable.plane_green);
        	mBmpPlaneRed = BitmapFactory.decodeResource(getResources(), R.drawable.plane_red);
        	mBmpPlaneWhite = BitmapFactory.decodeResource(getResources(), R.drawable.plane_white);
        	// The plane bitmap MUST be a square!
        	mBmpRad = mBmpPlaneGreen.getWidth() / 2;
        	if ( (mBmpPlaneGreen.getHeight() / 2) != mBmpRad )
        		Log.e(TAG, "Plane Bitmap not square!");
        }
        
        public boolean isRunning() {
			return mRun;
		}

		/**
         * Used to signal the thread whether it should be running or not.
         * Passing true allows the thread to run; passing false will shut it
         * down if it's already running. Calling start() after this was most
         * recently called with false will result in an immediate shutdown.
         *
         * @param b true to run, false to shut down
         */
        public void setRunning(boolean b) {
            mRun = b;
        }

		
		@Override
        public void run() {
            while (mRun) {
                Canvas c = mSurfaceHolder.lockCanvas(null);
                if (c != null) {
                    synchronized (mSurfaceHolder) {
                        doDraw(c);
                    }
                	mSurfaceHolder.unlockCanvasAndPost(c);
                }
                try {
					Thread.sleep(100);
				} catch (InterruptedException e) {}
            }
        }


		private void doDraw(Canvas canvas) {
			//Log.d(TAG, "DRAW");
			// Draw background
	        canvas.drawColor(Color.BLACK);
	        
	        final long time = SystemClock.elapsedRealtime();
	        
	        // Draw axis and circles
	        canvas.drawLine(mCenterX-mRadius, mCenterY, mCenterX+mRadius, mCenterY, mAxisPaint);
	        canvas.drawLine(mCenterX, mCenterY-mRadius, mCenterX, mCenterY+mRadius, mAxisPaint);
	        // Draw 'North' Arrow head
	        final float cxArr = 5 * mDscale;
	        final float cyArr = 15 * mDscale;
	        final Path pathArr = new Path();
	        pathArr.moveTo(mCenterX, mCenterY - mRadius);
	        pathArr.rLineTo(-cxArr, cyArr);
	        pathArr.rLineTo(2*cxArr, 0);
	        pathArr.close();
	        canvas.drawPath(pathArr, mAxisTxtPaint);
	        // Draw circles w/ text
	        canvas.drawCircle(mCenterX, mCenterY, mRadius, mAxisPaint);
	        canvas.drawCircle(mCenterX, mCenterY, mRadius/2, mAxisPaint);
	        final String roTxt = RADIUS_KM + " km"; 
	        final String riTxt = RADIUS_KM / 2 + " km";
	        canvas.drawText(roTxt, mCenterX + 3, mCenterY - mRadius - 3, mAxisTxtPaint);
	        canvas.drawText(riTxt, mCenterX + 3, mCenterY - mRadius/2 - 3, mAxisTxtPaint);
	        
	        // Draw planes only if we have an ADSB manager attached
	        if (mAdsbManager == null)
	        	return;
	        
	        // Draw planes only if we have a valid base position
        	GeoMath.Position base;
    		synchronized (this) {
    			if (mLocation == null)
    				return;
    			else
    				base = new GeoMath.Position(mLocation);
    		}
	        
        	// Paint all active planes with a valid position
	        synchronized (mAdsbManager.mPlaneDb) {
	        	final int n = mAdsbManager.mPlaneDb.size();
	        	for (int i=0; i<n; ++i) {
	        		final AdsbPlane p = mAdsbManager.mPlaneDb.valueAt(i);
	        		if ( (time - p.mLastSeenTime) < PLANE_ACTIVE_TIMEOUT )
	        			if (p.mPosValid)
	        				drawPlane(canvas, base, p, time);
	        	}
	        }
		}

		// Caller must ensure that base- and plane-position are valid!
		private void drawPlane(Canvas canvas, GeoMath.Position base, AdsbPlane p, long time) {
			final GeoMath.DistBear range = GeoMath.getDistanceBearing(base, p.mPosition);
    		if (range != null) {
    			//Log.d(TAG, String.format("DrawPlane %3.0f° %3.0fkm", range.mBearing, range.mDistance/1000));
        		double phi = (90.0 - range.mBearing) / 180 * Math.PI;
        		float px = mCenterX + (float)(range.mDistance / (RADIUS_KM * 1000.0) * mRadius * Math.cos(phi));
        		float py = mCenterY - (float)(range.mDistance / (RADIUS_KM * 1000.0) * mRadius * Math.sin(phi));
        		if ( (px > 0) && (px < mCanvasWidth) && (py > 0) && (py < mCanvasHeight) && (p.mIdStr != null) ) {
        			// Select paint color and bitmap
        			final Paint paint;
        			final Bitmap bmpPlane;
        			if ((time - p.mPosTime) < 5000) {
        				// Plane with recent position
        				paint = mActivePlanePaint;
        				bmpPlane = mBmpPlaneGreen;
        			} else if ((time - p.mLastSeenTime) < 15000) {
        				// Plane which has recently been seen 
        				paint = mPlanePaint;
        				bmpPlane = mBmpPlaneWhite;
        			} else {
        				// Plane which has been inactive for some time but has not yet aged out of the DB
        				paint = mOldPlanePaint;
        				bmpPlane = mBmpPlaneRed;
        			}
        			
        			// Draw symbol
        			if (p.mBearingValid) {
        				// With bearing -> draw plane bitmap rotated for correct bearing
        				final Matrix matrix = new Matrix();
        				matrix.setRotate((float)p.mBearing, mBmpRad, mBmpRad);
        				matrix.postTranslate(px-mBmpRad, py-mBmpRad);
        				canvas.drawBitmap(bmpPlane, matrix, null);
        				
        			} else
        				canvas.drawCircle(px, py, 5, paint);
        			
        			// Draw ID text
    				float cx = paint.measureText(p.mIdStr);
    				float cy = paint.getTextSize();
    				canvas.drawText(p.mIdStr, px-cx/2, py + 2 + mBmpRad + cy, paint);
    				// Draw altitude text 
    				if (p.mAltValid) {
    					// Select altitude change symbol (up/down/level/none)
    					char rateChar = !p.mVertRateValid ? ' ' : 
    						(p.mVertRateMagn <= 64) ? '-' : p.mVertRateUp ? '\u25b2' : '\u25bc'; 
        				canvas.drawText(String.format("%.0f  %c", p.mAlt, rateChar), px-cx/2, py+ 5 + mBmpRad +2*cy, paint); // 25bc
    				}
        		}
    		}
		}
	}
	

}

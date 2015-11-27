package com.gnsgmbh.adsbtest;

import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.util.Log;
import android.util.SparseArray;
import android.view.Menu;
import android.widget.TextView;

/**
 * AdsbTest's main and only activity.<p>
 *  
 * If the activity is already running when the USB device is inserted,
 * it will get paused, onNewIntent() will be called with  ACTION_USB_DEVICE_ATTTACHED
 * and then resumed. We simply always check in onResume() if there is any device to connect to.
 * Detaching the device works via a BroadcastReceiver.<p>

 * CAUTION: This activity should have android:launchMode="singleTask" in the manifest!
 * This way only one instance of the activity exists and gets ALL the ACTION_USB_DEVICE_ATTTACHED
 * intents. If the launch mode is "singleTop", we do not get an intent when the device is
 * plugged in while the activity is already on top and has been started last time via a device
 * being plugged in. This seems to be due to the fact that the activity is already on top
 * and the intent (ACTION_USB_DEVICE_ATTTACHED) does not differ from the last intent when the app 
 * has originally been started.<p>
 * 
*/
public class MainActivity extends Activity {

    private static final String TAG = MainActivity.class.getSimpleName();
	
    enum GpsState { GPS_DISABLED, GPS_NOPOS, GPS_OLDPOS, GPS_OK }
    
	private LocationManager mLocationManager;
	private Location mLocation;
	private long mLocTime;
	private GpsState mGpsState;
	
	private UsbManager mUsbManager;
	private AdsbUsbDriver mAdsbDriver;
	private AdsbManager mAdsbManager;
	private AdsbView mAdsbView;
	
	private ColorStateList mTextColors;
	
	/** 
	 * Used to store all the data we need to rescue during orientation changes
	 * (using onRetainNonConfigurationInstance(), getLastNonConfigurationInstance()) 
	 */
	private class NonConfigData {
		SparseArray<AdsbPlane> mPlaneDb;
		long mMsgCount;
		Location mLastLocation;
		
		public NonConfigData(SparseArray<AdsbPlane> planeDb, long msgCount, Location loc) {
			mPlaneDb = planeDb;
			mMsgCount = msgCount;
			mLastLocation = loc;
		}
	}
	
	@Override /** {@docRoot} */
    public void onCreate(Bundle savedInstanceState) {
    	Log.d(TAG, "onCreate()");
    	//Debug.startMethodTracing();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Init our subsystems
        mUsbManager = (UsbManager)getSystemService(Context.USB_SERVICE);
        mAdsbDriver = new AdsbUsbDriver(mUsbManager);
        mAdsbView = (AdsbView)findViewById(R.id.adsb);
        
        // Check if there was a previous instance (from an orientation change)
        // While getLastNonConfigurationInstance() is deprecated, it still exists
        // (and will for a while) and is just unobtrusive enough for this demo
        // to 'survive' orientation changes.
        @SuppressWarnings("deprecation")
		final Object data = getLastNonConfigurationInstance();
    	Log.d(TAG, "getLastNonConfigurationInstance() returned " + data);
        if ( (data != null) && (data.getClass().equals(NonConfigData.class)) ) {
        	final NonConfigData nonCfgData = (NonConfigData)data;
        	Log.d(TAG, "getLastNonConfigurationInstance() - " + nonCfgData);
        	mAdsbManager = new AdsbManager(nonCfgData.mPlaneDb, nonCfgData.mMsgCount);
        	mLocation = nonCfgData.mLastLocation;
        }
        else
        	mAdsbManager = new AdsbManager();
        	
        // Give our view a data source
        mAdsbView.attachAdsbManager(mAdsbManager);
        
        // Listen for unplugged devices and also include plugged-in devices although at 
        // the moment we never seem to get them. Anyway we should know if there comes a system
        // where we get those, so keep them in.
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); // We never get these in the moment
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(mUsbReceiver, filter);
        
        // Attach to location manager
        mLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
    }

	@Override /** {@docRoot} */
    public boolean onCreateOptionsMenu(Menu menu) {
		// Menu not really used at the moment
        getMenuInflater().inflate(R.menu.activity_main, menu);
        return true;
    }

	@Override
	protected void onDestroy() {
    	Log.d(TAG, "onDestroy()");
    	// Disconnect our broadcast receiver
        unregisterReceiver(mUsbReceiver);
		super.onDestroy();
		//Debug.stopMethodTracing();
	}

	@Override /** {@docRoot} */
	protected void onResume() {
    	Log.d(TAG, "onResume() - " + getIntent().getAction());
		super.onResume();
		
        // We don't care what the intent action is,
        // instead we try to connect to whatever device we find
    	if (mAdsbDriver.isOpen())
			Log.e(TAG, "Activity resumes while still connected!");
    	else {
    		// Iterate through the list of devices currently connected via USB.
    		// While there is normally only one device, somebody might want to use a USB hub.
	        for (final UsbDevice device : mUsbManager.getDeviceList().values()) {
	        	if (AdsbUsbDriver.IsSupported(device)) {
	        		// We have found a supported device --> connect our driver to it
	        		if (!mAdsbDriver.open(device)) 
	        			Log.e(TAG, "Unable to connect");
	        		else {
	        			Log.d(TAG, "Connected");
	        			mAdsbManager.start(mAdsbDriver);
	        		}
	        		break;
	        	}
	        }
    	}
    	
    	// We need to know where we are so request locations from GPS and others.
    	// If we cannot get a better position, start up with the last one from our last run.
    	if (!mLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
    		mGpsState = GpsState.GPS_DISABLED;
    	else {
    		if (mLocation == null) {
    			mLocation = mLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
    			if (mLocation != null)
	    			Log.d(TAG, "Using last known location from GPS");
    		}
	        if ( (mLocation == null) && mLocationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ) {
    			Log.d(TAG, "Using network provider");
	        	mLocationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, mLocationListener);
	        }
	        if (mLocation == null) {
	        	// Try to get an old position from preferences
	    		final SharedPreferences settings = getPreferences(0);
	    		float lat = settings.getFloat("lat", 401.0F);
	    		float lon = settings.getFloat("lon", 401.0F);
	    		if ( (lat < 400) && (lon < 400) ) {
	    			Log.d(TAG, "Using old location from preferences");
	    			mLocation = new Location("PREF");
	    			mLocation.setLatitude(lat);
	    			mLocation.setLongitude(lon);
	    		}
	        }
			mGpsState = (mLocation == null) ? GpsState.GPS_NOPOS : GpsState.GPS_OLDPOS;
    	}
		mAdsbView.setLocation(mLocation);
    	mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, mLocationListener);

    	DisplayState();
    	
    	// Should GPS be disabled, ask the user to enable it.
    	if (mGpsState == GpsState.GPS_DISABLED) {
            new AlertDialog.Builder(this, AlertDialog.THEME_HOLO_DARK)
            .setTitle(R.string.app_name)
            .setMessage("Please enable GPS now for this application to work correctly")
            .setPositiveButton("OK", new OnClickListener() {
				public void onClick(DialogInterface dialog, int which) {
					startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
				}
            })
            .setNegativeButton("Cancel", new OnClickListener() {
				public void onClick(DialogInterface dialog, int which) {
			    	// End this activity if the user does not enable GPS
					MainActivity.this.finish();
				}
            }) 
            .show();     		
    	}
    	
    	// Start our continuous update timer task
		mHandler.postDelayed(mUpdateTimerTask, 100);
	}

	@Override /** {@docRoot} */
	protected void onPause() {
    	Log.d(TAG, "onPause()");
		super.onPause();
		// Disconnect our location manager listener
		mLocationManager.removeUpdates(mLocationListener);
		// Stop our continuous update timer task
		mHandler.removeCallbacks(mUpdateTimerTask);
		// Stop the ADSB manager and close the ADSB driver
		if (mAdsbManager.isRunning())
			mAdsbManager.stop();
		if (mAdsbDriver.isOpen()) {
			mAdsbDriver.close();
		}
		// Save last position so we can fall back on it next time we start
		if (mLocation != null) {
			SharedPreferences settings = getPreferences(0);
			SharedPreferences.Editor editor = settings.edit();
			editor.putFloat("lat", (float)mLocation.getLatitude());
			editor.putFloat("lon", (float)mLocation.getLongitude());
			editor.commit();
		}

	}

	@Override /** {@docRoot} */
	public Object onRetainNonConfigurationInstance() {
		Log.d(TAG, "onRetainNonConfigurationInstance()");
		return new NonConfigData(mAdsbManager.mPlaneDb, mAdsbManager.getMsgCount(), mLocation);
	}

	@Override /** {@docRoot} */
	protected void onNewIntent(Intent intent) {
		Log.i(TAG, "New Intent Action: " + intent.getAction());
		// This will inform us about newly plugged in devices but a pause/resume cycle
		// will also always happen so we rely on this. (See class description)
		super.onNewIntent(intent);
	}

	/**
	 * Display the current state in the state text view.
	 */
	private void DisplayState() {
    	TextView tv = ((TextView)findViewById(R.id.state_txt));
    	// Remember the original coloring
    	if (mTextColors == null)
    		mTextColors = tv.getTextColors();
    	if (!mAdsbDriver.isOpen()) {
    		tv.setTextColor(Color.RED);
    		tv.setText(R.string.stat_plug_in);
    	} else {
    		switch (mGpsState) {
				case GPS_DISABLED: 
					tv.setTextColor(Color.RED);   
					tv.setText(R.string.stat_ena_gps);  
					((TextView)findViewById(R.id.loc_txt)).setText("");
					break;
				case GPS_NOPOS:    
					tv.setTextColor(Color.MAGENTA); 
					tv.setText(R.string.stat_wait_gps); 
					((TextView)findViewById(R.id.loc_txt)).setText("");
					break;
				case GPS_OLDPOS:   
					tv.setTextColor(Color.YELLOW); 
					tv.setText(R.string.stat_gps_old);
					((TextView)findViewById(R.id.loc_txt)).setText(FormatLocation(mLocation));
					break;
				case GPS_OK:       
					tv.setTextColor(Color.GREEN); 
					tv.setText(R.string.stat_ok);       
					break;
    		}
    	}
	}
	
	/**
	 * Returns a formatted string (lat/long) for a location.
	 * 
	 * @param loc Location to convert into string.
	 * @return The converted location.
	 */
	private static String FormatLocation(Location loc) {
		if (loc == null)
			return "";
		
		double lat = loc.getLatitude();
		char ns = (lat >= 0.0) ? 'N' : 'S';
		lat = Math.abs(lat);
		double lon = loc.getLongitude();
		char ew = (lon >= 0.0) ? 'E' : 'W';
		lon = Math.abs(lon);
		return String.format("%c %.6f° / %c %.6f°", ns, lat, ew, lon);
	}

	/**
	 * The broadcast receiver used to pick-up the device unplugged intent.
	 */
	BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
		public void onReceive(Context context, Intent intent) {
			Log.i(TAG, "BR Intent Action " + intent.getAction());
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                UsbDevice device = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                // Disconnect if this is our device
            	if (mAdsbDriver.isUsing(device)) {
	    			Log.i(TAG, "Disconnecting unplugged USB device");
	    			mAdsbManager.stop();
	    			mAdsbDriver.close();
	    			DisplayState();
            	}
            }
            else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            	// This has yet to happen, so keep it in as a warning so we can spot it in the future.
    			Log.w(TAG, "BR received ACTION_USB_DEVICE_ATTACHED");
            }
        }
	};
	

	/**
	 * The location listener that keeps us informed about our position and the state of providers.
	 */
	LocationListener mLocationListener = new LocationListener() {
		public void onLocationChanged(Location loc) {
			if (!loc.getProvider().equals(LocationManager.GPS_PROVIDER)) {
				// Only use non-GPS provider if we have no location yet
				if ( (mLocation != null) && (mLocation.getProvider().equals(LocationManager.GPS_PROVIDER)) )
					return;
				else {
					Log.d(TAG, "Using location provider " + loc.getProvider());
					mGpsState = GpsState.GPS_OLDPOS;
					DisplayState();
				}
			} else {
				// Handle GPS state
				mLocTime = SystemClock.elapsedRealtime();
				if (mGpsState != GpsState.GPS_OK) {
					mGpsState = GpsState.GPS_OK;
					DisplayState();
					Log.d(TAG, "GPS now valid");
				}
			}
			// Store and print location
			mLocation = loc;
			((TextView)findViewById(R.id.loc_txt)).setText(FormatLocation(loc));
			// Provide location to view
			mAdsbView.setLocation(loc);
		}

		public void onStatusChanged(String provider, int status, Bundle extras) 
		{
		}

		public void onProviderEnabled(String provider) 
		{
			// We are only interested if somebody enables GPS
			if (LocationManager.GPS_PROVIDER.equals(provider)) {
				if (mLocation == null)
					mLocation = mLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
				mAdsbView.setLocation(mLocation);
				mGpsState = (mLocation == null) ? GpsState.GPS_NOPOS : GpsState.GPS_OLDPOS;
				DisplayState();
			}
		}

		public void onProviderDisabled(String provider) 
		{
			// We are only interested if somebody disables GPS
			if (LocationManager.GPS_PROVIDER.equals(provider)) {
				mGpsState = GpsState.GPS_DISABLED;
				DisplayState();
			}
		}
	};
	
	
	/**
	 * This is the handler for our continuous UI update timer
	 */
	private Handler mHandler = new Handler();
	/**
	 * This is the task for our continuous UI update timer
	 */
	private Runnable mUpdateTimerTask = new Runnable() {
		public void run() {
			
			// Display statistics
			String strMsgCount = "";
			String strPlaneCount = "";
			if (mAdsbManager.isRunning()) {
				strMsgCount =  mAdsbManager.getMsgCount() + " messages received";
				strPlaneCount =  mAdsbManager.getPlaneCount() + " planes in DB";
			}
			((TextView)findViewById(R.id.msg_txt)).setText(strMsgCount);
			((TextView)findViewById(R.id.db_txt)).setText(strPlaneCount);
			
			// Check if GPS still valid (max. 10 sec since last location)
			if (mGpsState == GpsState.GPS_OK) {
				if ( (SystemClock.elapsedRealtime() - mLocTime) > 10000 ) {
					mGpsState = GpsState.GPS_OLDPOS;
					Log.d(TAG, "GPS now invalid");
					DisplayState();
				}
			}
			
			// Make the task continuous
			mHandler.postDelayed(this, 300);
		}
	};
	
}


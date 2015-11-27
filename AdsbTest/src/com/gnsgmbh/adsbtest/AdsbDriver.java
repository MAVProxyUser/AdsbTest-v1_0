package com.gnsgmbh.adsbtest;

/**
 * Abstract Base Class for an ADS-B device driver.<p>
 * 
 * Any derived AdsbDriver is required to implement {@link readAdsbMsg}, 
 * the sole abstract method of this class. While it will probably also 
 * implement open/close functions, the arguments passed to these functions 
 * are driver specific and thus not defined in this base class.
 * 
 * @author C. Diehl, GNS GmbH
 */
public abstract class AdsbDriver {

	/**
 	* Obtain the next ADS-B message from the driver.
 	* 
	* @return The return value is an array of bytes containing one single ADS-B message
	* as long as messages are available or null if the internal FIFO has been drained. 
	*/
    public abstract byte[] readAdsbMsg();
    
}

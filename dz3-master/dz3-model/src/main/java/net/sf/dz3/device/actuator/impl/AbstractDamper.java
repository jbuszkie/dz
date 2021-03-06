package net.sf.dz3.device.actuator.impl;

import java.io.IOException;

import net.sf.dz3.device.actuator.Damper;
import net.sf.dz3.util.digest.MessageDigestCache;
import net.sf.jukebox.datastream.logger.impl.DataBroadcaster;
import net.sf.jukebox.datastream.signal.model.DataSample;
import net.sf.jukebox.datastream.signal.model.DataSink;
import net.sf.jukebox.logger.LogAware;
import net.sf.jukebox.sem.ACT;

import org.apache.log4j.NDC;

/**
 * @author Copyright &copy; <a href="mailto:vt@freehold.crocodile.org"> Vadim Tkachenko</a> 2001-2012
 */
public abstract class AbstractDamper extends LogAware implements Damper {
    
    /**
     * Damper name.
     * 
     * Necessary evil to allow instrumentation signature.
     */
     private final String name;
     
     /**
      * Instrumentation signature.
      */
     private final String signature;

    private final DataBroadcaster<Double> dataBroadcaster = new DataBroadcaster<Double>();

    /**
     * A damper position defined as 'parked'.
     *
     * Default value is 1 (fully open).
     */
    private double parkPosition = 1;

    /**
     * Current position.
     */
    private double position = parkPosition;
    
    public AbstractDamper(String name) {
        
        if (name == null || "".equals(name)) {
            throw new IllegalArgumentException("name can't be null");
        }
        
        this.name = name;
        signature = MessageDigestCache.getMD5(name).substring(0, 19); 
    }
    
    @Override
    public final String getName() {
        return name;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final void setParkPosition(double parkPosition) {

        if (parkPosition < 0 || parkPosition > 1) {

            throw new IllegalArgumentException("Invalid position (" + parkPosition + ") - value can be 0..1.");
        }

        this.parkPosition = parkPosition;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final double getParkPosition() {

        return parkPosition;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final void set(double throttle) {
        
        NDC.push("set");
        
        try {
            
            logger.info("position=" + throttle);

            if ( throttle < 0 || throttle > 1.0 || Double.compare(throttle, Double.NaN) == 0) {

                throw new IllegalArgumentException("Throttle out of 0...1 range: " + throttle);
            }

            this.position = throttle;

            try {

                moveDamper(throttle);
                stateChanged();
                
            } catch (Throwable t) {

                logger.error("Failed to move damper to position " + throttle, t);
                // VT: FIXME: Need to change Damper to be a producer of DataSample<Double>, not Double
                stateChanged();
            }

        } finally {
            NDC.pop();
        }
    }

    /**
     * Move the actual damper.
     *
     * @param position Position to set.
     * 
     * @exception IOException if there was a problem moving the damper.
     */
    protected abstract void moveDamper(double position) throws IOException;

    /**
     * {@inheritDoc}
     */
    @Override
    public ACT park() {

        NDC.push("park");
        
        try {
            
            // VT: NOTE: Careful here. This implementation will work correctly only if
            // moveDamper() works synchronously. For others (ServoDamper being a good example)
            // you will have to provide your own implementation (again, ServoDamper is an
            // example of how this is done).
            
            ACT done = new ACT();

            try {
                
                moveDamper(parkPosition);
                done.complete(true);
                
            } catch (Throwable t) {
                
                done.complete(false);
            }

            return done;
        
        } finally {
            NDC.pop();
        }
    }

    /**
     * {@inheritDoc}
     */
    private synchronized void stateChanged() {

        dataBroadcaster.broadcast(new DataSample<Double>(System.currentTimeMillis(), name, signature, position, null));
    }

    @Override
    public void addConsumer(DataSink<Double> consumer) {
        
        dataBroadcaster.addConsumer(consumer);
    }

    @Override
    public void removeConsumer(DataSink<Double> consumer) {
        
        dataBroadcaster.removeConsumer(consumer);
    }

    @Override
    public void consume(DataSample<Double> signal) {
        
        set(signal.sample);
    }
}

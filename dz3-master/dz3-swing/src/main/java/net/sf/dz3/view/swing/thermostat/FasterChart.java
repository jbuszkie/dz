package net.sf.dz3.view.swing.thermostat;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import net.sf.dz3.controller.DataSet;
import net.sf.jukebox.datastream.signal.model.DataSample;
import net.sf.jukebox.util.Interval;

public class FasterChart extends AbstractChart {

    private static final long serialVersionUID = 8739949924865459025L;
    
    /**
     * Chart width in pixels for all the charts. Undefined until the first time
     * {@link #paintCharts(Graphics2D, Dimension, Insets, long, double, long, double, double)}
     * for any instance of this class is called.
     * 
     * Making it static is ugly, but gets the job done - the screen size will not change.
     */
    private static int globalWidth = 0;
    
    /**
     * Chart width of this instance.
     * 
     * @see #globalWidth
     * @see #paintCharts(Graphics2D, Dimension, Insets, long, double, long, double, double)
     */
    private int width = 0;
    
    private final Map<String, Averager> channel2avg = new HashMap<String, Averager>();

    public FasterChart(long chartLengthMillis) {

        super(chartLengthMillis);
    }

    @Override
    public synchronized void consume(DataSample<TintedValue> signal) {

        assert(signal != null);
        assert(signal.sample != null);

        String channel = signal.sourceName;
        
        if (record(channel, signal)) {

            repaint();
        }
    }

    /**
     * Record the signal, properly spacing it out.
     * 
     * @param channel Channel to use.
     * @param signal Signal to record.
     * 
     * @return {@code true} if the component needs to be repainted.
     */
    private boolean record(String channel, DataSample<TintedValue> signal) {
        
        adjustVerticalLimits(signal.timestamp, signal.sample.value);

        synchronized (getClass()) {
            
            if (width != globalWidth) {
                
                // Chart size changed, need to adjust the buffer
                
                width = globalWidth;
                
                long step = chartLengthMillis / width;
                
                logger.info("new width " + width + ", " + step + " ms per pixel");
                
                // We lose one sample this way, might want to improve it later, for now, no big deal

                channel2avg.put(channel, new Averager(step));
                
                return true;
            }
        }

        if (width == 0) {
            
            // There's nothing we can do before the width is set.
            // It's not even worth it to record the value.
            
            // Please repaint.
            logger.info("please repaint");
            return true;
        }
        
        Averager avg = channel2avg.get(channel);
        TintedValue tv = avg.record(signal);
        
        if (tv == null) {
            
            // The average is still being calculated, nothing to do
            return false;
        }
        
        DataSet<TintedValue> ds = channel2ds.get(channel);

        if (ds == null) {

            ds = new DataSet<TintedValue>(chartLengthMillis);
            channel2ds.put(channel, ds);
        }
        
        ds.record(signal.timestamp, tv);

        return true;
    }

    @Override
    protected void checkWidth(Dimension boundary) {

        // Chart size *can* change during runtime - see +/- Console#ResizeKeyListener.
        
        synchronized (getClass()) {
            
            if (globalWidth != boundary.width) {
                
                logger.info("width changed from " + globalWidth + " to " + boundary.width);
                
                globalWidth = boundary.width;
                
                long step = chartLengthMillis / globalWidth;

                logger.info("ms per pixel: " + step);
            }
        }
        
    }

    @Override
    protected void paintChart(Graphics2D g2d, Dimension boundary, Insets insets,
            long now, double x_scale, long x_offset, double y_scale, double y_offset,
            String channel, DataSet<TintedValue> ds) {

        Long time_trailer = null;
        TintedValue trailer = null;

        // Flag to reduce the color changes
        boolean dead = false;

        for (Iterator<Long> di = ds.iterator(); di.hasNext();) {

            long time_now = di.next();
            TintedValue cursor = ds.get(time_now);

            if (time_trailer != null) {

                double x0 = (time_trailer - x_offset) * x_scale
                        + insets.left;
                double y0 = (y_offset - trailer.value) * y_scale
                        + insets.top;

                double x1 = (time_now - x_offset) * x_scale
                        + insets.left;
                double y1 = (y_offset - cursor.value) * y_scale
                        + insets.top;

                // Decide whether the line is alive or dead

                if (time_now - time_trailer <= deadTimeout) {
                    
                } else {

                    if (!dead) {

                        dead = true;
                    }

                    // Paint the horizontal line in dead color
                    // and skew the x0 so the next part will be
                    // painted vertical

                    Color startColor = signal2color(trailer.tint - 1, SIGNAL_COLOR_LOW, SIGNAL_COLOR_HIGH);
                    Color endColor = getBackground();

                    drawGradientLine(g2d, x0, y0, x1, y0, startColor, endColor, cursor.emphasize);

                    x0 = x1;
                }

                if (dead) {
                    dead = false;
                }

                Color startColor = signal2color(trailer.tint - 1, SIGNAL_COLOR_LOW, SIGNAL_COLOR_HIGH);
                Color endColor = signal2color(cursor.tint - 1, SIGNAL_COLOR_LOW, SIGNAL_COLOR_HIGH);

                drawGradientLine(g2d, x0, y0, x1, y1, startColor, endColor, cursor.emphasize);
            }

            time_trailer = time_now;

            trailer = new TintedValue(cursor.value, cursor.tint, cursor.emphasize);
        }

        if (time_trailer != null && now - time_trailer > deadTimeout) {

            // There's a gap on the right, let's fill it

            double x0 = (time_trailer - x_offset) * x_scale
                    + insets.left;
            double x1 = (now - x_offset) * x_scale + insets.left;
            double y = (y_offset - trailer.value) * y_scale + insets.top;

            Color startColor = signal2color(trailer.tint - 1, SIGNAL_COLOR_LOW, SIGNAL_COLOR_HIGH);
            Color endColor = getBackground();

            drawGradientLine(g2d, x0, y, x1, y, startColor, endColor, false);
        }

        // Store the values so the readings can be displayed
        // over the curves

        //              if (value_trailer != null) {
        //                      values.put(name, value_trailer);
        //              }
    }

    /**
     * Averaging tool.
     */
    private class Averager {

        /**
         * The expiration interval. Values older than the last key by this many
         * milliseconds are expired.
         */
        private final long expirationInterval;
        
        /**
         * The timestamp of the oldest recorded sample.
         */
        private Long timestamp;
        
        private int count;
        private double valueAccumulator = 0;
        private double tintAccumulator = 0;
        private double emphasizeAccumulator = 0;
        
        public Averager(long expirationInterval) {
            this.expirationInterval = expirationInterval;
        }

        /**
         * Record a value.
         * 
         * @param signal Signal to record.
         * 
         * @return The average of all data stored in the buffer if this sample is more than {@link #expirationInterval}
         * away from the first sample stored, {@code null} otherwise.
         */
        public TintedValue record(DataSample<TintedValue> signal) {
            
            if (timestamp == null) {
                timestamp = signal.timestamp;
            }
            
            long age = signal.timestamp - timestamp;
            
            if ( age < expirationInterval) {

                count++;
                valueAccumulator += signal.sample.value;
                tintAccumulator += signal.sample.tint;
                emphasizeAccumulator += signal.sample.emphasize ? 1 : 0;
                
                return null;
            }
                
            logger.debug("RingBuffer: flushing at " + Interval.toTimeInterval(age));
            
            TintedValue result = new TintedValue(valueAccumulator / count, tintAccumulator / count, emphasizeAccumulator > 0);
            
            count = 1;
            valueAccumulator = signal.sample.value;
            tintAccumulator = signal.sample.tint;
            emphasizeAccumulator = 0;
            timestamp = signal.timestamp;
            
            return result;
        }
    }
}

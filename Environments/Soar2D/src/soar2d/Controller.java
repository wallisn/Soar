package soar2d;

import java.io.File;

import org.apache.log4j.Logger;

import sml.*;
import soar2d.visuals.WindowManager;

/**
 * @author voigtjr
 *
 * Control keeps track of the simulation, if it is running or not, the update
 * process, etc.
 */
public class Controller implements Kernel.UpdateEventInterface, Kernel.SystemEventInterface, Runnable {
	private static Logger logger = Logger.getLogger(Controller.class);

	/**
	 * Set to true when a stop is requested
	 */
	private boolean stop = false;
	/**
	 * Set to true when we're only stepping as opposed to running
	 */
	private boolean step = false;
	/**
	 * Set to true if the simulation is running. This is true also when stepping.
	 */
	private boolean running = false;
	/**
	 * This is not null when there is a thread running the simulation.
	 */
	private Thread runThread = null;
	/**
	 * This is true when things are in the process of shutting down
	 */
	private boolean shuttingDown = false;
	
	private double totalTime = 0;
	private double timeSlice = 0;

	/**
	 * Set to true when a stop is requested
	 */
	public synchronized boolean isStopped() { return this.stop ; }
	
	public void errorPopUp(String message) {
		if (Soar2D.wm.using()) {
			Soar2D.wm.errorMessage(Soar2D.config.title(), message);
		}
	}
	
	public void infoPopUp(String message) {
		if (Soar2D.wm.using()) {
			Soar2D.wm.infoMessage(Soar2D.config.title(), message);
		}
	}
	
	/**
	 * Called when there is a change in the number of players.
	 */
	public void playerEvent() {
		if (Soar2D.wm.using()) {
			Soar2D.wm.playerEvent();
		}		
	}
	
	/**
	 * @param step true indicates run only one step
	 * @param newThread true indicates do the run in a new thread
	 * 
	 * Called to start the simulation.
	 */
	public void startSimulation(boolean step, boolean newThread) {
		this.step = step;
		
		// update the status line in the gui
		if (step) {
			Soar2D.wm.setStatus("Stepping", WindowManager.black);
		} else {
			Soar2D.wm.setStatus("Running", WindowManager.black);
		}
		
		// TOSCA patch -- try a call to tosca code
		//soar2d.tosca2d.Tosca.test() ;
		
		// the old style
		// spawn a thread or just run it in this one
		if (newThread) {
			runThread = new Thread(this);
			runThread.start();
		} else {
			run();
		}
	}
	
	/**
	 * Called to stop the simulation. Requests soar to stop or tells the 
	 * run loop to stop executing. The current update will finish.
	 */
	public void stopSimulation() {
		// requests a stop
		stop = true;
	}
	
	/**
	 * @return true if the reset completed without error
	 * 
	 * Called to reset the simulation.
	 */
	public boolean resetSimulation() {
		if (!Soar2D.simulation.reset()) {
			return false;
		}
		if (Soar2D.wm.using()) {
			Soar2D.wm.reset();
		}
		return true;
	}
	
	/** 
	 * The thread, do not call directly!
	 */
	public void run() {
		
		// if there are soar agents
		if (Soar2D.simulation.hasSoarAgents()) {
			
			// have soar control things
			// it will call startEvent, tickEvent, and stopEvent in callbacks.
			if (step) {
				Soar2D.simulation.runStep();
			} else {
				Soar2D.simulation.runForever();
			}
		} else if (Soar2D.config.generalConfig().tosca) {
			if (step)
				soar2d.tosca2d.ToscaInterface.getTosca().runStep() ;
			else
				soar2d.tosca2d.ToscaInterface.getTosca().runForever() ;
		} else {
			
			// there are no soar agents, call the start event
			startEvent();
			
			// run as necessary
			if (!step) {
				while (!stop) {
					tickEvent();
				}
			} else {
				tickEvent();
			}
			
			// call the stop event
			stopEvent();
		}

		// reset the status message
		Soar2D.wm.setStatus("Ready", WindowManager.black);
	}
	
	/**
	 * Called internally to signal the actual start of the run.
	 * If soar is controlling things, soar calls this during the system start event.
	 * If soar is not controlling things, this gets called by run() before
	 * starting the sim.
	 */
	public void startEvent() {
		logger.trace(Names.Trace.startEvent);
		stop = false;
		running = true;

		if (Soar2D.wm.using()) {
			// this updates buttons and what-not
			Soar2D.wm.start();
		}
	}
	
	/**
	 * Fires a world update. If soar is running things, this is called during the 
	 * output callback. If soar is not running things, this is called by run() in 
	 * a loop if necessary.
	 */
	public void tickEvent() {
		// this is 50 except for book, where it is configurable
		timeSlice = Soar2D.config.generalConfig().cycle_time_slice / 1000.0f;
		totalTime += timeSlice;

		Soar2D.simulation.update();
		if (Soar2D.wm.using()) {
			Soar2D.wm.update();
		}
	}
	
	public double getTotalTime() {
		return totalTime;
	}
	
	public double getTimeSlice() {
		return timeSlice;
	}
	
	public void resetTime() {
		totalTime = 0;
		timeSlice = 0;
	}
	
	/**
	 * Signals the actual end of the run. If soar is running things, this is called
	 * by soar during the system stop event. Otherwise, this gets called by run
	 * after a stop is requested.
	 */
	public void stopEvent() {
		logger.trace(Names.Trace.stopEvent);
		running = false;
		
		if (Soar2D.wm.using()) {
//			 this updates buttons and what-not
			Soar2D.wm.stop();
		}
	}
	
  	/**
  	 * Handle an update event from Soar, do not call directly.
  	 */
  	public void updateEventHandler(int eventID, Object data, Kernel kernel, int runFlags) {

  		// check for override
  		int dontUpdate = runFlags & smlRunFlags.sml_DONT_UPDATE_WORLD.swigValue();
  		if (dontUpdate != 0) {
  			logger.warn(Names.Warn.noUpdate);
  			return;
  		}
  		
  		// this updates the world
  		tickEvent();
  		
		// Test this after the world has been updated, in case it's asking us to stop
		if (stop) {
			// the world has asked us to kindly stop running
  			logger.debug(Names.Debug.stopRequested);
  			
  			// note that soar actually controls when we stop
  			kernel.StopAllAgents();
  		}
  	}
  	
  	/**
  	 * Handle a system event from Soar, do not call directly
  	 */
   public void systemEventHandler(int eventID, Object data, Kernel kernel) {
  		if (eventID == smlSystemEventId.smlEVENT_SYSTEM_START.swigValue()) {
  			// soar says go
  			startEvent();
  		} else if (eventID == smlSystemEventId.smlEVENT_SYSTEM_STOP.swigValue()) {
  			// soar says stop
  			stopEvent();
  		} else {
  			// soar says something we weren't expecting
  			logger.warn(Names.Warn.unknownEvent + eventID);
 		}
   }
   
	/**
	 * Create the GUI and show it, and run its loop. Does not return until the 
	 * GUI is disposed. 
	 */
	public void runGUI() {
		if (Soar2D.wm.using()) {
			// creates, displays and loops the window. returns on shutdown *hopefully
			Soar2D.wm.run();
		}
	}

	/**
	 * @return true if the simulation is on its way down, as in closing to exit
	 * to OS (as opposed to stopping).
	 */
	public boolean isShuttingDown() {
		return shuttingDown;
	}
	/**
	 * Call to shutdown the simulation.
	 */
	public void shutdown() {
		// we're shutting down
		shuttingDown = true;
		
		// make sure things are stopped, doesn't hurt to call this when stopped
		stopSimulation();
		logger.info(Names.Info.shutdown);
		if (Soar2D.wm.using()) {
			// closes out the window manager
			Soar2D.wm.shutdown();
		}
		// closes out the simulation
		Soar2D.simulation.shutdown();
	}

	/**
	 * @return True if the simulation is currently running.
	 */
	public boolean isRunning() {
		return running;
	}
	
	private void error(String message) {
		logger.error(message);
		errorPopUp(message);

	}

	/**
	 * @param map the name of the map to change to
	 */
	public void changeMap(String map) {
		
		//TODO: this should take in a File object
		
		// make sure it is somewhat valid
		if ((map == null) || (map.length() <= 0)) {
			error(Names.Errors.mapRequired);
		}
		logger.debug(Names.Debug.changingMap + map);
		
		File mapFile = new File(map);
		// check as absolute
		if (!mapFile.exists()) {
			
			// doesn't exist as absolute, check relative to map dir
			mapFile = new File(Soar2D.simulation.getMapPath() + map);
			if (!mapFile.exists()) {
				
				// doesn't exist there either
				error(Names.Errors.findingMap + map);
				return;
			}
		}
		
		// save the old map in case the new one is screwed up
		File oldMap = new File(Soar2D.config.generalConfig().map);
		Soar2D.config.generalConfig().map = mapFile.getAbsolutePath();

		// the reset will fail if the map fails to load
		if (!resetSimulation()) {
			
			// and if it fails the map will remain unchanged, set it back
			Soar2D.config.generalConfig().map = oldMap.getAbsolutePath();
		}
	}
	
	
	/**
	 * Logger for Kernel print events
	 * @author Scott Lathrop
	 *
	 */
	public PrintLogger getLogger() { return PrintLogger.getLogger(); }
	
	
	public static class PrintLogger implements Agent.PrintEventInterface
	{
		protected static PrintLogger m_Logger = null;
		
		public static PrintLogger getLogger() 
		{
			if (m_Logger == null) {
				m_Logger = new PrintLogger();
			}
			
			return m_Logger;
		}
		
		/**
		 * @brief - callback from SoarKernel for print events
		 */
		public void printEventHandler (int eventID, Object data, Agent agent, String message) 
		{
			if (eventID == smlPrintEventId.smlEVENT_PRINT.swigValue()) {
				logger.info(message);
			}
				
		} // SoarAgentprintEventHandler	
		
		private PrintLogger () {}
		
	} // Logger
	
	/**
	 * End Logger for Kernel print events
	 * @author Scott Lathrop
	 *
	 */
	
	private int runsTerminal = 0;
	public void setRunsTerminal(int runsTerminal) {
		this.runsTerminal = runsTerminal;
	}
	public boolean checkRunsTerminal() {
		boolean stopNow = true;
		
		if (this.runsTerminal > 0) {
			this.runsTerminal -= 1;
			if (this.runsTerminal > 0) {
				stopNow = false;
			}
		}
		return stopNow;
	}
}

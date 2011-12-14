import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.math.stat.descriptive.SummaryStatistics;


public class SocketIOLoadTester extends Thread implements SocketClientEventListener {
	// Address to test against
	public static final String BASE_URI = "localhost:8080";
	
	public static final int STARTING_MESSAGES_PER_SECOND_RATE = 1;	
	public static final int SECONDS_TO_TEST_EACH_LOAD_STATE = 3;

	public static final int SECONDS_BETWEEN_TESTS = 2;
	
	public static final int MESSAGES_RECEIVED_PER_SECOND_RAMP = 1000;
	
	public static final int POST_TEST_RECEPTION_TIMEOUT_WINDOW = 5000;
	
	protected int[] concurrencyLevels = {25, 50, 75, 100, 200, 300, 400, 500, 750, 1000, 1250, 1500, 2000};
	//private static final int MAX_MESSAGES_PER_SECOND_SENT = 800;
	private static final int MAX_MESSAGES_RECV_PER_SECOND = 500000;
	
	protected SocketClientFactory factory;
	protected Set<SocketClient> clients = new HashSet<SocketClient>();
	
	protected int concurrency;
	
	protected int currentMessagesPerSecond = STARTING_MESSAGES_PER_SECOND_RATE;
	
	protected boolean lostConnection = false;
	
	protected Integer numConnectionsMade = 0;
	
	protected List<Long> roundtripTimes;
	
	private boolean postTestTimeout;
	
	private boolean testRunning;
	
	protected SocketIOLoadTester(SocketClientFactory factory, ArrayList<Integer> concurrencies) {
		this.factory = factory;
		
		if(concurrencies.size() > 0) {
			System.out.print("Using custom concurrency levels: ");
			this.concurrencyLevels = new int[concurrencies.size()];

			int i=0; 
			for(Integer concurrency : concurrencies) {
				this.concurrencyLevels[i] = concurrency.intValue();
				i++;
				
				System.out.print(concurrency + " ");
			}
			
			System.out.println();
		}
	}
	
	public synchronized void run() {
		
		BufferedWriter f = null;
		try {
				f =  new BufferedWriter(new FileWriter(System.currentTimeMillis() + ".log"));
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}

		
		for(int i=0; i<concurrencyLevels.length; i++) {
			this.concurrency = concurrencyLevels[i];

			// Reset the failure switches.
			this.lostConnection = false;
			this.postTestTimeout = false;

			
			System.out.println("---------------- CONCURRENCY " + this.concurrency + " ----------------");
			// This won't return until we get an ACK on all the connections.
			this.numConnectionsMade = 0;
			this.makeConnections(this.concurrency);
			
			Map<Double, SummaryStatistics> summaryStats = this.performLoadTest();
			
			// shutdown all the clients
			for(SocketClient c : this.clients) {
				try {
					c.close();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			
			
			for(Double messageRate : summaryStats.keySet()) {
				SummaryStatistics stats = summaryStats.get(messageRate);
				
				try {
					f.write(String.format("%d,%f,%d,%f,%f,%f,%f\n", this.concurrency, messageRate, stats.getN(), stats.getMin(), stats.getMean(), stats.getMax(), stats.getStandardDeviation()));
					f.flush();
					System.out.println("Wrote results of run to disk.");
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
				
		try {
			f.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		return;
	}
	
	protected void makeConnections(int numConnections) {
		// Start the connections. Wait for all of them to connect then we go.
		this.clients.clear();
		
		for(int i=0; i<this.concurrency; i++) {
			SocketClient client = this.factory.newClient(BASE_URI, this);
			this.clients.add(client);
			client.connect();
		}
		
		try {
			this.wait();
		} catch (InterruptedException e) {
			System.out.println("Interrupted!");
		}
		System.out.println("Woken up - time to start load test!");
	}
	
	protected Map<Double, SummaryStatistics> performLoadTest() {
		// Actually run the test.
		// Protocol is spend 3 seconds at each load level, then ramp up messages per second.
		Map<Double, SummaryStatistics> statisticsForThisConcurrency = new HashMap<Double, SummaryStatistics>();
		
		this.testRunning = true;
		
		// TODO Think about having this vary as an initial condition thing - for lower concurrencies, starting at 1 costs us a lot fo time to run the test.
		this.currentMessagesPerSecond = STARTING_MESSAGES_PER_SECOND_RATE;
		double effectiveRate = 0;
		
		while(!this.lostConnection && !this.postTestTimeout && currentMessagesPerSecond * this.concurrency < MAX_MESSAGES_RECV_PER_SECOND) {
			System.out.print(concurrency + " connections at " + currentMessagesPerSecond + ": ");
			
			this.roundtripTimes = new ArrayList<Long>(SECONDS_TO_TEST_EACH_LOAD_STATE * currentMessagesPerSecond);
			
			double overallEffectiveRate = 0;

			for(int i=0; i<SECONDS_TO_TEST_EACH_LOAD_STATE; i++) {
				effectiveRate = this.triggerChatMessagesOverTime(currentMessagesPerSecond, 1000);				
				overallEffectiveRate+=effectiveRate;
			}
			
			overallEffectiveRate = overallEffectiveRate / SECONDS_TO_TEST_EACH_LOAD_STATE;
			System.out.print(String.format(" rate: %.3f ", overallEffectiveRate));
			
			// At this point, all messages have been sent so we should wait until they've all been received.
			this.postTestTimeout = true;
			synchronized(this) {
				try {
					this.wait(POST_TEST_RECEPTION_TIMEOUT_WINDOW);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			
			if(this.postTestTimeout) {
				System.out.println(" failed - not all messages received in " + POST_TEST_RECEPTION_TIMEOUT_WINDOW + "ms");
			} else {
				// Grab and store the summary statistics for this run.
				statisticsForThisConcurrency.put(overallEffectiveRate, this.processRoundtripStats());
				
				// TODO Do a check here - if we saw a mean roundtrip time above 100ms or so, that's the congestion point and we should record that as the "knee" of the curve, basically.
			}
			
			// Make sure to always increase by at least 1 message per second. 
			currentMessagesPerSecond += Math.max(1, MESSAGES_RECEIVED_PER_SECOND_RAMP/this.concurrency);
			
			try {
				Thread.sleep(SECONDS_BETWEEN_TESTS*1000);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}		

		this.testRunning = false;
		return statisticsForThisConcurrency;
	}
	
	protected double triggerChatMessagesOverTime(long totalMessages, long ms) {
		long startTime = System.currentTimeMillis();

		long baseNsPerSend = (ms * 1000000) / totalMessages;
		
		long accumulator = 0;
						
		Iterator<SocketClient> clientsIterator = this.clients.iterator();
		
		long numMessages = totalMessages;
		
		while (numMessages > 0) {
			while (numMessages > 0 && accumulator <= 0) {
				long messageStartTime = System.nanoTime();
				
				SocketClient client = clientsIterator.next();
				client.sendTimestamp();
				numMessages -= 1;
				
				if(!clientsIterator.hasNext()) {
					clientsIterator = clients.iterator();
				}
							
				long delta = System.nanoTime() - messageStartTime;								
				accumulator = accumulator + baseNsPerSend - delta;				
			}
						
			while (numMessages > 0 && accumulator > 0)
			{
				long waitStart =  System.nanoTime();
				try
				{
					Thread.sleep(accumulator / 1000000, (int)(accumulator % 1000000));
				} catch (InterruptedException ex)
				{					
				}
				
				long waitDelta = System.nanoTime() - waitStart;
				accumulator = accumulator - waitDelta;
			}
		}
				
		double duration = Math.max(1, (System.currentTimeMillis() - startTime) / 1000.0);	
		return totalMessages/duration;
	}
	
	protected SummaryStatistics processRoundtripStats() {
		SummaryStatistics stats = new SummaryStatistics();
		
		for(Long roundtripTime : this.roundtripTimes) {
			stats.addValue(roundtripTime);
		}
		
		System.out.format(" n: %5d min: %8.0f  mean: %8.0f   max: %8.0f   stdev: %8.0f\n", stats.getN(), stats.getMin(), stats.getMean(), stats.getMax(), stats.getStandardDeviation());
		
		return stats;
	}
	
	/**
	 * @param args
	 */
	public static void main(String[] args) {
		// Just start the thread.
		
		ArrayList<Integer> concurrencies = new ArrayList<Integer>();
		if(args.length > 0) {
			// Assume all the arguments are concurrency levels we want to test at.

			for(String arg : args) {
				concurrencies.add(new Integer(arg));
			}
		}
		
		SockJSClientFactory factory = new SockJSClientFactory();		
		SocketIOLoadTester tester = new SocketIOLoadTester(factory, concurrencies);
		tester.start();
	}

	@Override
	public void onError(IOException e) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void onMessage(String message) {
		//		System.out.println("message: " + message);
	}
	
	

	@Override
	public void onClose() {
		if(this.testRunning) {
			lostConnection = true;
			System.out.println(" failed!");
			System.out.println("Lost a connection. Shutting down.");
		}
	}

	@Override
	public void onOpen() {
		synchronized(this) {
			numConnectionsMade++;
			if(numConnectionsMade.compareTo(concurrency)==0) {
				System.out.println("All " + concurrency + " clients connected successfully.");
				// Turn the main testing thread back on. We don't want to accidentally
				// be executing on some clients main thread.
				this.notifyAll();
			}
		}
	}

	@Override
	public void messageArrivedWithRoundtrip(long roundtripTime) {
		this.roundtripTimes.add(roundtripTime);
		
		if(this.roundtripTimes.size() == SECONDS_TO_TEST_EACH_LOAD_STATE * currentMessagesPerSecond) {
			synchronized(this) {
				this.postTestTimeout = false;
				this.notifyAll();
			}
		}
	}

}
package comp512st.paxos;
import comp512.gcl.*;
import comp512.utils.*;

import java.io.*;
import java.util.logging.*;
import java.net.UnknownHostException;
import java.nio.channels.Pipe.SourceChannel;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;



public class Paxos
{
	GCL gcl;
	FailCheck failCheck;


	//Queuing System
	LinkedBlockingDeque<Object> outgoing;
	LinkedBlockingDeque<Object> incoming;
	LinkedBlockingDeque<Object> proposerQueue;

	Proposer proposer;
	Acceptor acceptor;
	private enum MsgType {PROPOSE,PROMISE,REFUSE,ACCEPT,ACCEPTACK,DENY,CONFIRM}

	//Timing
	long startTime; // Start time in milliseconds
	long pstartTime; // Start time in milliseconds
	long testingStartTime;
	long rateTime; long a; long b;
    long maxDuration = TimeUnit.MILLISECONDS.toMillis(100);

	int numProcesses; int majority;

	public Paxos(String myProcess, String[] allGroupProcesses, Logger logger, FailCheck failCheck) throws IOException, UnknownHostException
	{
		// Rember to call the failCheck.checkFailure(..) with appropriate arguments throughout your Paxos code to force fail points if necessary.
		this.failCheck = failCheck;
		this.gcl = new GCL(myProcess, allGroupProcesses, null, logger);
		this.testingStartTime = System.currentTimeMillis();
		numProcesses = allGroupProcesses.length;
		majority = (numProcesses / 2)+1;
		outgoing = new LinkedBlockingDeque<>();
		incoming = new LinkedBlockingDeque<>();
		proposerQueue = new LinkedBlockingDeque<>();
		acceptor = new Acceptor(myProcess);
		proposer = new Proposer(myProcess);
		proposer.start();
		acceptor.start();

		
	}

	// This is what the application layer is going to call to send a message/value, such as the player and the move
	public void broadcastTOMsg(Object val)
	{	
		outgoing.offer(val);
	}


	public Object acceptTOMsg() throws InterruptedException
	{
		Object obj = incoming.take();
		return obj;
	}


	public void shutdownPaxos()
	{
		
		Thread monitorThread = new Thread(() -> {
			try {
				while (true) {
					if (incoming.isEmpty() && outgoing.isEmpty() && proposerQueue.isEmpty()) {
						System.out.println("Queues are empty");
						break; // Exit the loop and end the thread
					} 
					Thread.sleep(100); 
				}
			} catch (InterruptedException e) {
				System.out.println("Monitor thread interrupted.");
			}
		});

		monitorThread.start();
		try {
			Thread.sleep(500);
		} catch (Exception e){

		}
		System.out.println(System.currentTimeMillis() - testingStartTime);
		System.out.println("This process's RateTime: " + rateTime);

		gcl.shutdownGCL();
	}


	private class Proposer extends Thread{
		int ballotID; int acceptedBID; int counter; int port; String processID; 
		int promises; int refusals;
		Object acceptedVal;
		int numAcceptAcks; int numDenies;

		private Proposer(String myProcess){
			ballotID = -1;
			counter = 0;
			this.port = Integer.parseInt(myProcess.split(":")[1]);;
			this.processID = myProcess;
			promises = 0; refusals = 0; 
		}

		@Override
		public void run(){

			while (true){
				acceptedVal = null; acceptedBID = -1;
				Object val = outgoing.poll();
				if (val != null){
					propose();
					a = System.currentTimeMillis();
					failCheck.checkFailure(FailCheck.FailureType.AFTERSENDPROPOSE);
					pstartTime = System.currentTimeMillis();
					while (promises < majority && refusals < majority && (System.currentTimeMillis() - pstartTime < maxDuration)){
						PaxosMessage response = (PaxosMessage) proposerQueue.poll();
						if (response != null){
							if (response.ballotID < this.ballotID){continue;}
							switch(response.getType()){
								case PROMISE:
									promises+=1;
									if (response.acceptedBID > acceptedBID && response.val != null){
										outgoing.offerFirst(val);
										acceptedBID = response.acceptedBID;
										val = response.val;
									}
									//System.out.println("Recieved promise at " + response.senderProcess + " with ballotID " + Integer.toString(response.ballotID));
									break;
								case REFUSE: 
									refusals += 1;
									//System.out.println("Recieved refuse at " + response.senderProcess + " with ballotID " + Integer.toString(response.ballotID));
									break;
								default:
									continue;
								}
						}
					}
					//System.out.println("Exited " + promises +" " + refusals + " "+ majority);

					if (promises >= majority){
						//System.out.println("Success! We are now the leader of " + Integer.toString(promises));
						failCheck.checkFailure(FailCheck.FailureType.AFTERBECOMINGLEADER);
						reset();
						
						//---------------------Begin Accept Phase---------------------------------------------------
						accept(this.ballotID, val);
						numAcceptAcks = 0; numDenies = 0;
						
						startTime = System.currentTimeMillis();
						while (numAcceptAcks < majority && numDenies < majority && (System.currentTimeMillis() - startTime < maxDuration)){
							PaxosMessage response = (PaxosMessage) proposerQueue.poll();
							if (response != null){
								if (response.ballotID < this.ballotID){continue;}
								switch(response.getType()){
									case ACCEPTACK:
										numAcceptAcks +=1;
										//System.out.println("Recieved acceptAck at " + response.senderProcess + " with ballotID " + Integer.toString(response.ballotID));
										break;
									case DENY:
										numDenies +=1;
										//System.out.println("Recieved deny at " + response.senderProcess + " with ballotID " + Integer.toString(response.ballotID));
										break;
									default: 
										continue;
								}
							}
						}
						//System.out.println("Exited with " + numAcceptAcks + " acceptAcks and " + numDenies + " denies");
						if (numAcceptAcks >= majority){
							//System.out.println("Value Accepted by Majority. BID: " + ballotID);
							failCheck.checkFailure(FailCheck.FailureType.AFTERVALUEACCEPT);
							acceptedVal = val;
							confirm(this.ballotID, this.acceptedVal);
							b = System.currentTimeMillis();
							rateTime += (b - a);
							reset();

						} else {
							//System.out.println("Accept? Failed with bid " + ballotID);
							reset();
							outgoing.offerFirst(val);
						}


					} else {
						//System.out.println("Failed Proposal... we need to try again");
						reset();
						outgoing.offerFirst(val);
					}

				} else { continue;}
			}
		}

		int generateBID(){
			counter+=1;
			return (counter << 20) | port;
		}

		void propose(){
			ballotID = generateBID();
			//System.out.println("Creating proposal: " + ballotID);
			PaxosMessage proposal = new PaxosMessage(MsgType.PROPOSE, null, ballotID, -1, this.processID);
			gcl.broadcastMsg(proposal);
		}
		void reset(){
			promises = 0; 
			refusals = 0;
			numAcceptAcks = 0;
			numDenies = 0;
			proposerQueue.clear();
		}
		void accept(int ballotID, Object val){
			//System.out.println("Sending Accept? for BID: " + ballotID);
			PaxosMessage acceptq = new PaxosMessage(MsgType.ACCEPT, val, ballotID, -1, this.processID);
			gcl.broadcastMsg(acceptq);
		}
		void confirm(int BID, Object val){
			PaxosMessage confirmation = new PaxosMessage(MsgType.CONFIRM, val, BID, BID, processID);
			gcl.broadcastMsg(confirmation);
		}

	}

	private class Acceptor extends Thread{
		int maxBID; int acceptedBID; Object acceptedVal; String processID;

		private Acceptor(String processID){
			maxBID = -1; acceptedBID = -1; acceptedVal = null; this.processID = processID;
		}

		@Override
		public void run(){
			while (true) {
				GCMessage msg = null;

				try {msg = gcl.readGCMessage();} catch (InterruptedException e){ Thread.currentThread().interrupt(); break;};
				PaxosMessage pxmsg = (PaxosMessage) msg.val;

				switch(pxmsg.getType()){
					case PROPOSE:
						//System.out.println("Promising/Refusing " + pxmsg.ballotID + " to " + msg.senderProcess + " at " + this.processID);
						failCheck.checkFailure(FailCheck.FailureType.RECEIVEPROPOSE);
						promise(pxmsg.ballotID, msg.senderProcess);
						failCheck.checkFailure(FailCheck.FailureType.AFTERSENDVOTE);
						break;
					case PROMISE:
						proposerQueue.offer(pxmsg);
						break;
					case REFUSE:
						proposerQueue.offer(pxmsg);
						break;
					case ACCEPT:
						//System.out.println("Accepting/Refusing " + pxmsg.ballotID + " to " + msg.senderProcess + " at " + this.processID);
						accept(pxmsg.ballotID, msg.senderProcess, null);
						break;
					case ACCEPTACK:
						proposerQueue.offer(pxmsg);
						break;
					case DENY:
						proposerQueue.offer(pxmsg);
						break;
					case CONFIRM:
						incoming.offer(pxmsg.val);
						break;
					default:
						break;
				}

				//incoming.offer(pxmsg.getVal());  	
			}
		}

		void promise(int BID, String destProcess){
			if (BID < maxBID){
				//System.out.println("Refusing");
				PaxosMessage refuse = new PaxosMessage(MsgType.REFUSE, null, maxBID, -1, destProcess);
				gcl.sendMsg(refuse, destProcess);
			} else {
				PaxosMessage promise = new PaxosMessage(MsgType.PROMISE, acceptedVal, BID, acceptedBID, destProcess);
				maxBID = BID; 
				gcl.sendMsg(promise, destProcess);
				//System.out.println("Promised");
			}
		}

		void accept(int BID, String destProcess, Object val){
			if (BID == maxBID){
				//System.out.println("Accepting ");
				acceptedBID = BID; acceptedVal = val;
				PaxosMessage acceptAck = new PaxosMessage(MsgType.ACCEPTACK, val, BID, -1, destProcess);
				gcl.sendMsg(acceptAck, destProcess);
			} else {
				//System.out.println("Denying ");
				PaxosMessage denial = new PaxosMessage(MsgType.DENY, val, BID, -1, destProcess);
				gcl.sendMsg(denial, destProcess);
			}
		}
		
	}

	private static class PaxosMessage implements Serializable{
		private Object val;
		private String senderProcess;
		private MsgType type;
		int ballotID;
		int acceptedBID;

		PaxosMessage(MsgType type, Object val, int ballotID, int acceptedBID, String senderProcess){
			this.val = val;
			this.type = type;
			this.ballotID = ballotID;
			this.acceptedBID = acceptedBID;
			this.senderProcess = senderProcess;
		}

		Object getVal(){
			return this.val;
		}
		MsgType getType(){
			return this.type;
		}
	}
}

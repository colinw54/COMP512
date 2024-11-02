package comp512st.paxos;

// Access to the GCL layer
import comp512.gcl.*;

import comp512.utils.*;

// Any other imports that you may need.
import java.io.*;
import java.util.logging.*;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;




public class Paxos
{
	GCL gcl;
	FailCheck failCheck;
	ConcurrentLinkedQueue<Object> outgoing;
	ConcurrentLinkedQueue<Object> incoming;
	final int port;
	final int majority;

	Proposer proposer;

	public String log;
	int promises;
	int refuses;
	int acceptacks;
	int denies;
	FileWriter writer;

	public Paxos(String myProcess, String[] allGroupProcesses, Logger logger, FailCheck failCheck) throws IOException, UnknownHostException
	{

		// Rember to call the failCheck.checkFailure(..) with appropriate arguments throughout your Paxos code to force fail points if necessary.
		this.failCheck = failCheck;

		// Initialize the GCL communication system as well as anything else you need to.
		this.gcl = new GCL(myProcess, allGroupProcesses, null, logger) ;
		this.outgoing = new ConcurrentLinkedQueue<>();
		this.incoming = new ConcurrentLinkedQueue<>();
		this.port = Integer.parseInt(myProcess.split(":")[1]);
		this.majority = (allGroupProcesses.length /2) + 1;
		
		this.proposer = new Proposer(this.port, this.majority);
		proposer.start();
	}

	// This is what the application layer is going to call to send a message/value, such as the player and the move
	public void broadcastTOMsg(Object val)
	{
		// Reset promises and denies
		promises = 0;
		refuses = 0;
		acceptacks = 0;
		denies = 0;

	
		this.log += "Broadcasting\n";

		outgoing.offer(val);
		gcl.broadcastMsg(val);

		/*
		*	Paxos Implementation:
		* 	1. Messages will be placed in a thread-safe queue (ConcurrentLinkedQueue?)
		* 	2. A Proposer thread will continuously process messages in the queue
		* 	3. An Acceptor thread will continuously read paxosmessages, and place any confirmed messages into a thread-safe queue
		* 	**Each Process can only have one Proposer/Acceptor Thread running concurrently**
		*/		
	}

	// This is what the application layer is calling to figure out what is the next message in the total order.
	// Messages delivered in ALL the processes in the group should deliver this in the same order.
	public Object acceptTOMsg() throws InterruptedException
	{
		// This is just a place holder.
		GCMessage gcmsg = gcl.readGCMessage();
		return gcmsg.val;
	}

	// Add any of your own shutdown code into this method.
	public void shutdownPaxos()
	{
		gcl.shutdownGCL();
	}

		/*  
	*	The MsgType will be piggybacked onto the messages so that processes can identify what the message type is when received
	* 	Message Types are based on the Paxos slides
	* 	DENY is used when the PROPOSE? ballot id suggested by the proposer is denied by a listener thread
	* 	REFUSE is used after the ballot id was accepted and the proposer sends an ACCEPT message with a value and a listener thread refuses
	*/ 
	private enum MsgType {
		PROPOSE, 
		PROMISE, 
		REFUSE,
		ACCEPT,
		ACCEPTACK,
		DENY,
		CONFIRM
	}


	// Implements Serializable so it can be sent accross network using gcl.sendMsg(). 
	// Uses ENUM to identify types, contains all information each type of message requires
	// If some info not necessary, eg. "val" for "propose", the field is left as null. 
	private class PaxosMessage implements Serializable
	{

		/*
		 * Propose: SenderID, BID
		 * Promise: SenderID, BID -- OR -- BID, acceptedBID, acceptedVal(gcmessage)
		 * Refuse: SenderID, BID
		 * Accept: SenderID, BID, val
		 * AcceptAck: SenderID, BID
		 * Deny: SenderID, BID
		 * Confirm: SenderID, BID
		 */
		private MsgType type; //All Messages contain
    	private int BID;
		private int BID2;
    	private Object val;
		private String senderID;

		// This constructur is used for {Propose, Promise, 	}
		private PaxosMessage(MsgType type, int BID, int BID2, Object val, String senderprocess)
		{
			this.type = type;
			this.BID = BID;
			this.BID2 = BID2;
			this.val = val;
			this.senderID = senderprocess;
		}
		
		/*
		 * Getter Methods:
		 * getType(), getBID(),getVal(), getSenderID(),
		 * Testing Aid: toString()
		 * No Setter Methods as PaxosMessage should be an immutable class
		 */
		public MsgType getType() {
			return type;
		}
	
		public int getBID() {
			return BID;
		}
	
		public int getBID2() {
			return BID2;
		}
	
	
		public Object getVal() {
			return val;
		}
	
		public String getSenderID() {
			return senderID;
		}
	

		@Override
 		public String toString() {
        	return "PaxosMessage{" +
                "type=" + type +
                ", senderId=" + getSenderID() +
                ", BallotID=" + getBID() +
                '}';
		}
	}


	private class Proposer extends Thread
	{	
		//proposerID is used to generate unique BID's
		private int BID;
		private Object val;
		private int majority;
		private int proposerID;
		private int counter;
		private int BID2;

		private Proposer(int proposerID, int majority){
			this.BID = -1;
			this.BID2 = -1;
			this.val = null;
			this.majority = majority;
			this.proposerID = proposerID;
			this.counter = 0;
		}

		//The counter goes in the highest 20 bits, proposerID at the bottom.
		private int generateBID(){
			counter+=1;
			return (counter << 20) | proposerID;
			
		}

		@Override 
		public void run(){
			//While running, will constantly try to pull a message out of "outgoing", and if successful, initiates an instance of PAXOS
			while (true){
				Object msg = outgoing.poll();
				System.out.println(log);
				if (msg != null) {
                    //Begin an instance of Paxos
					//Proposer should be blocking, meaning it can't send another proposal if it's in the middle of one
				
					//Create initial Proposal
					propose();

					//Wait for promises (Either until majority refuses/accepts or cancel after certain period of time)
					while (promises < majority && denies < majority){
				
						try {
							msg = gcl.readGCMessage();
						} catch (InterruptedException e){ // Catch an InterruptedException per the documentation in the doc
							e.printStackTrace();
						}

						PaxosMessage pxmsg = (PaxosMessage)(msg);
						
						switch (pxmsg.getType()){
							case PROPOSE:
								accept(pxmsg);
							
							case DENY:
								denyRecieved(pxmsg);
							default:
								System.out.println("Not relevant message");
								break;

						//If does nto recieve majority of promises, put the message back at the front of the outgoing queue to try again and break

					}
					//Send accept? to all acceptors and wwait for majority of acceptAcknowledgements (Should have been done in last call of accept())
					
					while (acceptacks < majority && refuses < majority) {
						//If a majority of AcceptAcks have come in: Send Confirm and break from the loop
						try {
							msg = gcl.readGCMessage();
						} catch (InterruptedException e){ // Catch an InterruptedException per the documentation in the doc
							e.printStackTrace();
						}

						pxmsg = (PaxosMessage)(msg);
						
						switch (pxmsg.getType()){
							case REFUSE:
								refuseReceived(pxmsg);
							
							case ACCEPTACK:
								acceptAckRecieved(pxmsg);

							default:
								System.out.println("Not relevant message");
								break;

						//If not, put back to the front of the queue to try again and break from the loop

					}
				}
				}
                }
			 	 else {
                    // No message available, pause for a short time
                    try {
                        Thread.sleep(500); // Avoid busy-waiting, adjust time as needed
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }

			}
			}
		}

		/*
		 * Proposer Methods: 
		 * Propose()
		 * Accept()
		 * PromiseRecieved()
		 * AcceptAckRecieved()
		 */

		void propose(){
			//Choose a ballotID
			this.BID = generateBID();
			//Send propose(BallotID) to all acceptors)
			PaxosMessage proposalMsg = new PaxosMessage(MsgType.PROPOSE, this.BID, this.BID2, this.val, String.valueOf(this.proposerID));
			gcl.broadcastMsg(proposalMsg);

		}

		void accept(PaxosMessage pxmsg){

			// Important to note that once we reach majority votes, we need to ignore the rest of the promises from the threads that have yet to be delivered to avoid sending out multiple ACCEPT messages
			if (promises > majority) break;

			promises += 1;

			// If we find at least one promise(ballotID, bid2, v'), we need to keep track of it so that we can send accept?(ballotID, v')
			if (pxmsg.getBID2() > this.BID2) {
				this.BID2 = pxmsg.getBID2();
				this.val = pxmsg.getVal();
			}

			// Check whether we have majority of promises, if so, send out an ACCEPT? message
			if (promises >= majority){
				PaxosMessage newmsg = new PaxosMessage(MsgType.ACCEPT, this.BID, this.BID2, this.val, String.valueOf(this.proposerID));
				gcl.broadcastMsg(newmsg);
			}
		}

		void refuseReceived(PaxosMessage pxmsg){
			// If the majority refuse, we need to try again next round with a new ballotID
			refuses += 1;
			if (refuses >= majority) {
				reset();

				BID = generateBID();

				PaxosMessage newmsg = new PaxosMessage(MsgType.PROPOSE, BID, -1, null, String.valueOf(this.proposerID));
				gcl.broadcastMsg(newmsg);
				
				// Check for failures after sending the new PROPOSE message
				//failCheck.checkFailure(FailCheck.FailureType.AFTERSENDPROPOSE);
			}
		}
		

		void denyRecieved(PaxosMessage pxmsg){
			System.out.println("Handling DENY message.");

			denies += 1;

			// If we have majority of denies, we need to try again next round with a new ballotID
			if (denies >= majority) {

				reset();

				// TODO: Determine if this is the right way to create a new ballotID
				BID = generateBID();
				PaxosMessage newmsg = new PaxosMessage(MsgType.PROPOSE, BID, -1, null, String.valueOf(this.proposerID));
				gcl.broadcastMsg(newmsg);

				// Check for failures after sending the new PROPOSE message
				//failCheck.checkFailure(FailCheck.FailureType.AFTERSENDPROPOSE);
		}
	}
		

		void acceptAckRecieved(PaxosMessage pxmsg){
			// If we already exceeded majority, we can ignore the rest of the acceptacks to avoid sending out multiple CONFIRM messages
			if (acceptacks > majority) break;

			acceptacks += 1;

			// If we have majority of acceptacks, we can confirm the value
			if (acceptacks >= majority){ 

				// Check for failures after receiving an ACCEPTACK messages and becoming leader
				//failCheck.checkFailure(FailCheck.FailureType.AFTERBECOMINGLEADER);
				PaxosMessage newmsg = new PaxosMessage(MsgType.CONFIRM, this.BID, this.BID2, this.val, String.valueOf(this.proposerID));
				gcl.broadcastMsg(newmsg);	
			}

		}

		void reset(){
			promises = 0;	
			refuses = 0;
			acceptacks = 0;
			denies = 0;
		}
	}





	private class Acceptor extends Thread
	{	
		private int maxBID;
		private int acceptedBID;
		private int promisedBID;
		private Object acceptedVal;
		private final int acceptorID;
			
		//AcceptorID is just the process port
		private Acceptor(int acceptorID){
			this.acceptorID = acceptorID;
			this.maxBID = -1;
			this.acceptedBID = -1;
			this.promisedBID = -1;
			this.acceptedVal = null;
		}

		@Override
		public void run(){
			while (true) {
				GCMessage msg = null;

				// Check if there is a message
				try {
					msg = gcl.readGCMessage();
				} catch (InterruptedException e){ // Catch an InterruptedException per the documentation in the doc
					e.printStackTrace();
				}

				// Extract the PaxosMessage from the message received, and respond. 
				PaxosMessage pxmsg = (PaxosMessage)(msg.val);
				
				switch (pxmsg.getType()){
					case PROPOSE:
						promise(pxmsg);

					case ACCEPT:
						acceptAcknowledgement(pxmsg);
						break;

					case CONFIRM:
						confirm(pxmsg);
						break;

					default:
						System.out.println("Unknown message type.");
						break;
				}
			}
		}


		/*
		 * Acceptor Methods: 
		 * Promise()
		 * AcceptAcknowledgement()
		 * Reset()
		 * Confirm()
		 */
		void promise(PaxosMessage pxmsg){
			// Check for failures as soon as we receive a PROPOSE message
			// failCheck.checkFailure(FailCheck.FailureType.RECEIVEPROPOSE); 

			int ballotID = pxmsg.getBID();
			// Ensure that the proposed ballotID is the highest value
			// Sends out a PROMISE Paxos message to the proposer thread
			if (ballotID > this.maxBID){
				this.maxBID = ballotID;
				PaxosMessage newmsg = new PaxosMessage(MsgType.PROMISE, this.maxBID, this.acceptedBID, this.acceptedVal, String.valueOf(this.acceptorID));
				gcl.sendMsg(newmsg, pxmsg.getSenderID());

				// Check for failures after sending the PROMISE message for leader election
				failCheck.checkFailure(FailCheck.FailureType.AFTERSENDVOTE);
				
			}

			// Refuses the proposal if the ballotID isn't the highest
			// Sends out a REFUSE Paxos message to the proposer thread, the refuse contains the maxBallotID for that process
			else {
				PaxosMessage newmsg = new PaxosMessage(MsgType.REFUSE, this.promisedBID, this.acceptedBID, this.acceptedVal, String.valueOf(this.acceptorID));
				gcl.sendMsg(newmsg, pxmsg.getSenderID());
				// Check for failures after sending the PROMISE message for leader election
				//failCheck.checkFailure(FailCheck.FailureType.AFTERSENDVOTE);
			}

		}
		void acceptAcknowledgement(PaxosMessage pxmsg){
			
			if (pxmsg.BID == this.maxBID){
				this.acceptedBID = pxmsg.BID;
				this.acceptedVal = pxmsg.getVal();

				PaxosMessage newmsg = new PaxosMessage(MsgType.ACCEPTACK, this.maxBID, this.acceptedBID, this.acceptedVal, String.valueOf(this.acceptorID));
				gcl.sendMsg(newmsg, pxmsg.getSenderID());
			}

			// If the ballotID is not the same as the promised ballotID, we deny the value
			else {
				PaxosMessage newmsg = new PaxosMessage(MsgType.DENY, pxmsg.BID, this.acceptedBID, this.acceptedVal, String.valueOf(this.acceptorID));
				gcl.sendMsg(newmsg, pxmsg.getSenderID());
			}

		}
		void reset(){

		} 
		void confirm(PaxosMessage pxmsg){
			incoming.offer(pxmsg.getVal());
		}

	}
}

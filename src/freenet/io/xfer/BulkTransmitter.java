/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.io.xfer;

import freenet.io.comm.AsyncMessageCallback;
import freenet.io.comm.AsyncMessageFilterCallback;
import freenet.io.comm.ByteCounter;
import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.PeerContext;
import freenet.io.comm.PeerRestartedException;
import freenet.node.SyncSendWaitedTooLongException;
import freenet.support.BitArray;
import freenet.support.Logger;

/**
 * Bulk data transfer (not block). Bulk transfer is designed for files which may be much bigger than a 
 * key block, and where we have the whole file at the outset. Do not persist across node restarts.
 * 
 * Used by update over mandatory, sending a file to our peers attached to an N2NTM etc.
 * @author toad
 */
public class BulkTransmitter {

	/** If no packets sent in this period, and no completion acknowledgement / cancellation, assume failure. */
	static final int TIMEOUT = 5*60*1000;
	/** Time to hang around listening for the last FNPBulkReceivedAll message */
	static final int FINAL_ACK_TIMEOUT = 10*1000;
	/** Available blocks */
	final PartiallyReceivedBulk prb;
	/** Peer who we are sending the data to */
	final PeerContext peer;
	/** Transfer UID for messages */
	final long uid;
	/** Blocks we have but haven't sent yet. 0 = block sent or not present, 1 = block present but not sent */
	final BitArray blocksNotSentButPresent;
	private boolean cancelled;
	/** Not persistent over reboots */
	final long peerBootID;
	private boolean sentCancel;
	private boolean finished;
	final int packetSize;
	/** Not expecting a response? */
	final boolean noWait;
	private long finishTime=-1;
	private String cancelReason;
	private final ByteCounter ctr;
	
	/**
	 * Create a bulk data transmitter.
	 * @param prb The PartiallyReceivedBulk containing the file we want to send, or the part of it that we have so far.
	 * @param peer The peer we want to send it to.
	 * @param uid The unique identifier for this data transfer
	 * @param masterThrottle The overall output throttle
	 * @param noWait If true, don't wait for an FNPBulkReceivedAll, return as soon as we've sent everything.
	 * @throws DisconnectedException If the peer we are trying to send to becomes disconnected.
	 */
	public BulkTransmitter(PartiallyReceivedBulk prb, PeerContext peer, long uid, boolean noWait, ByteCounter ctr) throws DisconnectedException {
		this.prb = prb;
		this.peer = peer;
		this.uid = uid;
		this.noWait = noWait;
		this.ctr = ctr;
		if(ctr == null) throw new NullPointerException();
		peerBootID = peer.getBootID();
		// Need to sync on prb while doing both operations, to avoid race condition.
		// Specifically, we must not get calls to blockReceived() until blocksNotSentButPresent
		// has been set, AND it must be accurate, so there must not be an unlocked period
		// between cloning and adding.
		synchronized(prb) {
			// We can just clone it.
			blocksNotSentButPresent = prb.cloneBlocksReceived();
			prb.add(this);
		}
		try {
			prb.usm.addAsyncFilter(MessageFilter.create().setNoTimeout().setSource(peer).setType(DMT.FNPBulkReceiveAborted).setField(DMT.UID, uid),
					new AsyncMessageFilterCallback() {
						public void onMatched(Message m) {
							cancel("Other side sent FNPBulkReceiveAborted");
						}
						public boolean shouldTimeout() {
							synchronized(BulkTransmitter.this) {
								if(cancelled || finished) return true;
							}
							if(BulkTransmitter.this.prb.isAborted()) return true;
							return false;
						}
						public void onTimeout() {
							// Ignore
						}
						public void onDisconnect(PeerContext ctx) {
							// Ignore
						}
						public void onRestarted(PeerContext ctx) {
							// Ignore
						}
			});
			prb.usm.addAsyncFilter(MessageFilter.create().setNoTimeout().setSource(peer).setType(DMT.FNPBulkReceivedAll).setField(DMT.UID, uid),
					new AsyncMessageFilterCallback() {
						public void onMatched(Message m) {
							completed();
						}
						public boolean shouldTimeout() {
							synchronized(BulkTransmitter.this) {
								   if (cancelled) return true;
								   if (finished)  return (System.currentTimeMillis()-finishTime > FINAL_ACK_TIMEOUT);
							}
							if(BulkTransmitter.this.prb.isAborted()) return true;
							return false;
						}
						public void onTimeout() {
							// Ignore
						}
						public void onDisconnect(PeerContext ctx) {
							// Ignore
						}
						public void onRestarted(PeerContext ctx) {
							// Ignore
						}
			});
		} catch (DisconnectedException e) {
			cancel("Disconnected");
			throw e;
		}
		packetSize = DMT.bulkPacketTransmitSize(prb.blockSize) +
			peer.getOutgoingMangler().fullHeadersLengthOneMessage();
	}

	/**
	 * Received a block. Set the relevant bit to 1 to indicate that we have the block but haven't sent
	 * it yet. **Only called by PartiallyReceivedBulk.**
	 * @param block The block number that has been received.
	 */
	synchronized void blockReceived(int block) {
		blocksNotSentButPresent.setBit(block, true);
		notifyAll();
	}

	/**
	 * Called when the PRB is aborted.
	 */
	public void onAborted() {
		sendAbortedMessage();
		synchronized(this) {
			notifyAll();
		}
	}
	
	private void sendAbortedMessage() {
		synchronized(this) {
			if(sentCancel) return;
			sentCancel = true;
		}
		try {
			peer.sendAsync(DMT.createFNPBulkSendAborted(uid), null, ctr);
		} catch (NotConnectedException e) {
			// Cool
		}
	}

	public void cancel(String reason) {
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Cancelling "+this);
		sendAbortedMessage();
		synchronized(this) {
			cancelled = true;
			cancelReason = reason;
			notifyAll();
		}
		prb.remove(this);
	}

	/** Like cancel(), but without the negative overtones: The client says it's got everything,
	 * we believe them (even if we haven't sent everything; maybe they had a partial). */
	public void completed() {
		synchronized(this) {
			finished = true;
			finishTime = System.currentTimeMillis();
			notifyAll();
		}
		prb.remove(this);
	}
	
	/**
	 * Send the file.
	 * @return True if the file was successfully sent. False otherwise.
	 */
	public boolean send() {
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		long lastSentPacket = System.currentTimeMillis();
outer:	while(true) {
			if(prb.isAborted()) {
				if(logMINOR)
					Logger.minor(this, "Aborted "+this);
				return false;
			}
			int blockNo;
			if(peer.getBootID() != peerBootID) {
				synchronized(this) {
					cancelled = true;
					notifyAll();
				}
				prb.remove(BulkTransmitter.this);
				if(logMINOR)
					Logger.minor(this, "Failed to send "+uid+": peer restarted: "+peer);
				return false;
			}
			synchronized(this) {
				if(finished) return true;
				if(cancelled) return false;
				blockNo = blocksNotSentButPresent.firstOne();
			}
			if(blockNo < 0) {
				if(noWait && prb.hasWholeFile()) {
					completed();
					return true;
				}
				synchronized(this) {
					// Wait for all packets to complete
					while(true) {
						if(failedPacket) {
							cancel("Packet send failed");
							return false;
						}
						if(Logger.shouldLog(Logger.MINOR, this))
							Logger.minor(this, "Waiting for packets: remaining: "+inFlightPackets);
						if(inFlightPackets == 0) break;
						try {
							wait();
							if(failedPacket) {
								cancel("Packet send failed");
								return false;
							}
							if(inFlightPackets == 0) break;
							continue outer; // Might be a packet...
						} catch (InterruptedException e) {
							// Ignore
						}
					}
					
					// Wait for a packet to come in, BulkReceivedAll or BulkReceiveAborted
					try {
						wait(60*1000);
					} catch (InterruptedException e) {
						// No problem
						continue;
					}
				}
				long end = System.currentTimeMillis();
				if(end - lastSentPacket > TIMEOUT) {
					Logger.error(this, "Send timed out on "+this);
					cancel("Timeout awaiting BulkReceivedAll");
					return false;
				}
				continue;
			}
			// Send a packet
			byte[] buf = prb.getBlockData(blockNo);
			if(buf == null) {
				if(logMINOR)
					Logger.minor(this, "Block "+blockNo+" is null, presumably the send is cancelled: "+this);
				// Already cancelled, quit
				return false;
			}
			
			// Congestion control and bandwidth limiting
			try {
				if(logMINOR) Logger.minor(this, "Sending packet "+blockNo);
				peer.sendThrottledMessage(DMT.createFNPBulkPacketSend(uid, blockNo, buf), buf.length, ctr, BulkReceiver.TIMEOUT, false, new UnsentPacketTag());
				synchronized(this) {
					blocksNotSentButPresent.setBit(blockNo, false);
				}
				lastSentPacket = System.currentTimeMillis();
			} catch (NotConnectedException e) {
				cancel("Disconnected");
				if(logMINOR)
					Logger.minor(this, "Canclled: not connected "+this);
				return false;
			} catch (PeerRestartedException e) {
				cancel("PeerRestarted");
				if(logMINOR)
					Logger.minor(this, "Canclled: not connected "+this);
				return false;
			} catch (WaitedTooLongException e) {
				Logger.error(this, "Failed to send bulk packet "+blockNo+" for "+this);
				return false;
			} catch (SyncSendWaitedTooLongException e) {
				// Impossible
				Logger.error(this, "Impossible: Caught "+e, e);
				return false;
			}
		}
	}
	
	private int inFlightPackets = 0;
	private boolean failedPacket = false;
	
	private class UnsentPacketTag implements AsyncMessageCallback {

		private boolean finished;
		
		private UnsentPacketTag() {
			synchronized(BulkTransmitter.this) {
				inFlightPackets++;
			}
		}
		
		public void acknowledged() {
			complete(false);
		}

		private void complete(boolean failed) {
			synchronized(this) {
				if(finished) return;
				finished = true;
			}
			synchronized(BulkTransmitter.this) {
				if(failed) {
					failedPacket = true;
					BulkTransmitter.this.notifyAll();
					if(Logger.shouldLog(Logger.MINOR, this)) Logger.minor(this, "Packet failed for "+BulkTransmitter.this);
				} else {
					inFlightPackets--;
					if(inFlightPackets <= 0)
						BulkTransmitter.this.notifyAll();
					if(Logger.shouldLog(Logger.MINOR, this)) Logger.minor(this, "Packet sent "+BulkTransmitter.this+" remaining in flight: "+inFlightPackets);
				}
			}
		}

		public void disconnected() {
			complete(true);
		}

		public void fatalError() {
			complete(true);
		}

		public void sent() {
			// Wait for acknowledgment
		}
		
	}
	
	@Override
	public String toString() {
		return "BulkTransmitter:"+uid+":"+peer.shortToString();
	}
	
	public String getCancelReason() {
		return cancelReason;
	}
}

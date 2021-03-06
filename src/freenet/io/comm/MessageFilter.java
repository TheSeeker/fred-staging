/*
 * Dijjer - A Peer to Peer HTTP Cache
 * Copyright (C) 2004,2005 Change.Tv, Inc
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package freenet.io.comm;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

import freenet.support.Logger;

/**
 * @author ian
 *
 * To change the template for this generated type comment go to Window - Preferences - Java - Code Generation - Code and
 * Comments
 */
public final class MessageFilter {

    public static final String VERSION = "$Id: MessageFilter.java,v 1.7 2005/08/25 17:28:19 amphibian Exp $";

    private boolean _matched;
    private PeerContext _droppedConnection;
	private MessageType _type;
    private HashMap<String, Object> _fields = new HashMap<String, Object>();
    private List<String> _fieldList = new LinkedList<String>();
    private PeerContext _source;
    private long _timeout;
    /** If true, timeouts are relative to the start of waiting, if false, they are relative to
     * the time of calling setTimeout() */
    private boolean _timeoutFromWait;
    private int _initialTimeout;
    private MessageFilter _or;
    private Message _message;
    private long _oldBootID;
    private AsyncMessageFilterCallback _callback;
    private boolean _setTimeout = false;

    private MessageFilter() {
        _timeoutFromWait = true;
    }

    public static MessageFilter create() {
        return new MessageFilter();
    }

    void onStartWaiting(boolean waitFor) {
    	synchronized(this) {
    		/* We cannot wait on a MessageFilter with a callback, because onMatched() calls clearMatched()
    		 * if we have a callback. The solution would be to:
    		 * - Set a flag indicating we are waitFor()ing a filter here.
    		 * - On matching a message (setMessage), call the callback immediately if not waitFor()ing.
    		 * - If we are waitFor()ing, call the callback when we exit waitFor() (onStopWaiting()???).
    		 */
        	if(waitFor && _callback != null)
        		throw new IllegalStateException("Cannot wait on a MessageFilter with a callback!");
    		if(!_setTimeout)
    			Logger.error(this, "No timeout set on filter "+this, new Exception("error"));
    		if(_initialTimeout > 0 && _timeoutFromWait)
    			_timeout = System.currentTimeMillis() + _initialTimeout;
    	}
    	if(_or != null)
    		_or.onStartWaiting(waitFor);
    }
    
    /**
     * Set whether the timeout is relative to the creation of the filter, or the start of
     * waitFor().
     * @param b If true, the timeout is relative to the time at which setTimeout() was called,
     * if false, it's relative to the start of waitFor().
     */
    public MessageFilter setTimeoutRelativeToCreation(boolean b) {
    	_timeoutFromWait = !b;
    	return this;
    }
    
    /**
     * This filter will expire after the specificed amount of time. Note also that where two or more filters match the
     * same message, the one with the nearer expiry time will get priority
     *
     * @param timeout The time before this filter expires in ms
     * @return This message filter
     */
	public MessageFilter setTimeout(int timeout) {
		_setTimeout = true;
		_initialTimeout = timeout;
		_timeout = System.currentTimeMillis() + timeout;
		return this;
	}

	public MessageFilter setNoTimeout() {
		_setTimeout = true;
		_timeout = Long.MAX_VALUE;
		_initialTimeout = 0;
		return this;
	}
	
	public MessageFilter setType(MessageType type) {
		_type = type;
		return this;
	}

	public MessageFilter setSource(PeerContext source) {
		_source = source;
		if(source != null)
			_oldBootID = source.getBootID();
		return this;
	}
	
	/**
	 Returns the source that this filter (or chain) matches
	 */
	public PeerContext getSource() {
		return _source;
	}

	public MessageFilter setField(String fieldName, boolean value) {
		return setField(fieldName, Boolean.valueOf(value));
	}

	public MessageFilter setField(String fieldName, byte value) {
		return setField(fieldName, Byte.valueOf(value));
	}

	public MessageFilter setField(String fieldName, short value) {
		return setField(fieldName, Short.valueOf(value));
	}

	public MessageFilter setField(String fieldName, int value) {
		return setField(fieldName, Integer.valueOf(value));
	}

	public MessageFilter setField(String fieldName, long value) {
		return setField(fieldName, Long.valueOf(value));
	}

	public MessageFilter setField(String fieldName, Object fieldValue) {
		if ((_type != null) && (!_type.checkType(fieldName, fieldValue))) {
			throw new IncorrectTypeException("Got " + fieldValue.getClass() + ", expected " + _type.typeOf(fieldName) + " for " + _type.getName());
		}
		synchronized (_fields) {
			if(_fields.put(fieldName, fieldValue) == null)
				_fieldList.add(fieldName);
		}
		return this;
	}

	public MessageFilter or(MessageFilter or) {
		if((or != null) && (_or != null) && or != _or) {
			// FIXME maybe throw? this is almost certainly a bug, and a nasty one too!
			Logger.error(this, "or() replacement: "+_or+" -> "+or, new Exception("error"));
		}
		_or = or;
		return this;
	}

	public MessageFilter setAsyncCallback(AsyncMessageFilterCallback cb) {
		_callback = cb;
		return this;
	}
	
	public boolean match(Message m) {
		if ((_or != null) && (_or.match(m))) {
			return true;
		}
		if ((_type != null) && (!_type.equals(m.getSpec()))) {
			return false;
		}
		if ((_source != null) && (!_source.equals(m.getSource()))) {
			return false;
		}
		synchronized (_fields) {
			for (String fieldName : _fieldList) {
				if (!m.isSet(fieldName)) {
					return false;
				}
				if (!_fields.get(fieldName).equals(m.getFromPayload(fieldName))) {
					return false;
				}
			}
		}
		if(reallyTimedOut(System.currentTimeMillis())) return false;
		return true;
	}

	public boolean matched() {
		return _matched;
	}

	/**
	 * Which connection dropped or was restarted?
	 */
	public PeerContext droppedConnection() {
	    return _droppedConnection;
	}
	
	boolean reallyTimedOut(long time) {
		if(_callback != null && _callback.shouldTimeout())
			_timeout = -1; // timeout immediately
		return _timeout < time;
	}
	
	/**
	 * @param time The current time in milliseconds.
	 * @return True if the filter has timed out, or if it has been matched already. Caller will
	 * remove the filter from _filters if we return true.
	 */
	boolean timedOut(long time) {
		if(_matched) {
			Logger.error(this, "Impossible: filter already matched in timedOut(): "+this, new Exception("error"));
			return true; // Remove it.
		}
		return reallyTimedOut(time);
	}

    public Message getMessage() {
        return _message;
    }

    public synchronized void setMessage(Message message) {
        //Logger.debug(this, "setMessage("+message+") on "+this, new Exception("debug"));
        _message = message;
        _matched = _message != null;
        notifyAll();
    }

    public int getInitialTimeout() {
        return _initialTimeout;
    }
    
    public long getTimeout() {
        return _timeout;
    }

    @Override
	public String toString() {
    	return super.toString()+":"+_type.getName();
    }

    public void clearMatched() {
    	// If the filter matched in an _or, and it is re-used, then
    	// we need to clear all the _or's.
    	MessageFilter or;
    	synchronized(this) {
    		_matched = false;
    		_message = null;
    		or = _or;
    	}
    	if(or != null)
    		or.clearMatched();
    }

    public void clearOr() {
        _or = null;
    }
    
    public boolean matchesDroppedConnection(PeerContext ctx) {
    	if(_source == ctx) return true;
    	if(_or != null) return _or.matchesDroppedConnection(ctx);
    	return false;
    }
    
    public boolean matchesRestartedConnection(PeerContext ctx) {
    	if(_source == ctx) return true;
    	if(_or != null) return _or.matchesRestartedConnection(ctx);
    	return false;
    }
    
    /**
     * Notify because of a dropped connection.
     * Caller must verify _matchesDroppedConnection and _source.
     * @param ctx
     */
    public void onDroppedConnection(PeerContext ctx) {
    	synchronized(this) {
    		_droppedConnection = ctx;
    		notifyAll();
    	}
    	if(_callback != null)
    		_callback.onDisconnect(ctx);
    }

    /**
     * Notify because of a restarted connection.
     * Caller must verify _matchesDroppedConnection and _source.
     * @param ctx
     */
    public void onRestartedConnection(PeerContext ctx) {
    	synchronized(this) {
    		_droppedConnection = ctx;
    		notifyAll();
    	}
    	if(_callback != null)
    		_callback.onRestarted(ctx);
    }

    /**
     * Notify waiters that we have been matched.
     * Hopefully no locks will be held at this point by the caller.
     */
	public void onMatched() {
		Message msg;
		AsyncMessageFilterCallback cb;
		synchronized(this) {
			msg = _message;
			cb = _callback;
			// Clear matched before calling callback in case we are re-added.
			if(_callback != null)
				clearMatched();
		}
		if(cb != null) {
			cb.onMatched(msg);
		}
	}

	/**
	 * Notify waiters that we have timed out.
	 */
	public void onTimedOut() {
		synchronized(this) {
			notifyAll();
		}
		if(_callback != null)
			_callback.onTimeout();
	}

	/**
	 * Returns true if a connection related to this filter has been dropped or restarted.
	 */
	public boolean anyConnectionsDropped() {
		if(_matched) return false;
		if(_source != null) {
			if(!_source.isConnected()) {
				return true;
			} else if(_source.getBootID() != _oldBootID) {
				return true; // Counts as a disconnect.
			}
		}
		if(_or != null)
			return _or.anyConnectionsDropped();
		return false;
	}
}

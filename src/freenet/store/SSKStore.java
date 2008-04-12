package freenet.store;

import java.io.IOException;

import freenet.keys.NodeSSK;
import freenet.keys.SSKBlock;
import freenet.keys.SSKVerifyException;
import freenet.node.GetPubkey;

public class SSKStore extends StoreCallback {

	private final GetPubkey pubkeyCache;
	
	public SSKStore(GetPubkey pubkeyCache) {
		this.pubkeyCache = pubkeyCache;
	}
	
	public StorableBlock construct(byte[] data, byte[] headers,
			byte[] routingKey, byte[] fullKey) throws SSKVerifyException {
		if(data == null || headers == null) throw new SSKVerifyException("Need data and headers");
		if(fullKey == null) throw new SSKVerifyException("Need full key to reconstruct an SSK");
		NodeSSK key;
		key = NodeSSK.construct(fullKey);
		if(!key.grabPubkey(pubkeyCache))
			throw new SSKVerifyException("No pubkey found");
		SSKBlock block = new SSKBlock(data, headers, key, false);
		return block;
	}
	
	public SSKBlock fetch(NodeSSK chk, boolean dontPromote) throws IOException {
		return (SSKBlock) store.fetch(chk.getRoutingKey(), chk.getFullKey(), dontPromote);
	}

	public void put(SSKBlock b, boolean overwrite) throws IOException, KeyCollisionException {
		NodeSSK key = (NodeSSK) b.getKey();
		store.put(b, key.getRoutingKey(), key.getFullKey(), b.getRawData(), b.getRawHeaders(), overwrite);
	}
	
	public int dataLength() {
		return SSKBlock.DATA_LENGTH;
	}

	public int fullKeyLength() {
		return NodeSSK.FULL_KEY_LENGTH;
	}

	public int headerLength() {
		return SSKBlock.TOTAL_HEADERS_LENGTH;
	}

	public int routingKeyLength() {
		return NodeSSK.ROUTING_KEY_LENGTH;
	}

	public boolean storeFullKeys() {
		return true;
	}

	public boolean collisionPossible() {
		return true;
	}

	public boolean constructNeedsKey() {
		return true;
	}

	public byte[] routingKeyFromFullKey(byte[] keyBuf) {
		return NodeSSK.routingKeyFromFullKey(keyBuf);
	}

}

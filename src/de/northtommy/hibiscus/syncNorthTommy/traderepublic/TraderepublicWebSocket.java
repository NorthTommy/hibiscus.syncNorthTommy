package de.northtommy.hibiscus.syncNorthTommy.traderepublic;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.json.JSONArray;
import org.json.JSONObject;

import de.willuhn.logging.Level;
import de.willuhn.util.ApplicationException;

@WebSocket
public class TraderepublicWebSocket {
	
	public class Transaction {
		public JSONObject transaction = null;
		public JSONObject details = null;
	}
	
	protected TraderepublicSynchronizeJobKontoauszugI syncJob = null;
	
	private enum ProtoRequestStates{
		SUBSCRIBED,
		ANSWERED,
		// COMPLETED not needed as we simply send unsub and delete it from pending ids
	}
	
	public enum RxState {
		RUNNING,
		WAIT_REMAINING_SUBS,
		FINISHED
	}
	
	private String clientVersion;
	
	//public void setClientVersion(String clientVersion) {
	//	this.clientVersion = clientVersion;
	//}

	private Date rxDateRangeUntil = null;
	
	/** set Date until the transactions should roughly be requested; null for all available transactions */
	//public void setRxDateRangerUntil(Date value) {
	//	this.rxDateRangeUntil = value;
	//}
	
	// will be set if any error occurred during sync
	private Exception errorException = null;
	
	public Exception getErrorException() {
		return this.errorException;
	}
	
	/**
	 * JSON object with available cash data for the accounts
	 * contains cash account number we can differ between
	 */
	private JSONArray accountsAvailableCash = null;
	
	public JSONArray getAccountsAvailableCash() {
		return accountsAvailableCash;
	}
	
	private JSONArray accountsCash = null;
	
	public JSONArray getAccountsCash() {
		return accountsCash;
	}
	
	/**
	 * ordered list of transactions with transaction and detail data
	 */
	private ArrayList<Transaction> accountTransactions = new ArrayList<Transaction>(); 
	
	public ArrayList<Transaction> getAccountTransactions() {
		return accountTransactions;
	}

	private RxState rxState = RxState.RUNNING;
	
	public RxState getRxState() {
		return this.rxState;
	}
	
	private final CountDownLatch closeLatch = new CountDownLatch(1);
    private Session session;
    
    // first we have to connect and after that we can request data and subscribe to data
    private boolean protoConnected = false;
    /**
     * forward unique running counter for all requests of the websocket connection
     */
    private int protoCounter = 1;
    /**
     * key: protoCounter for transactionRequests
     * value: state
     * If request send, we set to subscribed
     * If key received and "A" and state is "SUBSCRIBED then we set we
     *    - add all transactions to accountTransitions
     *    - request details for each transition and add an entry for each detailRequest in protoTransitionSubscriptions
     *    - set ANSWERED as value for this key
     * If key received with "C" and state is ANSWERED we
     *    - send unsub key
     *    - delete it from this list  
     */
    private HashMap<Integer, ProtoRequestStates> protoTransactionSubscriptions = new HashMap<Integer, TraderepublicWebSocket.ProtoRequestStates>();
    
    // protoCounter for cash data
    private int protoCashSubscription = 0;
    private int protoAvailableCashSubscription = 0;
    
    /**
     * key: protoCounter
     * value: transaction initially set with transaction data only also stored in accountTransactions
     * 
     * If key again received we set details data as well if still null (initial)
     * If key again received we get the "Complete" and unsubscribe and delete it from this hashmap (all data stored in accountTransitions
     */
    private HashMap<Integer, Transaction> protoTransactionDetailSubscriptions = new HashMap<Integer, Transaction>();
    
    private SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
    
    public TraderepublicWebSocket(TraderepublicSynchronizeJobKontoauszugI syncJob, String version, Date rxDateRangeUntil) {
		if (syncJob == null) {
			throw new NullPointerException("syncJob must not be null");
		}
    	this.syncJob = syncJob;
    	this.clientVersion = version;
    	this.rxDateRangeUntil = rxDateRangeUntil;
	}
    
    public boolean awaitClose(int duration, TimeUnit unit) throws InterruptedException {
        return closeLatch.await(duration, unit);
    }

    @OnWebSocketConnect
    public void onConnect(Session session) {
        syncJob.logging(Level.DEBUG, "WebSocket connected"); 
        this.session = session;
        try {
        	String s = "connect 31  {\"locale\":\"en\",\"platformId\":\"webtrading\",\"platformVersion\":\"firefox - 140.0.0\",\"clientId\":\"app.traderepublic.com\",\"clientVersion\":\"" + this.clientVersion + "\"}";
    		syncJob.logging(Level.DEBUG, "WebSocket request subproto connect: " + s);
			session.getRemote().sendString(s);
		} catch (IOException e) {
			syncJob.logging(Level.ERROR, "WebSocket error subproto connect request: " + e.getMessage());
    		this.errorException = e;
		}
    }

    @OnWebSocketMessage
    public void onMessage(String msg) {
    	int sPC;
    	
    	// as websocket is async and any further responses may happen in background after error is already detected we need to drop msgs
    	if ((errorException != null) || (rxState==RxState.FINISHED)) {
    		return;
    	}
    	
    	syncJob.logging(Level.DEBUG, "WebSocket Received message: " + msg);
    	//var msgId = Integer.parseInt(msg.substring(0, msg.indexOf(" ")));
    	//protoCounter = msgId + 1;
        try {
	        if ((msg.compareToIgnoreCase("connected") == 0) && (!this.protoConnected)) {
	        	syncJob.logging(Level.DEBUG, "WebSocket subproto connected:");
	        	this.protoConnected = true;
	        	
	        	// send initial transactions request and cash request
	        	sPC = protoCounter++;
	        	// remember sPC for response
	        	protoAvailableCashSubscription = sPC;
	        	String s = "sub " + sPC + " {\"type\":\"availableCash\"}";
	        	syncJob.logging(Level.DEBUG, "WebSocket request avail chash info: " + s);
	        	this.session.getRemote().sendString(s);
	        	
	        	sPC = protoCounter++;
	        	// remember sPC for response
	        	protoCashSubscription = sPC;
	        	s = "sub " + sPC + " {\"type\":\"cash\"}";
	        	syncJob.logging(Level.DEBUG, "WebSocket request chash info: " + s);
	        	this.session.getRemote().sendString(s);
	        	

	        	sPC = protoCounter++;
	    		protoTransactionSubscriptions.put(sPC, ProtoRequestStates.SUBSCRIBED);
	    		s = "sub " + sPC + " {\"type\":\"timelineTransactions\"}";
	    		syncJob.logging(Level.DEBUG, "WebSocket request first transactions: " + s);
	        	this.session.getRemote().sendString(s);
	        } else if (protoConnected) {
	        	// check for all expected responses for outstanding requests
	    		var msgId = Integer.parseInt(msg.substring(0, msg.indexOf(" ")));
	    		// check if its response for transaction request
	    		if (protoTransactionSubscriptions.containsKey(msgId)) {
	    			var value = protoTransactionSubscriptions.get(msgId);
	    			syncJob.logging(Level.DEBUG, "WebSocket transaction request msg response");
	    			switch(value) {
	    				case SUBSCRIBED:
	    					// if "id A JSON" -> analyse array 
	    					Date lastTransactionDate = null;
	    					if (msg.charAt(msg.indexOf(" ") + 1) == 'A') {
	    						JSONObject json = new JSONObject(msg.substring(msg.indexOf(" ") + 3));
	    						JSONArray jsonTransactions = json.getJSONArray("items");
	    						JSONObject jsonCursors = json.getJSONObject("cursors");
	    						
	    						for (int i = 0; i < jsonTransactions.length(); i++) {
	    							var jsonTransaction = jsonTransactions.getJSONObject(i);
	    							var tr = new Transaction();
	        						tr.transaction = jsonTransaction;
	        						tr.details = null;
	        						accountTransactions.add(tr);
	        						// Details do not contain any structured non-visual content :-(
	        						//String action = jsonTransaction.optString("action");
	        						// received action seems to have an unsupported subscription type ("errorCode":"BAD_SUBSCRIPTION_TYPE") -> timelineDetail does not work fine
	        						//if (!action.isBlank()) {
	        							//sPC = protoCounter++;
	        							//protoTransactionDetailSubscriptions.put(sPC, tr);
	        							//String s = "sub " + sPC + " {\"type\":\"timelineDetailV2\", \"id\":\"" + jsonTransaction.getString("id") + "\"}";
	        							//String s = "sub " + sPC + " " + action;
	        							//syncJob.logging(Level.DEBUG, "WebSocket request transactions details: " + s);
	        							//this.session.getRemote().sendString(s);
	        						//}
	        			        	
	        			        	// remember date of the transaction
	        						lastTransactionDate = dateFormat.parse(jsonTransaction.getString("timestamp"));
	    						}
	    						
	    						if ( lastTransactionDate != null) {
	    							syncJob.logging(Level.DEBUG, "WebSocket received transactions until " + lastTransactionDate.toLocaleString() + "( > " + rxDateRangeUntil + ")");
	    						}
	    						// check cursors (more avail if after cursor is set) and if we need to request further transactions
	    						var afterCursor = jsonCursors.optString("after");
	    						// check if there is a further cursor AND 
	    						// if no transactions at all received (empty array), lastTransactionDate is null -> also break AND
	    						// last check if either until Date == null -> rx all as much possible OR lasttransactionDate is still newer than until-Date
	    						if ( (! afterCursor.isBlank()) && (lastTransactionDate != null) && ( (this.rxDateRangeUntil == null) || (lastTransactionDate.after(this.rxDateRangeUntil))) ) {
	    							protoTransactionSubscriptions.put(Integer.valueOf(protoCounter), ProtoRequestStates.SUBSCRIBED);
	    							String s = "sub " + this.protoCounter++ + " {\"type\":\"timelineTransactions\", \"after\":\"" + afterCursor + "\"}";
									syncJob.logging(Level.DEBUG, "WebSocket request further transactions: " + s);
	    							this.session.getRemote().sendString(s);
	    						} else {
	    							syncJob.logging(Level.INFO, "Umsaetze abgerufen.");
	    							this.rxState = RxState.WAIT_REMAINING_SUBS;
	    						}
	    						// we only expect A in this case
		    					
		    					protoTransactionSubscriptions.put(msgId, ProtoRequestStates.ANSWERED);
	    					} else {
	    						// includes <msgId> E ....
	    						throw new ApplicationException("Fehler beim Abrufen der Umsätze: " + msg);
	    					}
	    					break;
	    				case ANSWERED:
	    					// if C -> unsub and remove from subscriptionArray
	    					// if (msg.charAt(msg.indexOf(" ") + 1) == 'A') {
	    					String s = "unsub " + msgId;
	    		    		syncJob.logging(Level.DEBUG, "WebSocket transaction request finished: " + s);
	    					session.getRemote().sendString(s);
	    					protoTransactionSubscriptions.remove(msgId);
	    					break;
						default:
							// not an answer for transaction request
							break;
	    			}
	    		} // Transactions request pendig for the msgId
	    		else if (protoTransactionDetailSubscriptions.containsKey(msgId)) {
	    			// response for transaction detail request
	    			var valueDetails = protoTransactionDetailSubscriptions.get(msgId);
    				syncJob.logging(Level.DEBUG, "WebSocket transaction detail request msg response");
    				if (valueDetails.details == null) {
    					// still no details received - add them from msg
    					// if "id A JSON" -> analyse array 
    					if (msg.charAt(msg.indexOf(" ") + 1) == 'A') {
    						JSONObject json = new JSONObject(msg.substring(msg.indexOf(" ") + 3));
    						valueDetails.details = json;
    					} else {
    						// includes <msgId> E ....
    						throw new ApplicationException("Fehler beim Abrufen der UmsatzDetails: " + msg);
    					}
    				} else {
    					// details already received
    					// if (msg.charAt(msg.indexOf(" ") + 1) == 'C') {
    					String s = "unsub " + msgId;
    		    		syncJob.logging(Level.DEBUG, "WebSocket transaction detail request finished: " + s);
    					session.getRemote().sendString(s);
    					protoTransactionDetailSubscriptions.remove(msgId);
    				}
	    		} // transaction details request pending for the msgId
	    		else if (protoCashSubscription == msgId) {
	    			if (accountsCash == null) {
    					// still no accounts - add them from msg
    					// if "id A JSON" -> analyse array 
    					if (msg.charAt(msg.indexOf(" ") + 1) == 'A') {
    						syncJob.logging(Level.DEBUG, "WebSocket got account cash");
    						JSONArray json = new JSONArray(msg.substring(msg.indexOf(" ") + 3));
    						accountsCash = json;
    						
    						String s = "unsub " + msgId;
        		    		syncJob.logging(Level.DEBUG, "WebSocket getting account cash finished: " + s);
        					session.getRemote().sendString(s);
        					protoTransactionDetailSubscriptions.remove(msgId);
        					protoCashSubscription = 0;
    					} else {
    						// includes <msgId> E ....
    						throw new ApplicationException("Fehler beim Abrufen des Saldo: " + msg);
    					}
	    			}
	    		}
	    		else if (protoAvailableCashSubscription == msgId) {
	    			if (accountsAvailableCash == null) {
    					// still no accounts - add them from msg
    					// if "id A JSON" -> analyse array 
    					if (msg.charAt(msg.indexOf(" ") + 1) == 'A') {
    						syncJob.logging(Level.DEBUG, "WebSocket got account available cash");
    						JSONArray json = new JSONArray(msg.substring(msg.indexOf(" ") + 3));
    						accountsAvailableCash = json;
    						
    						String s = "unsub " + msgId;
        		    		syncJob.logging(Level.DEBUG, "WebSocket getting account available cash finished: " + s);
        					session.getRemote().sendString(s);
        					protoTransactionDetailSubscriptions.remove(msgId);
        					protoAvailableCashSubscription = 0;
    					} else {
    						// includes <msgId> E ....
    						throw new ApplicationException("Fehler beim Abrufen des verfügbaren Saldo: " + msg);
    					}
	    			}
	    		} else {
	    			// ignore msg - not subscribed
	    		}
	        }
        } catch (Exception e) {
    		syncJob.logging(Level.ERROR, "WebSocket error parsing rx message: " + e.getMessage());
    		this.errorException = e;
    		rxState = RxState.FINISHED;
    	}
        if ( (rxState == RxState.WAIT_REMAINING_SUBS) && 
        	(protoTransactionSubscriptions.isEmpty()) &&
        	(protoTransactionDetailSubscriptions.isEmpty()) &&
        	(protoCashSubscription == 0) &&
        	(protoAvailableCashSubscription == 0) ) {
        	this.syncJob.logging(Level.ERROR, "WebSocket finished");
        	rxState = RxState.FINISHED;
        }
    }

    @OnWebSocketClose
    public void onClose(int statusCode, String reason) {
        System.out.println("Connection closed: " + reason);
        syncJob.logging(Level.DEBUG, "WebSocket connection closed");
        closeLatch.countDown();
    }

    @OnWebSocketError
    public void onError(Throwable cause) {
        System.err.println("WebSocket error: " + cause.getMessage());
        this.syncJob.logging(Level.ERROR, "WebSocket connection error: " + cause.getMessage());
        this.errorException = new Exception(cause);
    }
}
